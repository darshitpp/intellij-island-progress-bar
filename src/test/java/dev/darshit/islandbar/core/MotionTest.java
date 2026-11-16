package dev.darshit.islandbar.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MotionTest {
    @Test void theSystemSettingDecidesUnlessATestOverridesIt() {
        assertTrue(Motion.reduceMotion(true, null));
        assertFalse(Motion.reduceMotion(false, null));
        assertTrue(Motion.reduceMotion(false, true));
        assertFalse(Motion.reduceMotion(true, false));
    }

    @Test void fadesAreShortAndEaseInOutSized() {
        for (Motion.Fade f : Motion.Fade.values()) {
            double s = Motion.fadeSeconds(f, false, false);
            assertTrue(s >= 0.10 && s <= 0.20, f + ": " + s); // brief: never make people wait
        }
    }

    @Test void reduceMotionMakesEveryFadeInstant() {
        for (Motion.Fade f : Motion.Fade.values()) assertEquals(0, Motion.fadeSeconds(f, true, false));
    }

    @Test void aShowThatInterruptsAFadeOutIsInstant() {
        assertEquals(0, Motion.fadeSeconds(Motion.Fade.SHOW, false, true));
        assertEquals(Motion.SHOW_FADE_S, Motion.fadeSeconds(Motion.Fade.SHOW, false, false));
        assertEquals(Motion.HIDE_FADE_S, Motion.fadeSeconds(Motion.Fade.HIDE, false, false));
        assertEquals(Motion.LIST_FADE_S, Motion.fadeSeconds(Motion.Fade.LIST, false, false));
    }

    @Test void theWindowIsOrderedOutJustAfterTheFadeEnds() {
        double fade = Motion.fadeSeconds(Motion.Fade.HIDE, false, false);
        double out = Motion.orderOutDelaySeconds(fade);
        assertTrue(out > fade && out <= fade + 0.1, "" + out);
        assertEquals(0, Motion.orderOutDelaySeconds(0)); // no fade: ordered out at once
    }

    @Test void theRingSpinsOnlyWhenIndeterminateVisibleRunningAndMotionIsAllowed() {
        assertTrue(Motion.spinRing(true, true, false));
        assertFalse(Motion.spinRing(true, true, true));  // Reduce Motion: a still arc
        assertFalse(Motion.spinRing(false, true, false)); // determinate
        assertFalse(Motion.spinRing(true, false, false)); // nobody sees it
    }
}
