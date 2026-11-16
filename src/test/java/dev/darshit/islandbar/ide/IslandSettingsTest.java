package dev.darshit.islandbar.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.darshit.islandbar.core.BarRenderer;
import dev.darshit.islandbar.core.PillStyle;
import org.junit.jupiter.api.Test;

class IslandSettingsTest {
    @Test void defaults() {
        IslandSettings s = new IslandSettings();
        assertEquals(PillStyle.B, s.getStyle());
        assertEquals(100, s.getDelayMs());
        assertEquals(BarRenderer.DEFAULT_FPS, s.getFps());
        assertTrue(s.isHideIdeBar());
    }

    @Test void delayClamped() {
        IslandSettings s = new IslandSettings();
        s.setDelayMs(-5);
        assertEquals(0, s.getDelayMs());
        s.setDelayMs(99999);
        assertEquals(10000, s.getDelayMs());
    }

    @Test void fpsClamped() {
        IslandSettings s = new IslandSettings();
        s.setFps(1);
        assertEquals(BarRenderer.MIN_FPS, s.getFps());
        s.setFps(1000);
        assertEquals(BarRenderer.MAX_FPS, s.getFps());
    }

    @Test void handEditedOutOfRangeValuesAreClampedOnRead() {
        IslandSettings.State st = new IslandSettings.State();
        st.delayMs = -100;
        st.fps = 999;
        IslandSettings s = new IslandSettings();
        s.loadState(st);
        assertEquals(0, s.getDelayMs());
        assertEquals(BarRenderer.MAX_FPS, s.getFps());
    }

    @Test void styleRoundTripsThroughState() {
        IslandSettings a = new IslandSettings();
        a.setStyle(PillStyle.C);
        a.setHideIdeBar(false);
        IslandSettings b = new IslandSettings();
        b.loadState(a.getState());
        assertEquals(PillStyle.C, b.getStyle());
        assertEquals(false, b.isHideIdeBar());
    }

    @Test void invalidOrBlankPersistedStyleLoadsAsB() {
        for (String bad : new String[] {"Z", "", "  ", null, "b"}) {
            IslandSettings.State st = new IslandSettings.State();
            st.style = bad;
            IslandSettings s = new IslandSettings();
            s.loadState(st);
            assertEquals(PillStyle.B, s.getStyle(), "style " + bad);
        }
    }
}
