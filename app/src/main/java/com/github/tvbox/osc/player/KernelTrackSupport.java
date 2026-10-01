package com.github.tvbox.osc.player;

import androidx.annotation.Nullable;

/**
 * 内核轨道能力接口（⑥ 适配层）：把 PlayerTrackHelper 里对具体内核的 instanceof 分发
 * 收口为"能力接口 + 各内核实现"，适配层/调用方不再感知 IJK/Media3 具体类型。
 * <p>
 * IjkMediaPlayer / EXOmPlayer 实现本接口；Media3 迁移在 EXOmPlayer 内完成。
 */
public interface KernelTrackSupport {

    /** 当前音轨/内置字幕信息（无则 null） */
    @Nullable
    TrackInfo getTrackInfo();

    /** 切换轨道（IJK 按 bean.trackId / Media3 按分组及轨道索引）；null bean 忽略 */
    void selectTrack(@Nullable TrackInfoBean bean);

    /** 切换后是否需要调用方恢复进度 UI（仅 Media3 需要 startProgress） */
    boolean requiresControllerProgressRestart();

    /** 注册内置字幕文本回调（IJK TimedText / Media3 CueGroup 差异在此收敛为文本） */
    void setOnSubtitleListener(PlayerTrackHelper.SubtitleListener listener);
}
