package dev.darshit.islandbar.ide;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.RoamingType;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.SettingsCategory;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import dev.darshit.islandbar.core.BarRenderer;
import dev.darshit.islandbar.core.PillStyle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Service(Service.Level.APP)
// a light application service that keeps state must not roam (platform rule), so these settings stay per machine
@State(name = "IslandProgressBarSettings", storages = @Storage(value = "islandProgressBar.xml", roamingType = RoamingType.DISABLED),
        category = SettingsCategory.UI)
public final class IslandSettings implements PersistentStateComponent<IslandSettings.State> {
    public static final class State {
        /** Stored as the enum name so an unknown value in a hand-edited file falls back instead of failing to load. */
        public @Nullable String style = PillStyle.B.name();
        public int delayMs = 100;
        public int fps = BarRenderer.DEFAULT_FPS;
        public boolean hideIdeBar = true;
    }

    public static final int MIN_DELAY_MS = 0, MAX_DELAY_MS = 10_000;

    private State state = new State();

    public static IslandSettings getInstance() {
        return ApplicationManager.getApplication().getService(IslandSettings.class);
    }

    /** Unknown or blank stored names fall back to the default, B. */
    public PillStyle getStyle() {
        String s = state.style;
        if (s != null) for (PillStyle p : PillStyle.values()) if (p.name().equals(s)) return p;
        return PillStyle.B;
    }
    public void setStyle(PillStyle v) { state.style = v.name(); }
    /** Clamped, so a hand-edited settings file can't produce a negative or absurd delay. */
    public int getDelayMs() { return Math.clamp(state.delayMs, MIN_DELAY_MS, MAX_DELAY_MS); }
    public void setDelayMs(int v) { state.delayMs = Math.clamp(v, MIN_DELAY_MS, MAX_DELAY_MS); }
    public int getFps() { return Math.clamp(state.fps, BarRenderer.MIN_FPS, BarRenderer.MAX_FPS); }
    public void setFps(int v) { state.fps = Math.clamp(v, BarRenderer.MIN_FPS, BarRenderer.MAX_FPS); }
    public boolean isHideIdeBar() { return state.hideIdeBar; }
    public void setHideIdeBar(boolean v) { state.hideIdeBar = v; }

    @Override public @NotNull State getState() { return state; }
    @Override public void loadState(@NotNull State s) { state = s; }
}
