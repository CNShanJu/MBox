package com.github.tvbox.osc.bean.theme;

import java.util.Map;

/**
 * @deprecated 兼容旧调用名;新代码使用 {@link ThemeColorPalette}。
 */
@Deprecated
public final class ThemePalette extends ThemeColorPalette {

    public ThemePalette(Map<String, Integer> values) {
        super(values);
    }
}
