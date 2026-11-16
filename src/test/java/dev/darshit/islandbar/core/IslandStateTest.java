package dev.darshit.islandbar.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class IslandStateTest {
    private final AtomicLong delay = new AtomicLong(500);
    private final IslandState state = new IslandState(delay::get);

    private static TaskRow row(long id) { return row(id, 0.5, false); }

    private static TaskRow row(long id, Double fraction, boolean stopping) {
        return new TaskRow(id, fraction, "t" + id, null, true, null, stopping, null);
    }

    @Test void nothingShownBeforeDelay() {
        assertEquals(IslandView.HIDDEN, state.update(0, List.of(row(1))));
        assertEquals(IslandView.HIDDEN, state.update(499, List.of(row(1))));
        assertEquals(1, state.update(500, List.of(row(1))).primary().id());
    }

    @Test void emptyListIsHidden() {
        assertEquals(IslandView.HIDDEN, state.update(1000, List.of()));
    }

    @Test void primaryIsOldestShownTask() {
        state.update(0, List.of(row(1)));
        state.update(100, List.of(row(1), row(2)));
        IslandView v = state.update(700, List.of(row(1), row(2)));
        assertEquals(1, v.primary().id());
        assertEquals(2, v.rows().size());
        assertTrue(v.showChevron());
    }

    @Test void chevronOnlyWithTwoOrMoreShownTasks() {
        state.update(0, List.of(row(1)));
        IslandView one = state.update(600, List.of(row(1)));
        assertFalse(one.showChevron());
        IslandView young = state.update(700, List.of(row(1), row(2)));
        assertEquals(List.of(1L), young.rows().stream().map(TaskRow::id).toList());
        assertFalse(young.showChevron());
    }

    @Test void hideRemovesTaskFromPillAndList() {
        state.update(0, List.of(row(1), row(2)));
        state.update(600, List.of(row(1), row(2)));
        state.hide(1);
        IslandView v = state.update(700, List.of(row(1), row(2)));
        assertEquals(2, v.primary().id());
        assertEquals(List.of(2L), v.rows().stream().map(TaskRow::id).toList());
        assertFalse(v.showChevron());
    }

    @Test void hidingLastTaskHidesPill() {
        state.update(0, List.of(row(1)));
        state.update(600, List.of(row(1)));
        state.hide(1);
        assertEquals(IslandView.HIDDEN, state.update(700, List.of(row(1))));
    }

    @Test void hiddenTaskReturnsAsNewWhenItDisappearsAndReappears() {
        state.update(0, List.of(row(1)));
        state.update(600, List.of(row(1)));
        state.hide(1);
        state.update(700, List.of());
        assertEquals(IslandView.HIDDEN, state.update(800, List.of(row(1))));
        assertEquals(1, state.update(1300, List.of(row(1))).primary().id());
    }

    @Test void staleHideIsNoOp() {
        state.update(0, List.of(row(1)));
        state.hide(99);
        assertEquals(1, state.update(600, List.of(row(1))).primary().id());
    }

    @Test void delayReadOnEveryUpdate() {
        assertEquals(IslandView.HIDDEN, state.update(0, List.of(row(1))));
        delay.set(100);
        assertEquals(1, state.update(100, List.of(row(1))).primary().id());
    }

    @Test void rowsKeepStoppingFlag() {
        state.update(0, List.of(row(1, 0.5, true), row(2, 0.5, false)));
        IslandView v = state.update(600, List.of(row(1, 0.5, true), row(2, 0.5, false)));
        assertTrue(v.rows().get(0).stopping());
        assertFalse(v.rows().get(1).stopping());
    }

    @Test void equalityIgnoresControls() {
        TaskControls a = new TaskControls() {
            public void cancel() {}
        };
        TaskControls b = new TaskControls() {
            public void cancel() {}
        };
        TaskRow r1 = new TaskRow(1, 0.5, "n", "d", true, "tip", false, a);
        TaskRow r2 = new TaskRow(1, 0.5, "n", "d", true, "tip", false, b);
        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }

    @Test void indeterminateAndNaNFractionPassThroughAsNull() {
        List<TaskRow> in = List.of(row(1, Double.NaN, false), row(2, 1.7, false),
                row(3, -0.2, false), row(4, null, false));
        state.update(0, in);
        List<TaskRow> out = state.update(600, in).rows();
        assertNull(out.get(0).fraction());
        assertEquals(1.0, out.get(1).fraction());
        assertEquals(0.0, out.get(2).fraction());
        assertNull(out.get(3).fraction());
    }
}
