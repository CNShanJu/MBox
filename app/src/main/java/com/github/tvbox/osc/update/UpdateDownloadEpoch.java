package com.github.tvbox.osc.update;

/** Identifies the active APK download worker across cancel, retry, and resume. */
final class UpdateDownloadEpoch {
    private volatile long value;

    synchronized long next() {
        return ++value;
    }

    long current() {
        return value;
    }

    boolean isCurrent(long candidate) {
        return value == candidate;
    }

    synchronized boolean runIfCurrent(long candidate, Runnable action) {
        if (value != candidate) return false;
        action.run();
        return true;
    }
}
