package com.github.tvbox.osc.util;

import java.io.File;

/**
 * 网络请求文件下载回调(轻量封装,替代 OkGo 的 AbsCallback)
 */
public interface FCallBack {
    void onSuccess(File file);

    void onError(Throwable e);
}
