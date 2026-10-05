package com.github.tvbox.osc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

public class AppDataManagerTest {

    @Test(timeout = 5000)
    public void nestedRunOnDbExecutesOnTheSameThread() {
        AtomicReference<Thread> outerThread = new AtomicReference<>();
        String result = AppDataManager.runOnDb(() -> {
            outerThread.set(Thread.currentThread());
            AppDataManager.runOnDb(() -> assertSame(outerThread.get(), Thread.currentThread()));
            return AppDataManager.runOnDb(() -> {
                assertSame(outerThread.get(), Thread.currentThread());
                return "done";
            });
        });
        assertEquals("done", result);
    }

    @Test(timeout = 5000)
    public void nestedCheckedFailureKeepsOriginalCause() {
        IOException failure = new IOException("read failed");
        try {
            AppDataManager.runOnDb((Callable<String>) () -> AppDataManager.runOnDb((Callable<String>) () -> {
                throw failure;
            }));
        } catch (RuntimeException error) {
            assertSame(failure, error.getCause());
            return;
        }
        throw new AssertionError("Expected checked failure");
    }
}
