"""服务入口：一个 aiohttp 进程同时提供 WebSocket 和 TTS 音频。

    GET  /            状态页（浏览器打开可看到当前状态和最近对话）
    GET  /ws          设备长连接（音频上行 + 事件双向）
    GET  /tts/<file>  合成好的音频
"""
from __future__ import annotations

import argparse
import asyncio
import json
import logging
import socket
import time
from datetime import datetime
from pathlib import Path

from aiohttp import WSMsgType, web

import numpy as np

from .audio import VAD, build_asr
from .config import load
from .ha import HomeAssistant
from .music import Music
from .llm import LLM
from .session import Session
from .tts import TTS

log = logging.getLogger("brain")
ROOT = Path(__file__).resolve().parent.parent.parent  # 项目根目录


def lan_ip() -> str:
    """挑一个设备能访问到的本机地址。"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("223.5.5.5", 80))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def lan_broadcasts() -> list[str]:
    """本机各网卡所在 /24 的广播地址，用来让设备发现服务端。"""
    out = {"255.255.255.255"}
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip.startswith("127.") or ip.startswith("169.254."):
                continue
            parts = ip.split(".")
            if len(parts) == 4:
                out.add(".".join(parts[:3]) + ".255")
    except OSError:
        pass
    return sorted(out)


async def beacon(port: int, ws_port: int) -> None:
    """每 3 秒广播一次自己的存在，App 收到后弹出连接确认。"""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    msg = json.dumps({"service": "xiaodu-brain", "port": ws_port}).encode()
    try:
        while True:
            for addr in lan_broadcasts():
                try:
                    sock.sendto(msg, (addr, port))
                except OSError:
                    pass
            await asyncio.sleep(3)
    finally:
        sock.close()


class Brain:
    """进程级共享资源，连接之间复用同一份模型。"""

    def __init__(self, cfg, public_url: str):
        self.cfg = cfg
        self.public_url = public_url
        try:
            self.asr = build_asr(cfg.asr)
        except Exception as e:
            log.error("ASR 初始化失败，语音输入不可用：%s", e)
            self.asr = None
        self.tts = TTS(cfg.tts, ROOT / "server" / "cache")
        self.llm = LLM(cfg.llm)
        self.ha = HomeAssistant(getattr(cfg, "ha", None))
        self.music = Music(getattr(cfg, "music", None), self.ha)
        self.started = time.time()
        self.recent: list[dict] = []
        # 按设备 IP 保存对话历史，断线重连后上下文还在
        self.memory: dict[str, list[dict]] = {}
        # 预合成的唤醒应答：text -> 文件名，命中时不走 TTS
        self.ack_files: dict[str, str] = {}
        self._chat_log = ROOT / "server" / "logs" / "chat.jsonl"
        self._chat_log.parent.mkdir(parents=True, exist_ok=True)

    def new_vad(self) -> VAD:
        return VAD(self.cfg.vad)

    def history_for(self, key: str) -> list[dict]:
        """同一台设备重连后继续用原来的上下文。"""
        return self.memory.setdefault(key or "default", [])

    def save_utterance(self, samples) -> None:
        """把这一句原始音频存下来，识别错了可以离线复现。"""
        try:
            import wave

            d = ROOT / "server" / "logs" / "asr"
            d.mkdir(parents=True, exist_ok=True)
            name = datetime.now().strftime("%H%M%S_%f")[:-3]
            path = d / f"{name}.wav"
            pcm = (np.clip(samples, -1.0, 1.0) * 32767).astype(np.int16)
            with wave.open(str(path), "wb") as w:
                w.setnchannels(1)
                w.setsampwidth(2)
                w.setframerate(16000)
                w.writeframes(pcm.tobytes())
            old = sorted(d.glob("*.wav"))
            for p in old[:-30]:
                p.unlink(missing_ok=True)
        except Exception as e:
            log.debug("保存音频失败：%s", e)

    async def warm_acks(self) -> None:
        """提前把「在」「哎」合成好，唤醒应答才能零延迟。"""
        for i, text in enumerate(self.cfg.wake.ack):
            try:
                p = await self.tts.synth(text)
                if not p:
                    continue
                target = p.with_name(f"ack_{i}{p.suffix}")
                try:
                    p.replace(target)
                except OSError:
                    pass
                self.ack_files[text] = target.name
            except Exception as e:
                log.warning("预合成应答失败 %s: %s", text, e)
        if self.ack_files:
            log.info("唤醒应答已就绪：%s", "、".join(self.ack_files))

    def log_turn(self, who: str, user: str, answer: str) -> None:
        item = {
            "t": datetime.now().strftime("%m-%d %H:%M:%S"),
            "who": who,
            "user": user,
            "bot": answer,
        }
        self.recent = (self.recent + [item])[-20:]
        try:
            with open(self._chat_log, "a", encoding="utf-8") as f:
                f.write(json.dumps(item, ensure_ascii=False) + "\n")
        except OSError:
            pass


async def handle_ws(request: web.Request) -> web.WebSocketResponse:
    brain: Brain = request.app["brain"]
    ws = web.WebSocketResponse(heartbeat=25, max_msg_size=2 * 1024 * 1024)
    await ws.prepare(request)
    # 用设备实际连进来的本地地址拼 TTS 链接，多网卡时才不会指错
    try:
        local_ip = request.transport.get_extra_info("sockname")[0]
    except Exception:
        local_ip = lan_ip()
    public_url = f"http://{local_ip}:{request.app['port']}"
    session = Session(ws, brain, public_url, remote=request.remote or "?")
    request.app["sessions"].add(session)
    try:
        await session.run()
    finally:
        request.app["sessions"].discard(session)
    return ws


async def handle_tts(request: web.Request) -> web.StreamResponse:
    name = request.match_info["name"]
    if "/" in name or "\\" in name or name.startswith("."):
        raise web.HTTPNotFound()
    path = ROOT / "server" / "cache" / name
    if not path.exists():
        raise web.HTTPNotFound()
    return web.FileResponse(path, headers={"Cache-Control": "no-store"})


async def handle_music(request: web.Request) -> web.StreamResponse:
    """音乐文件单独一个路由：支持 Range，播放器可以边下边播、拖动进度。"""
    name = request.match_info["name"]
    if "/" in name or "\\" in name or name.startswith("."):
        raise web.HTTPNotFound()
    path = ROOT / "server" / "cache" / "music" / name
    log.info("music 请求 %s from %s range=%s ua=%s", name, request.remote,
             request.headers.get("Range"), (request.headers.get("User-Agent") or "")[:40])
    if not path.exists():
        log.warning("music 文件不存在：%s", path)
        raise web.HTTPNotFound()
    return web.FileResponse(
        path, headers={"Cache-Control": "max-age=3600", "Accept-Ranges": "bytes"}
    )


async def handle_index(request: web.Request) -> web.Response:
    brain: Brain = request.app["brain"]
    rows = "".join(
        f"<tr><td class=n>{r['t']}</td><td>{r['user']}</td><td class=b>{r['bot']}</td></tr>"
        for r in reversed(brain.recent)
    ) or "<tr><td colspan=3 class=empty>还没有对话</td></tr>"
    up = int(time.time() - brain.started)
    ha = brain.ha
    if ha.enabled:
        ha_text = f"{ha.device_count} 个设备 ｜ {ha.url}"
        if ha.last_error:
            ha_text += f" ｜ 刷新失败：{ha.last_error[:60]}"
    else:
        ha_text = "未启用"
    html = f"""<!doctype html><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>小度大脑</title>
<style>
 body{{background:#0e1116;color:#e6edf3;font-family:system-ui,-apple-system,"Segoe UI",sans-serif;margin:0;padding:28px}}
 h1{{font-size:19px;font-weight:600;margin:0 0 4px}}
 .sub{{color:#8b949e;font-size:13px;margin-bottom:22px}}
 .card{{background:#161b22;border:1px solid #232a33;border-radius:10px;padding:16px 18px;margin-bottom:16px}}
 .k{{color:#8b949e;font-size:12px}} .v{{font-size:15px;margin-top:2px}}
 table{{width:100%;border-collapse:collapse;font-size:13px}}
 td{{padding:8px 6px;border-bottom:1px solid #21262d;vertical-align:top}}
 .n{{color:#8b949e;white-space:nowrap;width:70px}} .b{{color:#7ee787}}
 .empty{{color:#8b949e;text-align:center;padding:24px}}
</style>
<h1>小度大脑 · 运行中</h1>
<div class="sub">已运行 {up//3600}h{(up%3600)//60}m{up%60}s ｜ 服务地址 {brain.public_url}</div>
<div class="card">
  <div class="k">模型</div><div class="v">{brain.llm.model}</div>
  <div class="k" style="margin-top:10px">推理档位</div><div class="v">{brain.llm.reasoning_effort or '默认'}</div>
  <div class="k" style="margin-top:10px">在线设备</div><div class="v">{len(request.app['sessions'])}</div>
  <div class="k" style="margin-top:10px">智能家居</div><div class="v">{ha_text}</div>
</div>
<div class="card">
  <table><tr><td class="n k">时间</td><td class="k">你说</td><td class="k">小度</td></tr>{rows}</table>
</div>"""
    return web.Response(text=html, content_type="text/html", charset="utf-8")


async def amain(config_path: Path) -> None:
    cfg = load(config_path)
    host, port = str(cfg.server.host), int(cfg.server.port)
    public = str(getattr(cfg.server, "public_url", "") or "").strip()
    if not public:
        public = f"http://{lan_ip()}:{port}"

    brain = Brain(cfg, public)
    asyncio.create_task(brain.warm_acks())
    asyncio.create_task(brain.music.warm())
    asyncio.create_task(beacon(int(getattr(cfg.server, "discovery_port", 8766)), port))

    app = web.Application(client_max_size=8 * 1024 * 1024)
    app["brain"] = brain
    app["sessions"] = set()
    app["port"] = port
    app.router.add_get("/", handle_index)
    app.router.add_get("/ws", handle_ws)
    app.router.add_get("/tts/{name}", handle_tts)
    app.router.add_get("/music/{name}", handle_music)
    app.router.add_get("/healthz", lambda r: web.json_response({"ok": True}))

    runner = web.AppRunner(app, access_log=None)
    await runner.setup()
    site = web.TCPSite(runner, host, port)
    await site.start()

    # 启动就拉一次设备，状态页和第一次语音都不用现等
    if brain.ha.enabled:
        asyncio.create_task(brain.ha.ensure())

    log.info("=" * 58)
    log.info("小度大脑已启动")
    log.info("  设备连接地址 : %s/ws", public)
    log.info("  状态页       : %s", public)
    log.info("  模型         : %s (reasoning_effort=%s)", brain.llm.model, brain.llm.reasoning_effort)
    if brain.ha.enabled:
        log.info("  智能家居     : %s（直控=%s，工具=%s）", brain.ha.url, brain.ha.direct, brain.ha.use_tools)
    else:
        log.info("  智能家居     : 未启用")
    log.info("  唤醒词       : %s", "、".join(cfg.wake.words))
    log.info("  音频目录     : %s", ROOT / "server" / "cache")
    log.info("=" * 58)

    try:
        await asyncio.Event().wait()
    finally:
        await runner.cleanup()


def setup_logging(level: str = "INFO") -> None:
    (ROOT / "server" / "logs").mkdir(parents=True, exist_ok=True)
    fmt = "%(asctime)s %(levelname)-5s %(name)-8s %(message)s"
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        format=fmt,
        datefmt="%H:%M:%S",
        handlers=[
            logging.StreamHandler(),
            logging.FileHandler(ROOT / "server" / "logs" / "brain.log", encoding="utf-8"),
        ],
    )
    logging.getLogger("aiohttp").setLevel(logging.WARNING)


def main() -> None:
    parser = argparse.ArgumentParser(description="小度大脑服务")
    parser.add_argument("-c", "--config", default=str(ROOT / "server" / "config.yaml"))
    args = parser.parse_args()
    cfg = load(args.config)
    setup_logging(str(getattr(cfg.log, "level", "INFO")))
    try:
        asyncio.run(amain(Path(args.config)))
    except KeyboardInterrupt:
        log.info("已停止")
