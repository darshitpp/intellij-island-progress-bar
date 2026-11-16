package dev.darshit.islandbar.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class PillPaletteTest {
    private static final PillPalette.IdeColors DARK = new PillPalette.IdeColors(0xFF2B2D30, 0xFFDFE1E5, 0xFF868A91, 0xFF393B40, true);
    private static final PillPalette.IdeColors LIGHT = new PillPalette.IdeColors(0xFFF7F8FA, 0xFF000000, 0xFF6C707E, 0xFFEBECF0, false);
    private static final PillPalette.IdeColors HC = new PillPalette.IdeColors(0xFF000000, 0xFFFFFFFF, 0xFFCCCCCC, 0xFFFFFFFF, true);

    private static int ch(int argb, int shift) { return (argb >> shift) & 0xFF; }

    @Test void textHasAtLeast4_5ContrastOnBackground() {
        for (var c : List.of(DARK, LIGHT, HC)) {
            var p = PillPalette.from(c);
            assertTrue(PillPalette.contrast(p.text(), p.background()) >= 4.5);
        }
    }

    /** Default Light: Label.disabledForeground #8C8C8C on #F7F8FA is only ~3.2:1. */
    private static final PillPalette.IdeColors DEFAULT_LIGHT =
            new PillPalette.IdeColors(0xFFF7F8FA, 0xFF000000, 0xFF8C8C8C, 0xFFEBECF0, false);

    @Test void secondaryTextHasAtLeast4_5ContrastOnBackground() {
        for (var c : List.of(DARK, LIGHT, HC, DEFAULT_LIGHT,
                new PillPalette.IdeColors(0xFF777777, 0xFF808080, 0xFF808080, 0xFF666666, false),
                new PillPalette.IdeColors(0xFF3C3F41, 0xFFBBBBBB, 0xFF5E6060, 0xFF555555, true))) {
            var p = PillPalette.from(c);
            assertTrue(PillPalette.contrast(p.secondaryText(), p.background()) >= 4.5,
                    () -> Integer.toHexString(p.secondaryText()) + " on " + Integer.toHexString(p.background()));
        }
    }

    @Test void secondaryTextKeepsAThemeColourThatAlreadyPasses() {
        assertEquals(0xFF6C707E, PillPalette.from(LIGHT).secondaryText()); // ~4.7:1 on #F7F8FA
    }

    @Test void lowContrastThemeFallsBackToBlackOrWhite() {
        var p = PillPalette.from(new PillPalette.IdeColors(0xFF777777, 0xFF808080, 0xFF808080, 0xFF666666, false));
        assertTrue(p.text() == 0xFF000000 || p.text() == 0xFFFFFFFF);
        int other = p.text() == 0xFF000000 ? 0xFFFFFFFF : 0xFF000000;
        assertTrue(PillPalette.contrast(p.text(), p.background()) >= PillPalette.contrast(other, p.background()));
    }

    @Test void chevronIsMutedBetweenTextAndBackground() {
        for (var c : List.of(DARK, LIGHT)) {
            var p = PillPalette.from(c);
            assertNotEquals(p.text(), p.chevron());
            assertNotEquals(p.background(), p.chevron());
            double k = PillPalette.contrast(p.chevron(), p.background());
            assertTrue(k >= 2 && k <= PillPalette.contrast(p.text(), p.background()));
        }
    }

    @Test void cancelTintIsRedishInBothThemes() {
        for (var c : List.of(DARK, LIGHT)) {
            int cancel = PillPalette.from(c).cancel();
            assertTrue(ch(cancel, 16) > ch(cancel, 8) && ch(cancel, 16) > ch(cancel, 0));
        }
    }

    @Test void cancelDisabledIsLessProminentThanCancel() {
        for (var c : List.of(DARK, LIGHT)) {
            var p = PillPalette.from(c);
            assertTrue(PillPalette.contrast(p.cancelDisabled(), p.background()) < PillPalette.contrast(p.cancel(), p.background()));
        }
    }

    @Test void darkFlagPassesThrough() {
        assertTrue(PillPalette.from(DARK).dark());
        assertFalse(PillPalette.from(LIGHT).dark());
    }

    @Test void contrastOfIdenticalColoursIsOne() {
        assertEquals(1.0, PillPalette.contrast(0xFF123456, 0xFF123456), 1e-9);
    }

    @Test void contrastBlackWhiteIs21() {
        assertEquals(21.0, PillPalette.contrast(0xFF000000, 0xFFFFFFFF), 1e-9);
    }
}
