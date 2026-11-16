package dev.darshit.islandbar.ide;

import com.intellij.ide.plugins.DynamicPluginListener;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.ui.LafManager;
import com.intellij.ide.ui.LafManagerListener;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationActivationListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.ui.JBColor;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.messages.MessageBusConnection;
import dev.darshit.islandbar.core.BarRenderer;
import dev.darshit.islandbar.core.IslandCoordinator;
import dev.darshit.islandbar.core.PillPalette;
import dev.darshit.islandbar.core.PillStyle;
import dev.darshit.islandbar.core.TaskRow;
import dev.darshit.islandbar.mac.IslandPanel;
import java.awt.Color;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JComponent;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Polls every 250 ms and drives the pill through {@link IslandCoordinator}. While another app is in front the poll
 * runs only as long as an IDE bar is hidden, so a task that ends in the background still gives the IDE its bar back
 * (the pill itself stays ordered out until the IDE is active again). Created by {@link IslandStartup}. Inert unless
 * macOS on Apple silicon.
 */
@Service(Service.Level.APP)
public final class IslandController implements Disposable {
    private static final Logger LOG = Logger.getInstance(IslandController.class);
    static final PluginId PLUGIN_ID = PluginId.getId("dev.darshit.island-progress-bar");

    private final @Nullable IslandCoordinator<Project> coordinator;
    private final @Nullable ScheduledFuture<?> poll;
    private final AtomicBoolean queued = new AtomicBoolean();
    /** For the session: once a progress-bar theme failed to paint, every renderer draws the standard bar. */
    private final AtomicBoolean themeBroken = new AtomicBoolean();
    private volatile @Nullable FramedIsland current;
    private volatile boolean disposed;
    /** Some project's inline IDE bar is hidden right now; written at the end of every tick. */
    private volatile boolean ideBarHidden;

    // ---- EDT only ----
    private final Map<Project, IdeBarHider> hiders = new HashMap<>();
    private final Map<Project, Integer> taskCounts = new HashMap<>();   // latest poll
    private final Map<Project, Integer> hiddenCounts = new HashMap<>(); // at the last hide: rescan when it rises
    private @Nullable PillPalette palette;
    private @Nullable PillStyle lastStyle;
    private @Nullable Timer lafDebounce;
    private @Nullable TaskSource taskSource;

    public IslandController() {
        if (!isSupported()) {
            coordinator = null;
            poll = null;
            LOG.info("Island Progress Bar: not supported here (needs macOS on Apple silicon); doing nothing");
            return;
        }
        TaskSource source = new StatusBarTaskSource();
        taskSource = source;
        coordinator = new IslandCoordinator<>(
                p -> countingSource(source, p),
                onFail -> current = new FramedIsland(onFail, IslandSettings.getInstance().getStyle(),
                        () -> IslandSettings.getInstance().getFps(), this::palette, this::newRenderer,
                        () -> ApplicationManager.getApplication().isActive()),
                new IslandCoordinator.IdeBar<>() {
                    @Override public void hide(Project p) { hideIdeBar(p); }
                    @Override public void restore(Project p) { restoreIdeBar(p); }
                },
                () -> IslandSettings.getInstance().isHideIdeBar(),
                () -> IslandSettings.getInstance().getDelayMs(),
                () -> System.nanoTime() / 1_000_000,
                r -> ApplicationManager.getApplication().invokeLater(r, ModalityState.any()),
                this::onDisabled);

        MessageBusConnection bus = ApplicationManager.getApplication().getMessageBus().connect(this);
        bus.subscribe(ApplicationActivationListener.TOPIC, new ApplicationActivationListener() {
            @Override public void applicationActivated(@NotNull IdeFrame f) { activeChanged(true); }
            @Override public void applicationDeactivated(@NotNull IdeFrame f) { activeChanged(false); }
        });
        bus.subscribe(DynamicPluginListener.TOPIC, new DynamicPluginListener() {
            @Override public void beforePluginUnload(@NotNull IdeaPluginDescriptor d, boolean isUpdate) {
                if (PLUGIN_ID.equals(d.getPluginId())) shutDown(); // restore the IDE bar + remove the pill now
                else { FramedIsland f = current; if (f != null) f.dropTheme(); } // maybe a theme: let go of its UI
            }
        });
        bus.subscribe(LafManagerListener.TOPIC, this::lafChanged);
        poll = AppExecutorUtil.getAppScheduledExecutorService()
                .scheduleWithFixedDelay(this::requestTick, 250, 250, TimeUnit.MILLISECONDS);
        LOG.info("Island Progress Bar: started");
    }

    static boolean isSupported() {
        return System.getProperty("os.name", "").startsWith("Mac") && "aarch64".equals(System.getProperty("os.arch"));
    }

    // ---------------------------------------------------------------- poll

    /**
     * Inactive and nothing hidden: nothing to show and nothing to restore, so skip. Inactive with a hidden IDE bar:
     * keep ticking, so the bar comes back as soon as the last task ends.
     */
    static boolean shouldPoll(boolean appActive, boolean ideBarHidden) { return appActive || ideBarHidden; }

    /** The status bar's task list is mutated on the EDT, so read it there; at most one tick queued at a time. */
    private void requestTick() {
        if (!shouldPoll(ApplicationManager.getApplication().isActive(), ideBarHidden)) return;
        if (queued.compareAndSet(false, true)) {
            ApplicationManager.getApplication().invokeLater(() -> { queued.set(false); tick(); }, ModalityState.any());
        }
    }

    private void tick() {
        IslandCoordinator<Project> c = coordinator;
        if (disposed || c == null) return;
        try {
            List<Project> open = Arrays.stream(ProjectManager.getInstance().getOpenProjects())
                    .filter(p -> !p.isDisposed()).toList();
            IdeFrame frame = IdeFocusManager.getGlobalInstance().getLastFocusedFrame();
            Project focused = frame == null ? null : frame.getProject();
            PillStyle style = IslandSettings.getInstance().getStyle();
            if (lastStyle != null && style != lastStyle) c.tick(List.of(), null); // drop the pill; next one uses the new style
            lastStyle = style;
            c.tick(open, focused);
            // closed projects: the coordinator has restored their bars by now; drop our per-project entries
            hiders.keySet().retainAll(open);
            if (taskSource != null) taskSource.retainProjects(open);
            taskCounts.keySet().retainAll(open);
            hiddenCounts.keySet().retainAll(open);
        } catch (Throwable t) { // never let the scheduled task die
            LOG.warn("Island Progress Bar tick failed", t);
        } finally {
            updateIdeBarHidden();
        }
    }

    private void updateIdeBarHidden() {
        boolean any = false;
        for (IdeBarHider h : hiders.values()) any |= h.isHidden();
        ideBarHidden = any;
    }

    private List<TaskRow> countingSource(TaskSource source, Project p) {
        List<TaskRow> rows = source.all(p);
        taskCounts.put(p, rows.size());
        return rows;
    }

    // ---------------------------------------------------------------- IDE bar (EDT)

    private void hideIdeBar(Project p) {
        StatusBar bar = WindowManager.getInstance().getStatusBar(p);
        JComponent root = bar == null ? null : bar.getComponent();
        if (root == null) return;
        int count = taskCounts.getOrDefault(p, 0);
        boolean rescan = count > hiddenCounts.getOrDefault(p, 0); // a new task may have added a panel
        hiddenCounts.put(p, count);
        hiders.computeIfAbsent(p, _ -> new IdeBarHider()).hide(root, rescan);
    }

    /** Runs every tick while no pill is up: a map lookup and nothing else when nothing is hidden. */
    private void restoreIdeBar(Project p) {
        IdeBarHider h = hiders.get(p);
        if (h != null && h.isHidden()) h.restore();
        hiddenCounts.remove(p);
    }

    // ---------------------------------------------------------------- activation, theme

    private void activeChanged(boolean active) {
        FramedIsland f = current;
        if (f != null) f.setActive(active);
        if (active) requestTick();
    }

    /** EDT. Debounced: a theme switch fires several events; rebuild once, 100 ms after the last. */
    private void lafChanged(@NotNull LafManager source) {
        if (disposed) return;
        Timer t = lafDebounce;
        if (t == null) {
            t = new Timer(100, _ -> applyTheme());
            t.setRepeats(false);
            lafDebounce = t;
        }
        t.restart();
    }

    private void applyTheme() {
        if (disposed) return;
        PillPalette p = buildPalette();
        palette = p;
        FramedIsland f = current;
        if (f != null) f.themeChanged(p);
    }

    /** EDT. Cached until the next look-and-feel change. */
    private PillPalette palette() {
        PillPalette p = palette;
        if (p == null) palette = p = buildPalette();
        return p;
    }

    static PillPalette buildPalette() {
        boolean dark = !JBColor.isBright();
        int bg = rgb("Panel.background", dark ? 0x2B2D30 : 0xF7F8FA);
        int fg = rgb("Label.foreground", dark ? 0xDFE1E5 : 0x000000);
        int disabled = rgb("Label.disabledForeground", dark ? 0x6F737A : 0x8C8C8C);
        int separator = rgb("Separator.foreground", dark ? 0x393B40 : 0xEBECF0);
        return PillPalette.from(new PillPalette.IdeColors(bg, fg, disabled, separator, dark));
    }

    private static int rgb(String key, int fallback) {
        Color c = UIManager.getColor(key);
        return (c != null ? c.getRGB() : fallback) | 0xFF000000;
    }

    // ---------------------------------------------------------------- renderers

    private BarRenderer newRenderer(int w, int h) {
        return new BarRenderer(w, h, ui -> themeBroken.get() || BarRenderer.isBuiltInUi(ui), t -> {
            if (themeBroken.compareAndSet(false, true)) {
                LOG.warn("Island Progress Bar: the progress bar theme failed to paint; using the standard bar for this session", t);
            }
        }, IslandController::uiOfIdeProgressBar);
    }

    /**
     * The theme of the progress bar in the focused window's status bar (EDT), or null if it shows none. Theme plugins
     * overwrite one global setting and each bar keeps the look it was created with, so this is what the user sees.
     */
    private static @Nullable Class<?> uiOfIdeProgressBar() {
        IdeFrame frame = IdeFocusManager.getGlobalInstance().getLastFocusedFrame();
        StatusBar statusBar = frame == null ? null : frame.getStatusBar();
        JComponent root = statusBar == null ? null : statusBar.getComponent();
        JProgressBar bar = root == null ? null : BarRenderer.firstProgressBar(root);
        return bar == null || bar.getUI() == null ? null : bar.getUI().getClass();
    }

    // ---------------------------------------------------------------- shutdown

    /** Called once, with the cause, when the coordinator gives up; may come from the AppKit thread. */
    private void onDisabled(Throwable cause) {
        ApplicationManager.getApplication().invokeLater(() -> {
            LOG.warn("Island Progress Bar disabled for this session; the IDE's own progress bar has been restored", cause);
            if (poll != null) poll.cancel(false);
        }, ModalityState.any());
    }

    /**
     * EDT or not: stops polling, removes the pill, restores the IDE's bar and lets go of everything that could keep
     * this plugin's classes reachable after an unload (on the EDT). Idempotent. Order:
     * <ol>
     *   <li>stop the poll and the theme debounce timer (nothing new gets scheduled; ticks check {@code disposed});</li>
     *   <li>the empty tick: the pill's {@code remove()} queues the panel's release on the AppKit thread, and its
     *       timers and AWT listener go on the EDT;</li>
     *   <li>{@link IslandPanel#shutdownNative()}, queued behind that removal: frees the native callbacks once the
     *       AppKit thread has passed them;</li>
     *   <li>restore every IDE bar still hidden, whatever the coordinator's state (a restore queued by a disable on
     *       the AppKit thread may not have run yet), then drop per-project state (project references) and the last
     *       pill.</li>
     * </ol>
     * The message-bus connection goes with this service ({@code connect(this)}).
     */
    private void shutDown() {
        disposed = true;
        if (poll != null) poll.cancel(false);
        Runnable r = () -> {
            Timer t = lafDebounce;
            if (t != null) t.stop();
            lafDebounce = null;
            IslandCoordinator<Project> c = coordinator;
            if (c != null) {
                try { c.tick(List.of(), null); } // removes the pill and restores every bar we hid
                catch (Throwable e) { LOG.warn("Island Progress Bar: final tick failed", e); }
                IslandPanel.shutdownNative();
            }
            // A coordinator that was disabled on the AppKit thread does nothing in that tick: its restore is still
            // queued on the EDT and would find no hider once they are cleared (final-review M4). Restore here, so no
            // inline bar can stay hidden; a queued restore that comes later finds nothing to do.
            restoreAll(hiders.values());
            hiders.clear();
            if (taskSource != null) taskSource.release();
            taskCounts.clear();
            hiddenCounts.clear();
            current = null;
            palette = null;
            ideBarHidden = false;
        };
        if (SwingUtilities.isEventDispatchThread()) r.run();
        else ApplicationManager.getApplication().invokeLater(r, ModalityState.any());
    }

    /** EDT. Shows every inline bar these hiders hid; one failing hider does not stop the others. */
    static void restoreAll(Iterable<IdeBarHider> hs) {
        for (IdeBarHider h : hs) {
            try { if (h.isHidden()) h.restore(); }
            catch (Throwable t) { LOG.warn("Island Progress Bar: could not restore the IDE's progress bar", t); }
        }
    }

    @Override public void dispose() { shutDown(); }
}
