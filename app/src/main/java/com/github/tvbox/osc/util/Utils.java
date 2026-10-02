package com.github.tvbox.osc.util;
import com.github.tvbox.osc.config.SystemConfig;

import android.content.res.Configuration;
import android.database.Cursor;
import android.os.Build;
import android.provider.MediaStore;

import androidx.appcompat.app.AppCompatDelegate;

import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.VideoInfo;
import com.github.tvbox.osc.bean.VodInfo;

import java.util.ArrayList;
import java.util.Formatter;
import java.util.List;
import java.util.Locale;


public class Utils {

    public static boolean supportsPiPMode() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    /**
     * 集数网格自适应列数:基于文字平均长度,最多 3 列(1列/2列/3列)。
     * 平均长度 >= 12 → 1 列;>= 8 → 2 列;否则 3 列。
     */
    public static int getSeriesSpanCount(List<VodInfo.VodSeries> list) {
        int spanCount = 3;
        if (list == null || list.isEmpty()) {
            return spanCount;
        }
        int total = 0;
        for (VodInfo.VodSeries item : list) total += item.name.length();
        int offset = (int) Math.ceil((double) total / list.size());
        if (offset >= 12) spanCount = 1;
        else if (offset >= 8) spanCount = 2;
        return spanCount;
    }

    /**
     * 网格单卡最大宽度(dp):单卡超过该宽度时自动增加列数,避免卡片被拉得过宽。
     * 按需在各处调用 getAdaptiveGridSpan(maxCardWidthDp) 时可直接使用此常量。
     */
    public static final float GRID_CARD_MAX_WIDTH_DP = 190f;

    /**
     * 自适应网格列数:按屏幕宽度计算,保证单卡宽度不超过 maxCardWidthDp(屏幕越宽列数越多),
     * 不强制默认列数(如旧的"最少 3 列"逻辑,窄屏下会让单卡超出最大宽度)。
     * 可另设最小/最大列数兜底,适用于首页剧集网格、下载聚合、收藏/历史等所有需要
     * "宽度自适应屏幕"的网格场景,屏幕旋转/尺寸变化时重新调用即可得到新列数。
     *
     * @param maxCardWidthDp 单卡最大宽度(dp),必须 > 0
     * @param minSpan        最小列数(窄屏兜底,<=0 表示不限制,由宽度计算得出)
     * @param maxSpan        最大列数(超宽屏兜底,<=0 表示不限制)
     * @return 自适应列数,至少 1 列
     */
    public static int getAdaptiveGridSpan(float maxCardWidthDp, int minSpan, int maxSpan) {
        try {
            int widthDp = App.getInstance().getResources().getConfiguration().screenWidthDp;
            int span = (int) Math.ceil(widthDp / maxCardWidthDp);
            if (minSpan > 0) {
                span = Math.max(span, minSpan);
            }
            if (maxSpan > 0) {
                span = Math.min(span, maxSpan);
            }
            return Math.max(1, span);
        } catch (Throwable th) {
            return 1;
        }
    }

    /**
     * 便捷重载:仅按单卡最大宽度自适应,不限制最小/最大列数。
     * 例:getAdaptiveGridSpan(Utils.GRID_CARD_MAX_WIDTH_DP)
     */
    public static int getAdaptiveGridSpan(float maxCardWidthDp) {
        return getAdaptiveGridSpan(maxCardWidthDp, 0, 0);
    }

    public static String stringForTime(long timeMs) {
//        if (timeMs <= 0 || timeMs >= 24 * 60 * 60 * 1000) {
//            return "00:00";
//        }
        long totalSeconds = timeMs / 1000;
        long seconds = totalSeconds % 60;
        long minutes = (totalSeconds / 60) % 60;
        long hours = totalSeconds / 3600;
        StringBuilder stringBuilder = new StringBuilder();
        Formatter mFormatter = new Formatter(stringBuilder, Locale.getDefault());
        if (hours > 0) {
            return mFormatter.format("%d:%02d:%02d", hours, minutes, seconds).toString();
        } else {
            return mFormatter.format("%02d:%02d", minutes, seconds).toString();
        }
    }

    public static List<VideoInfo> getVideoList() {
        List<VideoInfo> videoList = new ArrayList<>();
        Cursor cursor = App.getInstance().getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                new String[] { // 查询内容
                        MediaStore.Video.Media._ID, // 视频id
                        MediaStore.Video.Media.DATA, // 视频路径
                        MediaStore.Video.Media.SIZE, // 视频字节大小
                        MediaStore.Video.Media.DISPLAY_NAME, // 视频名称 xxx.mp4
                        MediaStore.Video.Media.TITLE, // 视频标题
                        MediaStore.Video.Media.DURATION, // 视频时长
                        MediaStore.Video.Media.RESOLUTION, // 视频分辨率 X x Y格式
                        MediaStore.Video.Media.IS_PRIVATE,
                        MediaStore.Video.Media.BUCKET_ID,
                        MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                        MediaStore.Video.Media.BOOKMARK // 上次视频播放的位置
                },
                null,
                null,
                null
        );
        if (cursor != null) {
            try {
                if (cursor.moveToFirst()) do {
                VideoInfo videoInfo = new VideoInfo();
                videoInfo.setId(cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)));
                String dataPath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA));
                videoInfo.setPath(dataPath);
                long size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE));
                // MediaStore 对刚下载/刚写入的文件 SIZE 可能为 0(索引未更新),用 File.length() 兜底
                if (size <= 0 && dataPath != null) {
                    try {
                        java.io.File f = new java.io.File(dataPath);
                        if (f.exists()) size = f.length();
                    } catch (Throwable ignored) {
                    }
                }
                videoInfo.setSize(size);
                videoInfo.setDisplayName(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)));
                videoInfo.setTitle(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)));
                videoInfo.setDuration(cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)));
                videoInfo.setResolution(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RESOLUTION)));
                videoInfo.setIsPrivate(cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.IS_PRIVATE)));
                videoInfo.setBucketId(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)));
                videoInfo.setBucketDisplayName(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)));
                videoInfo.setBookmark(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BOOKMARK)));
                videoList.add(videoInfo);
                } while (cursor.moveToNext());
            } finally {
                cursor.close();
            }
        }
        return videoList;
    }

    /**
     * 界面是否按深色那套画(状态栏图标、弹窗/气泡深浅等)。
     * <p>
     * <b>以主题设置为准</b>:口径统一到主题门面 {@link com.github.tvbox.osc.storage.theme.ThemeStore#activeType()}
     * (选了自定义主题 = 它的类型;显式浅色/深色 = 设置的那套;只有「跟随系统」才看系统明暗)。
     * 旧口径读的是 Application 的 {@code uiMode},**显式选了浅色而手机是深色时**会判成深色 ——
     * 白底页面配深色弹窗/浅色状态栏图标,整片看不清({@link #isAppDarkTheme()} 已在用新口径,这里跟上,免得两个口径打架)。
     */
    public static boolean isDarkTheme(){
        try {
            if (com.github.tvbox.osc.storage.theme.ThemeStore.isReady()) {
                return com.github.tvbox.osc.storage.theme.ThemeStore.activeType().isDark();
            }
        } catch (Throwable ignored) {
        }
        // 主题门面还没装配(极早期调用):退回旧口径
        int currentNightMode = App.getInstance().getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return currentNightMode == Configuration.UI_MODE_NIGHT_YES || AppCompatDelegate.getDefaultNightMode()==AppCompatDelegate.MODE_NIGHT_YES;
    }

    /**
     * 是否深色主题(直接读 app 主题设置,不依赖 AppCompatDelegate/系统 uiMode)。
     * <p>口径来自主题门面 {@link com.github.tvbox.osc.storage.theme.ThemeStore}:选了自定义主题就是它的
     * type;否则按"跟随系统/浅色/深色"解析出<b>该类型的默认主题</b>。
     * 用于气泡等自绘控件取色,避免部分 ROM 上 AppCompatDelegate 夜间模式与系统 uiMode 不同步。
     */
    public static boolean isAppDarkTheme(){
        try {
            if (com.github.tvbox.osc.storage.theme.ThemeStore.isReady()) {
                return com.github.tvbox.osc.storage.theme.ThemeStore.activeType().isDark();
            }
        } catch (Throwable ignored) {
        }
        // 主题门面还没装配(极早期调用):退回旧口径
        try {
            int tag = SystemConfig.getTheme();
            if (tag == 2) return true;
            if (tag == 1) return false;
            // 跟随系统
            int night = App.getInstance().getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return night == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable th) {
            return isDarkTheme();
        }
    }

    /**
     * 按主题设置决定 AppCompat 夜间模式(启动时调一次;每次"主题生效"也调一次)。
     *
     * <p>顺带重解析运行时换肤快照({@code ThemeRuntime.refresh()}):主题改完走的是"带标志重载主页",
     * 进程并没有重启,不换快照界面会继续按启动那一刻的调色板画。
     *
     * <p>与"主题颜色"功能的关系:
     * <ul>
     *   <li><b>选了自定义主题</b> → 强制成它的类型。自定义主题的配色是固定的,夜间模式必须跟着它,
     *       否则弹窗/气泡/状态栏会按系统明暗取反(浅色主题 + 深色系统 = 白底黑字的页面配深色气泡);</li>
     *   <li><b>跟随系统 / 浅色 / 深色</b> → 跟随系统只读取手机的亮暗状态，再明确启用
     *       应用自己的亮/暗资源；{@code ThemeStore} 选该类型的默认主题。</li>
     * </ul>
     */
    public static void initTheme(){
        // "生效"这一步必须同时刷新换肤快照:切主题走的是带标志重载主页,进程并没有重启,
        // 不换快照的话界面仍按进程启动那一刻的调色板画(见 ThemeRuntime.refresh 的说明)
        com.github.tvbox.osc.theme.ThemeRuntime.refresh();
        int mode;
        try {
            if (com.github.tvbox.osc.storage.theme.ThemeStore.isReady()) {
                com.github.tvbox.osc.storage.theme.ThemeStore.Selection s =
                        com.github.tvbox.osc.storage.theme.ThemeStore.selection();
                if (!s.customId.isEmpty()) {
                    AppCompatDelegate.setDefaultNightMode(
                            com.github.tvbox.osc.storage.theme.ThemeStore.activeType().isDark()
                                    ? AppCompatDelegate.MODE_NIGHT_YES
                                    : AppCompatDelegate.MODE_NIGHT_NO);
                    return;
                }
                mode = s.mode;
            } else {
                mode = SystemConfig.getTheme();
            }
        } catch (Throwable th) {
            mode = SystemConfig.getTheme();
        }
        switch (mode) {
            case 0:
                // 跟随系统只决定亮暗类型；具体资源仍锁定到应用自己的亮/暗主题。
                AppCompatDelegate.setDefaultNightMode(
                        com.github.tvbox.osc.storage.theme.ThemeStore.activeType().isDark()
                                ? AppCompatDelegate.MODE_NIGHT_YES
                                : AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case 1:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case 2:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            default:
                AppCompatDelegate.setDefaultNightMode(
                        com.github.tvbox.osc.storage.theme.ThemeStore.activeType().isDark()
                                ? AppCompatDelegate.MODE_NIGHT_YES
                                : AppCompatDelegate.MODE_NIGHT_NO);
                break;
        }
        logNightModeState("主题生效");
    }

    /**
     * 排障用一行:"明暗到底谁说了算"。
     *
     * <p>四项一起写:①系统设置里的夜间模式 + 应用自己声明的夜间模式(Android 12+);
     * ②<b>应用级</b> {@code Configuration} 的明暗位(系统/OEM 有没有把进程翻成夜间 —— 魅族 Flyme 会翻);
     * ③我们生效的主题类型;④供色通道是不是运行时调色板(是的话②翻了界面颜色也不会跟着翻)。
     *
     * <p>用途:遇到"被系统深色模式强制覆盖"的机型先看这一行 ——
     * ②是夜间而③是亮色、④为 true,就是"系统翻了、我们没跟",属于预期(界面由我们自己的主题说了算);
     * 若④为 false,说明快照没装配好,那条通道仍会按 {@code -night} 资源画(该修的是装配时机)。
     */
    public static void logNightModeState(String when) {
        try {
            int appUiMode = App.getInstance().getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            String setting = "-";
            try {
                android.app.UiModeManager um = (android.app.UiModeManager)
                        App.getInstance().getSystemService(android.content.Context.UI_MODE_SERVICE);
                // getNightMode() = 系统设置里选的夜间模式偏好(0=自动,1=关,2=开,3=自定义时间)
                if (um != null) setting = String.valueOf(um.getNightMode());
            } catch (Throwable ignored) {
            }
            com.github.tvbox.osc.log.LogStore.log(com.github.tvbox.osc.log.Category.SYSTEM,
                    "明暗(" + when + "): 系统配置=" + (appUiMode == Configuration.UI_MODE_NIGHT_YES ? "夜间" : "白天")
                            + ", 系统偏好=" + setting
                            + ", 生效主题=" + com.github.tvbox.osc.theme.ThemeRuntime.type()
                            + ", 运行时供色=" + (com.github.tvbox.osc.theme.ThemeRuntime.runtimePalette() != null)
                            + ", 自定义=" + com.github.tvbox.osc.theme.ThemeRuntime.active()
                            + ", 跟随系统=" + com.github.tvbox.osc.theme.ThemeRuntime.followsSystem());
        } catch (Throwable ignored) {
        }
    }
}
