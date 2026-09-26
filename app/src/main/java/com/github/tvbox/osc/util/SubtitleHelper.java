package com.github.tvbox.osc.util;

import android.app.Activity;

import com.github.tvbox.osc.player.api.PlayConfig;


public class SubtitleHelper {

    public static int getSubtitleTextAutoSize(Activity activity) {
        double screenSqrt = ScreenUtils.getSqrt(activity);
        int subtitleTextSize = 16;
        if (screenSqrt > 7.0 && screenSqrt <= 13.0) {
            subtitleTextSize = 24;
        } else if (screenSqrt > 13.0 && screenSqrt <= 50.0) {
            subtitleTextSize = 36;
        } else if (screenSqrt > 50.0) {
            subtitleTextSize = 46;
        }
        return subtitleTextSize;
    }

    public static int getTextSize(Activity activity) {
        int autoSize = getSubtitleTextAutoSize(activity);
        int subtitleConfigSize = PlayConfig.getSubtitleTextSize();
        if (subtitleConfigSize <= 0) subtitleConfigSize = autoSize; // 未设置(-1)回退自动
        return subtitleConfigSize;
    }

    public static void setTextSize(int size) {
        PlayConfig.setSubtitleTextSize(size);
    }

    public static int getTimeDelay() {
        return PlayConfig.getSubtitleTimeDelay();
    }

    public static void setTimeDelay(int delay) {
        PlayConfig.setSubtitleTimeDelay(delay);
    }

}
