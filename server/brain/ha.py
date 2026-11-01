"""Home Assistant 接入：实体发现、名字匹配、服务调用。

小度说的"把客厅灯打开"，难点不在调 API，而在把这句话对上
`light.example_light`。这里分三层：

  1. aliases   —— config 里手写的口头叫法，优先级最高、最省心
  2. area      —— 从 HA 模板接口取实体所在区域，拼成"客厅 主灯"
  3. 模糊匹配  —— 名称包含 + 设备类别词 + 区域词打分

匹配到多个就交回上层反问，绝不瞎猜着去开设备。

安全上默认只开放物理设备相关的域，并排掉 SSH、终端、集成开关这类
"一句话就能把家拆了"的实体，白名单在 config.yaml 的 ha 段里改。
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import time
from dataclasses import dataclass, field
from pathlib import Path

import aiohttp

log = logging.getLogger("ha")

# 允许语音控制的域。锁、摄像头、自动化默认不给，避免误触。
DEFAULT_DOMAINS = [
    "light", "switch", "fan", "cover", "climate", "humidifier",
    "media_player", "scene", "script", "vacuum", "water_heater",
    "input_boolean",
]

# 默认排掉的实体：系统工具、集成自身开关、设备配置项
DEFAULT_EXCLUDE = [
    "terminal", "ssh", "file_editor", "sendspin", "music_assistant",
    "sgcc_electricity", "nvr_", "dxs_", "_indicator_", "_status_",
    "physical_controls", "_disable_p_", "_alarm_p_", "constant_protect",
    "overwet_protect", "_mode_p_", "configurator", "hacs",
]

# 动作 → 各域真正的服务名
_ACTION_SERVICE = {
    ("cover", "turn_on"): "open_cover",
    ("cover", "turn_off"): "close_cover",
    ("vacuum", "turn_on"): "start",
    ("vacuum", "turn_off"): "return_to_base",
    ("scene", "turn_on"): "turn_on",
    ("script", "turn_on"): "turn_on",
}

_ACTION_CN = {"turn_on": "打开", "turn_off": "关闭", "toggle": "切换", "status": "查询"}

# 设备类别词：用来把"灯"这种泛称对上具体设备
_TYPE_WORDS = [
    "床头灯", "台灯", "吸顶灯", "吊灯", "落地灯", "氛围灯", "灯",
    "空调", "除湿机", "加湿器", "净化器", "净水器", "热水器",
    "打印机", "窗帘", "卷帘", "风扇", "插座", "电视", "音箱",
    "扫地机", "扫地机器人", "门锁", "监控", "摄像头", "投影仪",
    "浴霸", "取暖器", "电暖器", "洗衣机", "烘干机", "冰箱",
]

# 位置词：用户说"卧室的灯"时加一分
_AREA_WORDS = [
    "客厅", "卧室", "主卧", "次卧", "儿童房", "书房", "厨房", "餐厅",
    "卫生间", "浴室", "阳台", "玄关", "走廊", "地下室", "车库", "办公室",
]

# 同义区域："卧室"包括主卧次卧，"卫生间"包括浴室厕所
_AREA_GROUPS = [
    {"卧室", "主卧", "次卧", "卧房"},
    {"卫生间", "浴室", "厕所", "洗手间"},
    {"客厅", "大厅", "起居室"},
]

# 口语里对某一类设备的叫法。用户把加湿器说成除湿机也认，
# 反正回执会念出真实设备名，说错了能当场发现。
_DOMAIN_WORDS = {
    "light": ["灯", "照明", "亮"],
    "humidifier": ["加湿器", "除湿机", "加湿", "除湿", "湿度"],
    "climate": ["空调", "冷气", "暖气", "暖风", "中央空调"],
    "fan": ["风扇", "电扇", "新风"],
    "cover": ["窗帘", "卷帘", "百叶"],
    "media_player": ["电视", "音箱", "音响", "播放器", "音乐"],
    "switch": ["插座", "开关"],
}

# 这些名字是设备的配置项而不是本体，语音控制没意义还容易误触
DEFAULT_EXCLUDE_NAMES = ["指示灯", "状态", "提示音", "锁定", "辅助开关"]

_PUNCT = re.compile(r"[\s，。！？、；：,.!?;:…—\-~\"'“”‘’()（）\[\]【】《》*#@_/\\|]+")


def _norm(s: str) -> str:
    """去掉标点空白，统一小写，便于比对。"""
    return _PUNCT.sub("", str(s or "")).lower()


def _clean_name(s: str) -> str:
    """整理 friendly_name：去掉装饰符号和重复片段。

    米家设备的名字经常长这样："米家床头灯2  灯"、"空调  空调"。
    """
    s = re.sub(r"[*#]+", " ", str(s or ""))
    s = re.sub(r"\s+", " ", s).strip()
    out: list[str] = []
    for part in [p for p in s.split(" ") if p]:
        if out and (part == out[-1] or part in out[-1] or out[-1] in part):
            if len(part) <= len(out[-1]) and len(part) <= 4:
                continue
        out.append(part)
    return " ".join(out) or s


def _read_local_env() -> dict[str, str]:
    """读 ~/.config/opencode/ha.env，和服务启动时有没有环境变量无关。"""
    path = Path.home() / ".config" / "opencode" / "ha.env"
    out: dict[str, str] = {}
    if not path.exists():
        return out
    try:
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip().strip('"').strip("'")
    except OSError:
        pass
    return out


def as_dict(obj) -> dict:
    """config.yaml 里的 dict 会被包装成 SimpleNamespace，这里还原。"""
    if obj is None:
        return {}
    if isinstance(obj, dict):
        return dict(obj)
    if hasattr(obj, "__dict__"):
        return dict(vars(obj))
    return {}


@dataclass
class Device:
    entity_id: str
    domain: str
    name: str
    area: str
    state: str
    attributes: dict = field(default_factory=dict)

    @property
    def label(self) -> str:
        """播报用的短名："客厅 主灯"。名称已含区域就不重复。"""
        name = self.name or self.entity_id
        if self.area and self.area not in name:
            return f"{self.area}{name}"
        return name

    @property
    def is_on(self) -> bool:
        return self.state in ("on", "open", "playing", "heat", "cool", "auto", "dry", "fan_only")

    @property
    def offline(self) -> bool:
        return self.state in ("unavailable", "unknown")

    def score(self, q: str) -> int:
        """用户说法和设备名的相关度，0 表示不相关。"""
        if not q:
            return 0
        name = _norm(self.name)
        area = _norm(self.area)
        qn = _norm(q)
        s = 0

        if name and qn == name:
            s += 120
        elif name and (qn in name or name in qn):
            # "灯" 能命中 "米家床头灯2"，但太短的匹配要压一压权重
            s += 70 if min(len(qn), len(name)) >= 2 else 45

        for w in _TYPE_WORDS:
            if w in qn and w in name:
                s += 30
                break

        # 按设备类别兜底：家里只有加湿器时，"除湿机"也让它对上
        for w in _DOMAIN_WORDS.get(self.domain, []):
            if w in qn:
                s += 30
                break

        mentioned = [w for w in _AREA_WORDS if w in qn]
        if mentioned:
            if not area:
                # 用户说了房间，而这台设备没录区域，别让它冒名顶替
                s = int(s * 0.3)
            else:
                hit = any(w in area or area in w for w in mentioned)
                if not hit:
                    for group in _AREA_GROUPS:
                        if any(w in qn for w in group) and any(w in area for w in group):
                            hit = True
                            break
                if hit:
                    s += 25
                else:
                    # 用户明确说了别的房间，卧室的灯就不该响应"客厅灯"
                    s = int(s * 0.2)

        # 字符重合度兜底，处理"卧室床头"这种缺字的说法
        if name:
            common = len(set(qn) & set(name)) / max(1, len(set(qn)))
            if common >= 0.5:
                s += int(common * 18)

        # 英文型号名（"ExampleLamp-0000 Mijia bedside lamp"）用户不会这么叫，
        # 中文名优先，避免和"米家床头灯2代"抢
        cjk = len(re.findall(r"[\u4e00-\u9fff]", self.name))
        if cjk < max(1, len(self.name) // 3):
            s = int(s * 0.5)
        return s


def service_for(domain: str, action: str) -> str | None:
    if (domain, action) in _ACTION_SERVICE:
        return _ACTION_SERVICE[(domain, action)]
    if action in ("turn_on", "turn_off", "toggle") and domain not in ("scene", "script", "climate"):
        return action
    return None


def describe(dev: Device) -> str:
    """一句话说清设备现在的状态。"""
    if dev.offline:
        return "离线了"
    if dev.is_on:
        base = "开着"
    elif dev.state in ("off", "closed", "idle", "standby", "paused"):
        base = "关着"
    else:
        base = f"是{dev.state}"

    if dev.domain == "light" and dev.is_on:
        b = dev.attributes.get("brightness")
        if isinstance(b, (int, float)) and b > 0:
            base += f"，亮度百分之{round(b / 255 * 100)}"
    if dev.domain == "climate":
        cur = dev.attributes.get("current_temperature")
        tgt = dev.attributes.get("temperature")
        if tgt is not None:
            base += f"，设定{round(float(tgt))}度"
        elif cur is not None:
            base += f"，室温{round(float(cur))}度"
    if dev.domain == "cover":
        pos = dev.attributes.get("current_position")
        if pos is not None:
            base += f"，开合度百分之{round(float(pos))}"
    return base


def reply_for(dev: Device, action: str) -> str:
    """执行完给用户的口语化回执。"""
    label = dev.label
    if dev.offline:
        return f"{label}好像掉线了，没控制成。"
    if action == "status":
        return f"{label}{describe(dev)}。"
    if dev.is_on:
        return f"好的，{label}打开了。"
    return f"好的，{label}关掉了。"


class HomeAssistant:
    def __init__(self, cfg):
        cfg = cfg if cfg is not None else {}
        local = _read_local_env()

        self.enabled = bool(getattr(cfg, "enabled", True))
        self.url = str(getattr(cfg, "url", "") or os.environ.get("HA_URL") or local.get("HA_URL", "")).rstrip("/")
        self.token = str(
            getattr(cfg, "token", "") or os.environ.get("HA_TOKEN") or local.get("HA_TOKEN", "")
        ).strip()

        self.cache_seconds = float(getattr(cfg, "cache_seconds", 60))
        self.timeout = float(getattr(cfg, "timeout", 8))
        self.direct = bool(getattr(cfg, "direct_intent", True))
        self.use_tools = bool(getattr(cfg, "tools", True))

        domains = getattr(cfg, "domains", None) or DEFAULT_DOMAINS
        self.domains = {str(d).strip() for d in domains if str(d).strip()}
        exclude = getattr(cfg, "exclude", None)
        self.exclude = [str(x).lower() for x in (DEFAULT_EXCLUDE if exclude is None else exclude)]
        exclude_names = getattr(cfg, "exclude_names", None)
        self.exclude_names = [
            str(x) for x in (DEFAULT_EXCLUDE_NAMES if exclude_names is None else exclude_names)
        ]

        self.aliases = {
            _norm(k): str(v) for k, v in as_dict(getattr(cfg, "aliases", None)).items() if _norm(k)
        }
        self.names = {str(k): str(v) for k, v in as_dict(getattr(cfg, "names", None)).items()}

        self._devices: dict[str, Device] = {}
        self._loaded_at = 0.0
        self._lock = asyncio.Lock()
        self.last_error = ""

        if self.enabled and not (self.url and self.token):
            log.warning("HA 已启用但缺少地址或令牌，先关掉（填 config.yaml 的 ha.url，令牌放 HA_TOKEN）")
            self.enabled = False

        if self.enabled:
            log.info("HA: %s（%d 个域）", self.url, len(self.domains))

    # ---------- HTTP ----------

    async def _request(self, method: str, path: str, body: dict | None = None):
        if not self.enabled:
            raise RuntimeError("HA 未启用")
        headers = {"Authorization": f"Bearer {self.token}"}
        if body is not None:
            headers["Content-Type"] = "application/json"
        timeout = aiohttp.ClientTimeout(total=self.timeout, sock_connect=4)
        async with aiohttp.ClientSession(timeout=timeout) as sess:
            async with sess.request(
                method, f"{self.url}{path}", json=body, headers=headers
            ) as resp:
                text = await resp.text()
                if resp.status >= 400:
                    raise RuntimeError(f"HA HTTP {resp.status}: {text[:200]}")
                if not text:
                    return None
                try:
                    return json.loads(text)
                except json.JSONDecodeError:
                    return text

    # ---------- 实体 ----------

    async def ensure(self, force: bool = False) -> None:
        """按 TTL 刷新实体表；HA 挂了就沿用旧数据，不影响聊天。"""
        if not self.enabled:
            return
        if not force and self._devices and time.time() - self._loaded_at < self.cache_seconds:
            return
        async with self._lock:
            if not force and self._devices and time.time() - self._loaded_at < self.cache_seconds:
                return
            try:
                await self._reload()
                self.last_error = ""
            except Exception as e:
                self.last_error = str(e)
                log.warning("HA 刷新失败：%s", e)

    async def _reload(self) -> None:
        states = await self._request("GET", "/api/states") or []
        areas = await self._load_areas()

        devices: dict[str, Device] = {}
        for item in states:
            eid = str(item.get("entity_id") or "")
            if "." not in eid:
                continue
            domain = eid.split(".", 1)[0]
            if domain not in self.domains:
                continue
            low = eid.lower()
            if any(x in low for x in self.exclude):
                continue
            attrs = item.get("attributes") or {}
            name = self.names.get(eid) or attrs.get("friendly_name") or eid
            if any(x in str(name) for x in self.exclude_names):
                continue
            devices[eid] = Device(
                entity_id=eid,
                domain=domain,
                name=_clean_name(name),
                area=areas.get(eid, ""),
                state=str(item.get("state") or ""),
                attributes=attrs,
            )

        self._devices = devices
        self._loaded_at = time.time()
        log.info("HA：%d 个可控设备", len(devices))

    async def _load_areas(self) -> dict[str, str]:
        """用模板接口批量取实体所属区域；老版本 HA 没有 area_name 就返回空。"""
        domains = json.dumps(sorted(self.domains), ensure_ascii=False)
        tmpl = (
            "{% for s in states %}"
            "{% if s.entity_id.split('.')[0] in " + domains + " %}"
            "{{ s.entity_id }}|{{ area_name(s.entity_id) or '' }}\n"
            "{% endif %}{% endfor %}"
        )
        try:
            text = await self._request("POST", "/api/template", {"template": tmpl})
        except Exception as e:
            log.debug("HA 区域信息不可用：%s", e)
            return {}
        out: dict[str, str] = {}
        for line in str(text or "").splitlines():
            if "|" not in line:
                continue
            eid, area = line.split("|", 1)
            if area.strip():
                out[eid.strip()] = area.strip()
        return out

    async def catalog(self) -> list[Device]:
        await self.ensure()
        return sorted(self._devices.values(), key=lambda d: (d.area, d.name))

    @property
    def device_count(self) -> int:
        return len(self._devices)

    def domain_of(self, query: str) -> str | None:
        """这句口令说的是哪一类设备（light/climate/...），认不出返回 None。"""
        q = _norm(query)
        for domain, words in _DOMAIN_WORDS.items():
            if any(w in q for w in words):
                return domain
        return None

    def by_domain(self, domain: str) -> list[Device]:
        return [d for d in self._devices.values() if d.domain == domain and not d.offline]

    # ---------- 匹配 ----------

    def match(self, query: str, domain: str | None = None) -> list[Device]:
        """把口头称呼对到设备，按相关度从高到低返回。"""
        q = _norm(query)
        if not q:
            return []
        pool = [d for d in self._devices.values() if domain is None or d.domain == domain]

        # 1. 别名：最长优先，命中即唯一
        for alias, eid in sorted(self.aliases.items(), key=lambda kv: -len(kv[0])):
            if alias and alias in q:
                dev = self._devices.get(eid)
                if dev is not None:
                    return [dev]

        # 2. 打分
        scored = [(d.score(q), d) for d in pool]
        scored = [(s, d) for s, d in scored if s >= 30]
        scored.sort(key=lambda x: (-x[0], x[1].label))
        if not scored:
            return []
        top = scored[0][0]
        devices = [d for s, d in scored if s >= top - 10]
        # 同名设备里只有一台在线（另一台 unavailable），不用问用户
        alive = [d for d in devices if not d.offline]
        if len(alive) == 1:
            return alive
        return devices

    def find_exact(self, entity_id: str) -> Device | None:
        return self._devices.get(entity_id)

    # ---------- 执行 ----------

    async def act(self, dev: Device, action: str, data: dict | None = None) -> Device:
        svc = service_for(dev.domain, action)
        if svc is None:
            raise RuntimeError(f"{dev.domain} 不支持 {action}")
        body = {"entity_id": dev.entity_id}
        if data:
            body.update(data)
        await self._request("POST", f"/api/services/{dev.domain}/{svc}", body)
        # 回读状态，确保回执说的是真实结果而不是"我以为"
        try:
            state = await self._request("GET", f"/api/states/{dev.entity_id}")
        except Exception:
            state = None
        if isinstance(state, dict):
            dev.state = str(state.get("state") or dev.state)
            dev.attributes = state.get("attributes") or dev.attributes
            self._devices[dev.entity_id] = dev
        log.info("HA 动作：%s → %s (%s) = %s", dev.label, action, svc, dev.state)
        return dev

    async def set_brightness(self, dev: Device, pct: float) -> Device:
        pct = max(1, min(100, int(pct)))
        return await self.act(dev, "turn_on", {"brightness_pct": pct})

    async def set_temperature(self, dev: Device, value: float) -> Device:
        await self._request(
            "POST", "/api/services/climate/set_temperature",
            {"entity_id": dev.entity_id, "temperature": float(value)},
        )
        return await self.act(dev, "turn_on")

    # ---------- 给模型看的工具 ----------

    def tools(self) -> list[dict]:
        """OpenAI 兼容的 function 定义。模型只需要说人话的名字，实体解析交给我们。"""
        return [
            {
                "type": "function",
                "function": {
                    "name": "home_control",
                    "description": "打开、关闭或切换家里的智能设备（灯、空调、除湿机、窗帘、插座、场景等）",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "name": {"type": "string", "description": "设备的口头名称，例如：客厅灯、卧室空调、除湿机"},
                            "action": {
                                "type": "string",
                                "enum": ["turn_on", "turn_off", "toggle"],
                                "description": "turn_on 打开，turn_off 关闭，toggle 切换",
                            },
                        },
                        "required": ["name", "action"],
                    },
                },
            },
            {
                "type": "function",
                "function": {
                    "name": "home_status",
                    "description": "查询家里智能设备现在的状态",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "name": {"type": "string", "description": "设备的口头名称，例如：客厅灯"},
                        },
                        "required": ["name"],
                    },
                },
            },
            {
                "type": "function",
                "function": {
                    "name": "home_set",
                    "description": "调整灯的亮度或空调温度",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "name": {"type": "string", "description": "设备的口头名称"},
                            "attribute": {
                                "type": "string",
                                "enum": ["brightness", "temperature"],
                                "description": "brightness 亮度百分比，temperature 空调温度",
                            },
                            "value": {"type": "number", "description": "数值，亮度是 1~100，温度是摄氏度"},
                        },
                        "required": ["name", "attribute", "value"],
                    },
                },
            },
            {
                "type": "function",
                "function": {
                    "name": "home_list",
                    "description": "列出家里可以控制的设备及当前状态",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "domain": {
                                "type": "string",
                                "description": "可选，只看某一类：light/switch/climate/fan/cover 等",
                            }
                        },
                    },
                },
            },
        ]

    def prompt_hint(self) -> str:
        """塞进 system prompt 的设备清单，让模型知道家里有什么。

        只列名字不列状态：带上"关着"模型就会自作主张回"已经关好了"，
        而不是真的去调工具。
        """
        if not self.enabled or not self._devices:
            return ""
        names = []
        for d in sorted(self._devices.values(), key=lambda x: (x.area, x.name))[:60]:
            label = d.label
            if label not in names:
                names.append(label)
        return (
            "你可以直接控制家里这些设备：" + "、".join(names) + "。"
            "用户要求开关或调节设备时，必须调用工具去执行，"
            "不要根据这份清单猜测设备状态，也不要假装已经操作。"
            "例如：“把卧室灯关掉”就调用 home_control；"
            "“空调开到26度”就调用 home_set；"
            "“客厅灯还亮着吗”就调用 home_status。"
        )

    def labels(self) -> list[str]:
        """给意图解析用的设备名列表（去重、按区域排序）。"""
        if not self._devices:
            return []
        names = []
        for d in sorted(self._devices.values(), key=lambda x: (x.area, x.name)):
            if d.label not in names:
                names.append(d.label)
        return names[:60]

    async def run_tool(self, name: str, args: dict) -> str:
        """执行模型发起的工具调用，返回一段给模型看的结果文字。"""
        await self.ensure()
        args = args or {}

        if name == "home_list":
            domain = str(args.get("domain") or "").strip() or None
            devs = await self.catalog()
            if domain:
                devs = [d for d in devs if d.domain == domain]
            if not devs:
                return "没有找到可控制的设备。"
            return "；".join(f"{d.label}（{describe(d)}）" for d in devs[:40])

        target = str(args.get("name") or args.get("target") or "").strip()
        if not target:
            return "没有说明要控制哪个设备。"

        if name == "home_status":
            devices = self.match(target)
            if not devices:
                return f"家里没有叫“{target}”的设备。"
            if len(devices) > 1:
                return "有多个设备符合：" + "、".join(d.label for d in devices[:4]) + "，需要用户说清楚是哪一个。"
            return f"{devices[0].label}{describe(devices[0])}。"

        if name == "home_set":
            attr = str(args.get("attribute") or "")
            try:
                value = float(args.get("value"))
            except (TypeError, ValueError):
                return "数值不对，没法设置。"
            domain = "light" if attr == "brightness" else "climate" if attr == "temperature" else None
            devices = self.match(target, domain=domain)
            if not devices:
                return f"家里没有叫“{target}”的{'灯' if attr == 'brightness' else '空调'}。"
            if len(devices) > 1:
                return "有多个设备符合：" + "、".join(d.label for d in devices[:4]) + "，需要用户说清楚。"
            dev = devices[0]
            if attr == "brightness":
                await self.set_brightness(dev, value)
            elif attr == "temperature":
                await self.set_temperature(dev, value)
            else:
                return "不支持这个设置项。"
            return f"已设置。{dev.label}{describe(dev)}。"

        if name == "home_control":
            action = str(args.get("action") or "").strip()
            if action not in ("turn_on", "turn_off", "toggle"):
                return "动作只能是打开、关闭或切换。"
            devices = self.match(target)
            if not devices:
                return f"家里没有叫“{target}”的设备。"
            if len(devices) > 1:
                return "有多个设备符合：" + "、".join(d.label for d in devices[:4]) + "，需要用户说清楚是哪一个。"
            dev = await self.act(devices[0], action)
            return f"已执行。{reply_for(dev, action)}"

        return f"未知工具：{name}"
