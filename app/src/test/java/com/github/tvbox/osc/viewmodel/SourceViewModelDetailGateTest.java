package com.github.tvbox.osc.viewmodel;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 模拟网络与主线程队列不同顺序完成时的详情发布。 */
public class SourceViewModelDetailGateTest {

    @Test
    public void oldSuccessAndFailureQueuedOnMainCannotReplaceNewDetail() {
        SourceViewModel.DetailRequestGate gate = new SourceViewModel.DetailRequestGate();
        List<Runnable> mainQueue = new ArrayList<>();
        List<String> displayed = new ArrayList<>();
        int first = gate.begin();
        assertTrue(gate.postIfCurrent(first, mainQueue::add, () -> displayed.add("old result")));
        assertTrue(gate.postIfCurrent(first, mainQueue::add, () -> displayed.add("old failure")));

        int second = gate.begin();
        assertTrue(gate.postIfCurrent(second, mainQueue::add, () -> displayed.add("new result")));
        for (Runnable callback : mainQueue) callback.run();

        assertEquals(Arrays.asList("new result"), displayed);
        assertFalse(gate.isCurrent(first));
        assertTrue(gate.isCurrent(second));
    }

    @Test
    public void staleWorkerCallbackIsRejectedBeforeItCanQueueAPublish() {
        SourceViewModel.DetailRequestGate gate = new SourceViewModel.DetailRequestGate();
        int oldRequest = gate.begin();
        int currentRequest = gate.begin();
        List<String> displayed = new ArrayList<>();
        List<Runnable> mainQueue = new ArrayList<>();

        if (gate.isCurrent(oldRequest)) displayed.add("old HTTP or Thunder callback");
        assertFalse(gate.postIfCurrent(oldRequest, mainQueue::add,
                () -> displayed.add("old queued callback")));
        assertTrue(gate.postIfCurrent(currentRequest, mainQueue::add,
                () -> displayed.add("current callback")));
        for (Runnable callback : mainQueue) callback.run();

        assertEquals(Arrays.asList("current callback"), displayed);
    }

    @Test
    public void clearingViewModelInvalidatesPendingDetailAndFutureRequests() {
        SourceViewModel.DetailRequestGate gate = new SourceViewModel.DetailRequestGate();
        int request = gate.begin();
        List<Runnable> mainQueue = new ArrayList<>();
        assertTrue(gate.postIfCurrent(request, mainQueue::add, () -> {
            throw new AssertionError("pending result survived ViewModel clear");
        }));
        gate.invalidate();
        for (Runnable callback : mainQueue) callback.run();

        assertFalse(gate.isCurrent(request));
        assertFalse(gate.postIfCurrent(request, runnable -> {
            throw new AssertionError("cleared ViewModel queued a detail result");
        }, () -> {
            throw new AssertionError("cleared ViewModel delivered a detail result");
        }));
        assertFalse(gate.isCurrent(gate.begin()));
    }
}
