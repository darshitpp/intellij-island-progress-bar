package dev.darshit.islandbar.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PillStyleTest {
    @Test void styleAOnANotchedScreenFallsBackToB() {
        assertEquals(PillStyle.B, PillStyle.effective(PillStyle.A, true));
    }

    @Test void styleAWithoutANotchStaysA() {
        assertEquals(PillStyle.A, PillStyle.effective(PillStyle.A, false));
    }

    @Test void stylesBAndCAreNeverChanged() {
        for (boolean notch : new boolean[]{false, true}) {
            assertEquals(PillStyle.B, PillStyle.effective(PillStyle.B, notch));
            assertEquals(PillStyle.C, PillStyle.effective(PillStyle.C, notch));
        }
    }

    /** NSScreen: a screen has a camera housing iff its top safe-area inset is non-zero. */
    @Test void notchIsAPositiveTopInset() {
        assertFalse(PillStyle.hasNotch(0));
        assertTrue(PillStyle.hasNotch(32));
        assertTrue(PillStyle.hasNotch(38));
        assertTrue(PillStyle.hasNotch(0.5));
    }

    /** Notch simulation with realistic MacBook top safe-area insets (pt): A is drawn as B, B and C are unchanged. */
    @Test void effectiveStyleWithRealisticNotchInsets() {
        for (double inset : new double[]{32, 38}) {
            boolean notch = PillStyle.hasNotch(inset);
            assertEquals(PillStyle.B, PillStyle.effective(PillStyle.A, notch), "A at inset " + inset);
            assertEquals(PillStyle.B, PillStyle.effective(PillStyle.B, notch), "B at inset " + inset);
            assertEquals(PillStyle.C, PillStyle.effective(PillStyle.C, notch), "C at inset " + inset);
        }
        assertEquals(PillStyle.A, PillStyle.effective(PillStyle.A, PillStyle.hasNotch(0)), "external screen keeps A");
    }

    @Test void negativeOrNaNInsetIsNoNotch() {
        assertFalse(PillStyle.hasNotch(-1));
        assertFalse(PillStyle.hasNotch(Double.NaN));
    }
}
