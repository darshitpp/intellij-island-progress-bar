package dev.darshit.islandbar.core;

/** Pill colours derived from the IDE look and feel. Colours are opaque ARGB ints. */
public record PillPalette(int background, int text, int secondaryText, int separator, int chevron,
                          int cancel, int cancelDisabled, boolean dark) {

    public record IdeColors(int panelBackground, int labelForeground, int disabledForeground, int separator, boolean dark) {}

    private static final int BLACK = 0xFF000000, WHITE = 0xFFFFFFFF;
    private static final int CANCEL_ON_DARK = 0xFFFF6B63, CANCEL_ON_LIGHT = 0xFFD32F2F;

    public static PillPalette from(IdeColors c) {
        int bg = c.panelBackground() | 0xFF000000;
        int text = c.labelForeground() | 0xFF000000;
        if (contrast(text, bg) < 4.5) {
            text = contrast(BLACK, bg) >= contrast(WHITE, bg) ? BLACK : WHITE;
        }
        int secondary = c.disabledForeground() | 0xFF000000;
        // muted toward the background, but never below 4.5:1 (text itself always reaches it, so the loop ends there)
        for (double k = 0.65; contrast(secondary, bg) < 4.5 && k <= 1.0001; k += 0.05) secondary = blend(text, bg, Math.min(1, k));
        if (contrast(secondary, bg) < 4.5) secondary = text;
        int cancel = c.dark() ? CANCEL_ON_DARK : CANCEL_ON_LIGHT;
        return new PillPalette(bg, text, secondary, c.separator() | 0xFF000000, blend(text, bg, 0.55),
                cancel, blend(cancel, bg, 0.4), c.dark());
    }

    /** WCAG 2 contrast ratio between two colours (alpha ignored). */
    public static double contrast(int argbA, int argbB) {
        double a = luminance(argbA), b = luminance(argbB);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double luminance(int argb) {
        return 0.2126 * lin((argb >> 16) & 0xFF) + 0.7152 * lin((argb >> 8) & 0xFF) + 0.0722 * lin(argb & 0xFF);
    }

    private static double lin(int v) {
        double s = v / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    /** {@code fg} weighted by {@code amount}, the rest {@code bg}. */
    private static int blend(int fg, int bg, double amount) {
        int out = 0xFF000000;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int f = (fg >> shift) & 0xFF, b = (bg >> shift) & 0xFF;
            out |= (int) Math.round(f * amount + b * (1 - amount)) << shift;
        }
        return out;
    }
}
