package dev.darshit.islandbar.ide;

import dev.darshit.islandbar.core.PillStyle;

/**
 * What the settings page's style picker offers. On a Mac with any notched screen, style A cannot be picked (it would
 * sit behind the camera housing and is shown as B there): its card is disabled, shows an explanation instead of its
 * description and previews B. A saved A is kept as it is.
 */
record StyleChoice(boolean anyNotch) {
    static StyleChoice of(boolean anyNotch) { return new StyleChoice(anyNotch); }

    boolean selectable(PillStyle s) { return !(anyNotch && s == PillStyle.A); }

    /** Whether a card shows the notch explanation in place of its description: the one that cannot be picked. */
    boolean showNotchNote(PillStyle s) { return !selectable(s); }

    /** The style a card previews: what will actually appear on screen if that style is picked. */
    PillStyle preview(PillStyle s) { return PillStyle.effective(s, anyNotch); }
}
