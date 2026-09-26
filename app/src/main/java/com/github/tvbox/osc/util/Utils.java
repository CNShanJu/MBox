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
        if (cursor != null && cursor.moveToFirst()) {
            do {
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
            cursor.close();
        }
        return videoList;
    }

    public static boolean isDarkTheme(){
        int currentNightMode = App.getInstance().getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return currentNightMode == Configuration.UI_MODE_NIGHT_YES || AppCompatDelegate.getDefaultNightMode()==AppCompatDelegate.MODE_NIGHT_YES;
    }

    /**
     * 是否深色主题(直接读 app 主题设置,不依赖 AppCompatDelegate/系统 uiMode):
     * THEME_TAG: 0=跟随系统, 1=浅色, 2=深色。
     * 用于气泡等自绘控件取色,避免部分 ROM 上 AppCompatDelegate 夜间模式与系统 uiMode 不同步。
     */
    public static boolean isAppDarkTheme(){
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

    public static void initTheme(){
        switch (SystemConfig.getTheme()) {
            case 0:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
            case 1:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case 2:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
        }
    }
}