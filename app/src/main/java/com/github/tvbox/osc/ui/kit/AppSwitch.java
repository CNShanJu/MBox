package com.github.tvbox.osc.ui.kit;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;

import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.R;
import com.google.android.material.switchmaterial.SwitchMaterial;

/**
 * 统一的开关组件(跟随主题,颜色全部来自 assets/theme/theme_colors*.json):
 * 开 = `switch_track_on`(蓝色 #1890FF,两主题一致),关 = `switch_track_off`
 * (该主题主色派生:亮色 #1F2937 / 暗色由主色压暗而来),圆点 = `switch_thumb`(固定白色)。
 * 开/关靠轨道颜色 + 圆点位置同时区分。
 */
public class AppSwitch extends SwitchMaterial {

    public AppSwitch(Context context) {
        this(context, null);
    }

    public AppSwitch(Context context, AttributeSet attrs) {
        super(context, attrs);
        initDefault();
    }

    private void initDefault() {
        int onColor = ContextCompat.getColor(getContext(), R.color.switch_track_on);
        int offColor = ContextCompat.getColor(getContext(), R.color.switch_track_off);
        setTrackTintList(new ColorStateList(
                new int[][]{
                        new int[]{android.R.attr.state_checked},
                        new int[]{}
                },
                new int[]{onColor, offColor}));
        setThumbTintList(ColorStateList.valueOf(ContextCompat.getColor(getContext(), R.color.switch_thumb)));
    }
}
