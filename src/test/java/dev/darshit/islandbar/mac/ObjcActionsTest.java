package dev.darshit.islandbar.mac;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The button callbacks are static (one target per plugin load), so they must not keep a removed panel reachable.
 * Real Objective-C runtime (no AppKit, no main thread needed: NSObject messaging works on any thread). Never calls
 * {@link Objc#shutdown}, which is final for the JVM.
 */
class ObjcActionsTest {
    @BeforeAll static void macOnly() {
        assumeTrue(System.getProperty("os.name", "").startsWith("Mac") && "aarch64".equals(System.getProperty("os.arch")),
                "needs the Objective-C runtime on Apple silicon");
    }

    final AtomicInteger chevron = new AtomicInteger();

    private void click(java.lang.foreign.MemorySegment target) { Objc.v(target, "chevron:", Objc.NIL); }

    @Test void clearingWithTheOwnerDropsTheCallbacks() {
        Object panel = new Object();
        var target = Objc.actionTarget(panel, chevron::incrementAndGet, id -> {}, id -> {}, id -> {});
        click(target);
        assertEquals(1, chevron.get());
        Objc.clearActions(panel);
        click(target); // still a valid target, now with no-op callbacks
        assertEquals(1, chevron.get());
    }

    @Test void anOlderPanelCannotClearANewerOnesCallbacks() {
        Object older = new Object(), newer = new Object();
        Objc.actionTarget(older, () -> fail("older"), id -> {}, id -> {}, id -> {});
        var target = Objc.actionTarget(newer, chevron::incrementAndGet, id -> {}, id -> {}, id -> {});
        Objc.clearActions(older); // its remove() ran after the newer panel was created
        click(target);
        assertEquals(1, chevron.get());
        Objc.clearActions(newer);
    }

    @Test void oneTargetPerLoad() {
        Object a = new Object(), b = new Object();
        var first = Objc.actionTarget(a, () -> {}, id -> {}, id -> {}, id -> {});
        var second = Objc.actionTarget(b, () -> {}, id -> {}, id -> {}, id -> {});
        assertEquals(first, second);
        Objc.clearActions(b);
    }
}
