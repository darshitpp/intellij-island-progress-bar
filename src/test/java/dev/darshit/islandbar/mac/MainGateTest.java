package dev.darshit.islandbar.mac;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The teardown order of the native callbacks: a stub freed while native code may still call it is a JVM crash. */
class MainGateTest {
    final List<String> events = new ArrayList<>();
    final List<String> ran = new ArrayList<>();
    final MainQueue queue = new MainQueue(t -> {});
    boolean barrierPasses = true, failDrain;

    final MainGate.Native nat = new MainGate.Native() {
        @Override public void dispatchDrain() {
            if (failDrain) throw new IllegalStateException("dispatch failed");
            events.add("drain");
        }
        @Override public Object dispatchBarrier() { events.add("barrier"); return "sem"; }
        @Override public boolean awaitBarrier(Object barrier, long timeoutMs) {
            events.add("await " + barrier);
            return barrierPasses;
        }
        @Override public void freeStubs() { events.add("free"); }
    };
    final MainGate gate = new MainGate(queue, nat);

    /** What the main thread does: one entry per dispatched drain. */
    void mainThreadDrains(int n) { for (int i = 0; i < n; i++) queue.runOne(); }

    @Test void submitQueuesTheTaskAndDispatchesOneDrain() {
        gate.submit(() -> ran.add("a"), t -> fail(t));
        gate.submit(() -> ran.add("b"), t -> fail(t));
        assertEquals(List.of("drain", "drain"), events);
        mainThreadDrains(2);
        assertEquals(List.of("a", "b"), ran);
    }

    @Test void aFailedDispatchGoesToTheErrorHandler() {
        failDrain = true;
        List<Throwable> errors = new ArrayList<>();
        gate.submit(() -> {}, errors::add);
        assertEquals(1, errors.size());
    }

    @Test void closeQueuesTheLastTaskThenTheBarrierAfterIt() {
        gate.submit(() -> ran.add("remove panel"), t -> fail(t));
        assertTrue(gate.close(() -> ran.add("last"), t -> fail(t)));
        assertEquals(List.of("drain", "drain", "barrier"), events); // the barrier is dispatched after the last drain
        mainThreadDrains(2);
        assertEquals(List.of("remove panel", "last"), ran);
    }

    @Test void afterCloseNothingIsQueuedOrDispatched() {
        gate.close(() -> {}, t -> fail(t));
        events.clear();
        List<Throwable> errors = new ArrayList<>();
        gate.submit(() -> ran.add("late"), errors::add);
        assertEquals(List.of(), events);
        assertEquals(List.of(), errors); // dropped silently: the plugin is going away
        mainThreadDrains(2);
        assertEquals(List.of(), ran);
    }

    @Test void closeIsIdempotent() {
        assertTrue(gate.close(() -> ran.add("last"), t -> fail(t)));
        assertFalse(gate.close(() -> ran.add("last again"), t -> fail(t)));
        assertEquals(List.of("drain", "barrier"), events);
        mainThreadDrains(2);
        assertEquals(List.of("last"), ran);
    }

    @Test void stubsAreFreedOnlyAfterTheBarrierPassedAndTheLastTaskRan() {
        gate.close(() -> ran.add("last"), t -> fail(t));
        mainThreadDrains(1);
        assertTrue(gate.awaitAndFree(1000));
        assertEquals(List.of("drain", "barrier", "await sem", "free"), events);
        assertTrue(gate.isFreed());
    }

    @Test void freeingTwiceFreesOnce() {
        gate.close(() -> {}, t -> fail(t));
        mainThreadDrains(1);
        assertTrue(gate.awaitAndFree(1000));
        assertFalse(gate.awaitAndFree(1000));
        assertEquals(1, events.stream().filter("free"::equals).count());
    }

    @Test void aBarrierTimeoutKeepsTheStubs() {
        barrierPasses = false;
        gate.close(() -> {}, t -> fail(t));
        mainThreadDrains(1);
        assertFalse(gate.awaitAndFree(10));
        assertFalse(events.contains("free"));
        assertFalse(gate.isFreed());
    }

    @Test void theStubsAreKeptWhenTheLastTaskHasNotRun() {
        // e.g. an earlier dispatch failed, so the queue holds more entries than drains: "last" is still waiting
        gate.close(() -> ran.add("last"), t -> fail(t));
        assertFalse(gate.awaitAndFree(1000));
        assertFalse(events.contains("free"));
    }

    @Test void theStubsAreKeptWhenTheLastTaskFailed() {
        List<Throwable> errors = new ArrayList<>();
        gate.close(() -> { throw new IllegalStateException("could not neutralise"); }, errors::add);
        mainThreadDrains(1);
        assertEquals(1, errors.size());
        assertFalse(gate.awaitAndFree(1000));
        assertFalse(events.contains("free"));
    }

    @Test void theStubsAreKeptWhenTheLastDrainCouldNotBeDispatched() {
        failDrain = true;
        gate.close(() -> ran.add("last"), t -> {});
        assertFalse(gate.awaitAndFree(1000));
        assertFalse(events.contains("free"));
    }

    @Test void awaitBeforeCloseFreesNothing() {
        assertFalse(gate.awaitAndFree(1000));
        assertEquals(List.of(), events);
    }

    @Test void entriesLeftInTheQueueAreDroppedWhenFreed() {
        gate.close(() -> {}, t -> {});
        mainThreadDrains(1);
        gate.awaitAndFree(1000);
        assertFalse(gate.isClosed() && !gate.isFreed());
        assertEquals(0, queue.size());
    }

    @Test void theErrorHandlerRunsOutsideTheLock() throws Exception {
        // a failure handler that waits for another thread which needs the gate (as the coordinator's lock would)
        failDrain = true;
        CountDownLatch otherSubmitted = new CountDownLatch(1);
        gate.submit(() -> {}, t -> {
            Thread other = new Thread(() -> { gate.submit(() -> {}, x -> {}); otherSubmitted.countDown(); });
            other.start();
            try { assertTrue(otherSubmitted.await(5, TimeUnit.SECONDS), "deadlock: handler ran under the gate's lock"); }
            catch (InterruptedException e) { throw new RuntimeException(e); }
        });
    }
}
