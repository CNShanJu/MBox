package com.github.tvbox.osc.util

class SearchCompletionState {
    private enum class Phase { RUNNING, SETTLED, CLOSED }
    private var phase = Phase.RUNNING

    val feedbackSettled: Boolean get() = phase != Phase.RUNNING
    val closed: Boolean get() = phase == Phase.CLOSED

    fun begin() { phase = Phase.RUNNING }
    fun settle() { if (phase == Phase.RUNNING) phase = Phase.SETTLED }
    fun finish() { phase = Phase.CLOSED }
}
