package dev.darshit.islandbar.core;

import org.jetbrains.annotations.Nullable;

/**
 * Every animation timing and every Reduce Motion decision of the pill, in one place. Apple's HIG (Motion,
 * Accessibility) asks for brief, purposeful motion that never makes people wait, and, when Reduce Motion is on, for
 * fewer automatic and repetitive animations; it gives no durations, so these are ours. All fades are opacity only
 * (never size or position) and ease in and out.
 */
public final class Motion {
    private Motion() {}

    /** What fades: the pill appearing, the pill going away, the list's rows appearing when it opens. */
    public enum Fade { SHOW, HIDE, LIST }

    public static final double SHOW_FADE_S = 0.18, HIDE_FADE_S = 0.15, LIST_FADE_S = 0.12;
    /** The window is ordered out this long after the fade-out ends, so its last frame is never cut off. */
    public static final double ORDER_OUT_MARGIN_S = 0.03;
    /** One turn of the style C ring while a task is indeterminate. */
    public static final double SPIN_S = 1.0;

    /** The system setting, unless a test forces one ({@code override} non-null). */
    public static boolean reduceMotion(boolean system, @Nullable Boolean override) {
        return override != null ? override : system;
    }

    /**
     * Seconds for a fade; 0 means instant. Reduce Motion makes every fade instant; a pill shown while the previous one
     * was still fading out replaces it at once (no dip to transparent and back).
     */
    public static double fadeSeconds(Fade f, boolean reduceMotion, boolean interruptingFadeOut) {
        if (reduceMotion) return 0;
        return switch (f) {
            case SHOW -> interruptingFadeOut ? 0 : SHOW_FADE_S;
            case HIDE -> HIDE_FADE_S;
            case LIST -> LIST_FADE_S;
        };
    }

    /** When the faded-out window leaves the screen; 0 (at once) when there was no fade. */
    public static double orderOutDelaySeconds(double fadeSeconds) {
        return fadeSeconds <= 0 ? 0 : fadeSeconds + ORDER_OUT_MARGIN_S;
    }

    /** Style C: the indeterminate ring spins only while someone can see it, the task runs and motion is allowed. */
    public static boolean spinRing(boolean indeterminate, boolean visible, boolean paused, boolean reduceMotion) {
        return indeterminate && visible && !paused && !reduceMotion;
    }
}
