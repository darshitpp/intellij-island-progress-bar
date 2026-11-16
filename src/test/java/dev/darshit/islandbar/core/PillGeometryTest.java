package dev.darshit.islandbar.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PillGeometryTest {
    private static final PillGeometry.Rect SCREEN = new PillGeometry.Rect(0, 0, 1440, 900);
    private static final double MENU = 25;
    private static final double EPS = 1e-9;

    private static PillGeometry.Rect b(double bar) {
        return PillGeometry.compact(PillStyle.B, SCREEN, MENU, bar);
    }

    @Test void styleBTopEdgeIsMenuBarBottom() {
        var p = b(6);
        assertEquals(900 - 25, p.y() + p.h(), EPS);
        assertEquals(720, p.x() + p.w() / 2, EPS);
        assertEquals(400, p.w(), EPS);
    }

    @Test void styleAFillsMenuBarHeight() {
        var p = PillGeometry.compact(PillStyle.A, SCREEN, MENU, 6);
        assertEquals(25, p.h(), EPS);
        assertEquals(900, p.y() + p.h(), EPS);
        assertEquals(400, p.w(), EPS);
    }

    @Test void styleCIsSmallRing() {
        var p = PillGeometry.compact(PillStyle.C, SCREEN, MENU, 6);
        assertEquals(80, p.w(), EPS);
        assertEquals(30, p.h(), EPS);
        assertEquals(900 - 25, p.y() + p.h(), EPS);
    }

    @Test void themedBarMakesPillTaller() {
        assertTrue(b(22).h() > b(6).h());
    }

    @Test void neverWiderThanScreen() {
        var narrow = new PillGeometry.Rect(0, 0, 300, 900);
        for (PillStyle s : PillStyle.values()) {
            var p = PillGeometry.compact(s, narrow, MENU, 6);
            assertTrue(p.w() <= 300);
            assertEquals(150, p.x() + p.w() / 2, EPS);
        }
    }

    @Test void zeroOrNegativeMenuBarFallsBackTo25() {
        assertEquals(b(6), PillGeometry.compact(PillStyle.B, SCREEN, 0, 6));
        assertEquals(b(6), PillGeometry.compact(PillStyle.B, SCREEN, -3, 6));
    }

    @Test void expandedKeepsTopEdgeAndGrowsDownward() {
        var c = b(6);
        double prev = c.h();
        for (int n = 1; n <= 8; n++) {
            var e = PillGeometry.expanded(c, n, 6, SCREEN, MENU);
            assertEquals(c.y() + c.h(), e.y() + e.h(), EPS);
            assertEquals(c.x() + c.w() / 2, e.x() + e.w() / 2, EPS);
            assertTrue(e.h() > prev);
            assertTrue(e.h() <= SCREEN.h() - MENU);
            prev = e.h();
        }
        assertEquals(PillGeometry.expanded(c, 8, 6, SCREEN, MENU), PillGeometry.expanded(c, 12, 6, SCREEN, MENU));
    }

    @Test void widthIsTheSameCompactAndExpanded() {
        for (PillStyle s : new PillStyle[]{PillStyle.A, PillStyle.B}) {
            var c = PillGeometry.compact(s, SCREEN, MENU, 6);
            assertEquals(c.w(), PillGeometry.expanded(c, 3, 6, SCREEN, MENU).w(), EPS);
            assertEquals(c.x(), PillGeometry.expanded(c, 3, 6, SCREEN, MENU).x(), EPS);
        }
    }

    @Test void expandedNeverExceedsShortScreen() {
        var s = new PillGeometry.Rect(0, 0, 1440, 200);
        var c = PillGeometry.compact(PillStyle.B, s, MENU, 6);
        var e = PillGeometry.expanded(c, 8, 6, s, MENU);
        assertTrue(e.h() <= s.h() - MENU);
        assertEquals(c.y() + c.h(), e.y() + e.h(), EPS);
    }

    @Test void rowHeightGrowsWithWrappedNameLinesUpToThree() {
        double one = PillGeometry.rowHeight(0, 12);
        assertEquals(42, one, EPS); // a name that fits its column: the usual row
        assertEquals(one, PillGeometry.rowHeight(-1, 12), EPS);
        double prev = one;
        for (int k = 1; k <= PillGeometry.MAX_NAME_LINES; k++) {
            double h = PillGeometry.rowHeight(k, 12);
            assertTrue(h > prev, "a wrapped name line adds height: " + k);
            prev = h;
        }
        assertEquals(PillGeometry.rowHeight(3, 12), PillGeometry.rowHeight(4, 12), EPS);
        assertEquals(PillGeometry.rowHeight(3, 12), PillGeometry.rowHeight(500, 12), EPS);
        assertEquals(3, PillGeometry.MAX_NAME_LINES);
    }

    @Test void stackedRowFitsNameLinesAndTheBarLine() {
        for (int k = 1; k <= 3; k++) {
            double h = PillGeometry.rowHeight(k, 12);
            assertTrue(h >= 7 + k * PillGeometry.NAME_LINE_H + 16 + 7, "room for " + k + " lines and the bar line");
        }
        assertTrue(PillGeometry.rowHeight(1, 30) >= 7 + PillGeometry.NAME_LINE_H + 30 + 7); // a tall bar still fits
    }

    @Test void expandedAddsEachRowsHeight() {
        var c = b(6);
        var e = PillGeometry.expanded(c, new int[]{0, 2, 3}, 12, SCREEN, MENU);
        double rows = PillGeometry.rowHeight(0, 12) + PillGeometry.rowHeight(2, 12) + PillGeometry.rowHeight(3, 12);
        assertEquals(c.h() + 8 + rows, e.h(), EPS);
        assertEquals(c.y() + c.h(), e.y() + e.h(), EPS);
        assertEquals(c.x() + c.w() / 2, e.x() + e.w() / 2, EPS);
    }

    @Test void expandedWithUnwrappedNamesMatchesRowCountForm() {
        var c = b(6);
        for (int n = 0; n <= 10; n++) {
            assertEquals(PillGeometry.expanded(c, n, 12, SCREEN, MENU),
                    PillGeometry.expanded(c, new int[n], 12, SCREEN, MENU));
        }
    }

    @Test void expandedCountsOnlyTheFirstEightRows() {
        var c = b(6);
        int[] eight = {3, 3, 3, 3, 3, 3, 3, 3};
        int[] twelve = {3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3};
        assertEquals(PillGeometry.expanded(c, eight, 12, SCREEN, MENU), PillGeometry.expanded(c, twelve, 12, SCREEN, MENU));
    }

    @Test void expandedWithWrappedNamesNeverExceedsTheScreen() {
        int[] tall = {3, 3, 3, 3, 3, 3, 3, 3};
        for (double sh : new double[]{200, 500, 900}) {
            var s = new PillGeometry.Rect(0, 0, 1440, sh);
            var c = PillGeometry.compact(PillStyle.B, s, MENU, 6);
            var e = PillGeometry.expanded(c, tall, 12, s, MENU);
            assertTrue(e.h() <= sh - MENU + EPS, "screen " + sh);
            assertEquals(c.y() + c.h(), e.y() + e.h(), EPS);
            assertTrue(e.y() >= s.y() - EPS);
        }
    }

    @Test void expandedAcceptsAnEmptyOrNullLineArray() {
        var c = b(6);
        assertEquals(PillGeometry.expanded(c, 0, 12, SCREEN, MENU), PillGeometry.expanded(c, new int[0], 12, SCREEN, MENU));
        assertEquals(PillGeometry.expanded(c, 0, 12, SCREEN, MENU), PillGeometry.expanded(c, null, 12, SCREEN, MENU));
    }

    @Test void barHeightForClamps() {
        assertEquals(6.0, PillGeometry.barHeightFor(-1), EPS);
        assertEquals(16.0, PillGeometry.barHeightFor(5), EPS);
        assertEquals(20.0, PillGeometry.barHeightFor(20), EPS);
        assertEquals(30.0, PillGeometry.barHeightFor(99), EPS);
    }

    @Test void barPixelSizes() {
        assertEquals(12, PillGeometry.pillBarPxHeight(6));
        assertEquals(24, PillGeometry.ROW_BAR_PX_H);
    }

    @Test void barPxWidthIsTwoPixelsPerPointAndNeverBelowTwo() {
        assertEquals(696, PillGeometry.barPxWidth(348));
        assertEquals(245, PillGeometry.barPxWidth(122.4));
        assertEquals(2, PillGeometry.barPxWidth(0));
        assertEquals(2, PillGeometry.barPxWidth(-5));
    }

    @Test void rowBarWidthFollowsTheListWidth() {
        // 400 pt list: 12 pad + 120 name + 8 gap before the bar; 8 gap + 38 pct + 8 gap + 2 buttons + 1 gap + 12 pad after
        assertEquals(148.0, PillGeometry.rowBarWidthPt(400), EPS);
        assertEquals(296, PillGeometry.rowBarPxWidth());
        assertEquals(0.0, PillGeometry.rowBarWidthPt(100), EPS);
    }

    @Test void initialPillBarWidthIsStyleBWithoutChevronWithCancel() {
        assertEquals(PillGeometry.barPxWidth(400 - 24 - 20 - 8), PillGeometry.pillBarPxWidth());
    }

    @Test void barRebuildThresholdIsTwoPixels() {
        assertFalse(PillGeometry.barSizeChanged(100, 24, 101, 25));
        assertTrue(PillGeometry.barSizeChanged(100, 24, 102, 24));
        assertTrue(PillGeometry.barSizeChanged(100, 24, 100, 22));
    }
}
