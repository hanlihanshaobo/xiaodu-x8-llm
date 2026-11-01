"""大模型接入：OpenAI 兼容的流式 chat completions。

针对网关的 `cn:` 模型做了两件特殊处理：
1. 默认发 reasoning_effort=low —— glm-5.3-flash 只有压到 low 才会跳过思考，
   否则首字要 2.4s 以上，语音场景不能接受。
2. 只取 content 增量，reasoning_content 一律丢掉，绝不念出来。

另外支持 OpenAI 的 function calling：流式下 tool_calls 是分片下发的，
这里按 index 拼回完整调用；服务端不认 tools 就自动降级成纯文本。
"""
from __future__ import annotations

import json
import logging
from typing import AsyncIterator

import aiohttp

log = logging.getLogger("llm")


def _plain(obj):
    """把配置里包装过的 SimpleNamespace 还原成普通 dict。"""
    if obj is None:
        return None
    if isinstance(obj, dict):
        return {k: _plain(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [_plain(x) for x in obj]
    if hasattr(obj, "__dict__"):
        return {k: _plain(v) for k, v in vars(obj).items()}
    return obj


class _ToolsUnsupported(Exception):
    pass


class LLM:
    def __init__(self, cfg):
        self.base_url = str(cfg.base_url).rstrip("/")
        self.api_key = str(cfg.api_key)
        self.model = str(cfg.model)
        self.temperature = float(getattr(cfg, "temperature", 0.6))
        self.max_tokens = int(getattr(cfg, "max_tokens", 600))
        self.timeout = float(getattr(cfg, "timeout", 60))
        self.reasoning_effort = getattr(cfg, "reasoning_effort", None)
        self.extra = _plain(getattr(cfg, "extra", None))
        self.system_prompt = str(getattr(cfg, "system_prompt", "")).strip()
        # 网关不支持 tools 时置 False，之后不再白发一次
        self.tools_supported = True

    def build_messages(self, history: list[dict], turns: int = 8, extra_system: str = "") -> list[dict]:
        system = self.system_prompt
        if extra_system:
            system = f"{system}\n\n{extra_system}" if system else extra_system
        msgs = []
        if system:
            msgs.append({"role": "system", "content": system})
        msgs.extend(history[-turns * 2:])
        return msgs

    async def stream(self, messages: list[dict], tools: list[dict] | None = None) -> AsyncIterator[dict]:
        """产出事件：

            {"type": "text", "text": "..."}
            {"type": "tool_calls", "calls": [{id, type, function:{name, arguments}}]}

        tool_calls 一定在流末尾一次性给出，方便上层先播完文本再执行。
        """
        if tools and not self.tools_supported:
            tools = None
        try:
            async for ev in self._stream(messages, tools):
                yield ev
        except _ToolsUnsupported:
            if not tools:
                raise
            log.warning("模型不支持 function calling，降级为纯文本对话")
            self.tools_supported = False
            async for ev in self._stream(messages, None):
                yield ev

    async def _stream(self, messages: list[dict], tools: list[dict] | None) -> AsyncIterator[dict]:
        payload = {
            "model": self.model,
            "messages": messages,
            "stream": True,
            "temperature": self.temperature,
            "max_tokens": self.max_tokens,
        }
        if self.reasoning_effort:
            payload["reasoning_effort"] = self.reasoning_effort
        if tools:
            payload["tools"] = tools
            payload["tool_choice"] = "auto"
        if self.extra:
            payload.update(self.extra)

        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }
        timeout = aiohttp.ClientTimeout(total=None, sock_connect=10, sock_read=self.timeout)

        calls: dict[int, dict] = {}

        async with aiohttp.ClientSession(timeout=timeout) as sess:
            async with sess.post(f"{self.base_url}/chat/completions", json=payload, headers=headers) as resp:
                if resp.status != 200:
                    body = await resp.text()
                    if tools and resp.status in (400, 422) and "tool" in body.lower():
                        raise _ToolsUnsupported()
                    raise RuntimeError(f"LLM HTTP {resp.status}: {body[:300]}")
                async for raw in resp.content:
                    line = raw.decode("utf-8", "replace").strip()
                    if not line or not line.startswith("data:"):
                        continue
                    data = line[5:].strip()
                    if data == "[DONE]":
                        break
                    try:
                        chunk = json.loads(data)
                    except json.JSONDecodeError:
                        continue
                    choices = chunk.get("choices") or []
                    if not choices:
                        continue
                    delta = choices[0].get("delta") or {}

                    piece = delta.get("content")
                    if piece:
                        yield {"type": "text", "text": piece}

                    for tc in delta.get("tool_calls") or []:
                        idx = int(tc.get("index") or 0)
                        slot = calls.setdefault(idx, {"id": "", "name": "", "arguments": ""})
                        if tc.get("id"):
                            slot["id"] = str(tc["id"])
                        fn = tc.get("function") or {}
                        if fn.get("name"):
                            slot["name"] = str(fn["name"])
                        args = fn.get("arguments")
                        if args:
                            # 有的网关一次性给完整参数，有的按字符分片
                            if str(args).startswith(slot["arguments"]):
                                slot["arguments"] = str(args)
                            elif not slot["arguments"].startswith(str(args)):
                                slot["arguments"] += str(args)

        if calls:
            out = []
            for i in sorted(calls):
                c = calls[i]
                if not c["name"]:
                    continue
                out.append(
                    {
                        "id": c["id"] or f"call_{i}",
                        "type": "function",
                        "function": {"name": c["name"], "arguments": c["arguments"] or "{}"},
                    }
                )
            if out:
                yield {"type": "tool_calls", "calls": out}
