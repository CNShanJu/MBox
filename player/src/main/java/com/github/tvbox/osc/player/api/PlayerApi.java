package com.github.tvbox.osc.player.api;

import android.content.Context;

/**
 * 播放统一门面（引擎无关契约）。
 * <p>
 * 铁律：本接口只暴露项目自有类型（PlayState / PlayOptions / PlayListener），
 * **任何内核类型（ExoPlayer2 / IJK / 未来 Media3 的 Player/ExoPlayer）不得跨出适配层**。
 * 调用方（PlayFragment / 小窗 / 后台播放 / 预览）只依赖本接口，感知不到内核是谁。
 * 内核特有能力（track 选择、自定义渲染）需要时经本接口显式暴露，禁止调用方强转内核类型。
 */
public interface PlayerApi {

    /** 初始化并加载（内部按 PlayOptions.playType 经 PlayerFactory 选内核） */
    void init(Context context, PlayOptions options, PlayListener listener);

    /** 开始播放 / 从暂停恢复 */
    void play();

    /** 暂停 */
    void pause();

    /** 跳转（毫秒） */
    void seekTo(long positionMs);

    /** 倍速（0.5~3.0） */
    void setSpeed(float speed);

    /** 画面缩放（与 PlayerHelper 的 scale type 对应） */
    void setScale(int scaleType);

    // ── 查询（线程安全）──

    PlayState getState();

    long getPosition();

    long getDuration();

    /** {width, height}；未知返回 {0, 0} */
    int[] getVideoSize();

    // ── 监听 ──

    /** 追加监听（多订阅者；内部去重） */
    void subscribe(PlayListener listener);

    void unsubscribe(PlayListener listener);

    // ── 生命周期 / 小窗 / 后台 ──

    void release();

    /** 进入小窗（由外层 UI 容器承载，实现层提供钩子） */
    void enterWindow();

    /** 后台播放开关（无 UI 继续播放） */
    void backgroundPlay(boolean on);
}
