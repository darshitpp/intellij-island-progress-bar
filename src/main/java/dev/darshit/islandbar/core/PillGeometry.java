package dev.darshit.islandbar.core;

import org.jetbrains.annotations.Nullable;

/** Pure pill sizing in Cocoa coordinates (origin bottom-left). Values follow the approved mockups. */
public final class PillGeometry {
    public record Rect(double x, double y, double w, double h) {}

    public static final int ROW_BAR_PX_H = 24;
    /** Row layout constants shared with the native panel, so the row bar's size has one source of truth. */
    public static final double LIST_PAD = 12, BUTTON = 20, GAP = 8, ROW_NAME_W = 120, ROW_PCT_W = 38, ROW_BTN_GAP = 6;
    public static final int MAX_ROWS = 8;

    static final double DEFAULT_MENU_BAR = 25;
    static final double STANDARD_BAR = 6;
    /** A, B and the open list share one width, so the pill never changes width (a chevron appearing or the list opening). */
    static final double WIDTH = 400, WIDTH_C = 80, HEIGHT_C = 30;
    /** Style B: top padding 8 + name line 16 + gap 4 + bottom padding 10, plus the bar. */
    static final double B_CHROME = 38;
    /** One list row: two-line name (28) + 7 pt padding above and below. */
    static final double ROW_H = 42;
    /** Space between the compact header and the first list row. */
    static final double LIST_GAP = 8;

    private PillGeometry() {}

    public static Rect compact(PillStyle style, Rect screen, double menuBarHeight, double barHeight) {
        double mb = menuBarHeight > 0 ? menuBarHeight : DEFAULT_MENU_BAR;
        double w;
        double h;
        double top;
        switch (style) {
            case A -> { w = WIDTH; h = mb; top = screen.y() + screen.h(); }
            case C -> { w = WIDTH_C; h = HEIGHT_C; top = screen.y() + screen.h() - mb; }
            default -> { w = WIDTH; h = B_CHROME + barHeight; top = screen.y() + screen.h() - mb; }
        }
        w = Math.min(w, screen.w());
        return new Rect(screen.x() + (screen.w() - w) / 2, top - h, w, h);
    }

    /** Grows downward from the compact pill; rowCount is clamped to 8 and the result to the space under the menu bar. */
    public static Rect expanded(Rect compact, int rowCount, double rowBarHeight, Rect screen, double menuBarHeight) {
        return expanded(compact, new int[Math.max(0, Math.min(rowCount, MAX_ROWS))], rowBarHeight, screen, menuBarHeight);
    }

    /**
     * As {@link #expanded(Rect, int, double, Rect, double)}, one row per entry of {@code wrappedNameLines} (only the
     * first 8 count; null = no rows). Each entry is that row's wrapped name line count ({@link #rowHeight}).
     */
    public static Rect expanded(Rect compact, int @Nullable [] wrappedNameLines,
                                double rowBarHeight, Rect screen, double menuBarHeight) {
        double mb = menuBarHeight > 0 ? menuBarHeight : DEFAULT_MENU_BAR;
        int n = wrappedNameLines == null ? 0 : Math.min(wrappedNameLines.length, MAX_ROWS);
        double rows = 0;
        for (int i = 0; i < n; i++) rows += rowHeight(wrappedNameLines[i], rowBarHeight);
        double w = Math.min(Math.max(compact.w(), WIDTH), screen.w());
        double top = compact.y() + compact.h();
        double h = Math.min(compact.h() + LIST_GAP + rows, Math.max(compact.h(), screen.h() - mb));
        return new Rect(screen.x() + (screen.w() - w) / 2, top - h, w, h);
    }

    /** A list row's name wraps to at most this many lines (then ends in an ellipsis). */
    public static final int MAX_NAME_LINES = 3;
    /** One wrapped name line in a list row (12 pt system font). */
    public static final double NAME_LINE_H = 16;
    /** Row padding above and below, and the gap between a wrapped name and the bar line under it. */
    static final double ROW_PAD = 7, STACK_GAP = 2, BAR_LINE_H = 16;

    /**
     * A list row's height. {@code wrappedNameLines <= 0}: the name fits its column, the usual two-line row (name over
     * detail, bar beside them). 1..3: the name is too long for its column, so it spans the row on that many lines,
     * with detail, bar and percentage on one line under it. More than 3 counts as 3.
     */
    public static double rowHeight(int wrappedNameLines, double rowBarHeight) {
        double base = Math.max(ROW_H, rowBarHeight + 14);
        if (wrappedNameLines <= 0) return base;
        int k = Math.min(wrappedNameLines, MAX_NAME_LINES);
        return Math.max(base, ROW_PAD + k * NAME_LINE_H + STACK_GAP + Math.max(BAR_LINE_H, rowBarHeight) + ROW_PAD);
    }

    public static double barHeightFor(int themePreferredHeight) {
        return themePreferredHeight <= -1 ? STANDARD_BAR : Math.max(16, Math.min(30, themePreferredHeight));
    }

    /** Initial pill bar width (style B, no chevron, Cancel shown) until the panel reports its real bar view width. */
    public static int pillBarPxWidth() { return barPxWidth(WIDTH - 2 * LIST_PAD - BUTTON - GAP); }

    public static int pillBarPxHeight(double barHeightPt) { return (int) Math.round(barHeightPt * 2); }

    /** Pixels of a bar view {@code widthPt} wide (retina 2 px per point), so a theme draws at its natural aspect. */
    public static int barPxWidth(double widthPt) { return Math.max(2, (int) Math.round(widthPt * 2)); }

    /** A list row's bar view width in points: between the name column and the percentage, Cancel and Hide. */
    public static double rowBarWidthPt(double listWidth) {
        double barX = LIST_PAD + ROW_NAME_W + GAP;
        double pctX = listWidth - LIST_PAD - 2 * BUTTON - ROW_BTN_GAP - GAP - ROW_PCT_W;
        return Math.max(0, pctX - GAP - barX);
    }

    public static int rowBarPxWidth() { return barPxWidth(rowBarWidthPt(WIDTH)); }

    /** True when a bar renderer of the old pixel size no longer fits the view (2 px or more either way). */
    public static boolean barSizeChanged(int oldW, int oldH, int newW, int newH) {
        return Math.abs(oldW - newW) >= 2 || Math.abs(oldH - newH) >= 2;
    }
}
