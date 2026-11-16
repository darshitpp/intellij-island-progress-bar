package dev.darshit.islandbar.mac;

import com.intellij.openapi.diagnostic.Logger;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import org.jetbrains.annotations.Nullable;

/**
 * Minimal Objective-C runtime bridge over FFM. Only touch AppKit objects from the main thread (see {@link #onMain}).
 * Apple silicon only: struct returns (NSRect) go through plain {@code objc_msgSend}, there is no {@code _stret}.
 */
final class Objc {
    private static final Logger LOG = Logger.getInstance(Objc.class);
    static final MemorySegment NIL = MemorySegment.NULL;

    private static final Linker L = Linker.nativeLinker();
    // an automatic arena: the library handle goes (dlclose; libobjc itself never unloads) once these classes are unloaded
    private static final SymbolLookup OBJC = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", Arena.ofAuto());
    private static final MemorySegment MSG_SEND = OBJC.find("objc_msgSend").orElseThrow();
    private static final ConcurrentHashMap<FunctionDescriptor, MethodHandle> HANDLES = new ConcurrentHashMap<>();

    private static final MethodHandle GET_CLASS = fn("objc_getClass", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle SEL = fn("sel_registerName", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle ALLOC_CLASS = fn("objc_allocateClassPair",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
    private static final MethodHandle REGISTER_CLASS = fn("objc_registerClassPair", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    private static final MethodHandle ADD_METHOD = fn("class_addMethod",
            FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle REPLACE_METHOD = fn("class_replaceMethod",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle GET_IMP = fn("class_getMethodImplementation",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    private static MethodHandle fn(String name, FunctionDescriptor fd) {
        return L.downcallHandle(OBJC.find(name).orElseThrow(), fd);
    }

    // classes and selectors are interned by the runtime, so cache them (no per-call native allocation)
    private static final ConcurrentHashMap<String, MemorySegment> CLASSES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, MemorySegment> SELECTORS = new ConcurrentHashMap<>();

    static MemorySegment cls(String name) {
        return CLASSES.computeIfAbsent(name, n -> {
            try (Arena a = Arena.ofConfined()) { return (MemorySegment) GET_CLASS.invoke(a.allocateFrom(n)); }
            catch (Throwable t) { throw new RuntimeException(t); }
        });
    }

    static MemorySegment sel(String name) {
        return SELECTORS.computeIfAbsent(name, n -> {
            try (Arena a = Arena.ofConfined()) { return (MemorySegment) SEL.invoke(a.allocateFrom(n)); }
            catch (Throwable t) { throw new RuntimeException(t); }
        });
    }

    /**
     * Generic {@code objc_msgSend}. {@code ret == null} is void. A {@link GroupLayout} return (a struct such as
     * {@link ArgLayouts#RECT}) is written into memory from {@code alloc}, which the downcall handle takes as its
     * leading argument; {@code alloc} is ignored for every other return type.
     */
    private static Object call(SegmentAllocator alloc, MemoryLayout ret, MemorySegment recv, String selector, Object... args) {
        try {
            boolean struct = ret instanceof GroupLayout;
            int off = struct ? 1 : 0;
            MemoryLayout[] ls = new MemoryLayout[args.length + 2];
            Object[] vs = new Object[args.length + 2 + off];
            if (struct) vs[0] = alloc;
            ls[0] = ValueLayout.ADDRESS; vs[off] = recv;
            ls[1] = ValueLayout.ADDRESS; vs[off + 1] = sel(selector);
            for (int i = 0; i < args.length; i++) {
                ls[i + 2] = ArgLayouts.layoutOf(args[i]);
                vs[i + 2 + off] = ArgLayouts.valueOf(args[i]);
            }
            FunctionDescriptor fd = ret == null ? FunctionDescriptor.ofVoid(ls) : FunctionDescriptor.of(ret, ls);
            return HANDLES.computeIfAbsent(fd, k -> L.downcallHandle(MSG_SEND, k)).invokeWithArguments(vs);
        } catch (Throwable t) { throw new RuntimeException(selector, t); }
    }

    private static Object call(MemoryLayout ret, MemorySegment recv, String selector, Object... args) {
        return call(null, ret, recv, selector, args);
    }

    /** returns id */
    static MemorySegment p(MemorySegment recv, String sel, Object... args) { return (MemorySegment) call(ValueLayout.ADDRESS, recv, sel, args); }
    /** returns BOOL (a C {@code bool} on Apple silicon) */
    static boolean b(MemorySegment recv, String sel, Object... args) { return (Boolean) call(ValueLayout.JAVA_BOOLEAN, recv, sel, args); }
    /** returns NSInteger / NSUInteger */
    static long n(MemorySegment recv, String sel, Object... args) { return (Long) call(ValueLayout.JAVA_LONG, recv, sel, args); }
    static void v(MemorySegment recv, String sel, Object... args) { call(null, recv, sel, args); }
    /** returns float (e.g. {@code -[CALayer opacity]}) */
    static float fl(MemorySegment recv, String sel, Object... args) { return (Float) call(ValueLayout.JAVA_FLOAT, recv, sel, args); }

    /** An NSRect-returning, no-argument message ({@code frame}, {@code visibleFrame}, {@code bounds}). GC-managed memory. */
    static ArgLayouts.Rect rectOf(MemorySegment recv, String sel) {
        return new ArgLayouts.Rect((MemorySegment) call(Arena.ofAuto(), ArgLayouts.RECT, recv, sel));
    }

    /** An NSSize-returning message, as {width, height} (a struct return through plain objc_msgSend on Apple silicon). */
    static double[] sizeOf(MemorySegment recv, String sel, Object... args) {
        MemorySegment s = (MemorySegment) call(Arena.ofAuto(), ArgLayouts.SIZE, recv, sel, args);
        return new double[]{s.get(ValueLayout.JAVA_DOUBLE, 0), s.get(ValueLayout.JAVA_DOUBLE, 8)};
    }

    /**
     * {@code [screen safeAreaInsets].top} (NSEdgeInsets = {top, left, bottom, right}, four CGFloats; a struct return
     * through plain {@code objc_msgSend} on Apple silicon). 0 before macOS 12, where the selector does not exist.
     * Main thread.
     */
    static double safeAreaTop(MemorySegment screen) {
        if (!b(screen, "respondsToSelector:", sel("safeAreaInsets"))) return 0;
        MemorySegment insets = (MemorySegment) call(Arena.ofAuto(), ArgLayouts.EDGE_INSETS, screen, "safeAreaInsets");
        return insets.get(ValueLayout.JAVA_DOUBLE, 0);
    }

    /** The runtime copies the bytes, so the temporary native string can be freed right away. */
    static MemorySegment nsString(String s) {
        try (Arena a = Arena.ofConfined()) { return p(cls("NSString"), "stringWithUTF8String:", a.allocateFrom(s)); }
    }

    static ArgLayouts.Rect rect(double x, double y, double w, double h) { return ArgLayouts.rect(x, y, w, h); }

    // ---- hot path: typed, cached handles called with invokeExact (no boxing, no varargs, no descriptor lookup) ----
    private static final MethodHandle MSG_ID = L.downcallHandle(MSG_SEND,
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle MSG_VOID = L.downcallHandle(MSG_SEND,
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle MSG_VOID_ID = L.downcallHandle(MSG_SEND,
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MemorySegment SEL_LAYER = sel("layer");
    private static final MemorySegment SEL_SET_CONTENTS = sel("setContents:");
    private static final MemorySegment SEL_RELEASE = sel("release");

    /** {@code [[view layer] setContents:cgImage]}; the layer retains the image. A nil layer is a no-op. Main thread. */
    static void setLayerContents(MemorySegment view, MemorySegment cgImage) {
        try {
            MemorySegment layer = (MemorySegment) MSG_ID.invokeExact(view, SEL_LAYER);
            if (layer.equals(NIL)) return;
            MemorySegment img = cgImage == null ? NIL : cgImage; // a local: a ?: argument to invokeExact is typed Object
            MSG_VOID_ID.invokeExact(layer, SEL_SET_CONTENTS, img);
        } catch (RuntimeException | Error e) { throw e; }
        catch (Throwable t) { throw new RuntimeException("setContents:", t); }
    }

    /** {@code [obj release]}; nil is ignored. */
    static void release(MemorySegment obj) {
        if (obj == null || obj.equals(NIL)) return;
        try { MSG_VOID.invokeExact(obj, SEL_RELEASE); }
        catch (RuntimeException | Error e) { throw e; }
        catch (Throwable t) { throw new RuntimeException("release", t); }
    }

    // ---- native callbacks: a fixed set of upcall stubs per plugin load, all in one closeable arena ----
    //
    // Teardown (see MainGate for why this order is safe), started by shutdown() after the last panel's remove() was
    // queued:
    //   1. main thread, last queued task: point the four button selectors at a native no-op (NSObject's -self), so
    //      nothing can enter an action stub any more; release the button target; clear the callbacks (they hold
    //      the panel, coordinator and controller); release CoreGraphics' colour space.
    //   2. the gate is closed (later onMain calls are dropped) and a native barrier is queued behind task 1.
    //   3. a short-lived daemon thread waits for the barrier, then closes STUBS, which frees the drain stub and the
    //      four action stubs and their references to this class. If the barrier never comes, the stubs are kept.
    // The ObjC class itself stays registered under a stable name (classes cannot be safely disposed while AppKit may
    // still cache them); the next plugin load finds it and re-points its four methods at its own stubs.

    /** The drain stub and the four action stubs; closed only by the teardown thread, after the barrier. */
    private static final Arena STUBS = Arena.ofShared();

    private static final MainQueue MAIN = new MainQueue(t -> LOG.warn("island main-queue error handler failed", t));
    private static final MemorySegment MAIN_Q = L.defaultLookup().find("_dispatch_main_q").orElseThrow();
    private static final MethodHandle DISPATCH_ASYNC = L.downcallHandle(L.defaultLookup().find("dispatch_async_f").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MemorySegment DRAIN = drainStub();
    private static final MainGate GATE = new MainGate(MAIN, new DispatchNative());
    private static final long TEARDOWN_TIMEOUT_MS = 5_000;

    private static MemorySegment drainStub() {
        try {
            MethodHandle h = MethodHandles.lookup().findStatic(Objc.class, "drain", MethodType.methodType(void.class, MemorySegment.class));
            return L.upcallStub(h, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS), STUBS);
        } catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }

    @SuppressWarnings("unused") // called from native; MainQueue.runOne never throws
    private static void drain(MemorySegment ctx) { MAIN.runOne(); }

    /** Runs on the AppKit main thread, in call order. Never throws; failures go to {@code onError}. Dropped after {@link #shutdown}. */
    static void onMain(Runnable r, Consumer<Throwable> onError) { GATE.submit(r, onError); }

    /** libdispatch: drains through the stub; the barrier is {@code dispatch_semaphore_signal} itself (native, no stub). */
    private static final class DispatchNative implements MainGate.Native {
        private final MethodHandle semCreate = libSystem("dispatch_semaphore_create",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        private final MemorySegment semSignal = L.defaultLookup().find("dispatch_semaphore_signal").orElseThrow();
        private final MethodHandle semWait = libSystem("dispatch_semaphore_wait",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        private final MethodHandle time = libSystem("dispatch_time",
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
        private final MethodHandle dispatchRelease = libSystem("dispatch_release", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

        private static MethodHandle libSystem(String name, FunctionDescriptor fd) {
            return L.downcallHandle(L.defaultLookup().find(name).orElseThrow(), fd);
        }

        @Override public void dispatchDrain() throws Throwable { DISPATCH_ASYNC.invokeExact(MAIN_Q, NIL, DRAIN); }

        @Override public Object dispatchBarrier() throws Throwable {
            MemorySegment sem = (MemorySegment) semCreate.invokeExact(0L);
            if (sem.equals(NIL)) throw new IllegalStateException("dispatch_semaphore_create failed");
            // void (*)(void *) called with the semaphore: dispatch_semaphore_signal's long result is simply ignored
            DISPATCH_ASYNC.invokeExact(MAIN_Q, sem, semSignal);
            return sem;
        }

        @Override public boolean awaitBarrier(Object barrier, long timeoutMs) throws Throwable {
            MemorySegment sem = (MemorySegment) barrier;
            long deadline = (long) time.invokeExact(0L /* DISPATCH_TIME_NOW */, timeoutMs * 1_000_000L);
            boolean passed = (long) semWait.invokeExact(sem, deadline) == 0;
            if (passed) dispatchRelease.invokeExact(sem); // on timeout the signal may still come: keep it alive
            return passed;
        }

        @Override public void freeStubs() { STUBS.close(); }
    }

    /**
     * Final for this plugin load: queues the main-thread cleanup behind everything queued so far (call it after every
     * panel's {@code remove()}), then frees the stubs on a short-lived daemon thread once the main thread has passed
     * them. Never blocks. Idempotent.
     */
    static void shutdown() {
        if (!GATE.close(Objc::releaseOnMain, t -> LOG.warn("Island Progress Bar: native cleanup failed; keeping the native callbacks", t))) return;
        Thread t = new Thread(() -> {
            if (GATE.awaitAndFree(TEARDOWN_TIMEOUT_MS)) LOG.info("Island Progress Bar: native callbacks released");
            else LOG.warn("Island Progress Bar: the main thread did not confirm the native cleanup; keeping the native"
                    + " callbacks (the plugin can only be fully unloaded by restarting the IDE)");
        }, "Island Progress Bar native teardown");
        t.setDaemon(true);
        t.setContextClassLoader(null);
        t.start();
    }

    /** Main thread, the last task: after it no message can reach an action stub. Runs inside the drain stub, which stays valid. */
    private static void releaseOnMain() {
        synchronized (Objc.class) {
            MemorySegment c = targetClass, t = actionTarget;
            if (c != null) {
                MemorySegment noOp = (MemorySegment) invoke(GET_IMP, cls("NSObject"), sel("self")); // -(id)self: native, harmless
                if (noOp.equals(NIL)) throw new IllegalStateException("no -[NSObject self]");
                for (String selector : SELECTORS_IN_ORDER) invoke(REPLACE_METHOD, c, sel(selector), noOp, TYPES_NOW);
            }
            actionTarget = null;
            targetClass = null;
            actionOwner = null;
            clearCallbacks();
            if (t != null) release(t); // buttons hold it unretained; every panel set their targets to nil in remove()
        }
        CoreGraphics.releaseShared();
    }

    // ---- button target: one ObjC class under a stable name, reused across plugin loads; four selectors/stubs per load ----
    private static final String TARGET_CLASS = "DevDarshitIslandBarTarget";
    private static final String[] SELECTORS_IN_ORDER = {"chevron:", "cancel:", "pause:", "hide:"};
    private static final String[] JAVA_METHODS = {"chevronCalled", "cancelCalled", "pauseCalled", "hideCalled"};
    private static final MemorySegment TYPES_NOW = NIL; // class_replaceMethod ignores types when the method exists
    private static final LongConsumer NO_TASK = id -> {};
    private static final Runnable NOTHING = () -> {};
    private static volatile Runnable chevronAction = NOTHING;
    private static volatile LongConsumer cancelAction = NO_TASK;
    private static volatile LongConsumer pauseAction = NO_TASK;
    private static volatile LongConsumer hideAction = NO_TASK;
    private static @Nullable Object actionOwner;          // guarded by Objc.class: the panel whose callbacks are set
    private static @Nullable MemorySegment actionTarget;  // guarded by Objc.class
    private static @Nullable MemorySegment targetClass;   // guarded by Objc.class

    /**
     * The shared target for the pill's buttons. Selectors {@code chevron:}, {@code cancel:}, {@code pause:},
     * {@code hide:}; the last three pass {@code [sender tag]} as the task id. Callbacks run on the AppKit main
     * thread; anything touching Swing or the platform must hop to the EDT itself. Exceptions are caught and logged.
     * {@code owner} is the panel; {@link #clearActions} with the same owner drops the callbacks. Main thread.
     */
    static synchronized MemorySegment actionTarget(Object owner, Runnable onChevron, LongConsumer onCancel,
                                                   LongConsumer onTogglePause, LongConsumer onHide) {
        if (GATE.isClosed()) throw new IllegalStateException("the native bridge has been shut down");
        actionOwner = owner;
        chevronAction = onChevron;
        cancelAction = onCancel;
        pauseAction = onTogglePause;
        hideAction = onHide;
        if (actionTarget != null) return actionTarget;
        try (Arena strings = Arena.ofConfined()) { // the runtime copies class names and type strings
            MemorySegment c = (MemorySegment) GET_CLASS.invoke(strings.allocateFrom(TARGET_CLASS));
            boolean fresh = c.equals(NIL);
            if (fresh) {
                c = (MemorySegment) ALLOC_CLASS.invoke(cls("NSObject"), strings.allocateFrom(TARGET_CLASS), 0L);
                if (c.equals(NIL)) throw new IllegalStateException("objc_allocateClassPair failed for " + TARGET_CLASS);
            }
            MemorySegment types = strings.allocateFrom("v@:@");
            for (int i = 0; i < SELECTORS_IN_ORDER.length; i++) {
                MemorySegment stub = actionStub(JAVA_METHODS[i]);
                if (fresh) {
                    boolean ok = (boolean) ADD_METHOD.invokeExact(c, sel(SELECTORS_IN_ORDER[i]), stub, types);
                    if (!ok) throw new IllegalStateException("class_addMethod failed for " + SELECTORS_IN_ORDER[i]);
                } else { // left by an earlier plugin load, its methods pointing at a no-op: point them at our stubs
                    invoke(REPLACE_METHOD, c, sel(SELECTORS_IN_ORDER[i]), stub, types);
                }
            }
            if (fresh) REGISTER_CLASS.invoke(c);
            targetClass = c;
            actionTarget = p(p(c, "alloc"), "init");
            return actionTarget;
        } catch (RuntimeException | Error e) { throw e; }
        catch (Throwable t) { throw new RuntimeException(t); }
    }

    /** Main thread: the panel {@code owner} is gone, so its callbacks (and what they hold) must not stay reachable. */
    static synchronized void clearActions(Object owner) {
        if (actionOwner != owner) return; // a newer panel already set its own
        actionOwner = null;
        clearCallbacks();
    }

    private static void clearCallbacks() {
        chevronAction = NOTHING;
        cancelAction = NO_TASK;
        pauseAction = NO_TASK;
        hideAction = NO_TASK;
    }

    private static MemorySegment actionStub(String javaMethod) throws ReflectiveOperationException {
        MethodHandle imp = MethodHandles.lookup().findStatic(Objc.class, javaMethod,
                MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class));
        return L.upcallStub(imp, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), STUBS);
    }

    private static Object invoke(MethodHandle h, Object... args) {
        try { return h.invokeWithArguments(args); }
        catch (RuntimeException | Error e) { throw e; }
        catch (Throwable t) { throw new RuntimeException(t); }
    }

    // Called from native. Nothing may escape an upcall (the JVM would crash), so every body catches Throwable.
    @SuppressWarnings("unused")
    private static void chevronCalled(MemorySegment self, MemorySegment cmd, MemorySegment sender) {
        try { chevronAction.run(); } catch (Throwable t) { LOG.warn("chevron action failed", t); }
    }

    @SuppressWarnings("unused")
    private static void cancelCalled(MemorySegment self, MemorySegment cmd, MemorySegment sender) {
        try { cancelAction.accept(n(sender, "tag")); } catch (Throwable t) { LOG.warn("cancel action failed", t); }
    }

    @SuppressWarnings("unused")
    private static void pauseCalled(MemorySegment self, MemorySegment cmd, MemorySegment sender) {
        try { pauseAction.accept(n(sender, "tag")); } catch (Throwable t) { LOG.warn("pause action failed", t); }
    }

    @SuppressWarnings("unused")
    private static void hideCalled(MemorySegment self, MemorySegment cmd, MemorySegment sender) {
        try { hideAction.accept(n(sender, "tag")); } catch (Throwable t) { LOG.warn("hide action failed", t); }
    }
}
