"""单次设备连接的会话状态机。

一句话的生命周期：
    麦克风 PCM → VAD 切句 → ASR → 唤醒词判定 → LLM 流式 → 分句 → TTS → 推给设备播放

为了语音体验，做了这些取舍：
- 半双工：播放时设备停发麦克风，避免喇叭声音被自己听见。
- 分句合成：模型吐到第一个句号就先念，不等整段生成完。
- 播报最多领先 3 句，超过就等设备播完再继续，防止内存和延迟失控。
"""
from __future__ import annotations

import asyncio
import json
import logging
import random
import re
import time
from datetime import datetime
from uuid import uuid4

import aiohttp

from . import intent
from .ha import reply_for as ha_reply

log = logging.getLogger("session")

SENT_END = "。！？!?；;\n"
PAUSE = "，,、：:"
_NOISE = re.compile(r"[\s，。！？、；：,.!?;:…—\-~\"'“”‘’()（）]+")
_LEAD = re.compile(r"^[，。！？、；：,.!?;:\s]+")

# 常见同音误识别，放宽一点点
WAKE_ALIASES = {"小杜": "小度", "小渡": "小度"}


def _norm(text: str) -> str:
    t = _NOISE.sub("", text)
    for bad, good in WAKE_ALIASES.items():
        t = t.replace(bad, good)
    return t


def take_sentence(buf: str) -> tuple[str | None, str]:
    """从缓冲区头部切一句可播报的话。

    优先在句号处切；如果累积够长，就退一步在逗号处切，
    这样第一句能更早开始合成，用户不用等整段生成完。
    """
    for i, ch in enumerate(buf):
        if ch in SENT_END and i >= 1:
            return buf[: i + 1], buf[i + 1:]
    if len(buf) >= 8:
        idx = max(buf.rfind(c) for c in PAUSE)
        if idx >= 6:
            return buf[: idx + 1], buf[idx + 1:]
    if len(buf) >= 30:
        idx = max(buf.rfind(c) for c in PAUSE)
        if idx >= 8:
            return buf[: idx + 1], buf[idx + 1:]
    return None, buf


class Session:
    def __init__(self, ws: aiohttp.web.WebSocketResponse, brain, public_url: str, remote: str = "?"):
        self.ws = ws
        self.brain = brain
        self.remote = remote
        self.cfg = brain.cfg
        # 按设备实际连进来的网卡地址生成 TTS 链接，避免多网卡时指到设备够不着的那张网卡
        self.public_url = public_url
        try:
            self.vad = brain.new_vad()
        except Exception as e:
            log.error("VAD 初始化失败，本连接无法收音：%s", e)
            self.vad = None
        self.state = "idle"
        # 历史挂在 Brain 上，断线重连不会丢上下文
        self.history: list[dict] = brain.history_for(self.remote)
        self.conv_until = 0.0
        self._audio_q: asyncio.Queue[bytes] = asyncio.Queue(maxsize=400)
        self._send_lock = asyncio.Lock()
        self._turn_task: asyncio.Task | None = None
        self._pending = 0
        self._done_evt = asyncio.Event()
        self._closing = False
        self._last_activity = time.time()
        self._turn_started = 0.0
        self._played: list[dict] = []

    # ---------- 基础设施 ----------

    async def send(self, obj: dict) -> None:
        if self.ws.closed:
            return
        async with self._send_lock:
            try:
                await self.ws.send_str(json.dumps(obj, ensure_ascii=False))
            except (ConnectionResetError, RuntimeError):
                self._closing = True

    def trim_history(self) -> None:
        """只留最近若干轮，避免长期运行把上下文撑爆。"""
        limit = max(4, int(getattr(self.cfg.llm, "history_turns", 8)) * 2)
        if len(self.history) > limit:
            del self.history[: len(self.history) - limit]

    def set_state(self, value: str) -> None:
        if value == self.state:
            return
        self.state = value
        log.debug("[%s] 状态 → %s", self.remote, value)
        asyncio.create_task(self.send({"type": "state", "value": value}))

    # ---------- 主循环 ----------

    async def run(self) -> None:
        log.info("[%s] 设备接入", self.remote)
        worker = asyncio.create_task(self._audio_worker())
        watchdog = asyncio.create_task(self._watchdog())
        try:
            async for msg in self.ws:
                if msg.type == aiohttp.WSMsgType.BINARY:
                    try:
                        self._audio_q.put_nowait(msg.data)
                    except asyncio.QueueFull:
                        pass
                elif msg.type == aiohttp.WSMsgType.TEXT:
                    await self._on_control(msg.data)
                elif msg.type == aiohttp.WSMsgType.ERROR:
                    break
        finally:
            self._closing = True
            worker.cancel()
            watchdog.cancel()
            if self._turn_task:
                self._turn_task.cancel()
            self.trim_history()
            log.info("[%s] 设备断开（code=%s）", self.remote, self.ws.close_code)

    async def _on_control(self, raw: str) -> None:
        try:
            evt = json.loads(raw)
        except json.JSONDecodeError:
            return
        kind = evt.get("type")
        if kind == "hello":
            await self.send(
                {
                    "type": "ready",
                    "sampleRate": 16000,
                    "wakeWords": list(self.cfg.wake.words),
                    "model": self.brain.llm.model,
                }
            )
        elif kind == "interrupt":
            await self._interrupt()
        elif kind == "speak_done":
            self._pending = max(0, self._pending - 1)
            self._done_evt.set()
            if self._pending == 0 and not (self._turn_task and not self._turn_task.done()):
                self._settle_state()
        elif kind == "command":
            await self._on_text_command(str(evt.get("text") or ""))
        elif kind == "ping":
            await self.send({"type": "pong", "t": int(time.time())})
        elif kind == "log":
            log.warning("[%s] 设备日志：%s", self.remote, evt.get("text"))

    async def _on_text_command(self, text: str) -> None:
        """界面上的播放按钮：走和语音一样的直连逻辑。"""
        norm = _norm(text)
        if not norm:
            return
        music = getattr(self.brain, "music", None)
        if music is not None and music.handles(norm):
            res = await music.handle(norm)
            await self._run_music(res, norm, text)
            return
        log.info("[%s] 忽略界面指令：%s", self.remote, text)

    async def _interrupt(self) -> None:
        log.info("[%s] 打断", self.remote)
        self._pending = 0
        self._done_evt.set()
        if self._turn_task and not self._turn_task.done():
            self._turn_task.cancel()
        if self.vad is not None: self.vad.reset()
        self._settle_state()

    def _settle_state(self) -> None:
        self.set_state("listening" if time.time() < self.conv_until else "idle")

    async def _audio_worker(self) -> None:
        while not self._closing:
            data = await self._audio_q.get()
            try:
                if self.vad is None:
                    await self.send({"type": "error", "message": "服务端语音检测未就绪"})
                    return
                for seg in self.vad.feed(data):
                    # 播报/思考时又听到人说话 → 打断当前回答，按新的一句处理
                    if self.state in ("thinking", "speaking"):
                        await self._barge_in()
                    await self._on_segment(seg)
            except Exception as e:
                log.exception("VAD 处理异常：%s", e)

    async def _barge_in(self) -> None:
        """用户插话：停掉当前回答，立刻听新的这句。"""
        if time.time() - self._turn_started < 1.0:
            return  # 刚开口的瞬间别误判成插话
        log.info("[%s] 打断，改听新的", self.remote)
        if self._turn_task and not self._turn_task.done():
            self._turn_task.cancel()
        self._pending = 0
        self._done_evt.set()
        await self.send({"type": "stop"})
        self.set_state("listening")

    async def _watchdog(self) -> None:
        """超时 / 空闲维护：预约状态回落、清理旧音频。"""
        while not self._closing:
            await asyncio.sleep(2)
            if self.state == "listening" and time.time() > self.conv_until:
                self.set_state("idle")
            if time.time() - self._last_activity > 600:
                self.brain.tts.sweep()
                self._last_activity = time.time()

    # ---------- 一句话 ----------

    async def _on_segment(self, samples) -> None:
        if self.brain.asr is None:
            await self.send({"type": "error", "message": "语音识别不可用，请检查服务端 ASR 配置"})
            return
        dur = len(samples) / 16000.0
        if dur < 0.7:
            log.debug("[%s] 片段太短(%.2fs)，丢弃", self.remote, dur)
            return
        if getattr(self.brain.cfg.asr, "save_audio", False):
            self.brain.save_utterance(samples)
        loop = asyncio.get_running_loop()
        try:
            text = await loop.run_in_executor(None, self.brain.asr.transcribe, samples)
        except Exception as e:
            log.warning("识别失败：%s", e)
            await self.send({"type": "error", "message": "语音识别失败"})
            return
        text = (text or "").strip()
        if text and not re.search(r"[\u4e00-\u9fff]", text) and len(text) < 8:
            log.info("[%s] 丢弃疑似幻听：%s", self.remote, text)
            return
        if not text:
            return
        await self.send({"type": "asr", "text": text, "final": True})

        norm = _norm(text)
        if not norm:
            return

        cfg = self.cfg.wake
        in_conv = time.time() < self.conv_until

        handoff = [ _norm(w) for w in (getattr(cfg, "handoff_words", []) or []) ]
        if in_conv and any(w in norm for w in handoff):
            # 用户喊了原生唤醒词，主动让位，别和系统抢
            self.conv_until = 0
            await self.send({"type": "release"})
            self.set_state("idle")
            return

        if in_conv and any(w in norm for w in cfg.exit_words):
            self.conv_until = 0
            await self._say("好的，有事再叫我。")
            self.set_state("idle")
            return

        query, woke = self._strip_wake(norm)
        if woke:
            self.conv_until = time.time() + float(cfg.follow_up_seconds)
            if len(query) < 2:
                await self._say_ack()
                self.set_state("listening")
                return
        elif in_conv:
            query = norm
        else:
            log.info("[%s] 忽略（未唤醒）：%s", self.remote, text)
            return

        self._start_turn(query, text)

    def _strip_wake(self, norm: str) -> tuple[str, bool]:
        words = sorted(self.cfg.wake.words, key=len, reverse=True)
        for w in words:
            key = _norm(w)
            idx = norm.find(key)
            if idx >= 0:
                rest = norm[idx + len(key):]
                return _LEAD.sub("", rest), True
        return norm, False

    def _start_turn(self, query: str, display: str) -> None:
        if self._turn_task and not self._turn_task.done():
            self._turn_task.cancel()
        self._turn_started = time.time()
        self._turn_task = asyncio.create_task(self._turn(query, display))

    async def _turn(self, query: str, display: str) -> None:
        self.set_state("thinking")
        log.info("[%s] 用户：%s", self.remote, display)

        try:
            # 先试本地口令：开关灯这类高频动作不该等大模型
            if await self._try_direct(query, display):
                return
            await self._run_turn(query, display)
        except asyncio.CancelledError:
            raise
        except Exception as e:
            log.exception("对话失败：%s", e)
            await self.send({"type": "error", "message": "模型连不上，检查一下电脑上的服务"})
            await self._say("我这会儿连不上电脑，稍后再试。")

    # ---------- 智能家居 ----------

    async def _try_direct(self, query: str, display: str) -> bool:
        """规则命中就本地执行，不走模型。返回 False 表示交给模型。"""
        music = getattr(self.brain, "music", None)
        if music is not None and music.handles(query):
            res = await music.handle(query)
            await self._run_music(res, query, display)
            return True

        ha = getattr(self.brain, "ha", None)
        if ha is None or not ha.enabled or not ha.direct:
            return False

        cmd = intent.parse(query)
        if cmd is None:
            return False

        await ha.ensure()
        domain = None
        if cmd.attribute == "brightness":
            domain = "light"
        elif cmd.attribute == "temperature":
            domain = "climate"

        devices = ha.match(cmd.target, domain=domain)
        if not devices:
            log.info("[%s] 口令没对上设备：%s", self.remote, cmd.target)
            # 既然已经是在下命令，就宁可说没找到，也不能放给模型去"假装执行"
            kind = ha.domain_of(cmd.target) or domain
            cands = ha.by_domain(kind) if kind else []
            if cands:
                names = "、".join(d.label for d in cands[:3])
                text = f"{cmd.target}我这儿没有，你是要控制{names}吗？"
            else:
                text = f"{cmd.target}我控制不了，家里没有这个设备。"
            await self._reply_only(text, query, display)
            return True

        if len(devices) > 1:
            names = "、".join(d.label for d in devices[:3])
            log.info("[%s] 口令有歧义：%s → %s", self.remote, cmd.target, names)
            await self._reply_only(f"你说的是哪一个？有{names}。", query, display)
            return True

        dev = devices[0]
        try:
            if cmd.action == "status":
                text = ha_reply(dev, "status")
            elif cmd.attribute == "brightness" and cmd.value is not None:
                dev = await ha.set_brightness(dev, cmd.value)
                text = f"好的，{dev.label}亮度调到百分之{int(cmd.value)}了。"
            elif cmd.attribute == "temperature" and cmd.value is not None:
                dev = await ha.set_temperature(dev, cmd.value)
                text = f"好的，{dev.label}调到{int(cmd.value)}度了。"
            else:
                dev = await ha.act(dev, cmd.action)
                text = ha_reply(dev, cmd.action)
        except Exception as e:
            log.warning("[%s] HA 控制失败：%s", self.remote, e)
            text = f"{dev.label}没控制成功，稍后再试。"

        log.info("[%s] 直控：%s → %s", self.remote, text, dev.state)
        await self._reply_only(text, query, display)
        return True

    async def _run_music(self, res: dict, query: str, display: str) -> None:
        """放歌与控歌。音频走局域网，播放期间设备不收音，不会把歌当成指令。"""
        cmd = res.get("cmd")
        if cmd == "pause":
            await self.send({"type": "audio_pause"})
            await self._say(res.get("say") or "好，暂停了。")
            return
        if cmd == "resume":
            await self.send({"type": "audio_resume"})
            await self._say(res.get("say") or "接着放。")
            return
        if cmd in ("vol_up", "vol_down"):
            await self.send({"type": "volume", "dir": "up" if cmd == "vol_up" else "down"})
            await self._say(res.get("say") or "好的。")
            return
        if cmd == "stop":
            await self.send({"type": "stop"})
            self._pending = 0
            self._done_evt.set()
            await self._say(res.get("say") or "好，停了。")
            return
        if cmd == "next":
            await self.send({"type": "stop"})
            self._pending = 0
            self._done_evt.set()
            res = await self.brain.music.handle("放首歌")

        if cmd == "prev":
            await self.send({"type": "stop"})
            self._pending = 0
            self._done_evt.set()
            if len(self._played) >= 2:
                self._played.pop()          # 丢掉当前
                item = self._played.pop()   # 回到上一首
                await self._emit_music(item)
                return
            await self._say("已经是第一首了。")
            return

        audio = res.get("audio")
        say = res.get("say") or ""
        if audio is None:
            await self._reply_only(say, query, display)
            return

        item = {
            "path": audio.name,
            "title": res.get("title", ""),
            "artist": res.get("artist", ""),
            "lrc": res.get("lrc", ""),
            "pic": res.get("pic", ""),
        }
        await self._speak(say)  # 先报名，不阻塞
        await self._emit_music(item)
        self._played.append(item)
        if len(self._played) > 20:
            self._played.pop(0)
        log.info("[%s] 下发音乐 %s -> %s", self.remote, item["title"],
                 f"{self.public_url}/music/{item['path']}")
        self.brain.log_turn(self.remote, query, f"{say}〔播放 {item['title']}〕")
        self.conv_until = time.time() + float(self.cfg.wake.follow_up_seconds)

    async def _emit_music(self, item: dict) -> None:
        sid = uuid4().hex[:8]
        self._pending += 1
        self.set_state("speaking")
        await self.send(
            {
                "type": "music",
                "id": sid,
                "url": f"{self.public_url}/music/{item['path']}",
                "title": item.get("title", ""),
                "artist": item.get("artist", ""),
                "lrc": item.get("lrc", ""),
                "pic": f"{self.public_url}/music/{item['pic']}" if item.get("pic") else "",
            }
        )

    async def _reply_only(self, text: str, query: str, display: str) -> None:
        """不经过模型，直接说一句，并把这轮记进历史。"""
        self.history.append({"role": "user", "content": query})
        self.history.append({"role": "assistant", "content": text})
        self.brain.log_turn(self.remote, display, text)
        await self._say(text)
        self.conv_until = time.time() + float(self.cfg.wake.follow_up_seconds)
        self._settle_state()

    async def _run_turn(self, query: str, display: str) -> None:
        ha = getattr(self.brain, "ha", None)
        ha_on = bool(ha and ha.enabled)
        if ha_on:
            await ha.ensure()
        hint = ha.prompt_hint() if ha_on else ""
        tools = ha.tools() if (ha_on and ha.use_tools) else None
        now = datetime.now()
        week = "一二三四五六日"[now.weekday()]
        time_hint = f"当前时间：{now:%Y年%m月%d日} {now:%H:%M}，星期{week}。问到日期、时间、星期时以此为准，不要猜。"
        hint = f"{hint}\n{time_hint}" if hint else time_hint
        log.info("[%s] 历史 %d 条", self.remote, len(self.history))

        msgs = self.brain.llm.build_messages(
            self.history + [{"role": "user", "content": query}],
            int(self.cfg.llm.history_turns),
            extra_system=hint,
        )
        t0 = time.time()
        first_token = None
        first_audio = None
        full: list[str] = []

        # 最多两轮：模型调工具 → 执行 → 模型接着把结果说成话。
        # 第二轮不再给工具，逼它输出文本，别陷入连续调用的循环。
        for _round in range(2):
            buf = ""
            calls: list[dict] = []
            async for ev in self.brain.llm.stream(msgs, tools=tools if _round == 0 else None):
                if ev["type"] == "text":
                    piece = ev["text"]
                    if first_token is None:
                        first_token = time.time() - t0
                        log.info("[%s] 首字 %.2fs", self.remote, first_token)
                    buf += piece
                    full.append(piece)
                    await self.send({"type": "reply", "delta": piece})
                    while True:
                        sentence, buf = take_sentence(buf)
                        if not sentence:
                            break
                        await self._speak(sentence)
                        if first_audio is None:
                            first_audio = time.time() - t0
                            log.info("[%s] 首段音频 %.2fs（含合成）", self.remote, first_audio)
                elif ev["type"] == "tool_calls":
                    calls = ev["calls"]

            if buf.strip():
                await self._speak(buf)
            if not calls or not ha_on:
                break

            msgs.append({"role": "assistant", "content": None, "tool_calls": calls})
            for call in calls:
                name = (call.get("function") or {}).get("name") or ""
                raw_args = (call.get("function") or {}).get("arguments") or "{}"
                try:
                    args = json.loads(raw_args)
                except json.JSONDecodeError:
                    args = {}
                log.info("[%s] 工具调用 %s %s", self.remote, name, args)
                try:
                    result = await ha.run_tool(name, args)
                except Exception as e:
                    log.warning("[%s] 工具执行失败：%s", self.remote, e)
                    result = f"执行失败：{e}"
                msgs.append({"role": "tool", "tool_call_id": call.get("id", ""), "content": result})

        answer = "".join(full).strip()
        if answer:
            # 这一轮真正答完了才记进历史；被中途打断的不留痕
            self.history.append({"role": "user", "content": query})
            self.history.append({"role": "assistant", "content": answer})
            self.trim_history()
            log.info("[%s] 小度：%s", self.remote, answer[:80])
        self.brain.log_turn(self.remote, display, answer)
        self.conv_until = time.time() + float(self.cfg.wake.follow_up_seconds)
        self._settle_state()

    # ---------- 播报 ----------

    async def _say_ack(self) -> None:
        """唤醒应答走预合成文件，省掉 1.5 秒的 TTS 时间。"""
        text = random.choice(list(self.cfg.wake.ack))
        name = self.brain.ack_files.get(text)
        if name:
            self._pending += 1
            self.set_state("speaking")
            await self.send({
                "type": "speak",
                "id": uuid4().hex[:8],
                "url": f"{self.public_url}/tts/{name}",
                "text": text,
            })
            return
        await self._say(text)

    async def _say(self, text: str) -> None:
        await self._speak(text)
        # 等待播完，保证应答结束后状态干净
        for _ in range(40):
            if self._pending == 0:
                break
            await asyncio.sleep(0.25)

    async def _speak(self, text: str) -> None:
        if self.state != "speaking":
            if self.vad is not None: self.vad.reset()  # 进入播报前清掉半句残留，免得播完立刻误触发
        while self._pending >= 3 and not self._closing:
            self._done_evt.clear()
            try:
                await asyncio.wait_for(self._done_evt.wait(), 30)
            except asyncio.TimeoutError:
                self._pending = 0
        path = await self.brain.tts.synth(text)
        if path is None:
            return
        sid = uuid4().hex[:8]
        self._pending += 1
        self.set_state("speaking")
        await self.send(
            {
                "type": "speak",
                "id": sid,
                "url": f"{self.brain.public_url}/tts/{path.name}",
                "text": text,
            }
        )
