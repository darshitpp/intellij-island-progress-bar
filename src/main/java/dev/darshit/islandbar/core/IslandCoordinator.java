package dev.darshit.islandbar.core;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.jetbrains.annotations.Nullable;

/**
 * The decisions, IDE-free: per tick it feeds every open project's tasks into that project's {@link IslandState},
 * drives one native pill for the focused project and hides that project's inline IDE progress bar while the pill
 * is up. {@code P} is the project type (IntelliJ's {@code Project}).
 *
 * <p>Threading: {@link #tick} runs on the EDT. The port's failure callback and the {@link Actions} come from the
 * AppKit main thread; anything they do to the IDE (restoring its bar, cancel/hide) hops through {@code edt}.
 * Any fatal problem (native failure, or the platform class behind the task source changing) removes the pill,
 * restores the IDE's bar, disables the coordinator for the rest of the session and reports the cause once.
 */
public final class IslandCoordinator<P> {
    /** The native pill. All calls are asynchronous; failures arrive through the listener given to the factory. */
    public interface Port {
        void show(IslandView v, Actions a);
        void update(IslandView v);
        void remove();
    }

    /** Per-task buttons. Called from any thread; each hops to the EDT and is a no-op for an unknown id. */
    public interface Actions {
        void hide(long id);
        void cancel(long id);
    }

    /** The IDE's own inline progress bar for one project. EDT only; both calls must be idempotent. */
    public interface IdeBar<P> {
        void hide(P project);
        void restore(P project);
    }

    private static final System.Logger LOG = System.getLogger(IslandCoordinator.class.getName());

    private final Function<P, List<TaskRow>> source;
    private final Function<Consumer<Throwable>, Port> factory;
    private final IdeBar<P> ideBar;
    private final BooleanSupplier hideIdeBar;
    private final LongSupplier delayMs;
    private final LongSupplier clockMs;
    private final Executor edt;
    private final Consumer<Throwable> onDisabled;

    private final Map<P, IslandState> states = new HashMap<>();
    private @Nullable Port port;
    private @Nullable P barProject;
    private @Nullable IslandView lastView;
    private List<TaskRow> barSourceRows = List.of(); // the bar project's latest raw rows, to re-evaluate after Hide
    private List<TaskRow> currentRows = List.of();   // latest shown rows: their controls are the live ones
    private @Nullable P hiddenProject;                // whose IDE bar we hid; remembered even after it closes
    private boolean hideFailed;                       // hiding the IDE bar failed once: never try again this session
    private boolean disabled;

    public IslandCoordinator(Function<P, List<TaskRow>> source, Function<Consumer<Throwable>, Port> factory,
                             IdeBar<P> ideBar, BooleanSupplier hideIdeBar, LongSupplier delayMs, LongSupplier clockMs,
                             Executor edt, Consumer<Throwable> onDisabled) {
        this.source = source; this.factory = factory; this.ideBar = ideBar; this.hideIdeBar = hideIdeBar;
        this.delayMs = delayMs; this.clockMs = clockMs; this.edt = edt; this.onDisabled = onDisabled;
    }

    public synchronized boolean isDisabled() { return disabled; }

    /** EDT. An empty {@code open} removes everything (the dispose path). */
    public synchronized void tick(Collection<P> open, @Nullable P focused) {
        if (disabled) return;
        long now = clockMs.getAsLong();
        states.keySet().retainAll(open);
        Map<P, IslandView> views = new HashMap<>();
        Map<P, List<TaskRow>> raw = new HashMap<>();
        for (P p : open) {
            List<TaskRow> rows;
            try { rows = Objects.requireNonNullElse(source.apply(p), List.of()); }
            catch (LinkageError e) { disable(e, true); return; } // the platform class behind the source changed
            catch (RuntimeException e) { rows = List.of(); }       // transient: treat as "no task" this tick
            raw.put(p, rows);
            views.put(p, states.computeIfAbsent(p, k -> new IslandState(delayMs)).update(now, rows));
        }
        // Focus in another app (focused == null): the pill stays with its window until its tasks end.
        P target = focused != null && open.contains(focused) ? focused
                : (barProject != null && open.contains(barProject) ? barProject : null);
        IslandView view = target == null ? IslandView.HIDDEN : views.get(target);
        try { apply(target, view, target == null ? List.of() : raw.get(target)); }
        catch (Throwable e) { disable(e, true); } // port.show/update/remove failed (e.g. native init): once, then inert
    }

    /** EDT, under the lock. Shows/updates/removes the pill for {@code target} and syncs the IDE bar with it. */
    private void apply(@Nullable P target, IslandView view, List<TaskRow> sourceRows) {
        if (target == null || view.primary() == null) {
            removeBar();
            P restored = restoreHiddenUnless(null);
            if (target != null && !target.equals(restored)) restore(target); // idempotent; covers a bar we never hid
            return;
        }
        P want = hideIdeBar.getAsBoolean() && !hideFailed ? target : null;
        if (port == null || !target.equals(barProject)) {
            removeBar();
            restoreHiddenUnless(want);
            Port[] created = new Port[1];
            created[0] = factory.apply(t -> failed(created[0], t));
            port = created[0]; barProject = target;
            currentRows = view.rows();
            port.show(view, new PortActions(created[0]));
        } else {
            currentRows = view.rows();
            if (!view.equals(lastView)) port.update(view);
            restoreHiddenUnless(want);
        }
        lastView = view;
        barSourceRows = sourceRows;
        if (want != null) hideIde(want); // re-applied every tick: the IDE may re-show its panel
    }

    private void hideIde(P project) {
        try {
            ideBar.hide(project);
            hiddenProject = project;
        } catch (RuntimeException | LinkageError e) {
            LOG.log(System.Logger.Level.WARNING, "Could not hide the IDE progress bar; leaving it visible", e);
            hideFailed = true;
            hiddenProject = null;
            restore(project);
        }
    }

    /** Restores the hidden project unless it is {@code keep}; returns the project it restored, if any. */
    private @Nullable P restoreHiddenUnless(@Nullable P keep) {
        P h = hiddenProject;
        if (h == null || h.equals(keep)) return null;
        hiddenProject = null;
        restore(h);
        return h;
    }

    private void restore(P project) {
        try { ideBar.restore(project); }
        catch (RuntimeException | LinkageError e) {
            LOG.log(System.Logger.Level.WARNING, "Could not restore the IDE progress bar", e);
        }
    }

    /**
     * A native failure, on the AppKit main thread: stop for the rest of the session. The failed port is always
     * removed too (idempotent), so whatever it runs, such as a frame loop, stops with it.
     */
    private synchronized void failed(@Nullable Port from, Throwable cause) {
        if (from != null) {
            try { from.remove(); } catch (Throwable ignored) { /* already failing */ }
        }
        if (port == from) clearBar();
        disable(cause, false);
    }

    /** @param onEdt true inside {@link #tick} (already on the EDT); otherwise the IDE-bar restore hops via edt */
    private void disable(Throwable cause, boolean onEdt) {
        if (disabled) return;
        disabled = true;
        P toRestore = hiddenProject != null ? hiddenProject : barProject;
        hiddenProject = null;
        try { removeBar(); } catch (Throwable ignored) { /* the cause is reported below */ }
        states.clear();
        if (toRestore != null) {
            if (onEdt) restore(toRestore);
            else hop(() -> restore(toRestore));
        }
        onDisabled.accept(cause);
    }

    private void removeBar() {
        Port p = port;
        if (p == null) return;
        try { p.remove(); } finally { clearBar(); } // never keep a port that cannot be removed
    }

    private void clearBar() {
        port = null; barProject = null; lastView = null; currentRows = List.of(); barSourceRows = List.of();
    }

    private void hop(Runnable r) {
        try { edt.execute(r); }
        catch (RuntimeException e) { LOG.log(System.Logger.Level.WARNING, "Could not reach the EDT", e); }
    }

    private synchronized @Nullable TaskRow liveRow(Port from, long id) {
        if (disabled || port != from) return null; // a stale button: its pill is already gone
        for (TaskRow r : currentRows) if (r.id() == id) return r;
        return null;
    }

    /** EDT: hide one task and re-evaluate the pill at once (removing it and restoring the IDE bar if it was the last). */
    private synchronized void hideTask(Port from, long id) {
        if (liveRow(from, id) == null) return;
        P project = barProject;
        IslandState s = project == null ? null : states.get(project);
        if (s == null) return;
        s.hide(id);
        List<TaskRow> rows = barSourceRows;
        try { apply(project, s.update(clockMs.getAsLong(), rows), rows); }
        catch (Throwable e) { disable(e, true); }
    }

    /** Bound to one port, so a late click on a removed pill does nothing. */
    private final class PortActions implements Actions {
        private final Port from;

        PortActions(Port from) { this.from = from; }

        @Override public void hide(long id) { hop(() -> hideTask(from, id)); }

        @Override public void cancel(long id) {
            hop(() -> control(id, "cancel", (r, c) -> c.cancel()));
        }

        /** Runs outside the lock: platform code must not run while the AppKit thread may wait on it. */
        private void control(long id, String what, java.util.function.BiConsumer<TaskRow, TaskControls> call) {
            TaskRow r = liveRow(from, id);
            if (r == null || r.controls() == null) return;
            try { call.accept(r, r.controls()); }
            catch (RuntimeException | LinkageError e) { // one task's failure must not break the pill or disable it
                LOG.log(System.Logger.Level.WARNING, "Task " + what + " failed", e);
            }
        }
    }
}
