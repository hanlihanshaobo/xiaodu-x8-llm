"""配置加载：把 yaml 转成可用点号访问的对象。"""
from __future__ import annotations

import types
from pathlib import Path

import yaml


def _wrap(obj):
    if isinstance(obj, dict):
        ns = types.SimpleNamespace()
        for k, v in obj.items():
            setattr(ns, k, _wrap(v))
        return ns
    if isinstance(obj, list):
        return [_wrap(x) for x in obj]
    return obj


def load(path: str | Path):
    with open(path, "r", encoding="utf-8") as f:
        raw = yaml.safe_load(f)
    return _wrap(raw or {})
