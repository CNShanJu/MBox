package com.github.tvbox.osc.player;

import androidx.annotation.Nullable;

/**
 * 内核轨道能力接口（⑥ 适配层）：把 PlayerTrackHelper 里对具体内核的 instanceof 分发
 * 收口为"能力接口 + 各内核实现"，适配层/调用方不再感知 IJK/Exo 具体类型。
 * <p>
 * IjkMediaPlayer / EXOmPlayer 实现本接口；Media3 升级 = 新增实现，适配层零改动。
 */
public interface KernelTrackSupport {

    /** 当前音轨/内置字幕信息（无则 null） */
    @Nullable
    TrackInfo getTrackInfo();

    /** 切换轨道（IJK 按 bean.trackId / Exo 按 bean 完整索引）；bean 为 null 时清除字幕选择 */
    void selectTrack(@Nullable TrackInfoBean bean);

    /** 切换后是否需要调用方恢复进度 UI（仅 Exo 需要 startProgress） */
    boolean requiresControllerProgressRestart();

    /** 注册内置字幕文本回调（IJK TimedText / Exo Cue 差异在此收敛为文本） */
    void setOnSubtitleListener(PlayerTrackHelper.SubtitleListener listener);
}
