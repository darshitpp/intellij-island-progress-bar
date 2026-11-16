package dev.darshit.islandbar.core;

/** Compact pill layouts: A = one row in the menu bar, B = name over bar below it, C = small ring. */
public enum PillStyle {
    A, B, C;

    /**
     * The style actually drawn on a screen. A sits in the middle of the menu bar, which a camera housing hides on a
     * notched screen, so A is shown as B there; B and C hang below the menu bar and never change.
     */
    public static PillStyle effective(PillStyle requested, boolean screenHasNotch) {
        return requested == A && screenHasNotch ? B : requested;
    }

    /**
     * Apple's rule (NSScreen, macOS 12+): a screen has a camera housing iff the top of its {@code safeAreaInsets} is
     * non-zero. No pixel sizes are assumed.
     */
    public static boolean hasNotch(double safeAreaTopInset) { return safeAreaTopInset > 0; }
}
