package com.github.tvbox.osc.util.holiday;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.github.tvbox.osc.calendar.HolidayCatalog;
import com.github.tvbox.osc.log.Category;
import com.github.tvbox.osc.log.LogStore;
import com.github.tvbox.osc.util.HeavyTaskUtil;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Bundled holiday rules are parsed once off the UI thread and shared by display and launch flows. */
public final class HolidayCatalogRepository {
    private static final String ASSET = "calendar/holidays.json";
    private static final int MAX_CHARS = 256 * 1024;
    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile HolidayCatalog cached;
    private static volatile boolean loadFinished;
    private final Context appContext;

    public HolidayCatalogRepository(Context context) {
        appContext = context.getApplicationContext();
    }

    /** Never reads assets; safe for UI rendering. Null also means the asset failed to load. */
    public HolidayCatalog getCached() {
        return cached;
    }

    public boolean isLoadFinished() {
        return loadFinished;
    }

    /** Callback runs on the main thread after the first load attempt, whether successful or not. */
    public void loadAsync(Runnable onComplete) {
        HeavyTaskUtil.executeBigTask(() -> {
            getOrLoad();
            if (onComplete != null) MAIN.post(onComplete);
        });
    }

    /** Background only. A malformed/missing asset fails closed: callers skip celebration. */
    public HolidayCatalog getOrLoad() {
        synchronized (LOCK) {
            if (loadFinished) return cached;
            try (InputStream input = appContext.getAssets().open(ASSET);
                 InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                StringBuilder json = new StringBuilder();
                char[] buffer = new char[4096];
                int length;
                while ((length = reader.read(buffer)) != -1) {
                    if (json.length() + length > MAX_CHARS) {
                        throw new IllegalArgumentException("holiday asset too large");
                    }
                    json.append(buffer, 0, length);
                }
                cached = HolidayCatalog.fromJson(json.toString());
                LogStore.log(Category.SYSTEM, "节日配置: 已加载内置规则 " + ASSET);
                int ignoredSolarTermFireworks = 0;
                for (HolidayCatalog.Entry holiday : cached.getHolidays()) {
                    if ("solar_term".equals(holiday.getDateRule())
                            && holiday.getFireworks().isEnabled()) ignoredSolarTermFireworks++;
                }
                if (ignoredSolarTermFireworks > 0) {
                    LogStore.log(Category.SYSTEM, "节日配置: " + ignoredSolarTermFireworks
                            + " 条节气烟花设置已按规则忽略");
                }
            } catch (Exception error) {
                cached = null;
                LogStore.fail(Category.SYSTEM, "节日配置: 加载失败，已停用节日烟花与节日名称，原因="
                        + error.getClass().getSimpleName());
            } finally {
                loadFinished = true;
            }
            return cached;
        }
    }
}
