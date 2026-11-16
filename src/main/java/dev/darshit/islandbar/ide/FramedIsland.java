package dev.darshit.islandbar.ide;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import dev.darshit.islandbar.core.BarRenderer;
import dev.darshit.islandbar.core.IslandCoordinator;
import dev.darshit.islandbar.core.IslandView;
import dev.darshit.islandbar.core.PillGeometry;
import dev.darshit.islandbar.core.PillPalette;
import dev.darshit.islandbar.core.PillStyle;
import dev.darshit.islandbar.core.TaskRow;
import dev.darshit.islandbar.mac.IslandPanel;
import java.awt.AWTEvent;
import java.awt.Color;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import org.jetbrains.annotations.Nullable;

/**
 * One pill's life: the native {@link IslandPanel}, its frame loop and one {@link BarRenderer} per visible bar.
 * A Swing timer at the user's frame rate paints the pill bar and, while the list is open, up to 8 row bars, then
 * hands them to the panel in one {@code setFrames} call. A 1 s timer repositions the panel and restarts the frame
 * timer after the panel was occluded. Each instance has its own timers and stop flag, so a removed pill never paints
 * into the next one.
 *
 * <p>Reduce Motion (read by the panel on the AppKit thread about once a second) makes the standard indeterminate bars
 * still, so they send a frame only when their look changes; theme bars keep their own animation.
 *
 * <p>Style C has no pill bar (its ring is a native layer, coloured from the IDE's progress bar colours), so its frame
 * timer runs only while the list is open, for the row bars.
 *
 * <p>Threading: {@link #show}/{@link #update} come from the coordinator on the EDT; {@link #remove} may come from the
 * AppKit thread (under the coordinator's lock), so it never waits on the EDT.
 */
final class FramedIsland implements IslandCoordinator.Port {
    private static final Logger LOG = Logger.getInstance(FramedIsland.class);
    static final long SLOW_AFTER_MS = 10_000;
    static final int SLOW_INTERVAL_MS = 200; // 5 fps for a row whose bar has not changed for 10 s

    /** Creates a renderer of the given pixel size (the controller supplies the shared theme-failure handling). */
    interface BarFactory { BarRenderer create(int w, int h); }

    private static final class RowBar {
        final BarRenderer renderer;
        @Nullable Double lastFraction;
        @Nullable Object lastTint;
        long changedMs, paintedMs;
        RowBar(BarRenderer r) { renderer = r; }
    }

    private final Consumer<Throwable> failure;
    private final PillStyle style;
    private final IntSupplier fps;
    private final Supplier<PillPalette> palette;
    private final BarFactory bars;
    private final BooleanSupplier appActive;

    private final Timer frameTimer;
    private final Timer slowTimer;
    private volatile boolean stopped, listOpen;
    private volatile IslandView view = IslandView.HIDDEN;
    private volatile @Nullable IslandPanel panel;

    // ---- EDT only ----
    private final BarRenderer probe;               // asks the theme for its preferred bar height
    private @Nullable BarRenderer pill;
    private int pillPxW, pillPxH;
    private double pillPt = 6;                     // the theme's preferred bar height in points
    private final Map<Long, RowBar> rowBars = new HashMap<>();
    private final Queue<Long> clearedRows = new ConcurrentLinkedQueue<>(); // filled on the main thread, drained on the EDT
    private boolean active = true, logged;
    private final AWTEventListener clickOutside = e -> {
        if (e.getID() == MouseEvent.MOUSE_PRESSED && listOpen) {
            IslandPanel p = panel;
            if (p != null) p.closeList();
        }
    };
    private boolean listening;

    FramedIsland(Consumer<Throwable> failure, PillStyle style, IntSupplier fps, Supplier<PillPalette> palette,
                 BarFactory bars, BooleanSupplier appActive) {
        this.failure = failure; this.style = style; this.fps = fps; this.palette = palette; this.bars = bars;
        this.appActive = appActive;
        this.probe = bars.create(PillGeometry.pillBarPxWidth(), PillGeometry.pillBarPxHeight(6));
        frameTimer = new Timer(BarRenderer.intervalForFps(fps.getAsInt()), _ -> frame()); // coalesces overdue ticks
        slowTimer = new Timer(1000, _ -> slowTick());
    }

    /** EDT (the coordinator ticks there). */
    @Override public void show(IslandView v, IslandCoordinator.Actions a) {
        if (stopped) return;
        view = v;
        IslandPanel p = new IslandPanel(failure, style, a, this::listOpenChanged, this::rowCleared);
        panel = p;
        rebuildPillRenderer();
        active = appActive.getAsBoolean();
        if (!active) p.setVisibleToUser(false); // queued before show: the panel is created hidden
        PillPalette colours = palette.get();
        if (style == PillStyle.C) p.setRingColours(ringColours(colours));
        p.show(v, colours);
        sendPrimaryNow(); // the panel takes the bar height from the first frame
        // a failed first frame removes this pill right here (on the EDT, so remove()'s cleanup already ran): register
        // nothing after that, or the AWT listener and the timers would outlive the pill
        if (stopped) return;
        Toolkit.getDefaultToolkit().addAWTEventListener(clickOutside, AWTEvent.MOUSE_EVENT_MASK);
        listening = true;
        slowTimer.start();
        restartFrames(); // style C: only once the list opens
    }

    /** EDT (tick, or a Hide re-evaluation, which the coordinator also runs on the EDT). */
    @Override public void update(IslandView v) {
        view = v;
        IslandPanel p = panel;
        if (p != null) p.update(v);
        if (v.primary() == null) frameTimer.stop();
    }

    /** Any thread; never waits on the EDT. */
    @Override public void remove() {
        stopped = true;
        IslandPanel p = panel;
        if (p != null) p.remove();
        onEdt(() -> {
            frameTimer.stop();
            slowTimer.stop();
            if (listening) { Toolkit.getDefaultToolkit().removeAWTEventListener(clickOutside); listening = false; }
            probe.dispose();
            BarRenderer pr = pill;
            if (pr != null) pr.dispose();
            pill = null;
            clearRowBars();
            clearedRows.clear();
        });
    }

    /** EDT: the IDE became active or inactive. Inactive hides the pill and stops both timers. */
    void setActive(boolean isActive) {
        if (stopped) return;
        active = isActive;
        IslandPanel p = panel;
        if (p != null) p.setVisibleToUser(isActive);
        if (isActive) { slowTimer.start(); restartFrames(); }
        else { frameTimer.stop(); slowTimer.stop(); }
    }

    /** EDT: the look and feel changed. Re-colours the existing panel and repaints every bar with a fresh one. */
    void themeChanged(PillPalette p) {
        if (stopped) return;
        IslandPanel panel = this.panel;
        if (panel != null) {
            panel.applyPalette(p);
            if (style == PillStyle.C) panel.setRingColours(ringColours(p));
        }
        probe.newBar();
        for (RowBar r : rowBars.values()) r.renderer.newBar();
        rebuildPillRenderer();
        sendPrimaryNow(); // the bar height may have changed
    }

    /** EDT: a plugin (maybe a progress-bar theme) is unloading: let go of its UI classes. */
    void dropTheme() {
        probe.dropThemeUntilNextBar();
        BarRenderer pr = pill;
        if (pr != null) pr.dropThemeUntilNextBar();
        clearRowBars();
    }

    /**
     * AppKit main thread: the panel blanked a row slot that now shows task {@code id} (rows shifted, or the row was
     * hidden for height and shown again), so that task's renderer must send a frame even if its bar is unchanged.
     */
    void rowCleared(long id) { clearedRows.add(id); }

    /** AppKit main thread. Style C paints only while the list is open, so opening it (re)starts the frame timer. */
    private void listOpenChanged(boolean open) {
        listOpen = open;
        if (open && style == PillStyle.C) onEdt(this::restartFrames);
    }

    // ---------------------------------------------------------------- EDT internals

    /** The ring uses the IDE progress bar's own colours (as the standard bar does), with the same tints. */
    static IslandPanel.RingColours ringColours(PillPalette p) {
        return new IslandPanel.RingColours(uiColour("ProgressBar.trackColor", p.separator()),
                uiColour("ProgressBar.progressColor", 0xFF3574F0), STOPPING_ARGB);
    }

    static final int STOPPING_ARGB = 0xFFE8A33D; // as BarRenderer's tint

    private static int uiColour(String key, int fallback) {
        Color c = UIManager.getColor(key);
        return (c == null ? fallback : c.getRGB()) | 0xFF000000;
    }

    /** New pill renderer when the theme's bar height differs from the current one; otherwise a fresh bar. */
    private void rebuildPillRenderer() {
        if (style == PillStyle.C) return; // no pill bar: paintPrimary() returns null
        pillPt = PillGeometry.barHeightFor(probe.preferredBarHeight());
        BarRenderer pr = pill;
        if (!ensurePillSize()) pr.newBar();
    }

    /**
     * The pill renderer draws at 2 px per point of the panel's bar view (so the theme's art keeps its aspect), and is
     * rebuilt when that size changes by 2 px or more. True when it was (re)created.
     */
    private boolean ensurePillSize() {
        IslandPanel p = panel;
        int pw = p == null ? 0 : p.barPxWidth(), ph = p == null ? 0 : p.barPxHeight();
        int w = pw > 0 ? pw : PillGeometry.pillBarPxWidth(), h = pw > 0 ? ph : PillGeometry.pillBarPxHeight(pillPt);
        BarRenderer pr = pill;
        if (pr != null && !PillGeometry.barSizeChanged(pillPxW, pillPxH, w, h)) return false;
        if (pr != null) pr.dispose();
        pill = bars.create(w, h);
        pillPxW = w; pillPxH = h;
        return true;
    }

    private void sendPrimaryNow() {
        try {
            int[] px = paintPrimary();
            IslandPanel p = panel;
            if (px != null && p != null) p.setFrames(new IslandPanel.Frames(px, pillPxW, pillPt, Map.of()));
        } catch (Throwable t) { fail(t); }
    }

    private int @Nullable [] paintPrimary() {
        TaskRow primary = view.primary();
        if (primary == null || pill == null) return null;
        ensurePillSize();
        BarRenderer pr = pill;
        pr.setReduceMotion(IslandPanel.reduceMotion()); // a still indeterminate bar: no new frame per tick
        return pr.paint(primary.fraction(), IslandPanel.tintOf(primary));
    }

    private void restartFrames() {
        if (!stopped && active && view.primary() != null && (style != PillStyle.C || listOpen)
                && !frameTimer.isRunning()) frameTimer.start();
    }

    private void slowTick() {
        if (stopped) { slowTimer.stop(); return; }
        IslandPanel p = panel;
        if (p == null) return;
        p.reposition();
        int interval = BarRenderer.intervalForFps(fps.getAsInt()); // a settings change applies within a second
        if (frameTimer.getDelay() != interval) frameTimer.setDelay(interval);
        if (!p.isOccluded()) restartFrames();
    }

    private void frame() {
        IslandPanel p = panel;
        if (stopped || p == null || !active || view.primary() == null) { frameTimer.stop(); return; }
        if (p.isOccluded()) { frameTimer.stop(); return; } // the 1 s tick restarts it once visible again
        if (style == PillStyle.C && !listOpen) { dropRows(); frameTimer.stop(); return; } // reopening restarts it
        try {
            int[] primary = paintPrimary();
            Map<Long, int[]> rows = listOpen ? paintRows(view.rows()) : dropRows();
            if (!logged) {
                logged = true;
                BarRenderer pr = pill;
                LOG.info("Island Progress Bar: " + (pr != null && pr.isThemed() ? "themed (" + pr.themeName() + ")" : "standard")
                        + " bar, UI registered in the IDE: " + UIManager.get("ProgressBarUI") + ", frame interval "
                        + frameTimer.getDelay() + " ms");
            }
            if (primary != null || !rows.isEmpty()) p.setFrames(new IslandPanel.Frames(primary, pillPxW, pillPt, rows));
        } catch (Throwable t) { fail(t); }
    }

    private Map<Long, int[]> dropRows() {
        clearRowBars(); // a reopened list starts with fresh renderers, so every row gets a first frame
        return Map.of();
    }

    private void clearRowBars() {
        for (RowBar r : rowBars.values()) r.renderer.dispose();
        rowBars.clear();
    }

    /** The first 8 rows (the ones the list shows); unchanged rows for 10 s drop to 5 fps. */
    Map<Long, int[]> paintRows(List<TaskRow> all) {
        for (Long id; (id = clearedRows.poll()) != null; ) {
            RowBar rb = rowBars.get(id);
            if (rb != null) { rb.renderer.newBar(); rb.paintedMs = 0; } // next paint always returns a frame
        }
        long now = System.nanoTime() / 1_000_000;
        int n = Math.min(all.size(), PillGeometry.MAX_ROWS);
        Set<Long> shown = new HashSet<>();
        Map<Long, int[]> out = null;
        for (int i = 0; i < n; i++) {
            TaskRow r = all.get(i);
            shown.add(r.id());
            RowBar rb = rowBars.computeIfAbsent(r.id(),
                    _ -> new RowBar(bars.create(PillGeometry.rowBarPxWidth(), PillGeometry.ROW_BAR_PX_H)));
            Object tint = IslandPanel.tintOf(r);
            boolean first = rb.paintedMs == 0;
            if (first || !Objects.equals(rb.lastFraction, r.fraction()) || rb.lastTint != tint) {
                rb.lastFraction = r.fraction(); rb.lastTint = tint; rb.changedMs = now;
            } else if (now - rb.changedMs >= SLOW_AFTER_MS && now - rb.paintedMs < SLOW_INTERVAL_MS) {
                continue;
            }
            rb.paintedMs = now;
            rb.renderer.setReduceMotion(IslandPanel.reduceMotion());
            int[] px = rb.renderer.paint(r.fraction(), IslandPanel.tintOf(r));
            if (px != null) {
                if (out == null) out = new HashMap<>();
                out.put(r.id(), px);
            }
        }
        rowBars.entrySet().removeIf(e -> {
            if (shown.contains(e.getKey())) return false;
            e.getValue().renderer.dispose();
            return true;
        });
        return out == null ? Map.of() : out;
    }

    private void fail(Throwable t) {
        stopped = true;
        frameTimer.stop();
        slowTimer.stop();
        failure.accept(t); // the coordinator removes this pill, restores the IDE's bar and disables itself
    }

    private static void onEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) r.run();
        else ApplicationManager.getApplication().invokeLater(r, ModalityState.any());
    }
}
