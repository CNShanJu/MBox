package com.github.tvbox.osc.subtitle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.github.tvbox.osc.subtitle.model.Subtitle;
import com.github.tvbox.osc.subtitle.model.Time;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class SubtitleRefreshLoopTest {
    @Test
    public void startMatchesCurrentPositionImmediatelyEvenWhenPaused() {
        ManualScheduler scheduler = new ManualScheduler();
        FakePlayer player = new FakePlayer(caption(0, 60000, "first"));
        player.position = 25000;
        player.playing = false;
        SubtitleRefreshLoop loop = new SubtitleRefreshLoop(scheduler, player::refresh,
                error -> { throw new AssertionError(error); });

        loop.start();
        assertEquals(0, player.refreshCount);
        assertEquals(0, scheduler.nextDelay());
        scheduler.advanceBy(0);

        assertFalse(player.playing);
        assertEquals("first", player.visible.content);
        assertEquals(1, player.refreshCount);
        assertEquals(100, scheduler.nextDelay());
    }

    @Test
    public void seekDuringLongCaptionIsMatchedWithinOneRefreshInterval() {
        ManualScheduler scheduler = new ManualScheduler();
        Subtitle first = caption(0, 60000, "first");
        Subtitle second = caption(60001, 120000, "second");
        FakePlayer player = new FakePlayer(first, second);
        player.position = 1000;
        SubtitleRefreshLoop loop = new SubtitleRefreshLoop(scheduler, player::refresh,
                error -> { throw new AssertionError(error); });
        loop.start();
        scheduler.advanceBy(0);
        assertSame(first, player.visible);

        player.position = 90000;
        scheduler.advanceBy(99);
        assertSame(first, player.visible);
        scheduler.advanceBy(1);

        assertSame(second, player.visible);
        assertEquals(2, player.refreshCount);
    }

    @Test
    public void consecutiveFailuresReportOnceAndRefreshRecovers() {
        ManualScheduler scheduler = new ManualScheduler();
        FakePlayer player = new FakePlayer(caption(0, 60000, "caption"));
        player.failuresRemaining = 3;
        List<RuntimeException> failures = new ArrayList<>();
        SubtitleRefreshLoop loop = new SubtitleRefreshLoop(scheduler, player::refresh, failures::add);
        loop.start();

        scheduler.advanceBy(200);
        assertEquals(1, failures.size());
        assertEquals(3, player.refreshCount);
        scheduler.advanceBy(100);
        assertEquals("caption", player.visible.content);

        player.failuresRemaining = 1;
        scheduler.advanceBy(100);
        assertEquals(2, failures.size());
        scheduler.advanceBy(100);
        assertEquals(6, player.refreshCount);
        assertEquals("caption", player.visible.content);
        assertEquals(1, scheduler.size());
    }

    @Test
    public void stopCancelsPendingRefresh() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicInteger refreshes = new AtomicInteger();
        SubtitleRefreshLoop loop = new SubtitleRefreshLoop(scheduler, refreshes::incrementAndGet,
                error -> { throw new AssertionError(error); });
        loop.start();
        scheduler.advanceBy(0);
        loop.stop();

        assertEquals(0, scheduler.size());
        scheduler.advanceBy(1000);
        assertEquals(1, refreshes.get());
    }

    @Test
    public void dequeuedOldCallbackCannotReviveAfterRestart() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicInteger refreshes = new AtomicInteger();
        SubtitleRefreshLoop loop = new SubtitleRefreshLoop(scheduler, refreshes::incrementAndGet,
                error -> { throw new AssertionError(error); });
        loop.start();
        Runnable oldCallback = scheduler.takeNext();
        loop.stop();
        loop.start();

        oldCallback.run();
        assertEquals(0, refreshes.get());
        assertEquals(1, scheduler.size());
        scheduler.advanceBy(0);
        assertEquals(1, refreshes.get());
        assertEquals(1, scheduler.size());
        assertEquals(100, scheduler.nextDelay());
    }

    @Test
    public void stopInsideRefreshDoesNotScheduleAnotherTick() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicInteger refreshes = new AtomicInteger();
        SubtitleRefreshLoop[] loop = new SubtitleRefreshLoop[1];
        loop[0] = new SubtitleRefreshLoop(scheduler, () -> {
            refreshes.incrementAndGet();
            loop[0].stop();
        }, error -> { throw new AssertionError(error); });
        loop[0].start();

        scheduler.advanceBy(1000);
        assertEquals(1, refreshes.get());
        assertEquals(0, scheduler.size());
    }

    private static Subtitle caption(int start, int end, String text) {
        Subtitle caption = new Subtitle();
        caption.start = new Time("hh:mm:ss,ms", "00:00:00,000");
        caption.end = new Time("hh:mm:ss,ms", "00:00:00,000");
        caption.start.mseconds = start;
        caption.end.mseconds = end;
        caption.content = text;
        return caption;
    }

    /** Models Media3's requirement that position reads happen on the player's owning thread. */
    private static final class FakePlayer {
        private final Thread owner = Thread.currentThread();
        private final List<Subtitle> captions;
        long position;
        boolean playing;
        int failuresRemaining;
        int refreshCount;
        Subtitle visible;

        FakePlayer(Subtitle... captions) {
            this.captions = Arrays.asList(captions);
        }

        void refresh() {
            assertSame(owner, Thread.currentThread());
            refreshCount++;
            if (failuresRemaining > 0) {
                failuresRemaining--;
                throw new IllegalStateException("Player is temporarily unavailable");
            }
            visible = SubtitleFinder.find(position, captions);
        }
    }

    private static final class ManualScheduler implements SubtitleRefreshLoop.Scheduler {
        private final Thread owner = Thread.currentThread();
        private final List<Entry> entries = new ArrayList<>();
        private long now;
        private long nextOrder;

        @Override
        public void post(Runnable task, long delayMillis) {
            assertSame(owner, Thread.currentThread());
            assertTrue(delayMillis >= 0);
            entries.add(new Entry(task, now + delayMillis, nextOrder++));
        }

        @Override
        public void cancel(Runnable task) {
            assertSame(owner, Thread.currentThread());
            entries.removeIf(entry -> entry.task == task);
        }

        void advanceBy(long milliseconds) {
            long target = now + milliseconds;
            while (!entries.isEmpty() && nextEntry().due <= target) {
                Entry entry = nextEntry();
                entries.remove(entry);
                now = entry.due;
                entry.task.run();
            }
            now = target;
        }

        Runnable takeNext() {
            Entry entry = nextEntry();
            entries.remove(entry);
            return entry.task;
        }

        long nextDelay() {
            return nextEntry().due - now;
        }

        int size() {
            return entries.size();
        }

        private Entry nextEntry() {
            return entries.stream().min((left, right) -> {
                int byTime = Long.compare(left.due, right.due);
                return byTime == 0 ? Long.compare(left.order, right.order) : byTime;
            }).orElseThrow(() -> new AssertionError("No pending refresh"));
        }

        private static final class Entry {
            final Runnable task;
            final long due;
            final long order;

            Entry(Runnable task, long due, long order) {
                this.task = task;
                this.due = due;
                this.order = order;
            }
        }
    }
}
