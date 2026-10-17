package com.github.tvbox.osc.transfer;

import java.util.concurrent.Callable;

/**
 * 订阅文件写入互斥锁（app 层入口）。
 *
 * <p>与 share 域的锁共享<b>同一把锁</b>：配置导入的快照/回滚（share 的
 * {@code DefaultImportProtection}）与订阅文件的写入（订阅模块各处）必须互斥，
 * 否则导入期间订阅文件可能被并发改写、快照失真。
 */
public final class SubscriptionImportFiles {
    private SubscriptionImportFiles() { }

    public static <T> T runLocked(Callable<T> action) throws Exception {
        return com.github.tvbox.osc.share.domain.SubscriptionImportFiles.runLocked(action);
    }
}
