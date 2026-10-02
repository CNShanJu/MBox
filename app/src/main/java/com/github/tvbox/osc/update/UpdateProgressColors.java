package com.github.tvbox.osc.update;

import android.content.Context;
import android.graphics.Color;

import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.bean.theme.ThemePalette;
import com.github.tvbox.osc.theme.ThemeColorAliases;
import com.github.tvbox.osc.theme.ThemeRuntime;

/** 更新气泡与管理面板共用的进度配色。 */
final class UpdateProgressColors {

    private UpdateProgressColors() {
    }

    static int themeColor(Context context, int colorRes) {
        ThemePalette palette = ThemeRuntime.runtimePalette();
        String key = ThemeColorAliases.paletteNameOf(colorRes);
        return palette != null && key != null
                ? palette.get(key) : ContextCompat.getColor(context, colorRes);
    }

    static int trackColor(Context context) {
        // 开关关闭色由 success 派生，不能用作下载进度的中性轨道。
        int textColor = themeColor(context, R.color.text_foreground);
        return Color.argb(Math.round(Color.alpha(textColor) * 0.20f),
                Color.red(textColor), Color.green(textColor), Color.blue(textColor));
    }
}
