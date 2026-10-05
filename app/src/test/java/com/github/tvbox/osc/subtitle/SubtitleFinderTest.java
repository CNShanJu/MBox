package com.github.tvbox.osc.subtitle;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.github.tvbox.osc.subtitle.model.Subtitle;
import com.github.tvbox.osc.subtitle.model.Time;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SubtitleFinderTest {
    @Test
    public void longCaptionRemainsVisibleAfterShortOverlappingCaptionsEnd() {
        Subtitle longCaption = caption(0, 60000);
        Subtitle firstShort = caption(1000, 2000);
        Subtitle secondShort = caption(3000, 4000);
        SubtitleFinder.Index index = new SubtitleFinder.Index(
                Arrays.asList(longCaption, firstShort, secondShort));

        assertSame(firstShort, index.find(1500));
        assertSame(secondShort, index.find(3500));
        assertSame(longCaption, index.find(5000));
        assertSame(longCaption, index.find(60000));
        assertNull(index.find(60001));
    }

    @Test
    public void sameStartFallsBackToAnotherActiveCaptionWhenLastOneExpires() {
        Subtitle longCaption = caption(1000, 6000);
        Subtitle shortCaption = caption(1000, 2000);
        SubtitleFinder.Index index = new SubtitleFinder.Index(Arrays.asList(longCaption, shortCaption));

        assertSame(shortCaption, index.find(1000));
        assertSame(shortCaption, index.find(2000));
        assertSame(longCaption, index.find(2001));
    }

    @Test
    public void sortsActualTimesInsteadOfTrustingParserMapOrder() {
        Subtitle late = caption(4000, 5000);
        Subtitle early = caption(0, 1000);
        Subtitle middle = caption(2000, 3000);
        SubtitleFinder.Index index = new SubtitleFinder.Index(Arrays.asList(late, early, middle));

        assertSame(early, index.find(500));
        assertSame(middle, index.find(2500));
        assertSame(late, index.find(4500));
        // Keep the legacy entry point correct for callers without a persistent index.
        assertSame(middle, SubtitleFinder.find(2500, Arrays.asList(late, early, middle)));
    }

    @Test
    public void sortingDoesNotOverflowWhenTimesHaveDistantValues() {
        Subtitle late = caption(Integer.MAX_VALUE - 10, Integer.MAX_VALUE);
        Subtitle early = caption(Integer.MIN_VALUE, Integer.MIN_VALUE + 10);
        SubtitleFinder.Index index = new SubtitleFinder.Index(Arrays.asList(late, early));

        assertSame(early, index.find((long) Integer.MIN_VALUE + 5));
        assertSame(late, index.find((long) Integer.MAX_VALUE - 5));
        assertNull(index.find(Long.MIN_VALUE));
        assertNull(index.find(Long.MAX_VALUE));
    }

    @Test
    public void gapsStayEmptyAndBothBoundariesAreIncluded() {
        Subtitle first = caption(1000, 2000);
        Subtitle second = caption(3000, 4000);
        SubtitleFinder.Index index = new SubtitleFinder.Index(Arrays.asList(first, second));

        assertNull(index.find(999));
        assertSame(first, index.find(1000));
        assertSame(first, index.find(2000));
        assertNull(index.find(2001));
        assertNull(index.find(2999));
        assertSame(second, index.find(3000));
        assertSame(second, index.find(4000));
        assertNull(index.find(4001));
    }

    @Test
    public void ignoresInvalidEntriesAndAcceptsEmptyInputs() {
        Subtitle missingStart = caption(0, 1000);
        missingStart.start = null;
        Subtitle missingEnd = caption(0, 1000);
        missingEnd.end = null;
        Subtitle valid = caption(500, 500);
        SubtitleFinder.Index index = new SubtitleFinder.Index(Arrays.asList(
                null, missingStart, missingEnd, caption(1000, 999), valid));

        assertSame(valid, index.find(500));
        assertNull(index.find(1000));
        assertNull(new SubtitleFinder.Index(null).find(0));
        assertNull(new SubtitleFinder.Index(Collections.emptyList()).find(0));
    }

    @Test
    public void listMutationsDoNotChangeAnExistingIndex() {
        Subtitle retained = caption(0, 1000);
        List<Subtitle> source = new ArrayList<>(Collections.singletonList(retained));
        SubtitleFinder.Index index = new SubtitleFinder.Index(source);
        source.clear();
        source.add(caption(2000, 3000));

        assertSame(retained, index.find(500));
        assertNull(index.find(2500));
    }

    @Test
    public void timeMutationsRequireRebuildingTheIndex() {
        Subtitle shifted = caption(0, 1000);
        List<Subtitle> source = Collections.singletonList(shifted);
        SubtitleFinder.Index beforeDelay = new SubtitleFinder.Index(source);
        shifted.start.mseconds = 2000;
        shifted.end.mseconds = 3000;

        assertSame(shifted, beforeDelay.find(500));
        assertNull(beforeDelay.find(2500));
        SubtitleFinder.Index afterDelay = new SubtitleFinder.Index(source);
        assertNull(afterDelay.find(500));
        assertSame(shifted, afterDelay.find(2500));
    }

    private static Subtitle caption(int start, int end) {
        Subtitle subtitle = new Subtitle();
        subtitle.start = new Time("hh:mm:ss,ms", "00:00:00,000");
        subtitle.end = new Time("hh:mm:ss,ms", "00:00:00,000");
        subtitle.start.mseconds = start;
        subtitle.end.mseconds = end;
        return subtitle;
    }
}
