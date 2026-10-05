package com.github.tvbox.osc.subtitle;

import java.util.function.Consumer;

/** Runs subtitle matching on the player's owning scheduler without retaining stopped callbacks. */
final class SubtitleRefreshLoop {
    static final long REFRESH_INTERVAL_MS = 100;

    interface Scheduler {
        void post(Runnable task, long delayMillis);

        void cancel(Runnable task);
    }

    private final Scheduler scheduler;
    private final Runnable refresh;
    private final Consumer<RuntimeException> onFailure;
    private long epoch;
    private boolean running;
    private boolean failureReported;
    private Runnable pendingTask;

    SubtitleRefreshLoop(Scheduler scheduler, Runnable refresh,
                        Consumer<RuntimeException> onFailure) {
        this.scheduler = scheduler;
        this.refresh = refresh;
        this.onFailure = onFailure;
    }

    /** All calls and scheduled callbacks must run on the scheduler's owning thread. */
    void start() {
        stop();
        running = true;
        failureReported = false;
        final long token = epoch;
        pendingTask = new Runnable() {
            @Override
            public void run() {
                if (!running || token != epoch) return;
                try {
                    refresh.run();
                    if (running && token == epoch) failureReported = false;
                } catch (RuntimeException error) {
                    if (running && token == epoch && !failureReported) {
                        failureReported = true;
                        onFailure.accept(error);
                    }
                } finally {
                    if (running && token == epoch) {
                        scheduler.post(this, REFRESH_INTERVAL_MS);
                    }
                }
            }
        };
        scheduler.post(pendingTask, 0);
    }

    void stop() {
        running = false;
        epoch++;
        if (pendingTask != null) {
            scheduler.cancel(pendingTask);
            pendingTask = null;
        }
    }
}
