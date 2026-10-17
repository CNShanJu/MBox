package com.github.tvbox.osc.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchCompletionStateTest {
    @Test
    fun settlingFeedbackStillAcceptsLateResults() {
        val state = SearchCompletionState()
        assertFalse(state.feedbackSettled)
        state.settle()
        assertTrue(state.feedbackSettled)
        assertFalse(state.closed)
    }

    @Test
    fun completingOrTimingOutRejectsLateResults() {
        val state = SearchCompletionState()
        state.settle()
        state.finish()
        assertTrue(state.closed)
        assertTrue(state.feedbackSettled)
        state.settle()
        assertTrue(state.closed)
    }

    @Test
    fun nextSearchResetsFeedbackAndAdmission() {
        val state = SearchCompletionState()
        state.finish()
        state.begin()
        assertFalse(state.feedbackSettled)
        assertFalse(state.closed)
    }
}
