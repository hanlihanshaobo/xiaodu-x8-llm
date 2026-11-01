"""模拟设备：把一段 wav 按实时节奏推给服务端，验证整条链路。

用法：
    python tools/sim_device.py [wav路径] [服务端地址]

不传参数时自动挑 server/cache 里最新的 wav，连 ws://127.0.0.1:8765/ws。
"""
from __future__ import annotations

import asyncio
import json
import sys
import time
import wave
from pathlib import Path

import aiohttp
import numpy as np

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_WAV = ROOT / "server" / "cache"
URL = sys.argv[2] if len(sys.argv) > 2 else "http://127.0.0.1:8765"


def load_pcm(path: Path) -> bytes:
    if path.suffix.lower() == ".mp3":
        import soundfile as sf

        data, sr = sf.read(str(path), dtype="float32", always_2d=True)
        data = data.mean(axis=1) * 32768.0
    else:
        with wave.open(str(path), "rb") as w:
            sr, ch, sw = w.getframerate(), w.getnchannels(), w.getsampwidth()
            raw = w.readframes(w.getnframes())
        if sw != 2:
            sys.exit(f"只支持 16bit wav: {path}")
        data = np.frombuffer(raw, dtype=np.int16).astype(np.float32)
        if ch == 2:
            data = data.reshape(-1, 2).mean(axis=1)

    if sr != 16000:  # 线性重采样到 16k
        n = int(len(data) * 16000 / sr)
        data = np.interp(np.linspace(0, len(data), n, endpoint=False),
                         np.arange(len(data)), data)
    pcm = np.clip(data, -32768, 32767).astype(np.int16).tobytes()
    return pcm + b"\x00\x00" * 16000  # 补 1 秒静音，让 VAD 判定说完


def pick_wav() -> Path:
    if len(sys.argv) > 1:
        return Path(sys.argv[1])
    files = sorted(DEFAULT_WAV.glob("*.wav"), key=lambda p: p.stat().st_mtime)
    if not files:
        sys.exit("cache 里没有 wav，先跑一次 TTS 或手动指定")
    return files[-1]


async def main() -> None:
    wav = pick_wav()
    pcm = load_pcm(wav)
    print(f"音频: {wav.name}  {len(pcm)/32000:.2f}s")
    print(f"服务: {URL}")

    t0 = time.time()
    got_reply: list[str] = []
    speaks: list[str] = []

    async with aiohttp.ClientSession() as sess:
        async with sess.ws_connect(f"{URL.replace('http', 'ws')}/ws", heartbeat=20) as ws:
            await ws.send_str(json.dumps({"type": "hello", "sampleRate": 16000}))

            async def rx():
                async for msg in ws:
                    if msg.type != aiohttp.WSMsgType.TEXT:
                        continue
                    evt = json.loads(msg.data)
                    kind = evt.get("type")
                    stamp = f"[{time.time()-t0:5.2f}s]"
                    if kind == "ready":
                        print(f"{stamp} 已连接，模型 {evt.get('model')}")
                    elif kind == "state":
                        print(f"{stamp} 状态 → {evt.get('value')}")
                    elif kind == "asr":
                        print(f"{stamp} 识别: {evt.get('text')}")
                    elif kind == "reply":
                        got_reply.append(evt.get("delta", ""))
                    elif kind == "music":
                        speaks.append(evt["id"])
                        lrc = evt.get("lrc") or ""
                        print(f"{stamp} 音乐[{evt.get('title')} - {evt.get('artist')}] "
                              f"歌词 {len(lrc)} 字: {evt['url']}")
                        await ws.send_str(json.dumps({"type": "speak_done", "id": evt["id"]}))
                    elif kind == "speak":
                        speaks.append(evt["id"])
                        print(f"{stamp} 播报[{(evt.get('text') or '')[:30]}]: {evt['url']}")
                        try:
                            async with sess.get(evt["url"]) as r:
                                data = await r.read()
                            print(f"{stamp}   音频 {r.status} {len(data)}B {r.headers.get('Content-Type')}")
                            if r.status != 200:
                                print(f"{stamp}   !! 音频取不到")
                        except Exception as e:
                            print(f"{stamp}   !! 音频异常 {e}")
                        await ws.send_str(json.dumps({"type": "speak_done", "id": evt["id"]}))
                    elif kind == "error":
                        print(f"{stamp} 错误: {evt.get('message')}")

            rx_task = asyncio.create_task(rx())
            await asyncio.sleep(1.0)

            # 按 20ms 一帧推流，模拟真实麦克风
            frame = 640 * 2
            for i in range(0, len(pcm), frame):
                await ws.send_bytes(pcm[i:i + frame])
                await asyncio.sleep(0.02)

            print(f"[{time.time()-t0:5.2f}s] 音频推完，等待回复…")
            await asyncio.sleep(25)
            rx_task.cancel()

    answer = "".join(got_reply).strip()
    print("\n=== 结果 ===")
    print("模型回答:", answer[:300] if answer else "(空)")
    print("播报句数:", len(speaks))


if __name__ == "__main__":
    asyncio.run(main())
