package com.github.tvbox.osc.ui.dialog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LastViewedBubblePlacementTest {

    @Test
    public void wideWindowKeepsCardScreenCenteredInsteadOfHuggingLiveBubble() {
        LastViewedBubblePlacement.Position result = LastViewedBubblePlacement.calculate(
                100, 700, 626, 700, 754, 260, 44, 30, 4, 4);

        assertTrue(result.sameRow);
        assertEquals(270, result.left);
        assertEquals(705, result.top);
        assertEquals(96, 626 - (result.left + 260));
    }

    @Test
    public void typicalPhoneMovesCenteredCardFourPixelsAboveLiveBubble() {
        LastViewedBubblePlacement.Position result = LastViewedBubblePlacement.calculate(
                0, 400, 326, 700, 754, 260, 44, 30, 4, 4);

        assertFalse(result.sameRow);
        assertEquals(70, result.left);
        assertEquals(4, 700 - (result.top + 44));
    }

    @Test
    public void centeredCardUsesSameRowWhenSpaceActuallyFits() {
        LastViewedBubblePlacement.Position result = LastViewedBubblePlacement.calculate(
                0, 420, 346, 700, 754, 260, 44, 30, 4, 4);

        assertTrue(result.sameRow);
        assertEquals(80, result.left);
        assertEquals(705, result.top);
        assertEquals(6, 346 - (result.left + 260));
    }

    @Test
    public void sideInsetIsIncludedEvenIfCenteredCardClearsTheBall() {
        LastViewedBubblePlacement.Position fits = LastViewedBubblePlacement.calculate(
                0, 360, 340, 700, 754, 300, 44, 30, 4, 4);
        LastViewedBubblePlacement.Position missesInset = LastViewedBubblePlacement.calculate(
                0, 360, 340, 700, 754, 302, 44, 30, 4, 4);

        assertTrue(fits.sameRow);
        assertEquals(30, fits.left);
        assertFalse(missesInset.sameRow);
        assertEquals(29, missesInset.left);
    }
}
