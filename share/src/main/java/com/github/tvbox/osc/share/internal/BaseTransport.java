package com.github.tvbox.osc.share.internal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.tvbox.osc.share.transport.ShareTransport;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 传输实现基类:统一"同一时刻只认最后一次操作 + epoch 过期自检 + 取消"的骨架。
 *
 * <p>为什么值得抽基类(而不是让三个实现各写一遍):这套语义是 AGENTS 明确要求的
 * ("后台任务必须带取消/过期自检语义(epoch)"),而写错的方式非常隐蔽 ——
 * 用户连点两次"导出",第一次的慢请求后回来,把第二次的结果覆盖掉,界面就显示了一个
 * 已经作废的链接。只有把 epoch 收在基类里,"后发者胜"才不会因为某个实现忘了写而破功。
 *
 * <p>用法:
 * <pre>
 * void export(req, cb) {
 *     ShareCallbackHandle&lt;ShareLink&gt; h = ShareCallbackHandle.of(cb);
 *     long op = beginOperation(h);              // 作废上一次,拿到本次 epoch
 *     EXECUTOR.execute(() -&gt; {
 *         try {
 *             ...
 *             if (!isCurrent(op)) return;       // 已被新操作/取消取代:别回调,直接退
 *             if (h.isTerminal()) return;
 *             h.success(link);
 *         } catch (ShareException e) { h.error(e); }
 *         finally { finishOperation(op); }
 *     });
 * }
 * </pre>
 *
 * <p>子类要额外清理(撤销会话/删临时文件)时覆写 {@link #onCancelRequested()} /
 * {@link #onRelease()}。
 */
public abstract class BaseTransport implements ShareTransport {

    private final AtomicLong epoch = new AtomicLong();
    private volatile ShareCallbackHandle<?> active;

    /**
     * 开始一次"单次操作"(导出/拉取)。
     *
     * @return 本次 epoch;后台线程每轮用 {@link #isCurrent(long)} 自检
     */
    protected final long beginOperation(@NonNull ShareCallbackHandle<?> handle) {
        long e = epoch.incrementAndGet();
        ShareCallbackHandle<?> previous = active;
        active = handle;
        // 旧操作以 CANCELLED 收尾:用户看到的是"上一次被这次取代了",而不是它莫名其妙没声了。
        // 顺序很关键 —— 先装新 handle 再收旧 handle,否则旧 handle 的 cancel 会把新装的顶掉。
        if (previous != null && previous != handle) previous.cancel();
        return e;
    }

    /** 本次操作是否仍是"最新的一次"(被取代/已取消则为 false) */
    protected final boolean isCurrent(long operationEpoch) {
        return epoch.get() == operationEpoch;
    }

    /** 单次操作收尾:只有自己仍是最新时才清空槽位,避免把后来者的 handle 清掉 */
    protected final void finishOperation(long operationEpoch) {
        if (epoch.get() == operationEpoch) active = null;
    }

    @Override
    public void cancel() {
        epoch.incrementAndGet();
        ShareCallbackHandle<?> handle = active;
        active = null;
        if (handle != null) handle.cancel();
        onCancelRequested();
    }

    @Override
    public void release() {
        cancel();
        onRelease();
    }

    /** 子类钩子:取消时除了回调收尾,还要做的资源收尾(撤销会话/停接收口等) */
    protected void onCancelRequested() {
    }

    /** 子类钩子:{@link #release()} 时的额外清理 */
    protected void onRelease() {
    }

    /** 便捷:不可用即失败(带 availability 给的原因),所有实现开头都该这么做 */
    protected static void failUnavailable(@NonNull ShareCallbackHandle<?> handle,
                                         @Nullable String reason) {
        handle.error(com.github.tvbox.osc.share.ShareException.unavailable(reason));
    }
}
