package com.github.tvbox.osc.share;

import androidx.annotation.NonNull;

/**
 * {@link ShareCallback} 的省事基类:只关心成功/失败时用它,进度自动忽略。
 *
 * <p>存在意义是少写样板 —— 大量调用点(设置页"导出并分享"按钮)根本不显示进度条,
 * 让它们都实现一个空 {@code onProgress} 只会让代码变吵。
 */
public abstract class SimpleShareCallback<T> implements ShareCallback<T> {

    @Override
    public void onProgress(@NonNull ShareProgress progress) {
        // 默认忽略
    }
}
