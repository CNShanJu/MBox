package com.github.tvbox.osc.update;

/** Decide whether a failed request may safely switch to another APK source. */
final class UpdateDownloadFailurePolicy {
    private UpdateDownloadFailurePolicy() { }

    /** A broken connection must not discard bytes already downloaded from this source. */
    static boolean keepSourceForRetry(long partialBytes, boolean networkAvailable) {
        return partialBytes > 0 || !networkAvailable;
    }

    /** Transient HTTP responses should preserve an existing partial APK for a later retry. */
    static boolean keepSourceForRetry(int statusCode, long partialBytes) {
        return partialBytes > 0 && (statusCode == 408 || statusCode == 429 || statusCode >= 500);
    }
}
