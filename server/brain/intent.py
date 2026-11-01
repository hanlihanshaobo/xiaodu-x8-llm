"""把一句中文口令拆成「动作 + 设备名」。

语音场景等不起大模型两轮往返，常见说法必须本地就能认出来：

    把客厅灯打开 / 关掉卧室灯 / 空调调到二十六度 / 除湿机开着吗

这里只负责拆句，不判断设备存不存在——名字最后要拿去和 HA 实体匹配，
匹配不上就当普通聊天交给模型，所以解析可以宽松一点。
"""
from __future__ import annotations

import re
from dataclasses import dataclass

_CN_DIGITS = {
    "零": 0, "〇": 0, "一": 1, "二": 2, "两": 2, "三": 3, "四": 4,
    "五": 5, "六": 6, "七": 7, "八": 8, "九": 9,
}
_CN_UNITS = {"十": 10, "百": 100, "千": 1000}
_NUM_CHARS = "".join(list(_CN_DIGITS) + list(_CN_UNITS))

_NUM = rf"(\d{{1,3}}(?:\.\d)?|[{_NUM_CHARS}]{{1,4}})"

_STATUS_WORDS = [
    "开着吗", "开了吗", "关着吗", "关了吗", "开没开", "关没关",
    "是不是开着", "是不是关着", "什么状态", "状态如何", "状态怎么样",
    "现在状态", "亮着吗", "还开着吗", "还亮着吗",
]

# 顺序很重要：先长后短，避免"关一下"被"关"截胡
_TURN_OFF = ["关闭", "关掉", "关上", "关一下", "关下", "熄灭", "熄灭一下"]
_TURN_ON = ["打开", "开启", "启动", "开一下", "开下", "亮起来", "亮一下"]
_TOGGLE = ["切换一下", "切换", "换一下"]

# 名字里要剥掉的废话
_NOISE = [
    "帮我", "帮忙", "麻烦你", "麻烦", "请", "给我", "把", "将",
    "一下", "现在", "立刻", "马上", "快点", "小度同学", "小度",
    "调到", "调成", "设成", "设为", "改成", "打到", "温度", "亮度", "明暗",
    "百分之",
    "吧", "呢", "啊", "呀", "哦", "嘛", "好吗", "行吗", "谢谢",
    "的",
]

# 句末语气词，先剥掉才好认"把灯关了"里的"关"
_TAIL = re.compile(r"[了吧呢啊呀哦啦嘛哈]+$")


@dataclass
class Command:
    action: str                 # turn_on | turn_off | toggle | status
    target: str                 # 用户嘴里的设备名
    value: float | None = None  # 亮度百分比 / 温度
    attribute: str | None = None  # brightness | temperature


def cn_number(s: str) -> float | None:
    """中文数字转数值：二十六 → 26，百分之三十 → 30，3.5 → 3.5。"""
    s = (s or "").strip().replace("百分之", "")
    if not s:
        return None
    if re.fullmatch(r"\d+(?:\.\d)?", s):
        return float(s)

    dec = s.split("点", 1)
    integer = dec[0]
    total, section, number = 0, 0, 0
    for ch in integer:
        if ch in _CN_DIGITS:
            number = _CN_DIGITS[ch]
        elif ch in _CN_UNITS:
            section += (number or 1) * _CN_UNITS[ch]
            number = 0
        else:
            return None
    total = section + number
    if len(dec) == 2:
        frac = 0.0
        for i, ch in enumerate(dec[1]):
            if ch not in _CN_DIGITS:
                return None
            frac += _CN_DIGITS[ch] / (10 ** (i + 1))
        total += frac
    return float(total) if total else None


def _clean_target(s: str) -> str:
    t = s
    for w in _NOISE:
        t = t.replace(w, "")
    t = re.sub(r"[，。！？、；：,.!?;:\s]+", "", t)
    return t.strip()


def _cut(t: str, word: str) -> str | None:
    """按动作词切掉，返回剩下的设备名部分。"""
    if t.endswith(word):
        return t[: -len(word)]
    if t.startswith(word):
        return t[len(word):]
    if len(word) >= 2:
        i = t.find(word)
        if i >= 0:
            return t[:i] + t[i + len(word):]
    return None


def parse(text: str) -> Command | None:
    """认出来就返回 Command，认不出返回 None。"""
    t = re.sub(r"[\s，。！？、；：,.!?;:…—~\"'“”‘’()（）]+", "", text or "")
    # 句首称呼：小度同学，把灯关了
    t = re.sub(r"^(小度同学|小度|同学)", "", t)
    if len(t) < 2:
        return None

    # ---------- 状态查询 ----------
    for w in _STATUS_WORDS:
        if w in t:
            target = _clean_target(t.replace(w, "", 1))
            if target:
                return Command("status", target)

    # ---------- 数值设置 ----------
    # 温度：空调调到二十六度 / 温度设成 24 度
    m = re.search(rf"{_NUM}\s*(?:摄氏度|℃|度)", t)
    if m:
        value = cn_number(m.group(1))
        target = _clean_target(t[: m.start()] + t[m.end():])
        if value is not None and 5 <= value <= 40 and target:
            return Command("turn_on", target, value, "temperature")

    # 亮度：客厅灯亮度调到百分之三十 / 灯调到 50%
    span: tuple[float | None, int, int] | None = None
    m = re.search(rf"百分之\s*{_NUM}", t)
    if m:
        span = (cn_number(m.group(1)), m.start(), m.end())
    else:
        m = re.search(rf"{_NUM}\s*(?:%|％)", t)
        if m:
            span = (cn_number(m.group(1)), m.start(), m.end())
        else:
            m = re.search(rf"(?:亮度|明暗)[^0-9{_NUM_CHARS}]{{0,4}}{_NUM}", t)
            if m:
                span = (cn_number(m.group(1)), m.start(), m.end())
    if span and span[0] is not None:
        value, start, end = span
        target = _clean_target(t[:start] + t[end:])
        if target:
            return Command("turn_on", target, min(100.0, value), "brightness")

    # "把除湿机关了" 里的"了"先去掉，动作词才能落在句尾
    t = _TAIL.sub("", t)

    # ---------- 开关 ----------
    for words, action in ((_TOGGLE, "toggle"), (_TURN_OFF, "turn_off"), (_TURN_ON, "turn_on")):
        for w in sorted(words, key=len, reverse=True):
            rest = _cut(t, w)
            if rest is not None:
                target = _clean_target(rest)
                if target:
                    return Command(action, target)

    # 单字"开/关"只认句首句尾，避免"开心""关于"这种误伤；
    # 就算误认了，后面匹配不到设备也会退回给大模型。
    for w, action in (("关", "turn_off"), ("开", "turn_on")):
        if t.endswith(w) and len(t) > 1:
            target = _clean_target(t[:-1])
            if target:
                return Command(action, target)
        if t.startswith(w) and len(t) > 1:
            target = _clean_target(t[1:])
            if target:
                return Command(action, target)

    return None
