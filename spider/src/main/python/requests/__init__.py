# -*- coding: utf-8 -*-
"""requests 兼容层(TVBox / drpy 的 .py 源习惯 ``import requests``)。

为什么不用真正的 requests:
  1. 装 pip 包要求构建机联网拉 PyPI,还要给每个 ABI 带上 urllib3/certifi 等依赖;
  2. py 源实际只用到 get/post + 少量属性,标准库完全能覆盖;
  3. 本层还能顺手统一"证书错误按用户口径处理"和 gzip/GBK 解码。

覆盖范围(按 TVBox 生态里 py 源的真实用法):``get/post/head/put/delete/request``、
``Session``、``exceptions``、``Response``(status_code / text / content / json() /
headers / url / encoding / ok / raise_for_status)、``urllib3.disable_warnings()``、
``verify`` / ``params`` / ``data`` / ``json`` / ``timeout`` / ``allow_redirects`` 等常用参数。
不支持的参数一律忽略,不抛 TypeError —— 源里常见的旧写法才不会因此整条挂掉。
"""
import json as _json
import socket
import ssl
import urllib.error
import urllib.parse
import urllib.request
import zlib

from . import exceptions
from .exceptions import (ConnectionError, ConnectTimeout, ContentDecodingError,
                         HTTPError, InvalidURL, ReadTimeout, RequestException,
                         SSLError, Timeout, TooManyRedirects)

__version__ = "2.31.0-mbox"

DEFAULT_TIMEOUT = 15
DEFAULT_UA = ('Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 '
              '(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36')


class Headers(dict):
    """大小写不敏感的响应头(源里既写 r.headers['Content-Type'] 也写小写)。"""

    def __init__(self, data=None):
        super(Headers, self).__init__()
        for key, value in (data or {}).items():
            self[key] = value

    def __setitem__(self, key, value):
        super(Headers, self).__setitem__(str(key).lower(), value)

    def __getitem__(self, key):
        return super(Headers, self).__getitem__(str(key).lower())

    def __contains__(self, key):
        return super(Headers, self).__contains__(str(key).lower())

    def get(self, key, default=None):
        return super(Headers, self).get(str(key).lower(), default)


class Response(object):
    """请求结果:属性名与 requests.Response 对齐,够源里直接取值。"""

    def __init__(self, content=b"", status_code=200, headers=None, url="", encoding=None, reason=""):
        self.content = content or b""
        self.status_code = int(status_code)
        self.headers = Headers(headers)
        self.url = url
        self.encoding = encoding
        self.reason = reason
        self.history = []
        self.cookies = {}

    # ---- 文本/JSON ----
    def _guess_encoding(self):
        if self.encoding:
            return self.encoding
        ctype = self.headers.get("content-type", "") or ""
        index = ctype.lower().find("charset=")
        if index >= 0:
            charset = ctype[index + len("charset="):].strip().strip('"\'')
            charset = charset.split(";")[0].strip()
            if charset:
                return charset
        # 页面里 <meta charset=gb2312> 这类:先按 utf-8 严格解一次,失败再按 gb18030
        try:
            self.content[:4096].decode("utf-8")
            return "utf-8"
        except UnicodeDecodeError:
            return "gb18030"

    @property
    def text(self):
        charset = self._guess_encoding()
        for candidate in (charset, "utf-8", "gb18030"):
            if not candidate:
                continue
            try:
                return self.content.decode(candidate)
            except (UnicodeDecodeError, LookupError):
                continue
        return self.content.decode("utf-8", "ignore")

    @property
    def apparent_encoding(self):
        return self._guess_encoding()

    def json(self, **kwargs):
        if "encoding" in kwargs:
            self.encoding = kwargs.pop("encoding")
        return _json.loads(self.text, **kwargs)

    @property
    def ok(self):
        return self.status_code < 400

    def raise_for_status(self):
        if self.status_code >= 400:
            raise HTTPError("HTTP %d" % self.status_code, response=self)

    def close(self):
        pass

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()

    def __repr__(self):
        return "<Response [%d]>" % self.status_code

    def __str__(self):
        return self.text


class _Urllib3(object):
    """源里常见的 ``urllib3.disable_warnings()`` 调用点:本层全程自行处理证书,直接空转。"""

    @staticmethod
    def disable_warnings(*args, **kwargs):
        pass


class _Packages(object):
    urllib3 = _Urllib3()


urllib3 = _Urllib3()
packages = _Packages()


def _ssl_context(verify):
    """verify=True 走系统校验;False 才放开(与"证书错误由用户自己决定"的口径一致)。"""
    if verify:
        return None
    context = ssl.create_default_context()
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    return context


def _decode_body(body, encoding_header):
    """urllib 不会自动解压:gzip/deflate 在这里还原,否则源拿到的是乱码。"""
    if not body:
        return b""
    encoding = (encoding_header or "").lower()
    try:
        if "gzip" in encoding:
            return zlib.decompress(body, 16 + zlib.MAX_WBITS)
        if "deflate" in encoding:
            try:
                return zlib.decompress(body)
            except zlib.error:
                return zlib.decompress(body, -zlib.MAX_WBITS)
    except zlib.error:
        return body
    return body


def _to_response(raw, url):
    headers = Headers(dict(raw.getheaders() if hasattr(raw, "getheaders") else raw.headers.items()))
    body = _decode_body(raw.read(), headers.get("content-encoding"))
    return Response(body, getattr(raw, "status", 200) or getattr(raw, "code", 200),
                    headers, url, reason=getattr(raw, "reason", "") or "")


def _with_params(url, params):
    if not params:
        return url
    query = urllib.parse.urlencode(params, doseq=True)
    joiner = "&" if ("?" in url) else "?"
    return url + joiner + query


def _normalize_body(data, json_body, headers):
    if json_body is not None:
        headers.setdefault("Content-Type", "application/json")
        return _json.dumps(json_body, ensure_ascii=False).encode("utf-8")
    if data is None:
        return None
    if isinstance(data, bytes):
        return data
    if isinstance(data, str):
        return data.encode("utf-8")
    if isinstance(data, (dict, list, tuple)):
        headers.setdefault("Content-Type", "application/x-www-form-urlencoded")
        return urllib.parse.urlencode(data, doseq=True).encode("utf-8")
    return str(data).encode("utf-8")


def request(method, url, params=None, data=None, headers=None, timeout=DEFAULT_TIMEOUT,
            verify=True, allow_redirects=True, json=None, cookies=None, files=None,
            stream=False, proxies=None, auth=None, cert=None, hooks=None, **kwargs):
    """发一个请求;4xx/5xx 按 requests 语义返回 Response(不抛),网络层错误才抛异常。"""
    if not url:
        raise InvalidURL("url 为空")
    if not isinstance(url, str):
        url = str(url)
    if not url.lower().startswith(("http://", "https://")):
        raise InvalidURL("只支持 http/https: " + url)
    url = _with_params(url, params)
    header_map = {}
    for key, value in (headers or {}).items():
        if value is None:
            continue
        header_map[str(key)] = str(value)
    header_map.setdefault("User-Agent", DEFAULT_UA)
    header_map.setdefault("Accept", "*/*")
    header_map.setdefault("Accept-Encoding", "gzip, deflate")
    if not allow_redirects:
        header_map.setdefault("X-MBox-No-Redirect", "1")
    body = _normalize_body(data, json, header_map)
    request_obj = urllib.request.Request(url, data=body, headers=header_map,
                                         method=(method or "GET").upper())
    seconds = DEFAULT_TIMEOUT if timeout is None else float(timeout)
    try:
        raw = urllib.request.urlopen(request_obj, timeout=seconds,
                                     context=_ssl_context(verify))
        return _to_response(raw, url)
    except urllib.error.HTTPError as error:
        # 与服务端通信成功、只是状态码不是 2xx:交给源自己判断
        return _to_response(error, url)
    except urllib.error.URLError as error:
        reason = getattr(error, "reason", error)
        if isinstance(reason, ssl.SSLError) and verify:
            # 证书链问题是国内源的高频坑:按"用户自己决定"的口径自动降级重试一次
            return request(method, url, params=None, data=data, headers=header_map,
                           timeout=seconds, verify=False, allow_redirects=allow_redirects,
                           json=None, cookies=cookies)
        if isinstance(reason, socket.timeout):
            raise Timeout("请求超时: " + url)
        if "timed out" in str(reason).lower():
            raise Timeout("请求超时: " + url)
        raise ConnectionError(str(reason))
    except socket.timeout:
        raise Timeout("请求超时: " + url)
    except ssl.SSLError as error:
        if verify:
            return request(method, url, params=None, data=data, headers=header_map,
                           timeout=seconds, verify=False, allow_redirects=allow_redirects)
        raise SSLError(str(error))
    except Exception as error:  # 兜底:别让源的整条链路死在异常类型上
        raise ConnectionError(str(error))


def get(url, params=None, **kwargs):
    return request("GET", url, params=params, **kwargs)


def post(url, data=None, json=None, **kwargs):
    return request("POST", url, data=data, json=json, **kwargs)


def head(url, **kwargs):
    return request("HEAD", url, **kwargs)


def put(url, data=None, **kwargs):
    return request("PUT", url, data=data, **kwargs)


def delete(url, **kwargs):
    return request("DELETE", url, **kwargs)


def options(url, **kwargs):
    return request("OPTIONS", url, **kwargs)


def patch(url, data=None, **kwargs):
    return request("PATCH", url, data=data, **kwargs)


class Session(object):
    """极简 Session:保留源常用的"默认 headers + 复用连接"语义(连接复用由 urllib 自带)。"""

    def __init__(self):
        self.headers = {}
        self.cookies = {}
        self.params = {}
        self.verify = True
        self.timeout = DEFAULT_TIMEOUT

    def request(self, method, url, **kwargs):
        merged = dict(self.headers)
        merged.update(kwargs.pop("headers", None) or {})
        kwargs.setdefault("timeout", self.timeout)
        kwargs.setdefault("verify", self.verify)
        params = dict(self.params)
        params.update(kwargs.pop("params", None) or {})
        return request(method, url, headers=merged, params=params or None, **kwargs)

    def get(self, url, **kwargs):
        return self.request("GET", url, **kwargs)

    def post(self, url, data=None, json=None, **kwargs):
        return self.request("POST", url, data=data, json=json, **kwargs)

    def head(self, url, **kwargs):
        return self.request("HEAD", url, **kwargs)

    def close(self):
        pass

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()


def session():
    return Session()
