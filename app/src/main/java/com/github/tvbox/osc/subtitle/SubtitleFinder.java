/*
 *                       Copyright (C) of Avery
 *
 *                              _ooOoo_
 *                             o8888888o
 *                             88" . "88
 *                             (| -_- |)
 *                             O\  =  /O
 *                          ____/`- -'\____
 *                        .'  \\|     |//  `.
 *                       /  \\|||  :  |||//  \
 *                      /  _||||| -:- |||||-  \
 *                      |   | \\\  -  /// |   |
 *                      | \_|  ''\- -/''  |   |
 *                      \  .-\__  `-`  ___/-. /
 *                    ___`. .' /- -.- -\  `. . __
 *                 ."" '<  `.___\_<|>_/___.'  >'"".
 *                | | :  `- \`.;`\ _ /`;.`/ - ` : | |
 *                \  \ `-.   \_ __\ /__ _/   .-` /  /
 *           ======`-.____`-.___\_____/___.-`____.-'======
 *                              `=- -='
 *           ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
 *              Buddha bless, there will never be bug!!!
 */

package com.github.tvbox.osc.subtitle;

import androidx.annotation.Nullable;

import com.github.tvbox.osc.subtitle.model.Subtitle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * @author AveryZhong.
 */

public class SubtitleFinder {
    private SubtitleFinder() {
        throw new AssertionError("No instance for you");
    }

    @Nullable
    public static Subtitle find(long position, @Nullable List<Subtitle> subtitles) {
        return new Index(subtitles).find(position);
    }

    /**
     * A time snapshot built when captions are loaded or their delay changes. Caption content stays
     * on the original objects, while list changes and later time edits cannot reorder this index.
     */
    public static final class Index {
        private final Subtitle[] subtitles;
        private final long[] starts;
        private final long[] ends;
        private final long[] prefixMaxEnds;

        public Index(@Nullable List<Subtitle> source) {
            List<Entry> entries = new ArrayList<>();
            if (source != null) {
                for (Subtitle subtitle : source) {
                    if (subtitle == null || subtitle.start == null || subtitle.end == null) continue;
                    long start = subtitle.start.mseconds;
                    long end = subtitle.end.mseconds;
                    if (end >= start) entries.add(new Entry(subtitle, start, end));
                }
            }
            // Parser map keys are not a reliable ordering when captions overlap or share a start.
            entries.sort(Comparator.comparingLong(entry -> entry.start));
            int count = entries.size();
            subtitles = new Subtitle[count];
            starts = new long[count];
            ends = new long[count];
            prefixMaxEnds = new long[count];
            long maxEnd = Long.MIN_VALUE;
            for (int i = 0; i < count; i++) {
                Entry entry = entries.get(i);
                subtitles[i] = entry.subtitle;
                starts[i] = entry.start;
                ends[i] = entry.end;
                maxEnd = Math.max(maxEnd, entry.end);
                prefixMaxEnds[i] = maxEnd;
            }
        }

        /** Returns the active caption with the latest start, including both time boundaries. */
        @Nullable
        public Subtitle find(long position) {
            int lower = 0;
            int upper = starts.length;
            while (lower < upper) {
                int middle = lower + (upper - lower) / 2;
                if (starts[middle] <= position) lower = middle + 1;
                else upper = middle;
            }
            // A preceding long caption may still be active after shorter overlapping ones finish.
            // The prefix bound ends this walk immediately for ordinary non-overlapping tracks.
            for (int i = lower - 1; i >= 0 && prefixMaxEnds[i] >= position; i--) {
                if (ends[i] >= position) return subtitles[i];
            }
            return null;
        }

        private static final class Entry {
            final Subtitle subtitle;
            final long start;
            final long end;

            Entry(Subtitle subtitle, long start, long end) {
                this.subtitle = subtitle;
                this.start = start;
                this.end = end;
            }
        }
    }
}
