package com.github.tvbox.osc.util;
import com.github.tvbox.osc.config.SystemConfig;

import android.content.Context;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.airbnb.lottie.LottieAnimationView;
import com.airbnb.lottie.LottieDrawable;
import com.airbnb.lottie.LottieProperty;
import com.airbnb.lottie.SimpleColorFilter;
import com.airbnb.lottie.model.KeyPath;
import com.airbnb.lottie.value.LottieValueCallback;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.theme.ThemePalette;
import com.github.tvbox.osc.theme.ThemeRuntime;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 加载动画统一配置:通过设置页"加载动画"选项切换全局加载动画。
 * <p>
 * 目录结构(每个动画一个文件夹,统一配置文件 config.json):
 * <pre>
 * assets/loading/
 *   anim_loading/           旧默认动画
 *     anim_loading.json     Lottie 动画文件
 *     config.json           { "mbox_tipsname": "默认", "size_other": 30, "size_refresh": 36, "speed": 1.0 }
 *   glowing_fish_loader/    Glowing Fish(当前默认)
 *     glowing_fish_loader.json
 *     config.json           { "mbox_tipsname": "鱼", "size_other": 100, "msg_gap": -12, "speed": 1.0 }
 * </pre>
 * 展示名(mbox_tipsname)、页面显示尺寸(size_*,dp)、状态文字间距(msg_gap,dp)、播放速度(speed 倍率)、
 * 旋转角度(rotation)、左右镜像(flip_horizontal)和着色模式(color_mode: original/theme_text)
 * 统一从 config.json 读取,
 * 不再读 lottie 文件。选择值(HawkConfig.LOADING_ANIM)存动画文件夹名;旧版存的文件名/数字自动兼容。
 */
public class LoadingAnim {

    /** 可选动画根目录(assets 下) */
    public static final String DIR_NAME = "loading";
    /** 统一配置文件名称 */
    public static final String CONFIG_FILE = "config.json";
    /** 默认动画文件夹名(loading 下):鱼 */
    public static final String DEFAULT_NAME = "glowing_fish_loader";
    /** 配置键:视频播放里的尺寸(dp) */
    private static final String KEY_PLAYER = "size_player";
    /** 配置键:其他地方的尺寸(dp) */
    private static final String KEY_OTHER = "size_other";
    /** 配置键:下拉刷新指示的尺寸(dp)(单独可调,避免默认偏大/鱼偏小) */
    private static final String KEY_REFRESH = "size_refresh";
    /** 配置键:加载动画与其下方状态文字的间距(dp),可为负值(负值=把文字提进动画盒子底部的固有留白) */
    private static final String KEY_MSG_GAP = "msg_gap";
    /** 配置键:动画播放速度倍率(1=原速,0.5=半速,2=双倍速) */
    private static final String KEY_SPEED = "speed";
    /** 配置键:绕动画视图中心旋转的角度;180=上下倒转 */
    private static final String KEY_ROTATION = "rotation";
    /** 配置键:true=左右镜像,改变角色朝向 */
    private static final String KEY_FLIP_HORIZONTAL = "flip_horizontal";
    /** 配置键:original=素材原色,theme_text=主题主文字色 */
    private static final String KEY_COLOR_MODE = "color_mode";
    private static final KeyPath ALL_CONTENT = new KeyPath("**");

    /** 兼容旧版:Glowing Fish 的旧选择值 1 映射到文件夹名 */
    private static final String LEGACY_GLOWING_FISH_NAME = "glowing_fish_loader";

    /** 默认动画显示尺寸(dp),配置文件缺失/异常时兜底 */
    private static final int DEFAULT_SIZE_DP = 72;
    /** 下拉刷新指示的兜底尺寸(dp) */
    private static final int DEFAULT_REFRESH_SIZE_DP = 40;
    /** 状态文字间距的兜底值(dp);不配 msg_gap 的动画用这个安全值(正数=动画下方自然留一点缝) */
    private static final int DEFAULT_MSG_GAP_DP = 2;
    /** 旧 config.json 没有 speed 时保持原速 */
    private static final float DEFAULT_SPEED = 1f;

    /** 配置读取缓存:文件夹名 -> 配置 JSON */
    private static final Map<String, JSONObject> configCache = new HashMap<>();

    /**
     * 当前配置的动画文件夹名(如 anim_loading / glowing_fish_loader)。
     * 兼容旧值:文件名(glowing_fish_loader.json)、带 loading/ 前缀、旧数字(0/1)。
     */
    public static String getAnimName() {
        try {
            Object sel = SystemConfig.getLoadingAnimRaw();
            if (sel instanceof String) {
                String name = (String) sel;
                if (name != null && !name.isEmpty()) {
                    String bare = name.contains("/") ? name.substring(name.lastIndexOf('/') + 1) : name;
                    if (bare.endsWith(".json")) bare = bare.substring(0, bare.length() - 5);
                    if (exists(bare)) return bare;
                    return DEFAULT_NAME;
                }
                return DEFAULT_NAME;
            }
            if (sel instanceof Number) {
                int v = ((Number) sel).intValue();
                if (v == 1 && exists(LEGACY_GLOWING_FISH_NAME)) return LEGACY_GLOWING_FISH_NAME;
            }
        } catch (Throwable ignored) {
        }
        return DEFAULT_NAME;
    }

    /** 默认动画的 lottie 文件路径 */
    public static String getDefaultFileName() {
        return DIR_NAME + "/" + DEFAULT_NAME + "/" + DEFAULT_NAME + ".json";
    }

    /** 当前配置动画的 lottie 文件路径 */
    public static String getAnimFileName() {
        String name = getAnimName();
        return DIR_NAME + "/" + name + "/" + name + ".json";
    }

    /** 当前配置动画在视频播放里的显示尺寸(dp),来自 config.json 的 size_player */
    public static int getPlayerSizeDp() {
        return getSizeDp(getAnimName(), KEY_PLAYER);
    }

    /** 当前配置动画在其他地方的显示尺寸(dp),来自 config.json 的 size_other */
    public static int getOtherSizeDp() {
        return getSizeDp(getAnimName(), KEY_OTHER);
    }

    /** 当前配置动画在"下拉刷新指示"里的显示尺寸(dp),来自 config.json 的 size_refresh;缺失回退 40 */
    public static int getRefreshSizeDp() {
        return getSizeDp(getAnimName(), KEY_REFRESH, DEFAULT_REFRESH_SIZE_DP);
    }

    /**
     * 加载动画与其下方状态文字之间的间距(dp),来自当前动画 config.json 的 msg_gap。
     * <p>
     * 允许负值:多数 Lottie 图形的可见内容只占画布中上部(如"鱼"在 100dp 盒子里底部本就空着约 20dp),
     * 正间距会让"动画—文字"之间显得离得很远,此时配负值把文字提进这段固有留白即可贴紧;
     * 取值按"图形可见区底边(离线量出)再留 ≥5dp 余量"给,避免文字压到动画。
     * 未配置/读取失败时回退 {@link #DEFAULT_MSG_GAP_DP}。
     */
    public static int getMsgGapDp() {
        JSONObject cfg = readConfig(getAnimName());
        if (cfg != null && cfg.has(KEY_MSG_GAP)) {
            return cfg.optInt(KEY_MSG_GAP, DEFAULT_MSG_GAP_DP);
        }
        return DEFAULT_MSG_GAP_DP;
    }

    /** 当前动画的播放速度倍率,由 config.json 的 speed 控制 */
    public static float getPlaybackSpeed() {
        return getPlaybackSpeed(getAnimName());
    }

    /** 指定动画的播放速度倍率(设置页长按预览也使用对应选项的配置) */
    public static float getPlaybackSpeed(String animName) {
        JSONObject cfg = readConfig(animName);
        double speed = cfg != null ? cfg.optDouble(KEY_SPEED, DEFAULT_SPEED) : DEFAULT_SPEED;
        float playbackSpeed = (float) speed;
        return playbackSpeed > 0 && !Float.isInfinite(playbackSpeed)
                ? playbackSpeed : DEFAULT_SPEED;
    }

    /**
     * 应用指定动画的旋转、镜像与颜色,不修改素材或播放进度。
     * theme_text 将整个动画统一着色;压在视频画面上的动画使用固定白色。
     * 页面、刷新指示与设置预览共用此入口,应在 setAnimation 后调用。
     */
    public static void applyAppearance(LottieAnimationView view, String animName, boolean playerOverlay) {
        JSONObject cfg = readConfig(animName);
        double rotation = cfg != null ? cfg.optDouble(KEY_ROTATION, 0) : 0;
        view.setRotation(Double.isNaN(rotation) || Double.isInfinite(rotation)
                ? 0f : (float) (rotation % 360));
        boolean flipHorizontal = cfg != null && cfg.optBoolean(KEY_FLIP_HORIZONTAL, false);
        float scaleX = Math.abs(view.getScaleX());
        view.setScaleX(flipHorizontal ? -scaleX : scaleX);

        // 保留非空回调并返回 null,让复用的 Paint 显式清除上一次的滤镜。
        LottieValueCallback<ColorFilter> colorCallback = new LottieValueCallback<>((ColorFilter) null);
        LottieValueCallback<Integer> textColorCallback = null;
        if (cfg != null && "theme_text".equals(cfg.optString(KEY_COLOR_MODE, "original"))) {
            int color = Color.WHITE;
            if (!playerOverlay) {
                ThemePalette palette = ThemeRuntime.runtimePalette();
                color = palette != null ? palette.get("text_main")
                        : ContextCompat.getColor(view.getContext(), R.color.text_foreground);
            }
            colorCallback = new LottieValueCallback<>(new SimpleColorFilter(color));
            textColorCallback = new LottieValueCallback<>(color);
        }
        // Lottie 会在异步 composition 就绪后应用尚未执行的回调。
        view.addValueCallback(ALL_CONTENT, LottieProperty.COLOR_FILTER, colorCallback);
        // 文本图层不处理 COLOR_FILTER,另接填色与描边;null 恢复素材原本的颜色动画。
        view.addValueCallback(ALL_CONTENT, LottieProperty.COLOR, textColorCallback);
        view.addValueCallback(ALL_CONTENT, LottieProperty.STROKE_COLOR, textColorCallback);
    }

    /** 可用加载动画列表:loading/ 下的子目录(每个目录 = 一个动画),按目录名排序 */
    public static List<String> getAvailableAnimFiles() {
        List<String> list = new ArrayList<>();
        try {
            Context ctx = App.getInstance();
            if (ctx == null || ctx.getAssets() == null) return list;
            String[] dirs = ctx.getAssets().list(DIR_NAME);
            if (dirs != null) {
                for (String d : dirs) {
                    if (d != null && !d.startsWith(".") && !d.contains(".") && exists(d)) {
                        list.add(d);
                    }
                }
            }
        } catch (IOException ignored) {
        }
        Collections.sort(list);
        return list;
    }

    /**
     * 展示名:优先读取该动画 config.json 的 mbox_tipsname(如 "鱼"),
     * 缺失/读取失败时兜底用文件夹名。
     */
    public static String displayName(String animName) {
        if (animName == null) return "";
        String bare = animName.contains("/") ? animName.substring(animName.lastIndexOf('/') + 1) : animName;
        JSONObject cfg = readConfig(bare);
        if (cfg != null) {
            String tips = cfg.optString("mbox_tipsname", "");
            if (!tips.isEmpty()) return tips;
        }
        return bare;
    }

    /** 动画显示尺寸(dp):config.json 的对应键,缺失/异常返回默认 72 */
    private static int getSizeDp(String animName, String key) {
        return getSizeDp(animName, key, DEFAULT_SIZE_DP);
    }

    /** 动画显示尺寸(dp):config.json 的对应键,缺失/异常返回传入的兜底值 */
    private static int getSizeDp(String animName, String key, int fallback) {
        JSONObject cfg = readConfig(animName);
        int size = cfg != null ? cfg.optInt(key, fallback) : fallback;
        return size > 0 ? size : fallback;
    }

    /** 读取动画文件夹的 config.json(带缓存) */
    private static JSONObject readConfig(String animName) {
        synchronized (configCache) {
            JSONObject cached = configCache.get(animName);
            if (cached != null) return cached;
        }
        JSONObject cfg = null;
        try {
            String assetPath = DIR_NAME + "/" + animName + "/" + CONFIG_FILE;
            try (InputStream is = App.getInstance().getAssets().open(assetPath)) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                cfg = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
            }
        } catch (Throwable ignored) {
        }
        synchronized (configCache) {
            configCache.put(animName, cfg);
        }
        return cfg;
    }

    /** 动画目录是否存在 */
    private static boolean exists(String animName) {
        if (animName == null || animName.isEmpty()) return false;
        try {
            String[] list = App.getInstance().getAssets().list(DIR_NAME + "/" + animName);
            return list != null && list.length > 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** 把全局加载动画应用到指定 Lottie 视图(动态切换动画文件) */
    public static void apply(View view) {
        if (view instanceof LottieAnimationView) {
            LottieAnimationView lav = (LottieAnimationView) view;
            try {
                String animName = getAnimName();
                // 尺寸按配置区分:视频播放里(tag=vod_control_loading)用 size_player,其他地方用 size_other;
                // 先设动画再改尺寸,且尺寸未变化不触发重排,避免初始化/加载期间被干扰
                boolean player = "vod_control_loading".equals(view.getTag());
                int size = getSizeDp(animName, player ? KEY_PLAYER : KEY_OTHER);
                lav.setAnimation(DIR_NAME + "/" + animName + "/" + animName + ".json");
                lav.setRepeatMode(LottieDrawable.RESTART); // 从头循环,不是往返播放(reverse)
                lav.setRepeatCount(LottieDrawable.INFINITE);
                lav.setSpeed(getPlaybackSpeed(animName)); // 代码设置动画时 XML 的 lottie_speed 不生效
                // 动画含高斯模糊等超出画布内容时关闭按画布裁剪,避免光晕被边界切掉
                lav.setClipToCompositionBounds(false);
                applyAppearance(lav, animName, player);
                android.view.ViewGroup.LayoutParams lp = lav.getLayoutParams();
                if (lp != null) {
                    int px = Math.round(size * view.getResources().getDisplayMetrics().density);
                    if (lp.width != px || lp.height != px) {
                        lp.width = px;
                        lp.height = px;
                        lav.setLayoutParams(lp);
                    }
                }
                lav.playAnimation();
            } catch (Throwable th) {
                // 动画文件异常时静默回退,不阻塞加载页展示
                th.printStackTrace();
            }
        }
    }
}
