package com.github.tvbox.osc.util.player;

/**
 * "切换音轨/内置字幕后的进度恢复"这条延迟任务(800ms)的执行条件 —— 纯逻辑,便于 JVM 单测。
 * <p>
 * 背景:切轨道后内核会自己快进几秒,所以要把进度 seek 回去;但这条任务排在 800ms 之后,
 * 期间用户完全可能已经退出播放页、切了下一集、或者换了源(内核被替换/释放)。三种情况下都不能再执行:
 * <ul>
 *   <li>已释放:内核正在释放,{@code seekTo}/{@code start} 打到已释放的原生播放器上 → native 崩;</li>
 *   <li>已换集:拿上一集的进度去 seek 新一集,会把新集拉到旧位置;</li>
 *   <li>内核已换:同一个道理,操作对象已经不是当初那个内核。</li>
 * </ul>
 * 条件写在这里(而不是散在 Runnable 里)是为了能被单测固定住 —— 少任何一条都不会立刻报错,
 * 只会在真机上偶发闪退/跳进度。
 */
public final class TrackRestoreGuard {

    private TrackRestoreGuard() {
    }

    /**
     * @param released    协调器是否已释放(宿主 onDestroyView)
     * @param taskEpoch   排任务时的播放上下文版本
     * @param currentEpoch 当前播放上下文版本(宿主每次切集/换源递增)
     * @param sameKernel  延迟期间内核是否仍是当时那一个(未替换、未释放)
     * @return 是否允许执行这次进度恢复
     */
    public static boolean shouldRun(boolean released, int taskEpoch, int currentEpoch, boolean sameKernel) {
        return !released && taskEpoch == currentEpoch && sameKernel;
    }
}
