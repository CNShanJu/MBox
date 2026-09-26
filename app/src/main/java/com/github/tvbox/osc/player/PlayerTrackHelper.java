package com.github.tvbox.osc.player;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import xyz.doikki.videoplayer.player.AbstractPlayer;

/**
 * 播放器内核能力收敛层（⑥ 适配层第一步）：
 * 调用方（PlayFragment 等）不再直接 `instanceof` 强转内核类型，
 * track/字幕相关操作统一走本类——各内核实现 {@link KernelTrackSupport} 承载差异，
 * 后续 Media3 升级 = 新增实现，适配层零改动。
 */
public final class PlayerTrackHelper {

    private PlayerTrackHelper() {
    }

    /** 内置字幕文本回调（内核差异在此收敛：IJK TimedText / Exo Cue） */
    public interface SubtitleListener {
        /** text 为 null 表示字幕清除（Exo 空 cues 场景） */
        void onSubtitle(@Nullable String text);
    }

    /** 获取音轨/字幕信息（内核无关）；不支持的内核返回 null */
    public static TrackInfo getTrackInfo(AbstractPlayer mediaPlayer) {
        return mediaPlayer instanceof KernelTrackSupport
                ? ((KernelTrackSupport) mediaPlayer).getTrackInfo() : null;
    }

    /** 切换轨道（IJK 按 trackId / Exo 按 TrackInfoBean）；null bean 或未实现内核忽略 */
    public static void selectTrack(AbstractPlayer mediaPlayer, @Nullable TrackInfoBean bean) {
        if (mediaPlayer == null || bean == null) return;
        if (mediaPlayer instanceof KernelTrackSupport) {
            ((KernelTrackSupport) mediaPlayer).selectTrack(bean);
        }
    }

    /** 切换后是否需调用方恢复进度 UI（仅 Exo 需要 startProgress，IJK 由内核自行恢复） */
    public static boolean requiresControllerProgressRestart(AbstractPlayer mediaPlayer) {
        return mediaPlayer instanceof KernelTrackSupport
                && ((KernelTrackSupport) mediaPlayer).requiresControllerProgressRestart();
    }

    /** 注册内置字幕回调（IJK/Exo 差异由内核实现收敛，回调统一为文本）；不支持的内核忽略 */
    public static void setOnSubtitleListener(@NonNull AbstractPlayer mediaPlayer, @NonNull SubtitleListener listener) {
        if (mediaPlayer instanceof KernelTrackSupport) {
            ((KernelTrackSupport) mediaPlayer).setOnSubtitleListener(listener);
        }
    }

    /** DoT 端口开关(仅 IJK 内核提供;随安全 DNS 选择联动)。内核差异集中在本适配层,UI 不直触内核类 */
    public static void toggleDotPort(boolean enable) {
        tv.danmaku.ijk.media.player.IjkMediaPlayer.toggleDotPort(enable);
    }
}
