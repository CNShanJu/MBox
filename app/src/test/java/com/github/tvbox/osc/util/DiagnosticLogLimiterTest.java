package com.github.tvbox.osc.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DiagnosticLogLimiterTest {
    @Test
    public void keepsFirstFailureAndChangedCauseThenAllowsLaterRetry() {
        DiagnosticLogLimiter limiter = new DiagnosticLogLimiter(30_000, 8);

        assertTrue(limiter.allow("url=A|dns=system|reset", 1_000));
        assertFalse(limiter.allow("url=A|dns=system|reset", 10_000));
        assertTrue(limiter.allow("url=A|dns=doh|reset", 10_001));
        assertTrue(limiter.allow("url=A|dns=system|reset", 31_000));
    }

    @Test
    public void boundsRememberedFailuresAndHandlesClockReset() {
        DiagnosticLogLimiter limiter = new DiagnosticLogLimiter(30_000, 2);

        assertTrue(limiter.allow("A", 100_000));
        assertTrue(limiter.allow("B", 100_001));
        assertTrue(limiter.allow("C", 100_002));
        assertTrue(limiter.allow("A", 100_003));
        assertTrue(limiter.allow("A", 5));
    }
}
