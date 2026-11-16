package dev.darshit.islandbar.ide;

import static org.junit.jupiter.api.Assertions.*;

import dev.darshit.islandbar.core.BarRenderer;
import dev.darshit.islandbar.core.PillPalette;
import dev.darshit.islandbar.core.PillStyle;
import dev.darshit.islandbar.core.TaskRow;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FramedIslandTest {
    private static FramedIsland island() {
        return new FramedIsland(t -> { throw new AssertionError(t); }, PillStyle.B, () -> 30,
                () -> PillPalette.from(new PillPalette.IdeColors(0xFF2B2D30, 0xFFDFE1E5, 0xFF868A91, 0xFF393B40, true)),
                (w, h) -> new BarRenderer(w, h, c -> true, t -> {}, () -> null), () -> true);
    }

    private static TaskRow row(long id, double f) {
        return new TaskRow(id, f, "Task " + id, null, true, null, false, false, false, null);
    }

    /** A stalled row whose slot the panel cleared (rows shifted, or hidden then shown) must get a frame again. */
    @Test void clearedRowIsRepaintedEvenWhenUnchanged() {
        FramedIsland fi = island();
        List<TaskRow> rows = List.of(row(1, 0.5), row(2, 0.25));
        assertEquals(2, fi.paintRows(rows).size());
        assertTrue(fi.paintRows(rows).isEmpty(), "unchanged rows send nothing");

        fi.rowCleared(2);
        Map<Long, int[]> out = fi.paintRows(rows);
        assertEquals(java.util.Set.of(2L), out.keySet());
        assertTrue(fi.paintRows(rows).isEmpty());
    }
}
