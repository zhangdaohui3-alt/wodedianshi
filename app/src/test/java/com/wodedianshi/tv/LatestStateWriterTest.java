package com.wodedianshi.tv;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class LatestStateWriterTest {
    @Test public void latestValueWinsBeforeWorkerRuns() {
        LatestStateWriter<String> state = new LatestStateWriter<>();
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        ArrayList<String> writes = new ArrayList<>();
        state.initialize("A");
        state.offer("B", queue::add, writes::add);
        state.offer("C", queue::add, writes::add);
        assertEquals(1, queue.size());
        queue.remove().run();
        assertEquals(Arrays.asList("C"), writes);
        state.offer("C", queue::add, writes::add);
        assertTrue(queue.isEmpty());
    }

    @Test public void clearedResumeSupersedesQueuedStationAndAllowsNewSelection() {
        LatestStateWriter<String> state = new LatestStateWriter<>();
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        ArrayList<String> writes = new ArrayList<>();
        state.initialize("old");
        state.offer("removed", queue::add, writes::add);
        state.offer("", queue::add, writes::add);
        queue.remove().run();
        assertEquals(Arrays.asList(""), writes);
        assertEquals("", state.initialize("stale disk"));
        state.offer("new", queue::add, writes::add);
        queue.remove().run();
        assertEquals(Arrays.asList("","new"), writes);
    }

    @Test public void failedValueCanBeOfferedAgain() {
        LatestStateWriter<String> state = new LatestStateWriter<>();
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        ArrayList<String> writes = new ArrayList<>();
        state.initialize("A");
        state.offer("B", queue::add, v -> {throw new IllegalStateException();});
        queue.remove().run();
        assertTrue(queue.isEmpty());
        state.offer("B", queue::add, writes::add);
        queue.remove().run();
        assertEquals(Arrays.asList("B"), writes);
    }

    @Test public void newerValueDuringWriteRunsAfterOtherQueuedWork() {
        LatestStateWriter<String> state = new LatestStateWriter<>();
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        ArrayList<String> writes = new ArrayList<>();
        state.initialize("A");
        state.offer("B", queue::add, v -> {
            writes.add(v);
            state.offer("C", queue::add, writes::add);
        });
        queue.add(() -> writes.add("other"));
        queue.remove().run();
        queue.remove().run();
        queue.remove().run();
        assertEquals(Arrays.asList("B","other","C"), writes);
        assertEquals("C", state.initialize("old disk state"));
    }

    @Test public void rejectionLeavesValueRetryable() {
        LatestStateWriter<String> state = new LatestStateWriter<>();
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        ArrayList<String> writes = new ArrayList<>();
        state.initialize("A");
        state.offer("B", task -> {throw new java.util.concurrent.RejectedExecutionException();}, writes::add);
        state.offer("B", queue::add, writes::add);
        queue.remove().run();
        assertEquals(Arrays.asList("B"), writes);
    }
}
