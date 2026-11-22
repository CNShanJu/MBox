# -*- coding: utf-8 -*-
"""宿主(Java 侧 SpiderPY)与 py 源之间的调用桥。

Java 只通过这里的 4 个函数与 Python 打交道:
  * loads(text)   JSON 文本 → Python 对象(参数都按 JSON 传,绕开 Java/Python 类型转换差异);
  * dump(value)   返回值 → 文本(源返回 dict/list 就转 JSON,返回 str 就原样给);
  * call(...)     按方法签名自适应地调用源方法(源里 searchContent 有 2 参/3 参两种写法);
  * to_response() localProxy 的返回值 → [状态码, Content-Type, base64 内容]。
"""
import base64
import inspect
import json
import ast


def loads(text):
    """JSON 文本 → Python 对象;空串当 None。"""
    if text is None:
        return None
    if isinstance(text, (bytes, bytearray)):
        text = bytes(text).decode("utf-8", "ignore")
    text = text.strip()
    if not text:
        return None
    return json.loads(text)


def dump(value):
    """源方法的返回值 → 文本。

    源返回 str 时原样输出(很多源自己就是 json.dumps 出来的),
    返回 bytes 时按 UTF-8 解,其余走 JSON。
    """
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    if isinstance(value, (bytes, bytearray)):
        return bytes(value).decode("utf-8", "ignore")
    try:
        return json.dumps(value, ensure_ascii=False, default=str)
    except (TypeError, ValueError):
        return str(value)


def arity(function):
    """可位置传参的参数个数;签名拿不到或带 *args 时返回 -1(表示"有多少传多少")。"""
    try:
        parameters = list(inspect.signature(function).parameters.values())
    except (TypeError, ValueError):
        return -1
    positional = 0
    for parameter in parameters:
        if parameter.kind in (parameter.VAR_POSITIONAL, parameter.VAR_KEYWORD):
            return -1
        if parameter.kind in (parameter.POSITIONAL_ONLY, parameter.POSITIONAL_OR_KEYWORD):
            positional += 1
    return positional


def call(obj, name, args=None, kwargs=None):
    """调用 obj.name,按签名截断多余的位置参数;方法不存在时返回 None(不抛)。"""
    function = getattr(obj, name, None)
    if function is None or not callable(function):
        return None
    positional = list(args or [])
    keywords = dict(kwargs or {})
    limit = arity(function)
    if limit >= 0 and not keywords and len(positional) > limit:
        positional = positional[:limit]
    return function(*positional, **keywords)


def to_response(result):
    """localProxy 的返回值 → [状态码, Content-Type, base64(内容)]。

    支持:None(没接管)/ str / bytes / [code, content_type, body] / dict(code, content_type, content)。
    """
    code = 200
    content_type = "text/plain; charset=utf-8"
    body = b""
    if result is None:
        return []
    if isinstance(result, (bytes, bytearray)):
        body = bytes(result)
        content_type = "application/octet-stream"
    elif isinstance(result, str):
        body = result.encode("utf-8")
    elif isinstance(result, dict):
        code = int(result.get("code") or result.get("status") or 200)
        content_type = str(result.get("content_type") or result.get("type") or content_type)
        value = result.get("content")
        if value is None:
            value = result.get("body")
        body = _as_bytes(value)
    elif isinstance(result, (list, tuple)):
        if len(result) >= 3:
            code = int(result[0] or 200)
            content_type = str(result[1] or content_type)
            body = _as_bytes(result[2])
        elif len(result) == 2:
            content_type = str(result[0] or content_type)
            body = _as_bytes(result[1])
        elif len(result) == 1:
            body = _as_bytes(result[0])
        else:
            return []
    else:
        body = str(result).encode("utf-8")
    return [code, content_type, base64.b64encode(body).decode("ascii")]


def _as_bytes(value):
    if value is None:
        return b""
    if isinstance(value, (bytes, bytearray)):
        return bytes(value)
    if isinstance(value, str):
        return value.encode("utf-8")
    return str(value).encode("utf-8")


def normalize_player(result):
    """playerContent 返回值规整:``header`` 一律转成 JSON 字符串。

    应用侧是按字符串解析 header 的(``JSONObject(info.getString("header"))``),
    而 py 源两种写法都会出现:直接给 dict,或给 ``str(dict)`` 出来的单引号字典
    (``"{'User-Agent': ...}"`` 不是合法 JSON)。这里统一成一样。
    """
    if not isinstance(result, dict):
        return result
    header = result.get("header")
    if isinstance(header, str):
        text = header.strip()
        if not text.startswith("{"):
            return result
        try:
            header = ast.literal_eval(text)
        except (ValueError, SyntaxError):
            return result
    if isinstance(header, (dict, list)):
        try:
            result["header"] = json.dumps(header, ensure_ascii=False)
        except (TypeError, ValueError):
            result.pop("header", None)
    return result


def describe(obj):
    """调试用:列出实例上被源覆盖的方法名。"""
    names = []
    for name in ("init", "homeContent", "homeVideoContent", "categoryContent", "detailContent",
                 "searchContent", "playerContent", "liveContent", "localProxy", "action"):
        if callable(getattr(obj, name, None)):
            names.append(name)
    return ",".join(names)
