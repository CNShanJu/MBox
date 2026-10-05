package com.github.tvbox.osc.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.ui.startup.AppLaunchSource;

/**
 * Opens the current playback page directly while the app is alive. A notification can also outlive
 * the process that created it; in that case the splash assembles the configuration before the
 * playback page is opened again.
 */
public final class NotificationEntryActivity extends Activity {
    public static final String EXTRA_DETAIL_ID = "com.github.tvbox.osc.notification.DETAIL_ID";
    public static final String EXTRA_DETAIL_SOURCE_KEY = "com.github.tvbox.osc.notification.DETAIL_SOURCE_KEY";
    public static final String EXTRA_DETAIL_NAME = "com.github.tvbox.osc.notification.DETAIL_NAME";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent incoming = getIntent();
        String id = incoming == null ? null : incoming.getStringExtra(EXTRA_DETAIL_ID);
        String sourceKey = incoming == null ? null : incoming.getStringExtra(EXTRA_DETAIL_SOURCE_KEY);
        String name = incoming == null ? null : incoming.getStringExtra(EXTRA_DETAIL_NAME);
        try {
            if (App.getInstance().isNormalStart) {
                Intent detail = new Intent(this, DetailActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                if (!TextUtils.isEmpty(id) && !TextUtils.isEmpty(sourceKey)) {
                    detail.putExtra("id", id)
                            .putExtra("sourceKey", sourceKey)
                            .putExtra("vodName", name);
                }
                startActivity(detail);
            } else {
                Intent splash = new Intent(this, SplashActivity.class)
                        .putExtra(AppLaunchSource.EXTRA_NON_USER_ENTRY, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                if (!TextUtils.isEmpty(id) && !TextUtils.isEmpty(sourceKey)) {
                    splash.putExtra(EXTRA_DETAIL_ID, id)
                            .putExtra(EXTRA_DETAIL_SOURCE_KEY, sourceKey)
                            .putExtra(EXTRA_DETAIL_NAME, name);
                }
                startActivity(splash);
            }
        } catch (RuntimeException failure) {
            Log.w("MBox-Startup", "打开后台播放通知失败", failure);
        }
        finish();
        overridePendingTransition(0, 0);
    }
}
