package dev.darshit.islandbar.mac;

import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

/**
 * The only way work reaches the AppKit main thread, and the one place that decides when the native callbacks (the
 * upcall stubs) may be freed. Pure Java: the native side comes in through {@link Native}, so the order is unit-tested.
 *
 * <p>Teardown order. Freeing a stub that native code can still call crashes the JVM, so:
 * <ol>
 *   <li>{@link #close}: under the lock that every {@link #submit} also dispatches under, queue {@code last} (on the
 *       main thread it makes the button IMPs native no-ops, releases the button target and clears the callbacks) and
 *       dispatch its drain, mark the gate closed, then dispatch the barrier: a native function, not a stub. From now
 *       on no drain is dispatched, so the barrier is the last thing in the main queue that came from us.</li>
 *   <li>{@link #awaitAndFree}, on a thread that is neither the main thread nor the EDT: wait for the barrier. The main
 *       queue is serial and FIFO, so once the barrier ran, every earlier drain (including the one that ran
 *       {@code last}) has returned out of its stub. None of our main-thread work spins a nested run loop, so no drain
 *       can still be on the main thread's stack either.</li>
 *   <li>Only then, and only if {@code last} ran to the end, free the stubs and drop whatever is left in the queue. In
 *       any other case (timeout, a failed dispatch, {@code last} threw) the stubs are kept: a few hundred bytes and a
 *       class loader that cannot be unloaded, instead of a crash.</li>
 * </ol>
 * Closing is final: one gate per plugin load, so the number of stubs per load stays fixed.
 */
final class MainGate {
    interface Native {
        /** {@code dispatch_async_f(main queue, drain stub)}. */
        void dispatchDrain() throws Throwable;
        /** Queues a native (non-Java) marker behind everything dispatched so far; the handle goes to {@link #awaitBarrier}. */
        Object dispatchBarrier() throws Throwable;
        /** True once the marker ran on the main thread, false on timeout. */
        boolean awaitBarrier(Object barrier, long timeoutMs) throws Throwable;
        /** Frees every upcall stub (closes their arena). */
        void freeStubs() throws Throwable;
    }

    private final MainQueue queue;
    private final Native nat;
    private boolean closed;            // guarded by this
    private @Nullable Object barrier;  // guarded by this; taken by the one awaitAndFree that may free
    private volatile boolean lastRan, freed;

    MainGate(MainQueue queue, Native nat) { this.queue = queue; this.nat = nat; }

    /** Runs {@code r} on the main thread, in call order. Dropped silently once closed. Never throws. */
    void submit(Runnable r, Consumer<Throwable> onError) {
        Throwable failed;
        synchronized (this) {
            if (closed) return;
            failed = enqueue(r, onError);
        }
        if (failed != null) onError.accept(failed); // outside the lock: the handler may take other locks
    }

    /**
     * Queues {@code last} as the final main-thread task, then closes the gate and queues the barrier behind it.
     * True for the call that closed it; later calls do nothing.
     */
    boolean close(Runnable last, Consumer<Throwable> onError) {
        Throwable failed;
        synchronized (this) {
            if (closed) return false;
            failed = enqueue(() -> { last.run(); lastRan = true; }, onError);
            closed = true;
            if (failed == null) {
                try { barrier = nat.dispatchBarrier(); }
                catch (Throwable t) { failed = t; }
            }
        }
        if (failed != null) onError.accept(failed);
        return true;
    }

    /** Off the main thread and off the EDT: waits for the barrier, then frees the stubs if that is safe. True if freed. */
    boolean awaitAndFree(long timeoutMs) {
        Object b;
        synchronized (this) { b = barrier; barrier = null; }
        if (b == null) return false;
        boolean passed;
        try { passed = nat.awaitBarrier(b, timeoutMs); } catch (Throwable t) { passed = false; }
        if (!passed || !lastRan) return false;
        queue.clear();
        try { nat.freeStubs(); } catch (Throwable t) { return false; }
        freed = true;
        return true;
    }

    synchronized boolean isClosed() { return closed; }

    boolean isFreed() { return freed; }

    private @Nullable Throwable enqueue(Runnable r, Consumer<Throwable> onError) {
        queue.enqueue(r, onError);
        try { nat.dispatchDrain(); return null; }
        catch (Throwable t) { return t; }
    }
}
