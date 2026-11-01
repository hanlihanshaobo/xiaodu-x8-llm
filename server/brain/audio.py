"""音频前端：静音检测(VAD) + 语音识别(ASR)。

ASR 有两种后端，由 asr.provider 选择：

  openai  任意 OpenAI 兼容的 /v1/audio/transcriptions（默认，走局域网）
  local   sherpa-onnx 的 SenseVoice（需自行下载模型，本机默认不装）

VAD 始终在本地跑：它只是判断"说完了没有"，0.6MB 的模型，不值得为它多一次网络往返。
"""
from __future__ import annotations

import io
import json
import logging
import urllib.error
import urllib.request
import uuid
import wave

import numpy as np

log = logging.getLogger("audio")

SAMPLE_RATE = 16000


class VAD:
    """纯算法的语音端点检测，不依赖任何模型文件。

    原理：按 20ms 分帧算能量，用滑动噪声底自适应判断有没有人在说话。
    安静环境下和模型版差别不大；嘈杂环境会比 silero 差一些，但不占空间、
    不用下载，开机即用。
    """

    FRAME = 320  # 20ms @16k

    def __init__(self, cfg):
        self.min_silence = float(getattr(cfg, "min_silence", 0.35))
        self.min_speech = float(getattr(cfg, "min_speech", 0.25))
        self.max_speech = float(getattr(cfg, "max_speech", 15.0))
        self._carry = np.zeros(0, dtype=np.float32)
        self._frame_parts: list[np.ndarray] = []
        self._pre: list[np.ndarray] = []
        self._in_speech = False
        self._silence = 0
        self._frames = 0
        self._noise = 0.005

    def _is_speech(self, rms: float) -> bool:
        return rms > max(self._noise * 2.5, 0.012)

    def _update_noise(self, rms: float) -> None:
        # 只在不说话时更新，慢慢跟住环境底噪
        self._noise = 0.95 * self._noise + 0.05 * rms

    def feed(self, pcm: bytes) -> list[np.ndarray]:
        if not pcm:
            return []
        samples = np.frombuffer(pcm, dtype=np.int16).astype(np.float32) / 32768.0
        buf = np.concatenate((self._carry, samples)) if self._carry.size else samples
        out: list[np.ndarray] = []
        i = 0
        while i + self.FRAME <= buf.size:
            frame = buf[i:i + self.FRAME]
            i += self.FRAME
            rms = float(np.sqrt(np.mean(frame ** 2)) + 1e-9)

            if not self._in_speech:
                self._update_noise(rms)
                if self._is_speech(rms):
                    self._in_speech = True
                    self._frames = 1
                    self._silence = 0
                    self._frame_parts = list(self._pre) + [frame]
                    self._pre = []
                else:
                    self._pre.append(frame)
                    if len(self._pre) > 6:  # 保留 120ms 前置音频，避免切掉字头
                        self._pre.pop(0)
            else:
                self._frame_parts.append(frame)
                self._frames += 1
                if rms < max(self._noise * 2.0, 0.010):
                    self._silence += 1
                else:
                    self._silence = 0

                too_long = self._frames * 0.02 >= self.max_speech
                ended = self._silence * 0.02 >= self.min_silence
                if ended or too_long:
                    keep = self._frames - (0 if too_long else self._silence)
                    parts = self._frame_parts[:max(keep, 1)]
                    seg = np.concatenate(parts)
                    self._reset()
                    if len(seg) / 16000.0 >= self.min_speech:
                        out.append(seg)
        self._carry = buf[i:] if i < buf.size else np.zeros(0, dtype=np.float32)
        return out

    def _reset(self) -> None:
        self._in_speech = False
        self._silence = 0
        self._frames = 0
        self._frame_parts = []
        self._pre = []

    def reset(self) -> None:
        self._reset()
        self._carry = np.zeros(0, dtype=np.float32)


def _normalize(samples: np.ndarray) -> np.ndarray:
    """远场说话常常偏轻，先拉到位再送识别；太响则压一下防削波。"""
    if samples.size == 0:
        return samples
    rms = float(np.sqrt(np.mean(samples ** 2)) + 1e-9)
    target = 0.06  # 约 -24 dBFS，识别引擎比较喜欢的音量
    if rms < target:
        gain = min(target / rms, 8.0)  # 最多放大约 18dB，避免把噪声一起拉起来
        return np.clip(samples * gain, -1.0, 1.0)
    peak = float(np.max(np.abs(samples)))
    if peak > 0.98:
        return np.clip(samples * (0.98 / peak), -1.0, 1.0)
    return samples


def _to_wav_bytes(samples: np.ndarray) -> bytes:
    samples = _normalize(samples)
    pcm = (np.clip(samples, -1.0, 1.0) * 32767).astype(np.int16)
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SAMPLE_RATE)
        w.writeframes(pcm.tobytes())
    return buf.getvalue()


class OpenAIASR:
    """把一句话 WAV 丢给 OpenAI 兼容的转写接口。"""

    def __init__(self, cfg):
        self.base_url = str(cfg.base_url).rstrip("/")
        self.api_key = str(getattr(cfg, "api_key", ""))
        self.model = str(cfg.model)
        self.language = str(getattr(cfg, "language", "zh") or "")
        self.prompt = str(getattr(cfg, "prompt", "") or "")
        self.hint = ""
        self.timeout = float(getattr(cfg, "timeout", 20))
        log.info("ASR: 远程转写 %s (model=%s)", self.base_url, self.model)

    def set_hint(self, text: str) -> None:
        """每次识别前塞入热词（当前歌曲、家里设备名），支持 prompt 的模型会用它纠错。"""
        self.hint = (text or "").strip()

    def transcribe(self, samples: np.ndarray) -> str:
        boundary = uuid.uuid4().hex
        fields = {"model": self.model}
        if self.language:
            fields["language"] = self.language
        prompt = "\n".join(x for x in (self.prompt, self.hint) if x).strip()
        if prompt:
            fields["prompt"] = prompt

        body = b""
        for k, v in fields.items():
            body += f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n".encode()
        body += (
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
            f"filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n"
        ).encode()
        body += _to_wav_bytes(samples)
        body += f"\r\n--{boundary}--\r\n".encode()

        headers = {
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "Authorization": f"Bearer {self.api_key}",
        }
        req = urllib.request.Request(
            f"{self.base_url}/audio/transcriptions", data=body, headers=headers
        )
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                raw = r.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "replace")[:200]
            raise RuntimeError(f"ASR HTTP {e.code}: {detail}") from None

        try:
            data = json.loads(raw)
        except json.JSONDecodeError:
            return raw.strip()
        if isinstance(data, dict):
            return str(data.get("text") or data.get("transcription") or "").strip()
        return ""


class LocalASR:
    """sherpa-onnx SenseVoice，模型不在时不构造。"""

    def __init__(self, cfg):
        # 放到这里再导入：不用本地 ASR 的机器（比如 32 位 ARM 镜像）可以完全不装它
        import sherpa_onnx

        d = str(cfg.model_dir)
        self._rec = sherpa_onnx.OfflineRecognizer.from_sense_voice(
            model=f"{d}/model.int8.onnx",
            tokens=f"{d}/tokens.txt",
            num_threads=int(getattr(cfg, "num_threads", 4)),
            use_itn=True,
            debug=False,
            provider="cpu",
        )
        log.info("ASR: 本地 SenseVoice (%s)", d)

    def transcribe(self, samples: np.ndarray) -> str:
        import re

        stream = self._rec.create_stream()
        stream.accept_waveform(SAMPLE_RATE, samples)
        self._rec.decode_stream(stream)
        return re.sub(r"<\|[^|]*\|>", "", stream.result.text or "").strip()


def build_asr(cfg):
    provider = str(getattr(cfg, "provider", "openai")).lower()
    if provider == "local":
        try:
            return LocalASR(cfg.local)
        except Exception as e:
            log.error("本地 ASR 不可用（%s），回退到远程转写", e)
            return OpenAIASR(cfg.openai)
    return OpenAIASR(cfg.openai)
