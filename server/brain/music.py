"""音乐：搜歌 → 取直链 → 缓存到本地 → 用局域网地址推给设备播。

音源可多个，按顺序尝试：
  netease  GD Studio 的公开 API，能给无损 FLAC 和歌词
  kuwo     酷我自己的接口，曲库更全，但只有 MP3、没有歌词

播放链路和 TTS 一样走局域网 HTTP，不依赖外部流媒体，避免断流。
"""
from __future__ import annotations

import asyncio
import hashlib
import logging
import random
import re
import urllib.parse
import urllib.request
from pathlib import Path

log = logging.getLogger("music")

_STOP = ("暂停", "别放了", "停止播放", "停一下", "关掉音乐", "别唱了")
_RESUME = ("继续放", "接着放", "继续播", "接着播", "继续", "接着")
_NEXT = ("下一首", "换一首", "下一曲", "切歌")
_PREV = ("上一首", "上一曲", "前一首")
_VOL_UP = ("音量大", "声音大", "大声点", "大点声", "调大声")
_VOL_DOWN = ("音量小", "声音小", "小声点", "小点声", "调小声")

_PLAY = re.compile(r"^(?:帮我|给我|请|麻烦)?\s*(?:播放|放|来|听)\s*(?:一首|一手|一段|一个|一首歌|首|手|曲|点)?\s*(.*)$")
_LEAD_PLAY = re.compile(r"^(?:我想听|想听|我要听|要听|播放|放|来|听)\s*")
_LEAD_UNIT = re.compile(r"^[一首手曲段个支张]\s*")
_TAIL = re.compile(r"(的歌|的歌曲|的曲子|音乐|歌曲|曲子|听听|吧|呀|啊)+$")

KW_SEARCH = "http://search.kuwo.cn/r.s?all={q}&ft=music&itemset=web_2013&client=kt&pn=0&rn={n}&rformat=json&encoding=utf8"
KW_URL = "http://antiserver.kuwo.cn/anti.s?type=convert_url&format=mp3&br=320&response=url&rid=MUSIC_{rid}"

BILI_UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
           "(KHTML, like Gecko) Chrome/120.0 Safari/537.36")
BILI_HEADERS = {"User-Agent": BILI_UA, "Referer": "https://www.bilibili.com/"}
# WBI 签名用的混淆表，B 站前端写死的
_MIXIN = [46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
          33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
          26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
          20, 34, 44, 52]


def _clean(s: str) -> str:
    return (s or "").replace("&nbsp;", " ").replace("\\u0026", "&").replace("\\", "").strip()


class Music:
    def __init__(self, cfg, ha=None):
        self.cfg = cfg
        self.ha = ha
        self.enabled = bool(getattr(cfg, "enabled", False))
        self.api = str(getattr(cfg, "api", "https://music-api.gdstudio.xyz/api.php"))
        self.providers = list(getattr(cfg, "providers", []) or ["netease"])
        self.quality = int(getattr(cfg, "quality", 320))
        self.seeds = list(getattr(cfg, "seeds", []) or [])
        root = Path(__file__).resolve().parent.parent.parent
        self.cache = root / str(getattr(cfg, "cache_dir", "server/cache/music"))
        self.cache.mkdir(parents=True, exist_ok=True)
        self.max_mb = int(getattr(cfg, "cache_max_mb", 2048))
        self.max_age_days = int(getattr(cfg, "cache_max_age_days", 14))
        self._wbi_key = ""
        self._wbi_at = 0.0
        self._pool: list[dict] = []
        self._refilling = False
        # 音质模式：original 不转码 / auto 自动 / 128 192 320 固定码率
        self.quality = str(getattr(cfg, "quality_mode", "auto"))

        nd = getattr(cfg, "navidrome", None)
        self.nd_url = str(getattr(nd, "url", "") or "").rstrip("/") if nd else ""
        self.nd_user = str(getattr(nd, "user", "") or "") if nd else ""
        self.nd_pass = str(getattr(nd, "password", "") or "") if nd else ""

    # ---------- 触发判断 ----------

    def handles(self, query: str) -> bool:
        if not self.enabled:
            return False
        q = query.strip()
        if any(w in q for w in _STOP + _RESUME + _NEXT + _PREV + _VOL_UP + _VOL_DOWN):
            return True
        return bool(_PLAY.match(q))

    def is_control(self, query: str) -> bool:
        """暂停/继续/切歌/音量这类确定性指令，仍然走本地，响应最快。"""
        q = (query or "").strip()
        return any(w in q for w in _STOP + _RESUME + _NEXT + _PREV + _VOL_UP + _VOL_DOWN)

    def has_song_name(self, query: str) -> bool:
        """带具体歌名的点歌，交给模型先理解，ASR 听错时还能结合上下文纠正。"""
        return bool(self._song_name(query or ""))

    def tools(self) -> list[dict]:
        return [
            {
                "type": "function",
                "function": {
                    "name": "music_play",
                    "description": (
                        "播放用户想听的音乐。用户点歌、换歌、想听某首歌或某个歌手时调用。"
                        "query 只填歌名或歌手，不要带“播放”“放一首”等词；"
                        "用户没指定歌名时留空，表示随机播放。"
                        "如果识别出的歌名明显有错，结合上下文换成最可能的歌名再调用。"
                    ),
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "query": {
                                "type": "string",
                                "description": "歌名或歌手，例如：晴天、周杰伦、周杰伦 晴天；随机播放留空",
                            }
                        },
                        "required": ["query"],
                    },
                },
            },
            {
                "type": "function",
                "function": {
                    "name": "music_control",
                    "description": "控制正在播放的音乐：暂停、继续、下一首、上一首、停止、音量加减",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "action": {
                                "type": "string",
                                "enum": ["pause", "resume", "next", "prev", "stop", "volume_up", "volume_down"],
                                "description": "要执行的动作",
                            }
                        },
                        "required": ["action"],
                    },
                },
            },
        ]

    def prompt_hint(self) -> str:
        return (
            "用户想听歌、点歌、切歌、暂停/继续/停止或调音量时，必须调用 "
            "music_play 或 music_control，不要只口头答应。"
            "用户说的歌名可能带识别错误，结合上下文判断；拿不准时也先按最可能的歌名调用 "
            "music_play，不要反问一堆。"
        )

    async def play(self, query: str = "") -> dict:
        """模型点歌：query 已经是干净的歌名/歌手，不再依赖“播放”前缀。"""
        q = (query or "").strip()
        try:
            if not q:
                return await self._play("")
            loop = asyncio.get_running_loop()
            songs = []
            for cand in self._query_variants(q):
                songs = await loop.run_in_executor(None, self._search, cand)
                if songs:
                    break
            if not songs:
                return {"say": f"没搜到《{q}》，换个歌名试试？"}
            item = await self._materialize(songs[0])
            if item is None:
                return {"say": f"《{songs[0]['title']}》拿不到播放地址。"}
            return {**item, "say": self._say_text(item)}
        except Exception as e:
            log.warning("点歌失败：%s", e)
            return {"say": "音乐服务没连上，稍后再试。"}

    def _query_variants(self, q: str) -> list[str]:
        """把“赵雷的成都”拆成“成都”“赵雷”，提高本地曲库命中率。"""
        out: list[str] = []

        def add(x: str) -> None:
            x = (x or "").strip()
            if x and x not in out:
                out.append(x)

        add(q)
        alt = _LEAD_PLAY.sub("", q).strip()
        alt = _TAIL.sub("", alt).strip(" ，,。.！!？?《》\"'“”")
        add(alt)
        for base in list(out):
            if re.search(r"\s", base):
                parts = [p.strip() for p in re.split(r"\s+", base) if p.strip()]
            elif "的" in base:
                parts = [p.strip() for p in base.split("的") if p.strip()]
            else:
                continue
            for p in reversed(parts):
                add(p)
            if len(parts) > 1:
                add(" ".join(parts))
        return out

    # ---------- 执行 ----------

    async def handle(self, query: str) -> dict:
        q = query.strip()
        try:
            if "暂停" in q:
                return {"say": "好，暂停了。", "cmd": "pause"}
            if any(w in q for w in _STOP):
                return {"say": "好，停了。", "cmd": "stop"}
            if any(w in q for w in _RESUME):
                return {"say": "接着放。", "cmd": "resume"}
            if any(w in q for w in _NEXT):
                return {"say": "换一首。", "cmd": "next"}
            if any(w in q for w in _PREV):
                return {"say": "上一首。", "cmd": "prev"}
            if any(w in q for w in _VOL_UP):
                return {"say": "好，音量调大了。", "cmd": "vol_up"}
            if any(w in q for w in _VOL_DOWN):
                return {"say": "好，音量调小了。", "cmd": "vol_down"}
            return await self._play(q)
        except Exception as e:
            log.warning("音乐操作失败：%s", e)
            return {"say": "音乐服务没连上，稍后再试。"}

    async def _play(self, q: str) -> dict:
        name = self._song_name(q)
        loop = asyncio.get_running_loop()

        if name:
            songs = await loop.run_in_executor(None, self._search, name)
            if not songs:
                return {"say": f"没搜到《{name}》，换个歌名试试？"}
            item = await self._materialize(songs[0])
            if item is None:
                return {"say": f"《{songs[0]['title']}》拿不到播放地址，可能没版权。"}
            return {**item, "say": self._say_text(item)}

        # 随机播放：优先用预加载池，点歌即出
        if self._pool:
            item = self._pool.pop(0)
            log.info("从预加载池取出：%s（剩 %d）", item["title"], len(self._pool))
            asyncio.create_task(self._refill_pool())
            return {**item, "say": self._say_text(item)}

        song = await loop.run_in_executor(None, self._nd_random)
        if not song:
            seed = random.choice(self.seeds) if self.seeds else "热歌"
            songs = await loop.run_in_executor(None, self._search, seed)
            if not songs:
                return {"say": "曲库暂时搜不到歌，等会儿再试。"}
            song = songs[0]
        item = await self._materialize(song)
        if item is None:
            return {"say": "这首歌拿不到播放地址，换一首试试。"}
        asyncio.create_task(self._refill_pool())
        return {**item, "say": self._say_text(item)}

    async def _materialize(self, song: dict) -> dict | None:
        """把一首歌落到本地：取直链 → 下载 → 歌词 → 封面"""
        loop = asyncio.get_running_loop()

        # 自动档：如果之前已经补过 320k，直接用高码率
        if self.quality == "auto" and song["provider"] == "navidrome":
            p320 = self.cache / (self._cache_key(song, "320") + ".mp3")
            if p320.exists() and p320.stat().st_size > 100_000:
                return await self._finish_item(song, p320)

        url = await loop.run_in_executor(None, self._stream_url, song)
        if not url:
            return None
        path = await loop.run_in_executor(None, self._download, url, song)
        if path is None:
            return None
        if self.quality == "auto" and song["provider"] == "navidrome":
            asyncio.create_task(self._upgrade(song))
        return await self._finish_item(song, path)

    async def _upgrade(self, song: dict) -> None:
        """后台补一份 320k，下一轮播放同一首歌就能用上"""
        loop = asyncio.get_running_loop()
        try:
            dst = self.cache / (self._cache_key(song, "320") + ".mp3")
            if dst.exists() and dst.stat().st_size > 100_000:
                return
            url = await loop.run_in_executor(None, lambda: self._nd_stream(song["ref"], "320"))
            ok = await loop.run_in_executor(None, self._download_to, url, dst)
            if ok:
                log.info("已补高码率：%s", song["title"])
        except Exception as e:
            log.info("补高码率失败：%s", e)

    async def _finish_item(self, song: dict, path) -> dict:
        loop = asyncio.get_running_loop()
        lrc = ""
        if song.get("lyric_ref") or song["provider"] == "navidrome":
            lrc = await loop.run_in_executor(None, self._lyric, song)
        pic_name = ""
        if song.get("pic_ref"):
            p = await loop.run_in_executor(None, self._pic, song)
            if p is not None:
                pic_name = p.name
        await loop.run_in_executor(None, self._sweep)
        return {
            "audio": path,
            "path": path.name,
            "title": song["title"],
            "artist": song["artist"],
            "lrc": lrc,
            "pic": pic_name,
        }

    def _say_text(self, item: dict) -> str:
        return (f"好的，为你播放《{item['title']}》"
                + (f"，{item['artist']}的。" if item.get("artist") else "。"))

    async def warm(self) -> None:
        """服务启动后先把池子填上，用户第一次点歌就不必等转码"""
        try:
            await self._refill_pool()
        except Exception as e:
            log.info("预热失败：%s", e)

    async def _refill_pool(self) -> None:
        """后台把下一批歌准备好，切歌/下一首时不用等"""
        if self._refilling:
            return
        self._refilling = True
        try:
            loop = asyncio.get_running_loop()
            while len(self._pool) < 3:
                song = await loop.run_in_executor(None, self._nd_random)
                if not song:
                    break
                if any(x["title"] == song["title"] for x in self._pool):
                    continue
                item = await self._materialize(song)
                if item:
                    self._pool.append(item)
                    log.info("预加载：%s（池 %d）", item["title"], len(self._pool))
        except Exception as e:
            log.info("预加载失败：%s", e)
        finally:
            self._refilling = False

    # ---------- 搜索：按 providers 顺序尝试 ----------

    def _search(self, name: str) -> list[dict]:
        for provider in self.providers:
            try:
                if provider == "navidrome":
                    hits = self._search_navidrome(name)
                elif provider == "kuwo":
                    hits = self._search_kuwo(name)
                elif provider == "bilibili":
                    hits = self._search_bili(name)
                else:
                    hits = self._search_netease(name)
                if hits:
                    log.info("搜到 %s 结果 %d 条", provider, len(hits))
                    return hits
                log.info("%s 无结果：%s", provider, name)
            except Exception as e:
                log.warning("%s 搜索失败 %s: %s", provider, name, e)
        return []

    def _search_netease(self, name: str) -> list[dict]:
        res = self._api(types="search", source="netease", name=name, count=6)
        if not isinstance(res, list):
            return []
        out = []
        for s in res:
            # 文档里 url_id 已废弃，统一用 id（老接口仍可能只给 url_id）
            ref = str(s.get("id") or s.get("url_id") or "")
            out.append({
                "provider": "netease",
                "title": _clean(s.get("name")),
                "artist": _clean("/".join(s.get("artist") or [])),
                "ref": ref,
                "lyric_ref": str(s.get("lyric_id") or ref),
                "pic_ref": str(s.get("pic_id") or ""),
            })
        return [x for x in out if x["title"] and x["ref"]]

    def _search_kuwo(self, name: str) -> list[dict]:
        url = KW_SEARCH.format(q=urllib.parse.quote(name), n=6)
        raw = self._http(url).decode("utf-8", "replace")
        txt = raw.replace("'", '"')
        import json

        try:
            data = json.loads(txt)
        except json.JSONDecodeError:
            return []
        out = []
        for it in data.get("abslist") or []:
            rid = str(it.get("MUSICRID") or "").replace("MUSIC_", "")
            if not rid:
                continue
            out.append({
                "provider": "kuwo",
                "title": _clean(it.get("SONGNAME")),
                "artist": _clean(it.get("ARTIST")).replace("&", "/"),
                "ref": rid,
                "lyric_ref": "",
                "pic_ref": "",
            })
        return [x for x in out if x["title"]]

    # ---------- Navidrome（Subsonic API） ----------

    def _nd_params(self) -> dict:
        import secrets

        salt = secrets.token_hex(6)
        token = hashlib.md5((self.nd_pass + salt).encode()).hexdigest()
        return {"u": self.nd_user, "t": token, "s": salt,
                "v": "1.16.1", "c": "xiaodu", "f": "json"}

    def _nd(self, endpoint: str, **params):
        q = urllib.parse.urlencode({**self._nd_params(), **params})
        return self._json(f"{self.nd_url}/rest/{endpoint}.view?{q}")

    def _search_navidrome(self, name: str) -> list[dict]:
        if not (self.nd_url and self.nd_user and self.nd_pass):
            return []
        res = self._nd("search3", query=name, songCount=6)
        body = (res or {}).get("subsonic-response", {}) or {}
        songs = ((body.get("searchResult3") or {}).get("song")) or []
        out = []
        for s in songs:
            sid = str(s.get("id") or "")
            if not sid:
                continue
            out.append({
                "provider": "navidrome",
                "title": _clean(s.get("title")),
                "artist": _clean(s.get("artist") or ""),
                "album": _clean(s.get("album") or ""),
                "ref": sid,
                "lyric_ref": "",
                "pic_ref": str(s.get("coverArt") or ""),
                "suffix": str(s.get("suffix") or ""),
            })
        return [x for x in out if x["title"]]

    def _nd_random(self) -> dict | None:
        """从本地曲库随机取一首，用于"放首歌"这种不带歌名的请求。"""
        if not (self.nd_url and self.nd_user and self.nd_pass):
            return None
        try:
            res = self._nd("getRandomSongs", size=1)
            body = (res or {}).get("subsonic-response", {}) or {}
            songs = ((body.get("randomSongs") or {}).get("song")) or []
            if not songs:
                return None
            s = songs[0]
            return {
                "provider": "navidrome",
                "title": _clean(s.get("title")),
                "artist": _clean(s.get("artist") or ""),
                "album": _clean(s.get("album") or ""),
                "ref": str(s.get("id") or ""),
                "lyric_ref": "",
                "pic_ref": str(s.get("coverArt") or ""),
                "suffix": str(s.get("suffix") or ""),
            }
        except Exception as e:
            log.warning("Navidrome 随机取歌失败：%s", e)
            return None

    def set_quality(self, q: str) -> None:
        """切换音质：池子里的歌是按旧码率转的，直接清空"""
        q = (q or "auto").strip().lower()
        if q == self.quality:
            return
        self.quality = q
        self._pool.clear()
        log.info("音质切换为 %s，预加载池已清空", q)
        try:
            asyncio.get_running_loop().create_task(self._refill_pool())
        except RuntimeError:
            pass

    def _cache_key(self, song: dict, quality: str) -> str:
        return hashlib.md5(f"{song['provider']}-{song['ref']}-{quality}".encode()).hexdigest()[:16]

    def _nd_stream(self, sid: str, quality: str | None = None) -> str:
        q = quality or self.quality
        # 让 Navidrome 按当前音质转码；original 不转，直接给源文件
        params = {**self._nd_params(), "id": sid}
        if q == "original":
            pass
        elif q == "auto":
            params["format"] = "mp3"
            params["maxBitRate"] = "128"      # 先低码率，保证秒开
        else:
            params["format"] = "mp3"
            params["maxBitRate"] = q
        return f"{self.nd_url}/rest/stream.view?{urllib.parse.urlencode(params)}"

    def _nd_lyric(self, sid: str) -> str:
        try:
            res = self._nd("getLyricsBySongId", id=sid)
            body = (res or {}).get("subsonic-response", {}) or {}
            lists = ((body.get("lyricsList") or {}).get("structuredLyrics")) or []
            if not lists:
                return ""
            lines = (lists[0].get("line")) or []
            out = []
            for ln in lines:
                start = int(ln.get("start") or 0)
                txt = (ln.get("value") or "").strip()
                if not txt:
                    continue
                out.append("[%02d:%05.2f]%s" % (start // 60000, (start % 60000) / 1000, txt))
            return "\n".join(out)
        except Exception as e:
            log.info("Navidrome 歌词失败：%s", e)
            return ""

    # ---------- B 站（WBI 签名） ----------

    def _bili_key(self) -> str:
        import time as _t

        if self._wbi_key and _t.time() - self._wbi_at < 36000:
            return self._wbi_key
        try:
            nav = self._json("https://api.bilibili.com/x/web-interface/nav", headers=BILI_HEADERS)
            img = nav["data"]["wbi_img"]["img_url"].rsplit("/", 1)[-1].split(".")[0]
            sub = nav["data"]["wbi_img"]["sub_url"].rsplit("/", 1)[-1].split(".")[0]
            raw = img + sub
            self._wbi_key = "".join(raw[i] for i in _MIXIN)[:32]
            self._wbi_at = _t.time()
            log.info("B 站签名 key 已更新")
        except Exception as e:
            log.warning("取 B 站签名 key 失败：%s", e)
        return self._wbi_key

    def _bili_sign(self, params: dict) -> str:
        import time as _t

        p = dict(params)
        p["wts"] = int(_t.time())
        q = urllib.parse.urlencode(sorted(p.items()))
        p["w_rid"] = hashlib.md5((q + self._bili_key()).encode()).hexdigest()
        return urllib.parse.urlencode(sorted(p.items()))

    def _search_bili(self, name: str) -> list[dict]:
        # 官方 API 支持 bilibili（带签名，省得我们自己算 WBI），失败再退回自建签名
        try:
            res = self._api(types="search", source="bilibili", name=name, count=6)
            if isinstance(res, list) and res:
                out = []
                for s in res:
                    ref = str(s.get("id") or "")
                    if not ref:
                        continue
                    out.append({
                        "provider": "bilibili",
                        "title": _clean(s.get("name")),
                        "artist": _clean("/".join(s.get("artist") or [])),
                        "ref": ref,
                        "lyric_ref": str(s.get("lyric_id") or ""),
                        "pic_ref": str(s.get("pic_id") or ""),
                        "bili_api": True,
                    })
                if out:
                    return out
        except Exception as e:
            log.info("B 站官方接口搜索失败，改用自建签名：%s", e)
        return self._search_bili_wbi(name)

    def _search_bili_wbi(self, name: str) -> list[dict]:
        import html

        q = self._bili_sign({"search_type": "video", "keyword": name, "page": 1})
        res = self._json(
            f"https://api.bilibili.com/x/web-interface/wbi/search/type?{q}",
            headers=BILI_HEADERS,
        )
        items = ((res.get("data") or {}).get("result")) or []
        out = []
        for it in items:
            title = html.unescape(re.sub(r"<[^>]+>", "", it.get("title") or ""))
            bvid = it.get("bvid")
            if not bvid or not title:
                continue
            out.append({
                "provider": "bilibili",
                "title": _clean(title),
                "artist": _clean(it.get("author") or ""),
                "ref": bvid,
                "lyric_ref": "",
                "pic_ref": "",
            })
        return out[:6]

    def _bili_stream(self, bvid: str) -> str | None:
        pg = self._json(f"https://api.bilibili.com/x/player/pagelist?bvid={bvid}", headers=BILI_HEADERS)
        pages = pg.get("data") or []
        cid = pages[0].get("cid") if pages else None
        if not cid:
            return None
        q = self._bili_sign({"bvid": bvid, "cid": cid, "fnval": 16})
        pu = self._json(f"https://api.bilibili.com/x/player/wbi/playurl?{q}", headers=BILI_HEADERS)
        auds = (((pu.get("data") or {}).get("dash") or {}).get("audio")) or []
        if not auds:
            return None
        best = max(auds, key=lambda a: a.get("bandwidth", 0))
        return best.get("baseUrl") or best.get("base_url")

    # ---------- 取直链 ----------

    def _stream_url(self, song: dict) -> str | None:
        try:
            if song["provider"] == "kuwo":
                raw = self._http(KW_URL.format(rid=song["ref"])).decode("utf-8", "replace")
                return raw if raw.startswith("http") else None
            if song["provider"] == "navidrome":
                return self._nd_stream(song["ref"])
            if song["provider"] == "bilibili":
                if song.get("bili_api"):
                    try:
                        res = self._api(types="url", source="bilibili", id=song["ref"], br=999)
                        u = (res or {}).get("url") if isinstance(res, dict) else None
                        if u:
                            return u
                    except Exception as e:
                        log.info("B 站官方取流失败，改用自建签名：%s", e)
                return self._bili_stream(song["ref"])
            res = self._api(types="url", source="netease", id=song["ref"], br=self.quality)
            return (res or {}).get("url") if isinstance(res, dict) else None
        except Exception as e:
            log.warning("取直链失败 %s: %s", song.get("title"), e)
            return None

    def _lyric(self, song: dict) -> str:
        try:
            if song["provider"] == "navidrome":
                return self._nd_lyric(song["ref"])
            res = self._api(types="lyric", source="netease", id=song["lyric_ref"])
            if isinstance(res, dict):
                return (res.get("lyric") or res.get("tlyric") or "")[:20000]
        except Exception as e:
            log.info("取歌词失败 %s: %s", song.get("title"), e)
        return ""

    def _pic(self, song: dict) -> Path | None:
        try:
            if song["provider"] == "navidrome":
                if not song.get("pic_ref"):
                    return None
                q = urllib.parse.urlencode(
                    {**self._nd_params(), "id": song["pic_ref"], "size": "500"}
                )
                url = f"{self.nd_url}/rest/getCoverArt.view?{q}"
                key = hashlib.md5(f"{song['ref']}-cover".encode()).hexdigest()[:16]
                return self._download_pic(url, key)
            res = self._api(types="pic", source="netease", id=song["pic_ref"])
            url = (res or {}).get("url") if isinstance(res, dict) else ""
            if not url:
                return None
            key = hashlib.md5(f"{song['ref']}-pic".encode()).hexdigest()[:16]
            return self._download_pic(url, key)
        except Exception as e:
            log.info("取封面失败 %s: %s", song.get("title"), e)
            return None

    # ---------- 下载与缓存 ----------

    def _download(self, url: str, song: dict) -> Path | None:
        key = self._cache_key(song, self.quality)
        if song["provider"] == "navidrome":
            if self.quality == "original" and song.get("suffix"):
                ext = "." + song["suffix"]
            else:
                ext = ".mp3"      # 其它档位都是让 Navidrome 转成 mp3
        elif ".flac" in url.lower():
            ext = ".flac"
        elif song["provider"] == "bilibili":
            ext = ".m4a"
        else:
            ext = ".mp3"
        path = self.cache / f"{key}{ext}"
        if path.exists() and path.stat().st_size > 100_000:
            return path
        try:
            hdr = dict(BILI_HEADERS) if song["provider"] == "bilibili" else {"User-Agent": "Mozilla/5.0"}
            req = urllib.request.Request(url, headers=hdr)
            with urllib.request.urlopen(req, timeout=60) as r, open(path, "wb") as f:
                while True:
                    chunk = r.read(256 * 1024)
                    if not chunk:
                        break
                    f.write(chunk)
            if path.stat().st_size < 100_000:
                path.unlink(missing_ok=True)
                return None
            return path
        except Exception as e:
            log.warning("下载失败：%s", e)
            path.unlink(missing_ok=True)
            return None

    def _download_to(self, url: str, path: Path) -> bool:
        """下到指定路径，返回是否成功"""
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req, timeout=120) as r, open(path, "wb") as f:
                while True:
                    chunk = r.read(256 * 1024)
                    if not chunk:
                        break
                    f.write(chunk)
            if path.stat().st_size < 100_000:
                path.unlink(missing_ok=True)
                return False
            return True
        except Exception as e:
            log.info("下载失败：%s", e)
            path.unlink(missing_ok=True)
            return False

    def _download_pic(self, url: str, key: str) -> Path | None:
        path = self.cache / f"{key}.jpg"
        if path.exists() and path.stat().st_size > 2000:
            return path
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req, timeout=20) as r, open(path, "wb") as f:
                f.write(r.read())
            if path.stat().st_size < 2000:
                path.unlink(missing_ok=True)
                return None
            return path
        except Exception as e:
            log.info("封面下载失败：%s", e)
            path.unlink(missing_ok=True)
            return None

    def _sweep(self) -> None:
        """按容量和时长清理缓存，最久没用的先删。"""
        import time as _t

        try:
            now = _t.time()
            limit = self.max_mb * 1024 * 1024
            files = []
            total = 0
            for p in self.cache.glob("*"):
                try:
                    st = p.stat()
                except OSError:
                    continue
                files.append((st.st_mtime, st.st_size, p))
                total += st.st_size
            files.sort()

            for mtime, size, p in files:
                if (now - mtime) / 86400 > self.max_age_days:
                    p.unlink(missing_ok=True)
                    total -= size
                    log.info("缓存过期删除 %s", p.name)

            target = int(limit * 0.9)
            for mtime, size, p in files:
                if total <= target:
                    break
                if not p.exists():
                    continue
                p.unlink(missing_ok=True)
                total -= size
                log.info("缓存超限删除 %s（%.1fMB），剩余 %.0fMB",
                         p.name, size / 1048576, total / 1048576)
        except Exception as e:
            log.debug("缓存清理失败：%s", e)

    # ---------- 基础 ----------

    def _api(self, **kw):
        q = urllib.parse.urlencode(kw)
        return self._json(f"{self.api}?{q}")

    def _json(self, url: str, headers=None):
        import json

        return json.loads(self._http(url, headers=headers).decode("utf-8", "replace"))

    def _http(self, url: str, timeout: int = 25, headers=None) -> bytes:
        h = {"User-Agent": "Mozilla/5.0"}
        if headers:
            h.update(headers)
        req = urllib.request.Request(url, headers=h)
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.read()

    def _song_name(self, q: str) -> str:
        m = _PLAY.match(q)
        raw = (m.group(1) if m else q).strip()
        raw = _LEAD_UNIT.sub("", raw)
        raw = _TAIL.sub("", raw).strip(" ，,。.！!？?《》\"'“”")
        if raw in ("", "歌", "首歌", "音乐", "点", "曲子", "点歌"):
            return ""
        if raw.startswith("首") or raw.startswith("点"):
            return ""
        return raw
