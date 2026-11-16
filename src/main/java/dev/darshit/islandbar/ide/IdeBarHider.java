package dev.darshit.islandbar.ide;

import java.awt.Component;
import java.awt.Container;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Hides the status bar's own inline progress bar (InlineProgressPanel only). EDT only. */
public final class IdeBarHider {
    private static final String TARGET = "InlineProgressPanel";

    private static final class Entry {
        final WeakReference<Component> ref;
        final boolean wasVisible;
        Entry(Component c, boolean wasVisible) { this.ref = new WeakReference<>(c); this.wasVisible = wasVisible; }
    }

    private final List<Entry> cache = new ArrayList<>();
    /** Every panel we may have hidden, with its original visibility; survives a detach so a re-attach is still ours. */
    private final Map<Component, Boolean> original = new WeakHashMap<>();

    /** Returns the number of panels currently hidden. */
    public int hide(Container statusBarRoot, boolean rescan) {
        if (rescan || cache.isEmpty() || anyDetached(statusBarRoot)) scan(statusBarRoot);
        int n = 0;
        for (Entry e : cache) {
            Component c = e.ref.get();
            if (c == null) continue;
            if (c.isVisible()) c.setVisible(false);
            n++;
        }
        return n;
    }

    public void restore() {
        for (Map.Entry<Component, Boolean> e : original.entrySet()) {
            if (e.getValue()) e.getKey().setVisible(true);
        }
        original.clear();
        cache.clear();
    }

    public boolean isHidden() { return !cache.isEmpty(); }

    private boolean anyDetached(Container root) {
        for (Entry e : cache) {
            Component c = e.ref.get();
            if (c == null || !isDescendant(root, c)) return true;
        }
        return false;
    }

    private static boolean isDescendant(Container root, Component c) {
        for (Component p = c; p != null; p = p.getParent()) if (p == root) return true;
        return false;
    }

    private void scan(Container root) {
        // keep already-known entries (so a hidden panel is not recorded as "originally visible"); drop dead ones
        cache.removeIf(e -> {
            Component c = e.ref.get();
            return c == null || !isDescendant(root, c);
        });
        walk(root);
    }

    private void walk(Container parent) {
        for (Component c : parent.getComponents()) {
            if (c.getClass().getSimpleName().equals(TARGET)) {
                if (!known(c)) {
                    boolean was = original.getOrDefault(c, c.isVisible()); // ours already: keep the true original
                    original.put(c, was);
                    cache.add(new Entry(c, was));
                }
            } else if (c instanceof Container k) walk(k);
        }
    }

    private boolean known(Component c) {
        for (Entry e : cache) if (e.ref.get() == c) return true;
        return false;
    }
}
