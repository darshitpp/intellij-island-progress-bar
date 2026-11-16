package dev.darshit.islandbar.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class IslandCoordinatorTest {
    final List<String> events = Collections.synchronizedList(new ArrayList<>());
    final Map<String, List<TaskRow>> tasks = new HashMap<>();
    final List<Consumer<Throwable>> failures = new ArrayList<>();
    final List<IslandCoordinator.Actions> actions = new ArrayList<>();
    final List<Throwable> disabledBy = new ArrayList<>();
    final List<Runnable> edtQueue = new ArrayList<>();
    long now = 0;
    long delay = 500;
    boolean hideSetting = true;
    boolean hideThrows = false;
    int edtRuns = 0;
    /** Runs at once by default; set {@code queueEdt} to record the hop and run it later. */
    boolean queueEdt = false;

    final Executor edt = r -> {
        edtRuns++;
        if (queueEdt) edtQueue.add(r); else r.run();
    };

    final Function<Consumer<Throwable>, IslandCoordinator.Port> factory = onFail -> {
        failures.add(onFail);
        return new IslandCoordinator.Port() {
            public void show(IslandView v, IslandCoordinator.Actions a) { events.add("show " + fmt(v)); actions.add(a); }
            public void update(IslandView v) { events.add("update " + fmt(v)); }
            public void remove() { events.add("remove"); }
        };
    };

    final IslandCoordinator.IdeBar<String> ideBar = new IslandCoordinator.IdeBar<>() {
        public void hide(String p) {
            events.add("hide " + p);
            if (hideThrows) throw new IllegalStateException("no status bar");
        }
        public void restore(String p) { events.add("restore " + p); }
    };

    static String fmt(IslandView v) {
        return v.rows().stream().map(r -> String.valueOf(r.id())).collect(Collectors.joining(",", "[", "]"));
    }

    IslandCoordinator<String> withSource(Function<String, List<TaskRow>> src) {
        return new IslandCoordinator<>(src, factory, ideBar, () -> hideSetting, () -> delay, () -> now, edt, disabledBy::add);
    }

    IslandCoordinator<String> c = withSource(p -> tasks.getOrDefault(p, List.of()));

    void tick(String focused, String... open) { c.tick(List.of(open), focused); }

    static TaskRow row(long id) { return row(id, false, null); }

    static TaskRow row(long id, boolean paused, TaskControls controls) {
        return new TaskRow(id, 0.5, "t" + id, null, true, null, true, paused, false, controls);
    }

    List<String> portEvents() {
        return events.stream().filter(e -> !e.startsWith("hide") && !e.startsWith("restore")).toList();
    }

    /** Shows a pill for A (task 1) and clears the log. */
    void showA() {
        tasks.put("A", List.of(row(1)));
        tick("A", "A"); now = 500; tick("A", "A");
        assertEquals(List.of("restore A", "show [1]", "hide A"), events); // restore: no pill during the delay
        events.clear();
    }

    static final class RecordingControls implements TaskControls {
        final List<String> calls = new ArrayList<>();
        RuntimeException toThrow;
        public void cancel() { calls.add("cancel"); if (toThrow != null) throw toThrow; }
        public void pause() { calls.add("pause"); }
        public void resume() { calls.add("resume"); }
    }

    @Test void showsPortForFocusedProjectAfterDelay() {
        tasks.put("A", List.of(row(1)));
        tick("A", "A"); now = 499; tick("A", "A");
        assertTrue(portEvents().isEmpty());
        now = 500; tick("A", "A");
        assertEquals(List.of("show [1]"), portEvents());
    }

    @Test void neverUsesFirstOpenProject() {
        tasks.put("welcome", List.of(row(7)));
        tasks.put("real", List.of(row(1)));
        tick("real", "welcome", "real"); now = 500; tick("real", "welcome", "real");
        assertEquals(List.of("show [1]"), portEvents());
        assertTrue(events.contains("hide real"));
        assertFalse(events.contains("hide welcome"));
    }

    @Test void portFollowsFocusAcrossProjects() {
        tasks.put("A", List.of(row(1)));
        tasks.put("B", List.of(row(2)));
        tick("A", "A", "B"); now = 500; tick("A", "A", "B");
        events.clear();
        now = 600; tick("B", "A", "B");
        assertEquals(List.of("remove", "restore A", "show [2]", "hide B"), events);
    }

    @Test void removesPortAndRestoresIdeBarWhenNoTasks() {
        showA();
        tasks.clear(); now = 600; tick("A", "A");
        assertEquals(List.of("remove", "restore A"), events);
    }

    @Test void restoreCalledEvenIfNoPortWasShown() {
        tasks.put("A", List.of(row(1)));
        tick("A", "A");                         // within the delay: no pill
        tasks.clear(); now = 100; tick("A", "A");
        assertTrue(portEvents().isEmpty());
        assertTrue(events.contains("restore A"));
        assertFalse(events.contains("hide A"));
        events.clear();
        now = 200; tick("A", "A");              // idempotent: calling it again is harmless
        assertEquals(List.of("restore A"), events);
    }

    @Test void projectClosedRemovesPortAndRestores() {
        showA();
        now = 600; tick(null);
        assertEquals(List.of("remove", "restore A"), events);
    }

    @Test void actionsHideCancelPauseRunOnEdtAndUseCurrentRows() {
        RecordingControls first = new RecordingControls();
        RecordingControls current = new RecordingControls();
        tasks.put("A", List.of(row(1, false, first)));
        tick("A", "A"); now = 500; tick("A", "A");
        IslandCoordinator.Actions a = actions.get(0);

        tasks.put("A", List.of(row(1, false, current))); // fresh controls next poll, same row
        now = 600; tick("A", "A");

        queueEdt = true;
        a.cancel(1);
        assertTrue(current.calls.isEmpty(), "must not run before the EDT hop");
        edtQueue.removeFirst().run();
        assertEquals(List.of("cancel"), current.calls);
        assertTrue(first.calls.isEmpty(), "uses the current rows' controls");

        a.togglePause(1);
        edtQueue.removeFirst().run();
        assertEquals(List.of("cancel", "pause"), current.calls);

        tasks.put("A", List.of(row(1, true, current)));
        queueEdt = false; now = 700; tick("A", "A");
        queueEdt = true;
        a.togglePause(1);
        edtQueue.removeFirst().run();
        assertEquals(List.of("cancel", "pause", "resume"), current.calls);

        events.clear();
        a.hide(1);
        assertTrue(events.isEmpty(), "hide also hops to the EDT");
        edtQueue.removeFirst().run();
        assertEquals(List.of("remove", "restore A"), events);
        events.clear();
        queueEdt = false; now = 800; tick("A", "A");
        assertEquals(List.of("restore A"), events, "a hidden task stays hidden");
    }

    @Test void hideOneOfTwoUpdatesThePill() {
        tasks.put("A", List.of(row(1), row(2)));
        tick("A", "A"); now = 500; tick("A", "A");
        events.clear();
        actions.get(0).hide(1);
        assertEquals(List.of("update [2]", "hide A"), events);
    }

    @Test void staleIdActionsAreNoOps() {
        RecordingControls controls = new RecordingControls();
        controls.toThrow = new IllegalStateException("platform says no");
        tasks.put("A", List.of(row(1, false, controls)));
        tick("A", "A"); now = 500; tick("A", "A");
        IslandCoordinator.Actions a = actions.get(0);
        events.clear();

        assertDoesNotThrow(() -> a.cancel(1));
        assertEquals(List.of("cancel"), controls.calls);
        assertFalse(c.isDisabled());
        assertTrue(disabledBy.isEmpty());
        assertTrue(events.isEmpty(), "the pill stays");

        assertDoesNotThrow(() -> { a.cancel(99); a.togglePause(99); a.hide(99); });
        assertEquals(List.of("cancel"), controls.calls);
        assertTrue(events.isEmpty());

        now = 600; tick("A", "A");
        assertEquals(List.of("hide A"), events, "pill still up, no reshow");
    }

    @Test void actionsFromAnOldPortAreNoOps() {
        RecordingControls controls = new RecordingControls();
        tasks.put("A", List.of(row(1, false, controls)));
        tick("A", "A"); now = 500; tick("A", "A");
        tasks.clear(); now = 600; tick("A", "A");          // pill removed
        tasks.put("A", List.of(row(1, false, controls)));
        now = 700; tick("A", "A"); now = 1200; tick("A", "A"); // a new pill
        events.clear();
        actions.get(0).cancel(1);
        actions.get(0).hide(1);
        assertTrue(controls.calls.isEmpty());
        assertTrue(events.isEmpty());
    }

    @Test void portFailureRemovesRestoresAndDisables() {
        showA();
        RuntimeException cause = new RuntimeException("native");
        failures.get(0).accept(cause);
        assertEquals(List.of("remove", "restore A"), events);
        assertEquals(List.of(cause), disabledBy);
        assertTrue(c.isDisabled());
        events.clear();
        now = 3000; tick("A", "A");
        tasks.put("A", List.of(row(2))); tick("A", "A"); now = 5000; tick("A", "A");
        assertTrue(events.isEmpty());
        failures.get(0).accept(new RuntimeException("again"));
        assertEquals(1, disabledBy.size(), "reported once");
    }

    @Test void sourceLinkageErrorIsFatal() {
        int[] calls = {0};
        IslandCoordinator<String> bc = withSource(p -> {
            if (++calls[0] > 2) throw new NoSuchMethodError("StatusBarEx.getBackgroundProcessModels");
            return List.of(row(1));
        });
        bc.tick(List.of("A"), "A"); now = 500; bc.tick(List.of("A"), "A");
        events.clear();
        now = 600; bc.tick(List.of("A"), "A");
        assertEquals(List.of("remove", "restore A"), events);
        assertTrue(bc.isDisabled());
        assertEquals(1, disabledBy.size());
        assertInstanceOf(NoSuchMethodError.class, disabledBy.get(0));
        int before = calls[0];
        now = 2000; bc.tick(List.of("A"), "A");
        assertEquals(before, calls[0], "a disabled coordinator must not touch the source again");
    }

    @Test void transientSourceErrorIsNotFatal() {
        IslandCoordinator<String> bad = withSource(p -> { throw new IllegalStateException(); });
        assertDoesNotThrow(() -> bad.tick(List.of("A"), "A"));
        assertFalse(bad.isDisabled());
    }

    @Test void hideIdeBarFailureRestoresAndStopsHidingButKeepsPill() {
        hideThrows = true;
        tasks.put("A", List.of(row(1)));
        tick("A", "A"); events.clear(); now = 500; tick("A", "A");
        assertEquals(List.of("show [1]", "hide A", "restore A"), events);
        assertFalse(c.isDisabled());
        events.clear();
        tasks.put("A", List.of(row(1), row(2)));
        now = 600; tick("A", "A"); now = 1100; tick("A", "A");
        assertEquals(List.of("update [1,2]"), events, "never hides again, pill continues");
    }

    @Test void hideIdeBarSettingOffNeverHides() {
        hideSetting = false;
        tasks.put("A", List.of(row(1)));
        tick("A", "A"); now = 500; tick("A", "A"); now = 600; tick("A", "A");
        assertEquals(List.of("show [1]"), portEvents());
        assertTrue(events.stream().noneMatch(e -> e.startsWith("hide")));
    }

    @Test void tickWithEmptyOpenRemovesEverything() {
        tasks.put("A", List.of(row(1)));
        tasks.put("B", List.of(row(2)));
        tick("A", "A", "B"); now = 500; tick("A", "A", "B");
        events.clear();
        c.tick(List.of(), null);
        assertEquals(List.of("remove", "restore A"), events);
        events.clear();
        c.tick(List.of("A"), "A"); // reopened: state was dropped, so the full delay applies again
        assertTrue(portEvents().isEmpty());
    }

    @Test void restoreRunsThroughEdtWhenPortFailsOffEdt() throws Exception {
        showA();
        queueEdt = true;
        int hopsBefore = edtRuns;
        Thread appKit = new Thread(() -> failures.get(0).accept(new RuntimeException("main thread")));
        appKit.start(); appKit.join();
        assertEquals(List.of("remove"), events, "restore must not run directly on the failing thread");
        assertTrue(edtRuns > hopsBefore);
        assertTrue(c.isDisabled());
        for (Runnable r : List.copyOf(edtQueue)) r.run();
        assertEquals(List.of("remove", "restore A"), events);
    }

    @Test void restoreWorksAfterProjectClosed() {
        tasks.put("A", List.of(row(1)));
        tasks.put("B", List.of());
        tick("A", "A", "B"); now = 500; tick("A", "A", "B");
        events.clear();
        now = 600; tick("B", "B"); // A closed, not in open any more
        assertTrue(events.contains("restore A"));
        assertTrue(events.contains("remove"));
    }

    @Test void settingTurnedOffWhileHiddenRestores() {
        showA();
        hideSetting = false;
        now = 600; tick("A", "A");
        assertEquals(List.of("restore A"), events, "pill stays, IDE bar comes back");
        events.clear();
        now = 700; tick("A", "A");
        assertTrue(events.isEmpty(), "restored once, not every tick while the pill is up");
        hideSetting = true;
        now = 800; tick("A", "A");
        assertEquals(List.of("hide A"), events, "turning it back on hides again");
    }

    @Test void hideIsReappliedEachTickIdempotently() {
        showA();
        now = 600; tick("A", "A"); now = 700; tick("A", "A");
        assertEquals(List.of("hide A", "hide A"), events);
    }

    @Test void unchangedViewIsNotResent() {
        showA();
        now = 600; tick("A", "A");
        assertEquals(List.of(), portEvents());
    }

    @Test void focusInAnotherAppKeepsPillWithItsWindow() {
        showA();
        now = 600; tick(null, "A");
        assertEquals(List.of("hide A"), events);
        events.clear();
        tasks.clear(); now = 700; tick(null, "A");
        assertEquals(List.of("remove", "restore A"), events);
    }

    @Test void portShowThrowingLinkageErrorDisablesOnceAndNeverHidesTheIdeBar() {
        int[] shows = {0};
        List<Throwable> logged = new ArrayList<>();
        IslandCoordinator<String> bad = new IslandCoordinator<>(p -> tasks.getOrDefault(p, List.of()),
                onFail -> new IslandCoordinator.Port() {
                    public void show(IslandView v, IslandCoordinator.Actions a) { shows[0]++; throw new NoClassDefFoundError("Objc"); }
                    public void update(IslandView v) { throw new AssertionError(); }
                    public void remove() { throw new NoClassDefFoundError("Objc"); }
                }, ideBar, () -> true, () -> delay, () -> now, edt, logged::add);
        tasks.put("A", List.of(row(1)));
        bad.tick(List.of("A"), "A"); now = 500;
        assertDoesNotThrow(() -> bad.tick(List.of("A"), "A"));
        assertDoesNotThrow(() -> { for (int i = 0; i < 5; i++) bad.tick(List.of("A"), "A"); });
        assertTrue(bad.isDisabled());
        assertEquals(1, shows[0]);
        assertEquals(1, logged.size());
        assertInstanceOf(NoClassDefFoundError.class, logged.get(0));
        assertFalse(events.contains("hide A"));
        assertEquals(2, events.stream().filter("restore A"::equals).count()); // pre-delay + the disable restore
    }

    @Test void portUpdateThrowingDisablesOnceAndRestoresTheHiddenBar() {
        List<Throwable> logged = new ArrayList<>();
        boolean[] boom = {false};
        IslandCoordinator<String> bad = new IslandCoordinator<>(p -> tasks.getOrDefault(p, List.of()),
                onFail -> new IslandCoordinator.Port() {
                    public void show(IslandView v, IslandCoordinator.Actions a) { events.add("show"); }
                    public void update(IslandView v) { if (boom[0]) throw new IllegalStateException("x"); }
                    public void remove() { events.add("remove"); }
                }, ideBar, () -> true, () -> delay, () -> now, edt, logged::add);
        tasks.put("A", List.of(row(1)));
        bad.tick(List.of("A"), "A"); now = 500; bad.tick(List.of("A"), "A");
        assertTrue(events.contains("hide A"));
        events.clear();
        boom[0] = true;
        tasks.put("A", List.of(row(1), row(2)));
        bad.tick(List.of("A"), "A"); now = 1000;
        bad.tick(List.of("A"), "A");
        bad.tick(List.of("A"), "A");
        assertTrue(bad.isDisabled());
        assertEquals(1, logged.size());
        assertEquals(List.of("hide A", "remove", "restore A"), events); // hide A: an earlier tick
    }
}
