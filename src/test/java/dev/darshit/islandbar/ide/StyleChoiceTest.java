package dev.darshit.islandbar.ide;

import static org.junit.jupiter.api.Assertions.*;

import dev.darshit.islandbar.core.PillStyle;
import org.junit.jupiter.api.Test;

class StyleChoiceTest {
    @Test void withoutANotchEveryStyleIsSelectableAndThereIsNoNote() {
        StyleChoice c = StyleChoice.of(false);
        for (PillStyle s : PillStyle.values()) assertTrue(c.selectable(s));
        for (PillStyle s : PillStyle.values()) assertFalse(c.showNotchNote(s));
    }

    @Test void withANotchStyleAIsNotSelectableAndTheNoteShows() {
        StyleChoice c = StyleChoice.of(true);
        assertFalse(c.selectable(PillStyle.A));
        assertTrue(c.selectable(PillStyle.B));
        assertTrue(c.selectable(PillStyle.C));
        assertTrue(c.showNotchNote(PillStyle.A));
        assertFalse(c.showNotchNote(PillStyle.B));
        assertFalse(c.showNotchNote(PillStyle.C));
    }

    /** Notch simulation from realistic MacBook top safe-area insets (pt). */
    @Test void realisticNotchInsetsDisableA() {
        for (double inset : new double[]{32, 38}) {
            StyleChoice c = StyleChoice.of(PillStyle.hasNotch(inset));
            assertFalse(c.selectable(PillStyle.A), "inset " + inset);
            assertTrue(c.showNotchNote(PillStyle.A), "inset " + inset);
        }
        assertTrue(StyleChoice.of(PillStyle.hasNotch(0)).selectable(PillStyle.A));
    }

    /** Each card previews what will actually appear: A as B on a notched Mac, everything else as itself. */
    @Test void previewsShowTheEffectiveStyle() {
        StyleChoice notch = StyleChoice.of(true), none = StyleChoice.of(false);
        assertEquals(PillStyle.B, notch.preview(PillStyle.A));
        assertEquals(PillStyle.B, notch.preview(PillStyle.B));
        assertEquals(PillStyle.C, notch.preview(PillStyle.C));
        for (PillStyle s : PillStyle.values()) assertEquals(s, none.preview(s));
    }
}
