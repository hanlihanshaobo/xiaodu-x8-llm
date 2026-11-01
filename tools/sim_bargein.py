"""模拟插话打断：A 说完后，趁它思考时再说 B，看 B 能不能接管。"""
import asyncio
import json
import sys
import time
import wave
from pathlib import Path

import aiohttp
import numpy as np

ROOT = Path(__file__).resolve().parent.parent
CACHE = ROOT / "server" / "cache"
URL = "http://127.0.0.1:8765"


def load_pcm(path: Path) -> bytes:
    if path.suffix.lower() == ".mp3":
        import soundfile as sf
        data, sr = sf.read(str(path), dtype="float32", always_2d=True)
        data = data.mean(axis=1) * 32768.0
    else:
        with wave.open(str(path), "rb") as w:
            sr = w.getframerate()
            data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32)
    if sr != 16000:
        n = int(len(data) * 16000 / sr)
        data = np.interp(np.linspace(0, len(data), n, endpoint=False), np.arange(len(data)), data)
    return np.clip(data, -32768, 32767).astype(np.int16).tobytes() + b"\x00\x00" * 16000


async def stream(ws, pcm, t0):
    frame = 640 * 2
    for i in range(0, len(pcm), frame):
        await ws.send_bytes(pcm[i:i + frame])
        await asyncio.sleep(0.02)
    print(f"[{time.time()-t0:5.2f}s] 推完一段")


async def main():
    a = load_pcm(CACHE / "t3.mp3")   # 小度同学，今天几号
    b = load_pcm(CACHE / "t1.mp3")   # 小度同学，我叫小明
    t0 = time.time()
    answers = []

    async with aiohttp.ClientSession() as sess:
        async with sess.ws_connect(f"{URL}/ws", heartbeat=20) as ws:
            await ws.send_str(json.dumps({"type": "hello", "sampleRate": 16000}))

            async def rx():
                async for msg in ws:
                    if msg.type != aiohttp.WSMsgType.TEXT:
                        continue
                    e = json.loads(msg.data)
                    k = e.get("type")
                    ts = f"[{time.time()-t0:5.2f}s]"
                    if k == "asr":
                        print(f"{ts} 识别: {e['text']}")
                    elif k == "state":
                        print(f"{ts} 状态: {e['value']}")
                    elif k == "reply":
                        answers.append(e["delta"])
                    elif k == "speak":
                        print(f"{ts} 播报: {(e.get('text') or '')[:24]}")
                        await ws.send_str(json.dumps({"type": "speak_done", "id": e["id"]}))
                    elif k == "stop":
                        print(f"{ts} 收到 stop（被打断）")

            task = asyncio.create_task(rx())
            await asyncio.sleep(0.8)
            print("--- 第一段 ---")
            await stream(ws, a, t0)
            await asyncio.sleep(1.2)          # 此时应在“思考”
            print("--- 插话 ---")
            await stream(ws, b, t0)
            await asyncio.sleep(12)
            task.cancel()

    print("\n=== 汇总 ===")
    print("回答片段:", "".join(answers)[:200])


if __name__ == "__main__":
    asyncio.run(main())
