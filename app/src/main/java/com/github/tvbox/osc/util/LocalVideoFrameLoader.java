package com.github.tvbox.osc.util;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import com.github.tvbox.osc.util.HeavyTaskUtil;


/**
 * 本地视频封面取帧复用工具:后台线程用 MediaMetadataRetriever 取第 1 秒关键帧,
 * 完成后回到主线程;holder 复用/滚动后以 tag 比对避免串图。
 * (替代原各处各自实现的 Glide/取帧逻辑,统一到同一套实现)
 */
public class LocalVideoFrameLoader {


    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 行内防串图 tag key */
    private static final int TAG_KEY_PATH = 0x6d001101;

    private LocalVideoFrameLoader() {
    }

    /** 异步给 ImageView 填充本地视频首帧;加载失败保持 ImageView 原样(由调用方先设占位图) */
    public static void load(final ImageView iv, final String path) {
        if (iv == null || path == null || path.isEmpty()) return;
        iv.setTag(TAG_KEY_PATH, path);
        // 取帧走模块级共享执行器(AGENTS §六.6:不再自建线程池;原为单线程池 "tvbox-local-frame")
        HeavyTaskUtil.getImageExecutorService().execute(() -> {
            Bitmap bmp = null;
            MediaMetadataRetriever mmr = null;
            try {
                mmr = new MediaMetadataRetriever();
                mmr.setDataSource(path);
                bmp = mmr.getFrameAtTime(1000 * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            } catch (Throwable th) {
                bmp = null;
            } finally {
                if (mmr != null) {
                    try {
                        mmr.release();
                    } catch (Throwable ignored) {
                    }
                }
            }
            final Bitmap fb = bmp;
            MAIN.post(() -> {
                if (!path.equals(iv.getTag(TAG_KEY_PATH))) return; // 条目已滚走/复用
                if (fb != null) {
                    iv.setImageBitmap(fb);
                }
            });
        });
    }
}
