package dev.darshit.islandbar.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/** Decides which tasks the pill shows. update() runs on the EDT, hide() on the AppKit main thread, hence synchronized. */
public final class IslandState {
    private final LongSupplier delayMs; // read on every update, so a settings change applies at once
    private final Map<Long, Long> firstSeenMs = new HashMap<>();
    private final Set<Long> hidden = new HashSet<>();

    public IslandState(LongSupplier delayMs) { this.delayMs = delayMs; }

    /** @param running oldest first */
    public synchronized IslandView update(long nowMs, List<TaskRow> running) {
        Set<Long> ids = new HashSet<>();
        for (TaskRow r : running) ids.add(r.id());
        firstSeenMs.keySet().retainAll(ids);
        hidden.retainAll(ids); // a task that disappears and returns is a new task

        long delay = delayMs.getAsLong();
        List<TaskRow> rows = new ArrayList<>();
        for (TaskRow r : running) {
            long since = firstSeenMs.computeIfAbsent(r.id(), k -> nowMs);
            if (nowMs - since < delay || hidden.contains(r.id())) continue;
            Double f = r.fraction();
            if (f != null) f = f.isNaN() ? null : Math.max(0.0, Math.min(1.0, f));
            rows.add(new TaskRow(r.id(), f, r.name(), r.detail(), r.cancellable(), r.cancelTooltip(),
                    r.stopping(), r.controls()));
        }
        if (rows.isEmpty()) return IslandView.HIDDEN;
        return new IslandView(rows.get(0), List.copyOf(rows), rows.size() >= 2);
    }

    public synchronized void hide(long id) {
        if (firstSeenMs.containsKey(id)) hidden.add(id);
    }
}
