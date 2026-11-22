# -*- coding: utf-8 -*-
"""MBox 内嵌 Python 源的基类(对齐 catvod / drpy 的 Python 源约定)。

第三方 .py 源里写的 ``from base.spider import Spider`` 指的就是这个类:
宿主(Java 侧 ``SpiderPY``)把源文件当模块跑起来,取其中的 ``Spider`` 子类实例,
再按 catvod 契约调用 init / homeContent / categoryContent / detailContent /
searchContent / playerContent / localProxy。

宿主会往实例上注入两个属性:
  * ``skey``   站点 key(订阅里 ``sites[].key``),拼本机代理地址用;
  * ``server`` 本机 HTTP 服务地址(``http://127.0.0.1:<port>``),``getProxyUrl`` 用。

依赖只有标准库 + 仓库自带的 requests 兼容层,APK 内直接可跑,不需要联网装包。
"""
import json
import re
from urllib.parse import quote, urljoin

import requests

DEFAULT_UA = requests.DEFAULT_UA


class Spider(object):
    """py 源基类。默认实现一律返回空结构,子类按需覆盖。"""

    def __init__(self, *args, **kwargs):
        # 源自己写的 __init__ 可能不调 super(),所以宿主注入的这两个值在实例化后由 Java 侧补写
        self.skey = kwargs.get("skey", "") or ""
        self.server = kwargs.get("server", "") or ""
        self.extend = ""
        self.ua = DEFAULT_UA

    # ------------------------------------------------------------------
    # 生命周期
    # ------------------------------------------------------------------

    def getName(self):
        return ""

    def init(self, extend=""):
        self.extend = extend or ""

    def destroy(self):
        pass

    def getDependence(self):
        return []

    # ------------------------------------------------------------------
    # 内容(由宿主按契约调用)
    # ------------------------------------------------------------------

    def homeContent(self, filter=False):
        return {"class": [], "filters": {}}

    def homeVideoContent(self):
        return {"list": []}

    def categoryContent(self, tid, pg="1", filter=False, extend=None):
        return {"list": [], "page": _int(pg, 1), "pagecount": 0, "limit": 0, "total": 0}

    def detailContent(self, ids):
        return {"list": []}

    def searchContent(self, key, quick=False, pg="1"):
        return {"list": []}

    def playerContent(self, flag="", id="", vipFlags=None):
        return {"parse": 0, "playUrl": "", "url": id or "", "header": ""}

    def liveContent(self, url=""):
        return {"class": [], "list": []}

    def action(self, action=""):
        return ""

    def localProxy(self, params=None):
        return None

    def isVideoFormat(self, url):
        if not url:
            return False
        lower = str(url).lower()
        return bool(re.search(r"\.(m3u8|mp4|m4a|flv|mkv|ts|mp3|mov)(\?|$)", lower)) \
            or ".m3u8?" in lower

    def manualVideoCheck(self):
        return False

    # ------------------------------------------------------------------
    # 请求助手(源里的 self.fetch / self.post)
    # ------------------------------------------------------------------

    def fetch(self, url, headers=None, timeout=10, **kwargs):
        """GET;timeout 兼容毫秒写法(源里常见 timeout=15000)。"""
        return requests.get(url, headers=self._merge_headers(headers),
                            timeout=_seconds(timeout), **kwargs)

    def post(self, url, data=None, headers=None, timeout=10, **kwargs):
        return requests.post(url, data=data, headers=self._merge_headers(headers),
                             timeout=_seconds(timeout), **kwargs)

    def _merge_headers(self, headers):
        merged = {"User-Agent": getattr(self, "ua", DEFAULT_UA)}
        referer = getattr(self, "referer", "") or ""
        if referer:
            merged["Referer"] = referer
        for key, value in (headers or {}).items():
            if value is not None:
                merged[str(key)] = str(value)
        return merged

    # ------------------------------------------------------------------
    # 本机代理:源把"要带鉴权/要解密的地址"交给宿主,由宿主转发到 localProxy
    # ------------------------------------------------------------------

    def getProxyUrl(self, flag="", id="", urls=None, **kwargs):
        """拼出宿主本机代理地址;源自己往后拼 &url=<原始地址> 即可。"""
        base = (getattr(self, "server", "") or "").rstrip("/")
        if not base:
            return ""
        parts = ["do=py", "siteKey=" + quote(str(getattr(self, "skey", "") or ""), safe="")]
        if flag:
            parts.append("flag=" + quote(str(flag), safe=""))
        if id:
            parts.append("id=" + quote(str(id), safe=""))
        if urls:
            first = urls if isinstance(urls, str) else list(urls)[0]
            if first:
                parts.append("url=" + quote(str(first), safe=""))
        return base + "/proxy?" + "&".join(parts)

    # ------------------------------------------------------------------
    # 小工具(源里常调)
    # ------------------------------------------------------------------

    def log(self, message):
        print("[py-spider] %s" % message)

    def regStr(self, pattern, text, index=1):
        try:
            matched = re.search(pattern, text or "", re.S)
            return matched.group(index) if matched else ""
        except (re.error, IndexError):
            return ""

    def joinUrl(self, base, path):
        if not path:
            return base or ""
        if path.startswith("http"):
            return path
        return urljoin(base or "", path)

    def removeHtmlTag(self, text):
        return re.sub(r"<[^>]+>", "", text or "")

    def json2str(self, value):
        return json.dumps(value, ensure_ascii=False)

    def str2json(self, text):
        return json.loads(text) if text else {}


def _int(value, default=0):
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def _seconds(timeout):
    """源里 timeout 有的写秒(5)、有的写毫秒(15000):>60 一律按毫秒处理。"""
    try:
        value = float(timeout)
    except (TypeError, ValueError):
        return 10
    if value <= 0:
        return 10
    return value / 1000.0 if value > 60 else value
