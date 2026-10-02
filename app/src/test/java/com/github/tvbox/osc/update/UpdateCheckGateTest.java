package com.github.tvbox.osc.update;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UpdateCheckGateTest {

    @Test
    public void manualCheckBeforeScheduledAutoPreventsItsStart() {
        UpdateCheckGate gate = new UpdateCheckGate();

        gate.onManualCheck();

        assertFalse(gate.beginAutoCheck());
        assertFalse(gate.mayShowAutoPrompt());
    }

    @Test
    public void manualCheckInvalidatesInFlightAutoResultAndPostedPrompt() {
        UpdateCheckGate gate = new UpdateCheckGate();

        assertTrue(gate.beginAutoCheck());
        assertTrue(gate.mayShowAutoPrompt());

        gate.onManualCheck();

        assertFalse(gate.mayShowAutoPrompt());
        assertFalse(gate.beginAutoCheck());
    }

    @Test
    public void autoCheckRunsOnlyOnceWithoutManualCheck() {
        UpdateCheckGate gate = new UpdateCheckGate();

        assertTrue(gate.beginAutoCheck());
        assertFalse(gate.beginAutoCheck());
        assertTrue(gate.mayShowAutoPrompt());
    }
}
