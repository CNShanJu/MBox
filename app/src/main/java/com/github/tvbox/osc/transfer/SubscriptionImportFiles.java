package com.github.tvbox.osc.transfer;

import java.util.concurrent.Callable;

/** Serializes imported subscription file writes with configuration import snapshots and rollback. */
public final class SubscriptionImportFiles {
    private static final Object LOCK = new Object();

    private SubscriptionImportFiles() { }

    public static <T> T runLocked(Callable<T> action) throws Exception {
        synchronized (LOCK) {
            return action.call();
        }
    }
}
