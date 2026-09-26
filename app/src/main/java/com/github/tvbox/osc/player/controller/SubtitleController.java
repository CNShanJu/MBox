package com.github.tvbox.osc.player.controller;

import com.github.tvbox.osc.subtitle.widget.SimpleSubtitleView;

/**
 * 字幕能力契约:在线全屏({@link VodController})与本地播放({@link LocalVideoController})共用字幕设置弹窗。
 * <p>{@link com.github.tvbox.osc.util.player.SubtitleCoordinator} 只依赖本接口,不耦合具体控制器
 * (改进.txt §三 PlayFragment 拆分/UI 组件不得直读业务单例),便于本地播放器复用同一套字幕逻辑。
 */
public interface SubtitleController {

    /** 字幕视图(显隐/字号/延迟/颜色/内容渲染都作用于此) */
    SimpleSubtitleView getSubtitleView();

    /** 字幕开关:持久化配置(PlayConfig)并显隐字幕视图 */
    void openSubtitle(boolean open);

    /** 请求进度刷新(内置字幕切换后重建进度,避免快进) */
    void startProgress();
}
