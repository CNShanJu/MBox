package com.github.tvbox.osc.util;

import java.io.IOException;

/**
 * 网络请求字符串回调(轻量封装,替代 OkGo 的 AbsCallback)
 */
public interface HCallBack {
    void onSuccess(String content);

    void onError(Throwable e);
}
