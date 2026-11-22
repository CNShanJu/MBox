# -*- coding: utf-8 -*-
"""requests 兼容层的异常类型(与真实 requests.exceptions 同名同层级)。"""


class RequestException(IOError):
    """所有请求异常的基类;带 response 时表示"拿到了响应但被判为失败"。"""

    def __init__(self, *args, **kwargs):
        self.response = kwargs.pop('response', None)
        self.request = kwargs.pop('request', None)
        super(RequestException, self).__init__(*args)


class InvalidURL(RequestException):
    pass


class ConnectionError(RequestException):
    pass


class SSLError(ConnectionError):
    pass


class Timeout(RequestException):
    pass


class ConnectTimeout(Timeout):
    pass


class ReadTimeout(Timeout):
    pass


class HTTPError(RequestException):
    pass


class TooManyRedirects(RequestException):
    pass


class ContentDecodingError(RequestException):
    pass
