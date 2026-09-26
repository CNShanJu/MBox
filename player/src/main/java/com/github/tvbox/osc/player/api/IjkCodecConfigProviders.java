package com.github.tvbox.osc.player.api;

import com.github.tvbox.osc.bean.IJKCode;

import java.util.Collections;
import java.util.List;

/**
 * IJK 解码配置服务持有者（App 组合根以适配器注入现有实现；默认安全降级：
 * 空解码组 / null 当前组 / null 指定组，播放内核与设置页据此走"无解码配置"分支）。
 */
public final class IjkCodecConfigProviders {

    private static volatile IjkCodecConfigApi impl = new IjkCodecConfigApi() {
        @Override
        public List<IJKCode> getIjkCodes() {
            return Collections.emptyList();
        }

        @Override
        public IJKCode getCurrentIJKCode() {
            return null;
        }

        @Override
        public IJKCode getIJKCodec(String name) {
            return null;
        }
    };

    private IjkCodecConfigProviders() {
    }

    public static void set(IjkCodecConfigApi api) {
        if (api != null) {
            impl = api;
        }
    }

    public static IjkCodecConfigApi get() {
        return impl;
    }
}
