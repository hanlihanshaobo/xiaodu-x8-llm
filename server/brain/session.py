"""单次设备连接的会话状态机。

一句话的生命周期：
    麦克风 PCM → VAD 切句 → ASR → 唤醒词判定 → LLM 流式 → 分句 → TTS → 推给设备播放

为了语音体验，做了这些取舍：
- 放歌时仍然收音：只靠唤醒词触发，避免歌词被当成指令。
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
_WAKE_FILLER = re.compile(r"^(?:嗯|啊|呃|哎|诶)+")

# 常见同音误识别，放宽一点点
WAKE_ALIASES = {"小杜": "小度", "小渡": "小度", "小豆": "小度"}

# 看起来像指令的词：只有这些才值得多花一次模型调用做意图解析
_CMD_WORDS = (
    "开", "关", "调", "放", "听", "歌", "音乐", "暂停", "继续", "停",
    "下一", "上一", "换", "切歌", "音量", "大声", "小声",
    "灯", "空调", "加湿", "热水", "窗帘", "插座", "风扇", "温度", "亮度", "模式",
)

# 短句里出现这些词，基本是在聊天/提问，不必先跑一次意图解析
_CHAT_HINTS = (
    "吗", "呢", "什么", "怎么", "为什么", "哪", "几点", "多少", "谁",
    "是不是", "能不能", "可以", "天气", "笑话", "故事", "新闻",
    "时间", "日期", "星期", "你好", "谢谢", "再见",
)

INTENT_PROMPT = """你是语音助手的意图解析器。根据上下文，把用户最新一句话解析成 JSON，只输出 JSON，不要解释、不要 Markdown。
可选意图：
{"intent":"music_play","query":"歌名或歌手"}
{"intent":"music_control","action":"pause|resume|next|prev|stop|volume_up|volume_down"}
{"intent":"home","name":"设备名","action":"turn_on|turn_off|toggle|status","attribute":"brightness|temperature|null","value":数字或null}
{"intent":"chat"}

规则：
- 点歌、想听歌、想听某个歌手 → music_play；query 只写歌名或歌手，歌手和歌名之间用空格，不要带“播放”“放一首”“的”等词；没指定歌名就留空。
- 控制播放、切歌、音量 → music_control。
- 开关、调节、查询设备 → home；name 必须从设备列表里选最接近的一个，识别文字听错时结合上下文纠正。
- 只是聊天、问歌曲信息、问天气等 → chat。
- 只输出 JSON。

__CONTEXT__
"""


def _json_obj(text: str) -> dict:
    """从模型输出里抠出第一个 JSON 对象，容忍 ```json 包裹。"""
    s = (text or "").strip()
    s = re.sub(r"^```(?:json)?\s*", "", s)
    s = re.sub(r"\s*```$", "", s)
    m = re.search(r"\{.*\}", s, re.S)
    if not m:
        return {}
    try:
        data = json.loads(m.group(0))
        return data if isinstance(data, dict) else {}
    except json.JSONDecodeError:
        return {}


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
        self._music_active = False
        self._music_id: str | None = None
        self._next_item: dict | None = None
        self._speech_ids: set[str] = set()
        self._music_result: dict | None = None
        self.quality = "auto"

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
            self.quality = str(evt.get("quality") or "auto")
            music = getattr(self.brain, "music", None)
            if music is not None:
                music.set_quality(self.quality)
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
        elif kind == "stop":
            # 界面点“停止播放”：立刻停，且不能让服务端自动接下一首
            self._pending = 0
            self._speech_ids.clear()
            self._music_active = False
            self._music_id = None
            self._next_item = None
            self._music_result = None
            self._done_evt.set()
            if self._turn_task and not self._turn_task.done():
                self._turn_task.cancel()
            if self.vad is not None:
                self.vad.reset()
            self._settle_state()
        elif kind == "speak_done":
            sid = str(evt.get("id") or "")
            # 歌放完：只有当前这首歌的 id 才算，TTS 播完不能触发自动下一首
            if sid and self._music_id and sid == self._music_id:
                self._music_active = False
                self._music_id = None
                asyncio.create_task(self._auto_next())
                return
            if sid:
                if sid not in self._speech_ids:
                    return
                self._speech_ids.discard(sid)
            self._pending = len(self._speech_ids)
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
        self._speech_ids.clear()
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
        self._speech_ids.clear()
        self._done_evt.set()
        # 只停播报，音乐接着放
        await self.send({"type": "stop_speech"})
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

    def _asr_hint(self) -> str:
        """给支持 prompt 的 ASR 模型做热词偏置：当前/最近歌曲 + 家里设备名。"""
        parts: list[str] = []
        for item in self._played[-3:]:
            title = item.get("title") or ""
            artist = item.get("artist") or ""
            if title:
                parts.append(f"《{title}》{artist}".strip())
        ha = getattr(self.brain, "ha", None)
        if ha is not None and ha.enabled:
            try:
                parts.extend(ha.labels()[:20])
            except Exception:
                pass
        if not parts:
            return ""
        return "、".join(parts)[:120]

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
        asr = self.brain.asr
        if hasattr(asr, "set_hint"):
            asr.set_hint(self._asr_hint())
        loop = asyncio.get_running_loop()
        text = ""
        # Qwen3-ASR 在短音频上偶发空结果（自动语种判断不稳），重试一次
        for _attempt in range(2):
            try:
                text = await loop.run_in_executor(None, asr.transcribe, samples)
            except Exception as e:
                log.warning("识别失败：%s", e)
                text = ""
            if text and text.strip():
                break
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
        # 只认句首的唤醒词：手机/电脑里的“小度”出现在句子中间时不再误触发
        base = _WAKE_FILLER.sub("", norm)
        for w in words:
            key = _norm(w)
            if key and base.startswith(key):
                rest = base[len(key):]
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
        """本地确定的指令直接执行；其余先让模型解析意图，再决定动作。"""
        music = getattr(self.brain, "music", None)
        if music is not None and music.handles(query):
            if music.is_control(query) or not music.has_song_name(query):
                res = await music.handle(query)
                await self._run_music(res, query, display)
                return True
            # 带歌名的点歌交给模型：ASR 可能听错，模型能结合上下文纠正
            log.info("[%s] 点歌交给模型理解：%s", self.remote, query)

        # 裸歌名/歌手（“赵磊的话”“水山少年梦”）先查曲库：
        # 拼音同音字纠错能直接命中，比让模型猜更稳，也更快
        if music is not None and music.enabled:
            norm = _norm(query)
            if not any(w in norm for w in _CHAT_HINTS):
                try:
                    looks = await asyncio.get_running_loop().run_in_executor(
                        None, music.looks_like_song, query)
                except Exception:
                    looks = False
                if looks:
                    res = await music.play(query)
                    await self._run_music(res, query, display)
                    return True

        ha = getattr(self.brain, "ha", None)
        ha_on = bool(ha and ha.enabled)
        if ha_on and ha.direct:
            cmd = intent.parse(query)
            if cmd is not None and await self._execute_home(cmd, query, display):
                return True

        if self._looks_like_command(query):
            if await self._llm_intent(query, display):
                return True
        return False

    def _looks_like_command(self, query: str) -> bool:
        q = query or ""
        if any(w in q for w in _CMD_WORDS):
            return True
        # 短句很像“光说歌名/歌手”的点歌，先让模型判断一下，避免漏掉“赵磊的话”
        norm = _norm(q)
        if len(norm) <= 10 and not any(w in norm for w in _CHAT_HINTS):
            return True
        ha = getattr(self.brain, "ha", None)
        if ha is not None and ha.enabled:
            try:
                return any(name and name in q for name in ha.labels())
            except Exception:
                return False
        return False

    def _home_name_ok(self, query: str, target: str) -> bool:
        """模型选的设备名要和用户原话对得上，避免把“客厅等”猜成客厅打印机。"""
        q = _norm(query)
        t = _norm(target)
        if not t:
            return False
        if t in q:
            return True
        cmd = intent.parse(query)
        raw = _norm(cmd.target) if cmd is not None and cmd.target else ""
        if raw and (raw in t or t in raw):
            return True
        return False

    async def _execute_home(self, cmd: intent.Command, query: str, display: str) -> bool:
        """执行一条已经拆好的家居指令；找不到设备返回 False，交给模型再猜。"""
        ha = getattr(self.brain, "ha", None)
        if ha is None or not ha.enabled:
            return False
        await ha.ensure()
        domain = None
        if cmd.attribute == "brightness":
            domain = "light"
        elif cmd.attribute == "temperature":
            domain = "climate"

        devices = ha.match(cmd.target, domain=domain)
        if not devices:
            log.info("[%s] 口令没对上设备，交给模型：%s", self.remote, cmd.target)
            return False

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

    async def _llm_intent(self, query: str, display: str) -> bool:
        """让模型先结合上下文判断意图，再执行；不确定就返回 False 走普通对话。"""
        music = getattr(self.brain, "music", None)
        music_on = bool(music and music.enabled)
        ha = getattr(self.brain, "ha", None)
        ha_on = bool(ha and ha.enabled)
        if ha_on:
            try:
                await ha.ensure()
            except Exception:
                pass

        labels = ha.labels() if ha_on else []
        if self._played:
            cur = self._played[-1]
            current = f"《{cur.get('title', '')}》- {cur.get('artist', '')}"
        else:
            current = "无"
        context = f"当前播放：{current}。"
        if labels:
            context += "\n设备列表：" + "、".join(labels) + "。"

        msgs = [{"role": "system", "content": INTENT_PROMPT.replace("__CONTEXT__", context)}]
        msgs += self.history[-6:]
        msgs.append({"role": "user", "content": query})

        out: list[str] = []
        try:
            async for ev in self.brain.llm.stream(
                    msgs, temperature=0.1, max_tokens=160):
                if ev["type"] == "text":
                    out.append(ev["text"])
        except Exception as e:
            log.info("[%s] 意图解析失败：%s", self.remote, e)
            return False

        data = _json_obj("".join(out))
        kind = str(data.get("intent") or "").strip()
        log.info("[%s] 意图：%s %s", self.remote, kind, data)

        if kind == "music_play" and music_on:
            q = str(data.get("query") or "").strip()
            q = re.sub(r"\s*[-—–]\s*", " ", q).strip()
            # 模型有时把“赵磊的话”简化成“赵磊”，丢掉歌名；原句里有歌名就用原句，
            # 交给 music.play 的拼音匹配去纠正同音字。
            try:
                _, title = music._split_artist_title(q)
                _, raw_title = music._split_artist_title(query)
                if not title and raw_title:
                    q = query
            except Exception:
                pass
            res = await music.play(q)
            await self._run_music(res, query, display)
            return True

        if kind == "music_control" and music_on:
            action = str(data.get("action") or "").strip()
            word = {
                "pause": "暂停", "resume": "继续放", "next": "下一首",
                "prev": "上一首", "stop": "停止播放",
                "volume_up": "音量大一点", "volume_down": "音量小一点",
            }.get(action)
            if word:
                res = await music.handle(word)
                await self._run_music(res, query, display)
                return True

        if kind == "home" and ha_on:
            target = str(data.get("name") or "").strip()
            action = str(data.get("action") or "").strip()
            attribute = data.get("attribute")
            value = data.get("value")
            if attribute not in ("brightness", "temperature"):
                attribute = None
            if target and action in ("turn_on", "turn_off", "toggle", "status"):
                if attribute and value is None:
                    return False
                if not self._home_name_ok(query, target):
                    log.info("[%s] 设备名不可靠，先确认：%s → %s", self.remote, query, target)
                    await self._reply_only(
                        f"你是要控制{target}吗？请再说一遍完整指令。", query, display)
                    return True
                try:
                    value = float(value) if value is not None else None
                except (TypeError, ValueError):
                    value = None
                cmd = intent.Command(action, target, value, attribute)
                return await self._execute_home(cmd, query, display)

        return False

    async def _run_music(self, res: dict, query: str, display: str) -> None:
        """放歌与控歌。放歌后仍能听见唤醒词；播报时只闭麦到 TTS 结束。"""
        cmd = res.get("cmd")
        if cmd == "pause":
            await self.send({"type": "audio_pause"})
            await self._say(res.get("say") or "好，暂停了。")
            return
        if cmd == "resume":
            # 先说完“接着放”，再恢复音乐，避免两句叠在一起
            await self._say(res.get("say") or "接着放。")
            await self.send({"type": "audio_resume"})
            return
        if cmd in ("vol_up", "vol_down"):
            await self.send({"type": "volume", "dir": "up" if cmd == "vol_up" else "down"})
            await self._say(res.get("say") or "好的。")
            return
        if cmd == "stop":
            self._music_active = False
            self._music_id = None
            await self.send({"type": "stop"})
            self._pending = 0
            self._speech_ids.clear()
            self._done_evt.set()
            await self._say(res.get("say") or "好，停了。")
            return
        if cmd == "next":
            self._music_active = False
            self._music_id = None
            await self.send({"type": "stop", "silent": True})
            self._pending = 0
            self._speech_ids.clear()
            self._done_evt.set()
            await self._auto_next()
            return

        if cmd == "prev":
            self._music_active = False
            self._music_id = None
            await self.send({"type": "stop", "silent": True})
            self._pending = 0
            self._speech_ids.clear()
            self._done_evt.set()
            if len(self._played) >= 2:
                self._played.pop()          # 丢掉当前
                item = self._played.pop()   # 回到上一首
                await self._emit_music(item)
                self._played.append(item)
                asyncio.create_task(self._prepare_next())
                self._settle_state()
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
        asyncio.create_task(self._prepare_next())
        log.info("[%s] 下发音乐 %s -> %s", self.remote, item["title"],
                 f"{self.public_url}/music/{item['path']}")
        self.brain.log_turn(self.remote, query, f"{say}〔播放 {item['title']}〕")
        # 放歌后不再自动续话：歌词容易被 ASR 当成指令，必须先喊唤醒词
        self.conv_until = 0
        if not self._speech_ids:
            self._settle_state()

    async def _start_music_item(self, item: dict) -> None:
        """模型点歌成功后真正下发音乐（确认语由模型自己说）。"""
        await self._emit_music(item)
        self._played.append(item)
        if len(self._played) > 20:
            self._played.pop(0)
        asyncio.create_task(self._prepare_next())
        log.info("[%s] 模型点歌：%s - %s", self.remote,
                 item.get("title", ""), item.get("artist", ""))

    async def _run_music_tool(self, name: str, args: dict) -> str:
        """执行模型发起的音乐工具调用，返回一段给模型看的结果。"""
        music = getattr(self.brain, "music", None)
        if music is None:
            return "音乐服务没有开启。"
        if name == "music_play":
            q = str(args.get("query") or args.get("name") or "").strip()
            res = await music.play(q)
            if res.get("audio"):
                self._music_result = res
                return f"已准备好《{res.get('title', '')}》- {res.get('artist', '')}。"
            return str(res.get("say") or "没找到这首歌。")
        if name == "music_control":
            return await self._music_control(str(args.get("action") or ""))
        return "不支持的音乐操作。"

    async def _music_control(self, action: str) -> str:
        """模型控歌：直接执行，确认语由模型自己说，避免和 TTS 叠音。"""
        if action == "pause":
            await self.send({"type": "audio_pause"})
            return "已暂停。"
        if action == "resume":
            await self.send({"type": "audio_resume"})
            return "已继续播放。"
        if action in ("volume_up", "volume_down"):
            await self.send({"type": "volume", "dir": "up" if action == "volume_up" else "down"})
            return "音量已调整。"

        if action in ("stop", "next", "prev"):
            self._music_active = False
            self._music_id = None
            await self.send({"type": "stop", "silent": action in ("next", "prev")})
            self._pending = 0
            self._speech_ids.clear()
            self._done_evt.set()

        if action == "stop":
            self._next_item = None
            return "已停止播放。"
        if action == "next":
            await self._auto_next()
            return "已切到下一首。"
        if action == "prev":
            self._next_item = None
            if len(self._played) >= 2:
                self._played.pop()
                item = self._played.pop()
                await self._emit_music(item)
                self._played.append(item)
                asyncio.create_task(self._prepare_next())
                return "已回到上一首。"
            return "已经是第一首了。"
        return "不支持的音乐操作。"

    async def _prepare_next(self) -> None:
        """提前把下一首准备好并让设备先下载，切歌时不用等。"""
        try:
            last = self._played[-1]["title"] if self._played else ""
            res = None
            for _ in range(3):
                res = await self.brain.music.handle("放首歌")
                if not res.get("audio"):
                    break
                if res.get("title") != last:
                    break
            audio = (res or {}).get("audio")
            if not audio:
                return
            item = {
                "path": audio.name,
                "title": res.get("title", ""),
                "artist": res.get("artist", ""),
                "lrc": res.get("lrc", ""),
                "pic": res.get("pic", ""),
            }
            self._next_item = item
            await self.send({
                "type": "preload",
                "url": f"{self.public_url}/music/{item['path']}",
                "title": item["title"],
            })
            log.info("[%s] 预加载下一首：%s", self.remote, item["title"])
        except Exception as e:
            log.info("预加载失败：%s", e)

    async def _auto_next(self) -> None:
        """自然放完 / 点下一首：直接接一首新的，不播报歌名。"""
        try:
            item = self._next_item
            self._next_item = None
            if not item:
                last = self._played[-1]["title"] if self._played else ""
                res = None
                for _ in range(3):
                    res = await self.brain.music.handle("放首歌")
                    if not res.get("audio"):
                        break
                    if res.get("title") != last:
                        break
                audio = (res or {}).get("audio")
                if not audio:
                    self._settle_state()
                    return
                item = {
                    "path": audio.name,
                    "title": res.get("title", ""),
                    "artist": res.get("artist", ""),
                    "lrc": res.get("lrc", ""),
                    "pic": res.get("pic", ""),
                }
            await self._emit_music(item)
            self._played.append(item)
            if len(self._played) > 20:
                self._played.pop(0)
            log.info("[%s] 自动下一首：%s", self.remote, item["title"])
            asyncio.create_task(self._prepare_next())
        except Exception as e:
            log.warning("自动下一首失败：%s", e)
            self._settle_state()

    async def _emit_music(self, item: dict) -> None:
        sid = uuid4().hex[:8]
        self._music_id = sid
        self._music_active = True
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
        music = getattr(self.brain, "music", None)
        music_on = bool(music and music.enabled)

        hints = []
        if ha_on:
            hints.append(ha.prompt_hint())
        if music_on:
            hints.append(music.prompt_hint())
            if self._played:
                cur = self._played[-1]
                hints.append(f"当前正在播放《{cur.get('title', '')}》- {cur.get('artist', '')}。")
            else:
                hints.append("当前没有在放音乐。")
        hint = "\n".join(h for h in hints if h)

        tools: list[dict] = []
        if ha_on and ha.use_tools:
            tools += ha.tools()
        if music_on:
            tools += music.tools()
        if not tools:
            tools = None

        self._music_result = None
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
            if not calls:
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
                    if name.startswith("music_") and music_on:
                        result = await self._run_music_tool(name, args)
                    elif ha_on:
                        target = str(args.get("name") or args.get("target") or "").strip()
                        if name != "home_list" and target and not self._home_name_ok(query, target):
                            log.info("[%s] 工具设备名不可靠，拒绝执行：%s → %s",
                                     self.remote, query, target)
                            result = f"设备名“{target}”和用户原话对不上，先不要执行，请用户确认。"
                        else:
                            result = await ha.run_tool(name, args)
                    else:
                        result = "这个设备我现在控制不了。"
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
        if self._music_result is not None:
            item = self._music_result
            self._music_result = None
            await self._start_music_item(item)
            # 放歌后必须先喊唤醒词，避免歌词被当成指令
            self.conv_until = 0
        else:
            self.conv_until = time.time() + float(self.cfg.wake.follow_up_seconds)
        self._settle_state()

    # ---------- 播报 ----------

    async def _say_ack(self) -> None:
        """唤醒应答走预合成文件，省掉 1.5 秒的 TTS 时间。"""
        text = random.choice(list(self.cfg.wake.ack))
        name = self.brain.ack_files.get(text)
        if name:
            sid = uuid4().hex[:8]
            self._speech_ids.add(sid)
            self._pending += 1
            self.set_state("speaking")
            await self.send({
                "type": "speak",
                "id": sid,
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
        self._speech_ids.add(sid)
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
