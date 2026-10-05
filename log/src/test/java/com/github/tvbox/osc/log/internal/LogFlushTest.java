package com.github.tvbox.osc.log.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.github.tvbox.osc.log.LogEntry;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class LogFlushTest {
    @Test
    public void pendingFlushReportsDatabaseFailureThenAllowsNextSuccessfulBatch() {
        AtomicInteger attempts = new AtomicInteger();
        List<LogEntry> persisted = Collections.synchronizedList(new ArrayList<>());
        LogRepository repository = new LogRepository(new LogRepository.BatchWriter() {
            @Override public void insertAll(List<LogEntry> batch) {
                if (attempts.incrementAndGet() == 1) throw new IllegalStateException("disk write failed");
                persisted.addAll(batch);
            }
            @Override public int count() { return persisted.size(); }
            @Override public void trimTo(int keep) { }
        });
        LogCollector collector = new LogCollector(repository);

        LogEntry first = new LogEntry();
        collector.offer(first);
        assertFalse(collector.flushNowBlocking(2_000));
        assertTrue(persisted.isEmpty());

        LogEntry second = new LogEntry();
        collector.offer(second);
        assertTrue(collector.flushNowBlocking(2_000));
        assertEquals(2, attempts.get());
        assertEquals(Collections.singletonList(second), persisted);
    }

    @Test
    public void barrierReportsAnAlreadyQueuedFailureOnlyOnce() {
        AtomicInteger attempts = new AtomicInteger();
        LogRepository repository = new LogRepository(new LogRepository.BatchWriter() {
            @Override public void insertAll(List<LogEntry> batch) {
                if (attempts.incrementAndGet() == 1) throw new IllegalStateException("disk write failed");
            }
            @Override public int count() { return attempts.get(); }
            @Override public void trimTo(int keep) { }
        });

        repository.insertAllAsync(Collections.singletonList(new LogEntry()));
        assertFalse(repository.awaitWrites(2_000));
        assertTrue(repository.awaitWrites(2_000));
        repository.insertAllAsync(Collections.singletonList(new LogEntry()));
        assertTrue(repository.awaitWrites(2_000));
        assertEquals(2, attempts.get());
    }
}
