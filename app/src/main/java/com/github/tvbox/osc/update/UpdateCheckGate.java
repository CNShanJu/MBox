package com.github.tvbox.osc.update;

import java.util.concurrent.atomic.AtomicInteger;

/** 一次进程生命周期内，手动检查接管排队或在途的自动检查。 */
final class UpdateCheckGate {

    private static final int IDLE = 0;
    private static final int AUTO_STARTED = 1;
    private static final int MANUAL_STARTED = 2;

    private final AtomicInteger state = new AtomicInteger(IDLE);

    void onManualCheck() {
        state.set(MANUAL_STARTED);
    }

    boolean beginAutoCheck() {
        return state.compareAndSet(IDLE, AUTO_STARTED);
    }

    boolean mayShowAutoPrompt() {
        return state.get() == AUTO_STARTED;
    }
}
