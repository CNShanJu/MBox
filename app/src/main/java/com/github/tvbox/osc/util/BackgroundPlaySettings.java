package com.github.tvbox.osc.util;

import android.app.Activity;
import android.os.Build;

import com.github.tvbox.osc.player.api.PlayConfig;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.Permission;
import com.hjq.permissions.XXPermissions;

import java.util.Arrays;
import java.util.List;

/** 播放页与“我的－设置”共用的后台播放选项和通知权限处理。 */
public final class BackgroundPlaySettings {

    public static final List<String> MODES = Arrays.asList("关闭", "开启", "画中画");

    private BackgroundPlaySettings() { }

    public static int currentMode() {
        return PlayConfig.getBackgroundPlayType();
    }

    public static String currentLabel() {
        return MODES.get(currentMode());
    }

    public static void select(Activity activity, int mode) {
        PlayConfig.setBackgroundPlayType(mode);
        if (mode != 1 || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || XXPermissions.isGranted(activity, Permission.NOTIFICATION_SERVICE)) {
            return;
        }
        XXPermissions.with(activity)
                .permission(Permission.NOTIFICATION_SERVICE)
                .request(new OnPermissionCallback() {
                    @Override
                    public void onGranted(List<String> permissions, boolean allGranted) {
                        AppBubble.toast("后台播放通知已开启");
                    }

                    @Override
                    public void onDenied(List<String> permissions, boolean never) {
                        AppBubble.toast("通知权限未开启，后台控制不可用");
                    }
                });
    }
}
