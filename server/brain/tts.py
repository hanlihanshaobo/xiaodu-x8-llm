"""语音合成。

三个后端，用 tts.engine 切换：

  edge   微软在线 TTS，中文最自然，需要联网（默认）
  openai 任意 OpenAI 兼容的 /v1/audio/speech（例如局域网里的 omlx / Breeze-TTS）
  offline 本地 sherpa-onnx VITS，断网兜底

同一个 synth() 出口，上层不关心用的是谁。
"""
from __future__ import annotations

import asyncio
import logging
import re
import time
import uuid
from pathlib import Path

log = logging.getLogger("tts")

# TTS 不喜欢的东西：Markdown 符号、表情、括号注释
_CLEAN = [
    (re.compile(r"[*_`#>\[\]{}]"), ""),
    (re.compile(r"\([^)]{0,30}\)"), ""),
    (re.compile(r"（[^）]{0,40}）"), ""),
    (re.compile(r"https?://\S+"), "链接"),
    (re.compile(r"\s+"), " "),
]


def clean(text: str) -> str:
    t = text
    for pat, rep in _CLEAN:
        t = pat.sub(rep, t)
    return t.strip(" ，,、")


class TTS:
    def __init__(self, cfg, cache_dir: Path):
        self.cfg = cfg
        self.engine = str(getattr(cfg, "engine", "edge")).lower()
        self.voice = str(getattr(cfg, "voice", ""))
        self.rate = str(getattr(cfg, "rate", "+0%"))
        self.volume = str(getattr(cfg, "volume", "+0%"))
        self.cache_dir = cache_dir
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        self._offline = None

        oa = getattr(cfg, "openai", None)
        self.oa_base = str(getattr(oa, "base_url", "")).rstrip("/") if oa else ""
        self.oa_key = str(getattr(oa, "api_key", "")) if oa else ""
        self.oa_model = str(getattr(oa, "model", "")) if oa else ""
        self.oa_voice = str(getattr(oa, "voice", "") or "") if oa else ""
        self.oa_lang = str(getattr(oa, "language", "") or "") if oa else ""
        self.oa_format = str(getattr(oa, "response_format", "wav")) if oa else "wav"
        self.oa_speed = float(getattr(oa, "speed", 1.0)) if oa else 1.0

    async def synth(self, text: str) -> Path | None:
        text = clean(text)
        if not text:
            return None
        base = self.cache_dir / f"{int(time.time() * 1000)}_{uuid.uuid4().hex[:6]}"

        if self.engine == "offline":
            return await self._synth_offline(text, base)

        if self.engine == "openai":
            p = await self._synth_openai(text, base)
            if p:
                return p
            log.warning("OpenAI TTS 不可用，回退 edge-tts")
            return await self._synth_edge(text, base)

        # 默认 edge：断网时回退到局域网里的 TTS（如果配了），再不行才放弃
        p = await self._synth_edge(text, base)
        if p:
            return p
        if self.oa_base and self.oa_model:
            log.warning("edge-tts 失败，改用局域网 TTS 兜底")
            return await self._synth_openai(text, base)
        return None

    # ---------- 微软 edge-tts ----------

    async def _synth_edge(self, text: str, base: Path) -> Path | None:
        path = base.with_suffix(".mp3")
        for attempt in range(2):
            try:
                import edge_tts

                comm = edge_tts.Communicate(text, self.voice or "zh-CN-XiaoxiaoNeural",
                                            rate=self.rate, volume=self.volume)
                await comm.save(str(path))
                if path.exists() and path.stat().st_size > 512:
                    return path
                log.warning("edge-tts 产出为空：%s", text[:20])
            except Exception as e:  # 网络抖动重试一次
                log.warning("edge-tts 第 %d 次失败：%s", attempt + 1, e)
                await asyncio.sleep(0.3)
        log.error("TTS 失败，跳过这句：%s", text[:30])
        return None

    # ---------- OpenAI 兼容 /audio/speech ----------

    async def _synth_openai(self, text: str, base: Path) -> Path | None:
        if not self.oa_base or not self.oa_model:
            return None
        import aiohttp

        payload = {
            "model": self.oa_model,
            "input": text,
            "response_format": self.oa_format,
            "speed": self.oa_speed,
        }
        if self.oa_voice:
            payload["voice"] = self.oa_voice
        if self.oa_lang:
            payload["language"] = self.oa_lang

        headers = {"Content-Type": "application/json"}
        if self.oa_key:
            headers["Authorization"] = f"Bearer {self.oa_key}"

        try:
            timeout = aiohttp.ClientTimeout(total=30, sock_connect=5)
            async with aiohttp.ClientSession(timeout=timeout) as sess:
                async with sess.post(f"{self.oa_base}/audio/speech", json=payload, headers=headers) as resp:
                    if resp.status != 200:
                        body = (await resp.text())[:200]
                        raise RuntimeError(f"HTTP {resp.status}: {body}")
                    data = await resp.read()
                    ctype = resp.headers.get("Content-Type", "")
        except Exception as e:
            log.warning("OpenAI TTS 失败：%s", e)
            return None

        if len(data) < 512:
            log.warning("OpenAI TTS 返回内容过短，可能是错误信息：%s", data[:120])
            return None

        ext = ".mp3" if "mpeg" in ctype or "mp3" in ctype else ".wav"
        path = base.with_suffix(ext)
        path.write_bytes(data)
        return path

    # ---------- 本地 VITS ----------

    async def _synth_offline(self, text: str, base: Path) -> Path | None:
        try:
            if self._offline is None:
                import sherpa_onnx

                d = str(self.cfg.offline_model_dir)
                cfgs = sherpa_onnx.OfflineTtsConfig(
                    model=sherpa_onnx.OfflineTtsModelConfig(
                        vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                            model=f"{d}/model.onnx", tokens=f"{d}/tokens.txt"
                        ),
                        num_threads=4,
                        provider="cpu",
                    ),
                    max_num_sentences=2,
                )
                self._offline = sherpa_onnx.OfflineTts(cfgs)
            loop = asyncio.get_running_loop()
            audio = await loop.run_in_executor(None, lambda: self._offline.generate(text, sid=0, speed=1.05))

            import wave

            import numpy as np

            pcm = (np.clip(audio.samples, -1, 1) * 32767).astype(np.int16)
            path = base.with_suffix(".wav")
            with wave.open(str(path), "wb") as w:
                w.setnchannels(1)
                w.setsampwidth(2)
                w.setframerate(audio.sample_rate)
                w.writeframes(pcm.tobytes())
            return path
        except Exception as e:
            log.error("离线 TTS 失败：%s", e)
            return None

    # ---------- 维护 ----------

    def sweep(self, keep_seconds: int = 1800) -> None:
        """清掉过期的音频文件，避免长期运行把磁盘塞满。"""
        cutoff = time.time() - keep_seconds
        for p in self.cache_dir.glob("*"):
            if p.name.startswith("ack_"):
                continue  # 唤醒应答是预先生成的，常驻缓存
            try:
                if p.stat().st_mtime < cutoff:
                    p.unlink()
            except OSError:
                pass
