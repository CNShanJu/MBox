package com.github.tvbox.osc.cast.api;

import androidx.annotation.Nullable;

/** Narrow, read-only DLNA cast state and generation-scoped stop control for UI callers. */
public interface DlnaCastControl {
    /** Returns a snapshot of the confirmed cast, or null before confirmation / after release. */
    @Nullable Status status();

    /** Whether this generation still has a media relay while the TV has not confirmed it. */
    boolean preparing(long generation);

    /** Sends Stop only for this generation. Callback is delivered on the main thread. */
    boolean cancel(long generation, @Nullable Callback callback);

    interface Callback {
        void onResult(boolean success, String message);
    }

    final class Status {
        public final long generation;
        public final String deviceName;
        public final boolean stopping;

        public Status(long generation, String deviceName, boolean stopping) {
            this.generation = generation;
            this.deviceName = deviceName == null ? "" : deviceName;
            this.stopping = stopping;
        }
    }
}
