package dev.darshit.islandbar.mac;

import com.intellij.openapi.diagnostic.Logger;
import dev.darshit.islandbar.ide.IslandBundle;
import dev.darshit.islandbar.core.BarTint;
import dev.darshit.islandbar.core.IslandCoordinator;
import dev.darshit.islandbar.core.IslandView;
import dev.darshit.islandbar.core.Motion;
import dev.darshit.islandbar.core.Percent;
import dev.darshit.islandbar.core.PillGeometry;
import dev.darshit.islandbar.core.PillPalette;
import dev.darshit.islandbar.core.PillStyle;
import dev.darshit.islandbar.core.TaskRow;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import org.jetbrains.annotations.Nullable;

import static dev.darshit.islandbar.mac.Objc.*;

/**
 * The native pill: a stock non-activating {@code NSPanel} at the top centre of the screen holding the focused IDE
 * window, and a drop-down list of up to 8 rows. Three compact shapes ({@link PillStyle}):
 * <ul>
 *   <li>B: two lines hanging under the menu bar (name and percentage; bar and Cancel), a chevron when 2+ tasks run;</li>
 *   <li>A and B: a truncated name gets a small inline chevron right after it that opens the same list (the list
 *       rows wrap a long name to 3 lines), and the name's tooltip is the full name;</li>
 *   <li>A: one line inside the menu bar (name, bar, percentage, Cancel), a chevron when 2+ tasks run. On a screen
 *       with a camera notch the middle of the menu bar is hidden, so A is drawn as B there (switched live);</li>
 *   <li>C: a ring and the percentage under the menu bar; a click anywhere on it opens the list (even for one task).
 *       The ring is two {@code CAShapeLayer}s: a determinate arc from the fraction, or a short arc spun by a
 *       Core Animation rotation when indeterminate (no Java timer). It takes no bar frames.</li>
 * </ul>
 *
 * <p>Motion ({@link Motion} holds every duration): the pill fades in when created and out when removed, and the
 * list's rows fade in when it opens; only opacity is animated (by Core Animation, no Java timer, no callback), never
 * the panel's size or position, and never per frame. Under the system's Reduce Motion setting (read at create and on
 * every {@link #reposition}) the fades are instant and style C's ring does not spin.
 *
 * <p>Threading: every public method may be called from any thread; all AppKit work is queued to the AppKit main
 * thread, where every field below except the atomics and {@link #occluded} is read and written. Button callbacks
 * arrive on the main thread and call {@code actions} as given (the coordinator's actions hop to the EDT
 * themselves); {@code onListOpenChanged} and {@code onRowCleared} (a row slot was blanked for a task, which then
 * needs a fresh frame) are also called on the main thread. Any native failure goes to
 * {@code failure} once per failing call.
 *
 * <p>Native pointers kept across calls are only the panel and the views this class created (each held at +1 and
 * released in {@link #remove}). Screens and windows are re-resolved on every call.
 */
public final class IslandPanel {
    /**
     * Latest bar pixels: {@code primary} is the pill bar (null = unchanged; {@code primaryW} px wide, {@code barPt}
     * the theme's preferred bar height in points), {@code rows} are list bars by task id.
     */
    public record Frames(int @Nullable [] primary, int primaryW, double barPt, Map<Long, int[]> rows) {}

    /** The pill bar view's size in pixels (2 px per point), written on the main thread, read by the frame producer; 0 = not laid out. */
    private volatile int barPxW, barPxH;
    public int barPxWidth() { return barPxW; }
    public int barPxHeight() { return barPxH; }

    // AppKit values (Apple silicon ABI)
    private static final long STYLE_NONACTIVATING_BORDERLESS = 128, BACKING_BUFFERED = 2;
    private static final long LEVEL_MAIN_MENU_PLUS_3 = 27, COLLECTION_BEHAVIOR = 337;
    private static final long OCCLUSION_VISIBLE = 1L << 1;
    private static final long LINE_BREAK_TRUNCATING_TAIL = 4, LINE_BREAK_WORD_WRAPPING = 0, BEZEL_RECESSED = 13, IMAGE_ONLY = 1;
    private static final long CORNERS_BOTTOM = 1 | 2; // kCALayerMinXMinYCorner | kCALayerMaxXMinYCorner
    private static final double FONT_WEIGHT_SEMIBOLD = 0.3;
    private static final String SPIN_KEY = "islandSpin", FADE_KEY = "islandFade";
    /** Run-loop modes for the delayed order-out: common modes plus AWT's own (the AppKit thread may run in it). */
    private static final String[] ORDER_OUT_MODES = {"kCFRunLoopCommonModes", "AWTRunLoopMode"};

    // layout in points, from the approved mockups (compact-multi-v6.html, expanded-list-v2.html)
    private static final double PAD_TOP = 8, PAD_LEFT = PillGeometry.LIST_PAD, PAD_RIGHT = PillGeometry.LIST_PAD, LINE1_H = 16, LINE_GAP = 4;
    private static final double CHEVRON_X = 8, CHEVRON_W = 34, CHEVRON_GAP = 10, BUTTON = PillGeometry.BUTTON, GAP = PillGeometry.GAP, CORNER = 14;
    private static final double ROW_NAME_W = PillGeometry.ROW_NAME_W, ROW_BTN_GAP = PillGeometry.ROW_BTN_GAP, MORE_H = 18;
    // the inline chevron right after a truncated pill name (A and B); the name gives up its width only when truncated
    private static final double NAME_CHEV_W = 18, NAME_CHEV_SIZE = 10;
    /** The truncation test reserves at least this for the percentage, so "9%" -> "10%" cannot flip it. */
    private static final double PCT_RESERVE = 36;
    /** Once a name is judged truncated it stays so until it fits with this much room (or the name changes). */
    private static final double LONG_NAME_HYSTERESIS = 4;
    private static final double STANDARD_BAR_PT = 6, ROW_BAR_PT = PillGeometry.ROW_BAR_PX_H / 2.0;
    // style A (compact-pill.html "A"): one line in the menu bar
    private static final double A_PAD = 10, A_CHEVRON_X = 4, A_CHEVRON_W = 32, A_NAME_MAX = 120, A_PCT_MIN = 28;
    private static final double A_CORNER = 10, A_BAR_INSET = 8;
    // style C (compact-pill.html "C"): a 20 pt ring, 3 pt stroke, radius 8, and the percentage
    private static final double RING = 20, RING_LINE = 3, RING_R = 8, RING_GAP = 6, C_CORNER = 15;
    /** The visible part of an indeterminate (spinning) ring. */
    private static final double SPIN_ARC = 0.25;
    private static final Logger LOG = Logger.getInstance(IslandPanel.class);

    private final Consumer<Throwable> failure;
    /** The style from the settings. */
    private final PillStyle requested;
    /**
     * Main thread: the style drawn on the current screen ({@link PillStyle#effective}: A is shown as B on a notched
     * screen). Re-evaluated on every layout, so moving between a notched and an external screen switches it live.
     */
    private PillStyle style;
    private final IslandCoordinator.Actions actions;
    private final Consumer<Boolean> onListOpenChanged;
    private final LongConsumer onRowCleared;

    private final AtomicReference<Frames> pendingFrames = new AtomicReference<>();
    private final AtomicBoolean drainQueued = new AtomicBoolean();
    private volatile boolean occluded;
    /** A native call of this panel failed: its removal skips the fade (teardown must not depend on more native work). */
    private volatile boolean failedOnce;

    // ---- motion (static: one setting for every panel) ----
    /** The system's Reduce Motion setting, read on the main thread at create and on every {@link #reposition}. */
    private static volatile boolean reduceMotion;
    /** Set by {@link #shutdownNative}: removals from then on are immediate (no fade on the unload/exit path). */
    private static volatile boolean closing;
    /**
     * Main thread only: the last removed panel while it fades out, held at +1 (this plugin created it). It has a
     * delayed native {@code orderOut:} pending (no Java callback); it is ordered out and released for good by the next
     * panel's creation, the next removal, or {@link #shutdownNative}, whichever comes first.
     */
    private static @Nullable MemorySegment lingering;

    // ---- main thread only ----
    private @Nullable MemorySegment panel;
    private MemorySegment target = NIL; // the shared button target: one per plugin load, released by shutdownNative()
    private final List<MemorySegment> owned = new ArrayList<>(); // +1 references released in remove()
    private final List<MemorySegment> buttons = new ArrayList<>(); // their targets are set to nil in remove()
    private @Nullable PillPalette palette;
    private IslandView view = IslandView.HIDDEN;
    private boolean listOpen, removed, visibleToUser = true;
    private double barPt = STANDARD_BAR_PT;
    private boolean symbols = true;
    private V content, chevron, chevronGlyph, name, percent, bar, cancel, more;
    /** A and B: the small chevron right after the pill's name, shown only while the name is truncated. */
    private @Nullable V nameChevron;
    /** Main thread: the pill's name does not fit its line (decided in the header layout, with hysteresis). */
    private boolean longName;
    private @Nullable String longNameText;
    // style C only: the ring view and its two shape layers (owned, released in remove())
    private @Nullable V ring;
    private @Nullable MemorySegment ringTrack, ringArc;
    private @Nullable Double ringEnd;
    private @Nullable Integer ringTrackColour, ringArcColour;
    private boolean spinning;
    private @Nullable RingColours ringColours;
    private final List<Row> rows = new ArrayList<>();
    private @Nullable String chevronGlyphKey;

    /** Style C's ring colours (the IDE progress bar's track and fill, plus the Stopping and Paused tints). */
    public record RingColours(int track, int normal, int stopping, int paused) {}

    public IslandPanel(Consumer<Throwable> failure, PillStyle style, IslandCoordinator.Actions actions,
                       Consumer<Boolean> onListOpenChanged, LongConsumer onRowCleared) {
        this.failure = failure; this.requested = style; this.style = style; this.actions = actions; this.onListOpenChanged = onListOpenChanged;
        this.onRowCleared = onRowCleared;
    }

    /** The standard bar's tint for a row; a theme bar ignores it. */
    public static BarTint tintOf(TaskRow r) {
        return r.stopping() ? BarTint.STOPPING : r.paused() ? BarTint.PAUSED : BarTint.NORMAL;
    }

    // ---------------------------------------------------------------- public API (any thread)

    /**
     * Creates the panel once (fading it in, {@link Motion.Fade#SHOW}), then just updates. Clicks work at once: the
     * fade is the content layer's opacity only.
     */
    public void show(IslandView v, PillPalette p) {
        runOnMain(() -> {
            if (removed) return;
            palette = p;
            boolean created = panel == null, replaced = false;
            if (created) replaced = create();
            applyView(v);
            applyColours();
            if (visibleToUser) v(panel, "orderFrontRegardless");
            if (created) fade(content, 0, 1, Motion.fadeSeconds(Motion.Fade.SHOW, reduceMotion, replaced));
        });
    }

    /** Whether Reduce Motion is on (as last read on the AppKit main thread); any thread. */
    public static boolean reduceMotion() { return reduceMotion; }

    /** Main thread. Two messages; NSWorkspace keeps the value current itself (no observer, no new native callback). */
    private static void readReduceMotion() {
        boolean system = b(p(cls("NSWorkspace"), "sharedWorkspace"), "accessibilityDisplayShouldReduceMotion");
        reduceMotion = Motion.reduceMotion(system, null);
    }

    public void update(IslandView v) { runOnMain(() -> { if (panel != null) applyView(v); }); }

    /** Stored, and applied live to an existing panel (no new panel). */
    public void applyPalette(PillPalette p) {
        runOnMain(() -> { palette = p; if (panel != null) applyColours(); });
    }

    /** Style C: stored, and applied live to an existing ring. */
    public void setRingColours(RingColours c) {
        runOnMain(() -> { ringColours = c; if (panel != null) updateRing(); });
    }

    /**
     * Latest wins: at most one drain is queued; frames that arrive before it runs replace each other per bar. A bar
     * the newer frame leaves out (null primary, missing row) keeps the undrained older picture, so an "unchanged"
     * frame never discards a change still waiting to be drawn.
     */
    public void setFrames(Frames f) {
        pendingFrames.getAndUpdate(old -> old == null ? f : merge(old, f));
        if (drainQueued.compareAndSet(false, true)) runOnMain(this::drainFrames);
    }

    private static Frames merge(Frames older, Frames newer) {
        Frames pf = newer.primary() != null ? newer : older;
        int[] primary = pf.primary();
        if (older.rows().isEmpty()) return new Frames(primary, pf.primaryW(), pf.barPt(), newer.rows());
        Map<Long, int[]> rows = new java.util.HashMap<>(older.rows());
        rows.putAll(newer.rows());
        return new Frames(primary, pf.primaryW(), pf.barPt(), rows);
    }

    /** Re-resolves the screen (and the occlusion state); call about once a second. */
    public void reposition() {
        runOnMain(() -> {
            if (panel == null) return;
            readReduceMotion(); // the layout below starts or stops the ring's spin to match
            if (listOpen && !b(app(), "isActive")) setListOpen(false); // app deactivated
            layout();
            occluded = (n(panel, "occlusionState") & OCCLUSION_VISIBLE) == 0;
        });
    }

    public void setVisibleToUser(boolean visible) {
        runOnMain(() -> {
            visibleToUser = visible;
            if (panel == null) return;
            if (visible) { layout(); v(panel, "orderFrontRegardless"); }
            else { setListOpen(false); v(panel, "orderOut:", NIL); updateRing(); } // stop a spin nobody sees
        });
    }

    /** True when the panel is not visible on screen; refreshed by {@link #reposition}. */
    public boolean isOccluded() { return occluded; }

    /** Closes the list if it is open (click outside the panel, from the EDT's AWT listener). */
    public void closeList() { runOnMain(() -> setListOpen(false)); }

    /**
     * Idempotent. Hides and releases the panel and every view this class created. A visible panel fades out
     * ({@link Motion.Fade#HIDE}): its buttons are dead and it ignores the mouse at once, its model opacity is 0 at once
     * (so it is invisible even if the animation never runs), and a native delayed {@code orderOut:} takes it off screen
     * after the fade, with no Java callback. It is released for good by {@link #finishLingering}. No fade under Reduce
     * Motion, after a native failure of this panel, or once {@link #shutdownNative} has been called.
     */
    public void remove() {
        pendingFrames.set(null);
        runOnMain(() -> {
            if (removed) return;
            removed = true;
            boolean wasOpen = listOpen;
            listOpen = false;
            clearActions(this); // the static callbacks must not keep this panel (and the coordinator) reachable
            if (panel != null) {
                boolean faded = false;
                try {
                    // a button still in a click-tracking loop must not send its action to the target after teardown
                    for (MemorySegment b : buttons) {
                        // nil target + live action would walk the responder chain (NSApplication implements hide:)
                        v(b, "setTarget:", NIL);
                        v(b, "setAction:", NIL);
                        v(b, "setEnabled:", false);
                    }
                    if (ringArc != null) v(ringArc, "removeAllAnimations");
                    double s = closing || failedOnce || !visibleToUser ? 0 : Motion.fadeSeconds(Motion.Fade.HIDE, reduceMotion, false);
                    faded = s > 0 && fadeOut(panel, s);
                    if (!faded) v(panel, "orderOut:", NIL);
                } finally {
                    if (faded) {
                        // at most one: an earlier one is ordered out and released now
                        try { finishLingering(); } catch (Throwable t) { LOG.warn("Island Progress Bar: could not release a fading pill", t); }
                        lingering = panel; // our +1 moves there
                    } else {
                        release(panel);
                    }
                    panel = null;
                    for (MemorySegment o : owned) release(o);
                    owned.clear();
                    buttons.clear();
                    rows.clear();
                    ring = null; ringTrack = null; ringArc = null; spinning = false;
                }
            }
            if (wasOpen) onListOpenChanged.accept(false);
        });
    }

    // ---------------------------------------------------------------- creation

    private void runOnMain(Runnable r) { used = true; onMain(r, t -> { failedOnce = true; failure.accept(t); }); }

    private static volatile boolean used;

    /**
     * Final, for plugin unload or IDE exit: frees the native callbacks once the AppKit main thread has run everything
     * queued so far. Call it after the last panel's {@link #remove()} (on the same thread, so the removal is queued
     * first). Never blocks; idempotent; does nothing if no panel was ever used. No panel works after it. A panel still
     * fading out is ordered out and released first (queued behind the removal, ahead of the native teardown), and any
     * removal not yet run skips its fade, so teardown stays immediate.
     */
    public static void shutdownNative() {
        if (!used) return;
        closing = true;
        onMain(IslandPanel::finishLingering, t -> LOG.warn("Island Progress Bar: could not release a fading pill", t));
        Objc.shutdown();
    }

    /**
     * Main thread: the fading panel (if any) leaves the screen and is released now; its pending delayed
     * {@code orderOut:} is cancelled first (it holds its own reference, so either order is safe). True if there was one.
     */
    private static boolean finishLingering() {
        MemorySegment l = lingering;
        if (l == null) return false;
        lingering = null;
        try {
            v(cls("NSObject"), "cancelPreviousPerformRequestsWithTarget:", l);
            v(l, "orderOut:", NIL);
        } finally { release(l); }
        return true;
    }

    /**
     * Main thread: fades {@code panel}'s content out over {@code seconds} and schedules a native delayed
     * {@code orderOut:} ({@code performSelector:withObject:afterDelay:inModes:} retains the panel until it fires, so it
     * is safe after our release and after the native teardown: no Java code runs). False, with nothing changed that
     * matters, if any step failed: the caller then orders the panel out at once.
     */
    private boolean fadeOut(MemorySegment pn, double seconds) {
        try {
            if (!b(pn, "isVisible")) return false;
            v(pn, "setIgnoresMouseEvents:", true); // clicks go to whatever is below the fading pill
            MemorySegment layer = p(content.id, "layer");
            MemorySegment shown = layer.equals(NIL) ? NIL : p(layer, "presentationLayer");
            double from = shown.equals(NIL) ? 1 : fl(shown, "opacity"); // a fade-in still running continues from here
            fade(content, from, 0, seconds);
            MemorySegment modes = p(cls("NSArray"), "arrayWithObject:", nsString(ORDER_OUT_MODES[0]));
            for (int i = 1; i < ORDER_OUT_MODES.length; i++) modes = p(modes, "arrayByAddingObject:", nsString(ORDER_OUT_MODES[i]));
            v(pn, "performSelector:withObject:afterDelay:inModes:", sel("orderOut:"), NIL,
                    Motion.orderOutDelaySeconds(seconds), modes);
            return true;
        } catch (Throwable t) {
            LOG.warn("Island Progress Bar: fade-out failed; hiding the pill at once", t);
            return false;
        }
    }

    /**
     * Main thread: the view's model alpha becomes {@code to} at once (so the end state never depends on the animation
     * running), and, for {@code seconds > 0}, an ease-in-out opacity animation from {@code from} runs on its layer in
     * the render server (no Java timer, no callback). A new fade on the same view replaces the running one.
     */
    private static void fade(V view, double from, double to, double seconds) {
        v(view.id, "setAlphaValue:", to);
        if (seconds <= 0) return;
        MemorySegment layer = p(view.id, "layer");
        if (layer.equals(NIL)) return;
        v(layer, "addAnimation:forKey:", opacityAnimation(from, to, seconds), nsString(FADE_KEY));
    }

    private static MemorySegment opacityAnimation(double from, double to, double seconds) {
        MemorySegment a = p(cls("CABasicAnimation"), "animationWithKeyPath:", nsString("opacity"));
        v(a, "setFromValue:", p(cls("NSNumber"), "numberWithDouble:", from));
        v(a, "setToValue:", p(cls("NSNumber"), "numberWithDouble:", to));
        v(a, "setDuration:", seconds);
        v(a, "setTimingFunction:", p(cls("CAMediaTimingFunction"), "functionWithName:", nsString("easeInEaseOut")));
        return a;
    }

    private static MemorySegment app() { return p(cls("NSApplication"), "sharedApplication"); }

    /** True if it replaced a panel that was still fading out (that one is gone now, so this one shows at once). */
    private boolean create() {
        boolean replaced = finishLingering();
        readReduceMotion();
        target = actionTarget(this, this::toggleList, actions::cancel, actions::togglePause, actions::hide);
        Screen s = screen();
        applyEffectiveStyle(s); // before the views are made: they are built for the effective style
        PillGeometry.Rect r = compactRect(s);
        MemorySegment pn = p(p(cls("NSPanel"), "alloc"), "initWithContentRect:styleMask:backing:defer:",
                rect(r.x(), r.y(), r.w(), r.h()), STYLE_NONACTIVATING_BORDERLESS, BACKING_BUFFERED, false);
        panel = pn;
        v(pn, "setReleasedWhenClosed:", false);
        v(pn, "setFloatingPanel:", true);
        v(pn, "setHasShadow:", false);
        v(pn, "setOpaque:", false);
        v(pn, "setBackgroundColor:", p(cls("NSColor"), "clearColor")); // the rounded content layer paints the pill
        v(pn, "setLevel:", LEVEL_MAIN_MENU_PLUS_3);
        v(pn, "setCollectionBehavior:", COLLECTION_BEHAVIOR);

        content = view("NSView", r.w(), r.h());
        MemorySegment layer = p(content.id, "layer");
        v(layer, "setCornerRadius:", cornerRadius());
        v(layer, "setMaskedCorners:", CORNERS_BOTTOM);
        v(layer, "setMasksToBounds:", true);
        v(pn, "setContentView:", content.id);

        symbols = !symbolImage("chevron.down", 16).equals(NIL);
        double font = headerFontSize();
        if (style == PillStyle.C) {
            createRing();
        } else {
            if (!symbols) chevronGlyph = add(view("NSView", CHEVRON_W, CHEVRON_W));
            chevron = add(button("chevron:", "chevron.down", chevronSymbolSize(), null));
            v(chevron.id, "setBezelStyle:", BEZEL_RECESSED);
            v(chevron.id, "setBordered:", true);
            v(chevron.id, "setShowsBorderOnlyWhileMouseInside:", true); // the faint fill appears on hover only
        }
        name = add(label(font, false));
        if (style != PillStyle.C) {
            V nc = add(button("chevron:", null, NAME_CHEV_SIZE, null)); // same action as the chevron: no new stub
            v(nc.id, "setBezelStyle:", BEZEL_RECESSED);
            v(nc.id, "setBordered:", true);
            v(nc.id, "setShowsBorderOnlyWhileMouseInside:", true);
            v(nc.id, "setHidden:", true);
            nc.hidden = true;
            nameChevron = nc;
        }
        percent = add(label(font, true));
        bar = add(view("NSView", 1, 1));
        cancel = add(button("cancel:", "xmark", 10, "✕"));
        more = add(label(11, false));
        v(more.id, "setWantsLayer:", true); // fades in with the list (see ensureRows)
        // style C: one invisible button over the whole ring and percentage (added last, so it is on top)
        if (style == PillStyle.C) chevron = add(button("chevron:", null, 0, null));
        return replaced;
    }

    /** Style C: a layer-backed view holding the track and the arc, both on one circle that starts at 12 o'clock. */
    private void createRing() {
        V r = add(view("NSView", RING, RING));
        ring = r;
        MemorySegment path = RingPath.circle(RING / 2, RING / 2, RING_R);
        try {
            ringTrack = shape(path);
            ringArc = shape(path);
        } finally { RingPath.release(path); } // the layers keep their own reference
        MemorySegment host = p(r.id, "layer");
        v(host, "addSublayer:", ringTrack);
        v(host, "addSublayer:", ringArc);
        v(ringArc, "setStrokeEnd:", 0.0);
        ringEnd = 0.0;
    }

    private MemorySegment shape(MemorySegment path) {
        MemorySegment l = p(cls("CAShapeLayer"), "layer");
        v(l, "retain");
        owned.add(l);
        v(l, "setFrame:", rect(0, 0, RING, RING)); // anchor point in the centre: the spin turns about the ring's centre
        v(l, "setContentsScale:", 2.0);            // a standalone layer does not inherit the Retina scale
        v(l, "setPath:", path);
        v(l, "setFillColor:", NIL);
        v(l, "setLineWidth:", RING_LINE);
        return l;
    }

    private V add(V child) { v(content.id, "addSubview:", child.id); return child; }

    private V own(MemorySegment id) { owned.add(id); return new V(id); }

    private V view(String cls, double w, double h) {
        V view = own(p(p(cls(cls), "alloc"), "initWithFrame:", rect(0, 0, w, h)));
        v(view.id, "setWantsLayer:", true);
        return view;
    }

    private V label(double size, boolean digits) {
        MemorySegment l = p(cls("NSTextField"), "labelWithString:", nsString(""));
        v(l, "retain");
        V label = own(l);
        v(l, "setFont:", font(size, digits));
        v(l, "setLineBreakMode:", LINE_BREAK_TRUNCATING_TAIL);
        v(l, "setDrawsBackground:", false);
        return label;
    }

    private static MemorySegment font(double size, boolean digits) {
        return digits
                ? p(cls("NSFont"), "monospacedDigitSystemFontOfSize:weight:", size, 0.0)
                : p(cls("NSFont"), "systemFontOfSize:", size);
    }

    private double cornerRadius() { return style == PillStyle.A ? A_CORNER : style == PillStyle.C ? C_CORNER : CORNER; }

    private double headerFontSize() { return style == PillStyle.B ? 13 : 12; } // A and C follow compact-pill.html (12 px)

    private double chevronSymbolSize() { return style == PillStyle.A ? 12 : 16; }

    /**
     * Main thread. Sets {@link #style} for the screen the pill is on: A on a notched screen is drawn as B. Only A and
     * B can swap, and they use the same views, so an existing panel is re-styled in place (corner radius, fonts,
     * chevron size); the layout that follows places everything for the new style. Logged once per change.
     */
    private void applyEffectiveStyle(@Nullable Screen s) {
        PillStyle e = PillStyle.effective(requested, s != null && s.notch());
        if (e == style) return;
        style = e;
        LOG.info(e == requested
                ? "Island Progress Bar: this screen has no camera notch; showing style " + e
                : "Island Progress Bar: this screen has a camera notch; style " + requested + " is shown as style " + e);
        if (panel == null || content == null) return; // create() builds the views for the new style
        v(p(content.id, "layer"), "setCornerRadius:", cornerRadius());
        for (V l : new V[]{name, percent}) {
            v(l.id, "setFont:", font(headerFontSize(), l == percent));
            l.text = null; // re-measure with the new font on the next text()
        }
        chevron.size = chevronSymbolSize();
        chevron.symbolName = null; // placeChevron() sets the symbol again at the new size
    }

    /** Main thread: whether any connected screen has a camera notch (Apple's top safe-area rule). */
    private static boolean anyScreenHasNotchNow() {
        MemorySegment screens = p(cls("NSScreen"), "screens");
        long count = n(screens, "count");
        for (long i = 0; i < count; i++) {
            if (PillStyle.hasNotch(safeAreaTop(p(screens, "objectAtIndex:", i)))) return true;
        }
        return false;
    }

    /**
     * Any thread, never blocks: answers on the AppKit main thread whether any connected screen has a camera notch.
     * Nothing is answered after {@link #shutdownNative()}. Like a panel, a call counts as using the native bridge, so
     * the bridge is torn down at unload.
     */
    public static void anyScreenHasNotch(Consumer<Boolean> onResult, Consumer<Throwable> onError) {
        used = true;
        onMain(() -> onResult.accept(anyScreenHasNotchNow()), onError);
    }

    private static MemorySegment symbolImage(String name, double pointSize) {
        MemorySegment img = p(cls("NSImage"), "imageWithSystemSymbolName:accessibilityDescription:", nsString(name), NIL);
        if (img.equals(NIL)) return NIL;
        MemorySegment cfg = p(cls("NSImageSymbolConfiguration"), "configurationWithPointSize:weight:",
                pointSize, FONT_WEIGHT_SEMIBOLD);
        MemorySegment sized = p(img, "imageWithSymbolConfiguration:", cfg);
        return sized.equals(NIL) ? img : sized;
    }

    /**
     * A borderless button; its image is the system symbol, or {@code fallback} text when there is none. A null
     * {@code symbolName} makes an invisible click area.
     */
    private V button(String action, @Nullable String symbolName, double size, @Nullable String fallback) {
        V b = own(p(p(cls("NSButton"), "alloc"), "initWithFrame:", rect(0, 0, BUTTON, BUTTON)));
        buttons.add(b.id);
        v(b.id, "setBordered:", false);
        v(b.id, "setTitle:", nsString(""));
        v(b.id, "setTarget:", target);
        v(b.id, "setAction:", sel(action));
        b.size = size;
        if (symbolName != null) b.symbol(symbolName, fallback);
        return b;
    }

    // ---------------------------------------------------------------- state

    private void applyView(IslandView v) {
        view = v;
        if (v.primary() == null) longName = false;
        if (!canList()) setListOpen(false);
        if (listOpen) ensureRows(listedRows()); // tasks that started while the list is open (style C opens for one)
        layout();
    }

    private void toggleList() {
        try { setListOpen(!listOpen); }
        catch (Throwable t) { failure.accept(t); }
    }

    private void setListOpen(boolean open) {
        if (panel == null) open = false;
        if (open && !canList()) open = false;
        if (open == listOpen) return;
        listOpen = open;
        if (open) ensureRows(listedRows());
        layout();
        if (open) fadeInRows();
        onListOpenChanged.accept(open);
    }

    /**
     * A and B: the chevron (2+ tasks), or the inline chevron after a truncated name (any task count). C: a click on
     * the ring opens the list for any task.
     */
    private boolean canList() {
        if (style == PillStyle.C || longName) return !view.rows().isEmpty();
        return view.showChevron() && view.rows().size() >= 2;
    }

    /**
     * A and B: whether the pill's name is truncated in a line {@code avail} points wide (measured natively: the
     * label's single-line width from {@code sizeToFit}). The test uses the full line width (the inline chevron's
     * space is reserved only after the answer, so the answer cannot feed back into itself); once on, it stays on
     * for the same name until the name fits with {@link #LONG_NAME_HYSTERESIS} to spare.
     */
    private boolean decideLongName(String text, double avail) {
        double fit = name.fitWidth();
        boolean was = longName && text.equals(longNameText);
        longName = was ? fit > avail - LONG_NAME_HYSTERESIS : fit > avail + 0.5;
        longNameText = text;
        return longName;
    }

    /** Places (or hides) the inline chevron right after the name; it shows the same up/down state as the chevron. */
    private void placeNameChevron(Box box, boolean show, double x, double top, double h) {
        V nc = nameChevron;
        if (nc == null) return;
        nc.hidden(!show);
        if (!show) return;
        box.place(nc, x, top, NAME_CHEV_W, h);
        nc.symbol(listOpen ? "chevron.up" : "chevron.down", listOpen ? "▴" : "▾");
        nc.tip(IslandBundle.message(listOpen ? "tip.name.hide" : "tip.name.show"));
    }

    /**
     * Main thread, when the list opens: the shown rows (and "+N more") fade in ({@link Motion.Fade#LIST}); the panel
     * itself grows at once. Closing is instant: fading the rows out would keep the panel large until a completion
     * callback (a new native callback) and make people wait. Rows added while the list stays open just appear.
     */
    private void fadeInRows() {
        double s = Motion.fadeSeconds(Motion.Fade.LIST, reduceMotion, false);
        if (s <= 0 || !visibleToUser) return;
        MemorySegment a = opacityAnimation(0, 1, s); // addAnimation copies it: one object for every layer
        MemorySegment key = nsString(FADE_KEY);
        List<V> views = new ArrayList<>();
        for (Row r : rows) {
            if (r.taskId != null) views.addAll(List.of(r.separator, r.name, r.detail, r.bar, r.percent, r.pause, r.cancel, r.hide));
        }
        if (Boolean.FALSE.equals(more.hidden)) views.add(more);
        for (V view : views) {
            MemorySegment layer = p(view.id, "layer");
            if (!layer.equals(NIL)) v(layer, "addAnimation:forKey:", a, key);
        }
    }

    private void ensureRows(int n) {
        while (rows.size() < n) {
            Row r = new Row();
            r.separator = add(view("NSView", 1, 1));
            r.name = add(label(12, false));
            // wraps to at most 3 lines, the last ending in an ellipsis (a one-line frame shows one line as before)
            v(r.name.id, "setLineBreakMode:", LINE_BREAK_WORD_WRAPPING);
            v(r.name.id, "setUsesSingleLineMode:", false);
            v(r.name.id, "setMaximumNumberOfLines:", (long) PillGeometry.MAX_NAME_LINES);
            MemorySegment cell = p(r.name.id, "cell");
            v(cell, "setWraps:", true);
            v(cell, "setTruncatesLastVisibleLine:", true);
            r.detail = add(label(10.5, false));
            r.bar = add(view("NSView", 1, 1));
            r.percent = add(label(11, true));
            r.pause = add(button("pause:", "pause.fill", 9, "❚❚"));
            r.cancel = add(button("cancel:", "xmark", 10, "✕"));
            r.hide = add(button("hide:", "minus", 10, "–"));
            // their own layers now: an implicitly backed view gets its layer replaced at first display, which would
            // drop the list's fade-in on the first open
            for (V view : new V[]{r.name, r.detail, r.percent, r.pause, r.cancel, r.hide}) v(view.id, "setWantsLayer:", true);
            rows.add(r);
        }
        if (palette != null) applyColours();
    }

    private void drainFrames() {
        drainQueued.set(false); // first, so a frame set while this runs queues the next drain
        Frames f = pendingFrames.getAndSet(null);
        if (f == null || panel == null) return;
        int[] px = f.primary();
        int w = f.primaryW();
        if (px != null && w > 0 && px.length > 0 && px.length % w == 0) {
            int h = px.length / w;
            double pt = f.barPt() > 0 ? f.barPt() : h / 2.0;
            if (pt != barPt) { barPt = pt; layout(); }
            // a frame drawn for another bar size (in flight across a layout change) would be stretched: skip it, the
            // producer rebuilds its renderer for the new size and the next frame is a first one
            boolean fits = barPxW == 0 || !PillGeometry.barSizeChanged(w, h, barPxW, barPxH);
            if (fits) setContents(bar, px, w, h);
        }
        if (!listOpen) return;
        for (Row r : rows) {
            if (r.taskId == null) continue;
            int[] rp = f.rows().get(r.taskId);
            if (rp != null && rp.length == PillGeometry.rowBarPxWidth() * PillGeometry.ROW_BAR_PX_H) {
                setContents(r.bar, rp, PillGeometry.rowBarPxWidth(), PillGeometry.ROW_BAR_PX_H);
            }
        }
    }

    private static void setContents(V v, int[] px, int w, int h) {
        MemorySegment img = CoreGraphics.imageFromArgbPre(px, w, h);
        try { setLayerContents(v.id, img); } finally { CoreGraphics.release(img); }
    }

    // ---------------------------------------------------------------- screen and geometry

    /** {@code notch}: the screen's top safe-area inset is non-zero (a camera housing in the menu bar). */
    private record Screen(PillGeometry.Rect frame, double menuBar, boolean notch) {}

    /** The screen holding the key (else main) IDE window, else the main screen. Re-resolved on every call. */
    private static @Nullable Screen screen() {
        MemorySegment win = p(app(), "keyWindow");
        if (win.equals(NIL)) win = p(app(), "mainWindow");
        MemorySegment chosen = NIL;
        if (!win.equals(NIL)) {
            PillGeometry.Rect w = geo(rectOf(win, "frame"));
            double cx = w.x() + w.w() / 2, cy = w.y() + w.h() / 2;
            MemorySegment screens = p(cls("NSScreen"), "screens");
            long count = n(screens, "count");
            for (long i = 0; i < count && chosen.equals(NIL); i++) {
                MemorySegment s = p(screens, "objectAtIndex:", i);
                PillGeometry.Rect f = geo(rectOf(s, "frame"));
                if (cx >= f.x() && cx < f.x() + f.w() && cy >= f.y() && cy < f.y() + f.h()) chosen = s;
            }
        }
        if (chosen.equals(NIL)) chosen = p(cls("NSScreen"), "mainScreen");
        if (chosen.equals(NIL)) return null;
        PillGeometry.Rect f = geo(rectOf(chosen, "frame"));
        PillGeometry.Rect vf = geo(rectOf(chosen, "visibleFrame"));
        return new Screen(f, Math.max(0, (f.y() + f.h()) - (vf.y() + vf.h())), PillStyle.hasNotch(safeAreaTop(chosen)));
    }

    private static PillGeometry.Rect geo(ArgLayouts.Rect r) {
        MemorySegment s = r.segment();
        return new PillGeometry.Rect(s.get(ValueLayout.JAVA_DOUBLE, 0), s.get(ValueLayout.JAVA_DOUBLE, 8),
                s.get(ValueLayout.JAVA_DOUBLE, 16), s.get(ValueLayout.JAVA_DOUBLE, 24));
    }

    private static final PillGeometry.Rect FALLBACK_SCREEN = new PillGeometry.Rect(0, 0, 1440, 900);

    private PillGeometry.Rect compactRect(@Nullable Screen s) {
        return PillGeometry.compact(style, s == null ? FALLBACK_SCREEN : s.frame(), s == null ? 0 : s.menuBar(), barPt);
    }

    private int listedRows() { return Math.min(view.rows().size(), PillGeometry.MAX_ROWS); }

    private int moreCount() { return Math.max(0, view.rows().size() - PillGeometry.MAX_ROWS); }

    /** Sizes the panel for the current screen and lays out every view; unchanged values are not sent to AppKit. */
    private void layout() {
        if (panel == null) return;
        Screen s = screen();
        applyEffectiveStyle(s);
        PillGeometry.Rect compact = compactRect(s);
        PillGeometry.Rect frame = compact;
        int[] lines = null;
        if (listOpen) {
            PillGeometry.Rect scr = s == null ? FALLBACK_SCREEN : s.frame();
            double mb = s == null ? 0 : s.menuBar();
            // the width does not depend on the rows; the rows' name lines depend on the width
            double w = PillGeometry.expanded(compact, 0, ROW_BAR_PT, scr, mb).w();
            lines = new int[listedRows()];
            for (int i = 0; i < lines.length && i < rows.size(); i++) lines[i] = rows.get(i).measureName(view.rows().get(i), w);
            frame = PillGeometry.expanded(compact, lines, ROW_BAR_PT, scr, mb);
            if (moreCount() > 0) {
                double extra = Math.clamp(frame.y() - scr.y(), 0, MORE_H);
                frame = new PillGeometry.Rect(frame.x(), frame.y() - extra, frame.w(), frame.h() + extra);
            }
        }
        panelFrame(frame);
        double W = frame.w(), H = frame.h(), headH = compact.h();
        // the open list widens the header; truncation is judged at the compact width, so opening the list cannot
        // make the name "fit" and close the list it just opened
        double narrower = W - compact.w();
        Box box = new Box(H);
        switch (style) {
            case A -> headerA(box, W, headH, narrower);
            case C -> headerC(box, W, headH);
            default -> headerB(box, W, headH, narrower);
        }
        // the header decides whether the name is truncated: a list opened by the inline chevron closes when the name
        // fits again (and it is not also a multi-task list); that layout redoes everything, so stop here
        if (listOpen && !canList()) { setListOpen(false); return; }

        int listed = listOpen && lines != null ? Math.min(listedRows(), lines.length) : 0;
        double top = headH + 8 / 2.0;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            double rowH = PillGeometry.rowHeight(i < listed ? lines[i] : 0, ROW_BAR_PT);
            boolean shown = i < listed && top + rowH <= H + 0.5;
            r.hidden(!shown);
            if (!shown) { r.taskId = null; continue; }
            layoutRow(box, r, view.rows().get(i), top, rowH, W, lines[i]);
            top += rowH;
        }
        int extra = listOpen ? moreCount() : 0;
        more.hidden(extra == 0);
        if (extra > 0) {
            more.text(IslandBundle.message("more.tasks", extra));
            box.place(more, PAD_LEFT, H - MORE_H, W - PAD_LEFT - PAD_RIGHT, MORE_H - 4);
        }
    }

    /**
     * Style B: name and percentage on the first line, bar and Cancel on the second; chevron on the left (2+ tasks).
     * A truncated name gives up {@link #NAME_CHEV_W} for the inline chevron right after its ellipsis.
     */
    private void headerB(Box box, double W, double headH, double narrower) {
        boolean chev = view.showChevron();
        TaskRow primary = view.primary();
        double x0 = chev ? CHEVRON_X + CHEVRON_W + CHEVRON_GAP : PAD_LEFT, x1 = W - PAD_RIGHT;
        double contentH = headH - PAD_TOP - 10;
        placeChevron(box, CHEVRON_X, PAD_TOP, CHEVRON_W, contentH);

        percent.text(primary == null ? "" : percentText(primary.fraction()));
        double pctW = percent.fitWidth();
        box.place(percent, x1 - pctW, PAD_TOP, pctW, LINE1_H);
        String nm = primary == null ? "" : nameOf(primary);
        name.text(nm);
        name.tip(nm);
        double nameW = Math.max(0, x1 - pctW - GAP - x0);
        boolean trunc = primary != null && decideLongName(nm, Math.max(0, x1 - narrower - Math.max(pctW, PCT_RESERVE) - GAP - x0));
        if (trunc) nameW = Math.max(0, Math.min(name.fitWidth(), nameW - NAME_CHEV_W)); // the chevron hugs the text
        box.place(name, x0, PAD_TOP, nameW, LINE1_H);
        placeNameChevron(box, trunc, x0 + nameW, PAD_TOP, LINE1_H);

        boolean canCancel = primary != null && primary.cancellable();
        double barTop = PAD_TOP + LINE1_H + LINE_GAP;
        double barRight = canCancel ? x1 - BUTTON - GAP : x1;
        bar.hidden(false);
        double barW = Math.max(0, barRight - x0);
        box.place(bar, x0, barTop, barW, barPt);
        barSize(barW, barPt);
        cancel.hidden(!canCancel);
        if (canCancel) {
            cancelButton(cancel, primary);
            box.place(cancel, x1 - BUTTON, barTop + barPt / 2 - BUTTON / 2, BUTTON, BUTTON);
        }
    }

    /**
     * Style A: one line as tall as the menu bar: [chevron] name (up to 120 pt) [inline chevron when truncated], bar
     * (the rest), percentage, Cancel.
     * A theme bar taller than the line is drawn squeezed to fit.
     */
    private void headerA(Box box, double W, double headH, double narrower) {
        boolean chev = view.showChevron();
        TaskRow primary = view.primary();
        double cy = headH / 2, x0 = chev ? A_CHEVRON_X + A_CHEVRON_W + GAP : A_PAD, right = W - A_PAD;
        placeChevron(box, A_CHEVRON_X, 0, A_CHEVRON_W, headH);

        boolean canCancel = primary != null && primary.cancellable();
        cancel.hidden(!canCancel);
        if (canCancel) {
            cancelButton(cancel, primary);
            box.place(cancel, right - BUTTON, cy - BUTTON / 2, BUTTON, BUTTON);
            right -= BUTTON + GAP;
        }
        percent.text(primary == null ? "" : percentText(primary.fraction()));
        double pctW = percent.fitWidth(), pctBox = Math.max(pctW, A_PCT_MIN);
        box.place(percent, right - pctW, cy - LINE1_H / 2, pctW, LINE1_H); // right-aligned in a 28 pt column
        right -= pctBox + GAP;

        String nm = primary == null ? "" : nameOf(primary);
        name.text(nm);
        name.tip(nm);
        double cap = Math.clamp(right - x0, 0, A_NAME_MAX);
        boolean trunc = primary != null
                && decideLongName(nm, Math.clamp(right - narrower - x0, 0, A_NAME_MAX));
        double nameW = Math.max(0, Math.min(name.fitWidth(), trunc ? cap - NAME_CHEV_W : cap));
        box.place(name, x0, cy - LINE1_H / 2, nameW, LINE1_H);
        placeNameChevron(box, trunc, x0 + nameW, cy - LINE1_H / 2, LINE1_H);
        double nameEnd = x0 + nameW + (trunc ? NAME_CHEV_W : 0);
        double barX = nameEnd + GAP, barH = Math.max(2, Math.min(barPt, headH - A_BAR_INSET));
        bar.hidden(false);
        double barW = Math.max(0, right - barX);
        box.place(bar, barX, cy - barH / 2, barW, barH);
        barSize(barW, barH);
    }

    private void barSize(double wPt, double hPt) {
        barPxW = PillGeometry.barPxWidth(wPt);
        barPxH = Math.max(2, PillGeometry.pillBarPxHeight(hPt));
    }

    /** Style C: the ring and the percentage, centred; an invisible button over the whole header toggles the list. */
    private void headerC(Box box, double W, double headH) {
        TaskRow primary = view.primary();
        percent.text(primary == null ? "" : percentText(primary.fraction()));
        double pctW = percent.fitWidth(), x = (W - (RING + RING_GAP + pctW)) / 2;
        V r = ring;
        if (r != null) box.place(r, x, (headH - RING) / 2, RING, RING);
        box.place(percent, x + RING + RING_GAP, (headH - LINE1_H) / 2, pctW, LINE1_H);
        name.hidden(true);
        longName = false; // C has no name in the pill: a click on the ring opens the list
        if (nameChevron != null) nameChevron.hidden(true);
        bar.hidden(true);
        cancel.hidden(true);
        chevron.hidden(false);
        chevron.tip(IslandBundle.message(listOpen ? "tip.list.hide" : "tip.list.show"));
        box.place(chevron, 0, 0, W, headH);
        updateRing();
    }

    /** A and B: the chevron button (with the Java-drawn glyph under it when the system has no symbols). */
    private void placeChevron(Box box, double x, double top, double w, double h) {
        boolean chev = view.showChevron();
        chevron.hidden(!chev);
        box.place(chevron, x, top, w, h);
        if (chevronGlyph != null) {
            double side = Math.min(CHEVRON_W, Math.min(w, h));
            chevronGlyph.hidden(!chev);
            box.place(chevronGlyph, x + (w - side) / 2, top + (h - side) / 2, side, side);
            drawChevronFallback();
        } else {
            chevron.symbol(listOpen ? "chevron.up" : "chevron.down", null);
        }
    }

    /**
     * Style C: the arc follows the primary task's fraction; indeterminate shows a quarter arc turned by a Core
     * Animation rotation (rendered by the window server, no Java timer), only while the pill is visible and the task
     * is not paused. Unchanged values are not sent.
     */
    private void updateRing() {
        MemorySegment arc = ringArc, track = ringTrack;
        if (arc == null || track == null || panel == null) return;
        TaskRow t = view.primary();
        RingColours c = ringColours != null ? ringColours
                : new RingColours(palette == null ? 0xFF808080 : palette.separator(), 0xFF3574F0, 0xFFE8A33D, 0xFF8C8C8C);
        Double f = t == null ? null : t.fraction();
        // Reduce Motion + indeterminate: a still quarter arc would read as 25%, so show a faint full ring instead
        boolean stillIndeterminate = t != null && f == null && reduceMotion;
        double end = t == null ? 0 : stillIndeterminate ? 1 : f == null ? SPIN_ARC : Math.clamp(f, 0, 1);
        int arcColour = t == null ? c.normal() : t.stopping() ? c.stopping() : t.paused() ? c.paused() : c.normal();
        if (stillIndeterminate) arcColour = (arcColour & 0x00FFFFFF) | 0x66000000;
        if (!Objects.equals(ringEnd, end)) { ringEnd = end; v(arc, "setStrokeEnd:", end); }
        if (!Objects.equals(ringTrackColour, c.track())) {
            ringTrackColour = c.track();
            v(track, "setStrokeColor:", p(colour(c.track()), "CGColor"));
        }
        if (!Objects.equals(ringArcColour, arcColour)) {
            ringArcColour = arcColour;
            v(arc, "setStrokeColor:", p(colour(arcColour), "CGColor"));
        }
        // Reduce Motion: no spin; the ring is a faint full circle (see above), never a quarter arc that reads as 25%
        boolean spin = t != null && Motion.spinRing(f == null, visibleToUser, t.paused(), reduceMotion);
        if (spin == spinning) return;
        spinning = spin;
        if (spin) {
            MemorySegment a = p(cls("CABasicAnimation"), "animationWithKeyPath:", nsString("transform.rotation.z"));
            v(a, "setFromValue:", p(cls("NSNumber"), "numberWithDouble:", 0.0));
            v(a, "setToValue:", p(cls("NSNumber"), "numberWithDouble:", -2 * Math.PI)); // clockwise (y-up layer)
            v(a, "setDuration:", Motion.SPIN_S);
            v(a, "setRepeatCount:", Float.POSITIVE_INFINITY);
            v(a, "setRemovedOnCompletion:", false);
            v(arc, "addAnimation:forKey:", a, nsString(SPIN_KEY));
        } else {
            v(arc, "removeAnimationForKey:", nsString(SPIN_KEY));
        }
    }

    /** CoreGraphics paths for the ring: one circle starting at 12 o'clock and running clockwise. */
    private static final class RingPath {
        private static final Linker L = Linker.nativeLinker();
        private static final SymbolLookup CG = SymbolLookup.libraryLookup(
                "/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics", Arena.ofAuto());
        private static final MethodHandle CREATE = L.downcallHandle(CG.find("CGPathCreateMutable").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS));
        private static final MethodHandle ADD_ARC = L.downcallHandle(CG.find("CGPathAddArc").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_DOUBLE,
                        ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE,
                        ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_BOOLEAN));
        private static final MethodHandle RELEASE = L.downcallHandle(CG.find("CGPathRelease").orElseThrow(),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

        /** A +1 CGMutablePathRef; release it with {@link #release}. */
        static MemorySegment circle(double cx, double cy, double r) {
            try {
                MemorySegment path = (MemorySegment) CREATE.invokeExact();
                if (path.equals(NIL)) throw new IllegalStateException("CGPathCreateMutable failed");
                ADD_ARC.invokeExact(path, NIL, cx, cy, r, Math.PI / 2, Math.PI / 2 - 2 * Math.PI, true);
                return path;
            } catch (RuntimeException | Error e) { throw e; }
            catch (Throwable t) { throw new RuntimeException(t); }
        }

        static void release(MemorySegment path) {
            try { RELEASE.invokeExact(path); }
            catch (RuntimeException | Error e) { throw e; }
            catch (Throwable t) { throw new RuntimeException(t); }
        }
    }

    /**
     * {@code nameLines} 0: the usual row (name over detail in a 120 pt column, bar beside them). 1..3: the name is
     * too long for that column, so it wraps across the row (left of the buttons) and detail, bar and percentage sit
     * on one line under it.
     */
    private void layoutRow(Box box, Row r, TaskRow t, double top, double h, double W, int nameLines) {
        boolean newTask = r.taskId == null || r.taskId != t.id();
        r.taskId = t.id();
        if (newTask) { // never show another task's last frame; the task's renderer sends a fresh one
            setLayerContents(r.bar.id, NIL);
            onRowCleared.accept(t.id());
        }
        box.place(r.separator, PAD_LEFT, top, W - PAD_LEFT - PAD_RIGHT, 1);
        double x = PAD_LEFT, right = W - PAD_RIGHT;
        String nm = nameOf(t);
        r.name.text(nm);
        r.name.tip(nm);
        String detail = t.detail() == null ? "" : t.detail();
        r.detail.text(detail);
        boolean hasDetail = !detail.isEmpty();
        r.detail.hidden(!hasDetail);
        double cy = top + h / 2, barCy = cy; // barCy: the vertical centre of the bar and the percentage
        if (nameLines <= 0) {
            double nameTop = hasDetail ? top + 7 : top + (h - LINE1_H) / 2;
            box.place(r.name, x, nameTop, ROW_NAME_W, LINE1_H);
            box.place(r.detail, x, top + 7 + LINE1_H - 1, ROW_NAME_W, 14);
        } else {
            int k = Math.min(nameLines, PillGeometry.MAX_NAME_LINES);
            double nameH = k * PillGeometry.NAME_LINE_H, lineTop = top + 7 + nameH + 2;
            box.place(r.name, x, top + 7, wideNameWidth(W), nameH);
            box.place(r.detail, x, lineTop + 1, ROW_NAME_W, 14);
            barCy = lineTop + LINE1_H / 2;
        }

        double hideX = right - BUTTON, cancelX = hideX - ROW_BTN_GAP - BUTTON, pauseX = cancelX - ROW_BTN_GAP - BUTTON;
        r.hide.tag(t.id());
        r.hide.tip(IslandBundle.message("action.hide"));
        box.place(r.hide, hideX, cy - BUTTON / 2, BUTTON, BUTTON);
        r.cancel.hidden(!t.cancellable());
        cancelButton(r.cancel, t);
        box.place(r.cancel, cancelX, cy - BUTTON / 2, BUTTON, BUTTON);
        r.pause.hidden(!t.pausable());
        r.pause.tag(t.id());
        r.pause.symbol(t.paused() ? "play.fill" : "pause.fill", t.paused() ? "▶" : "❚❚");
        r.pause.tip(IslandBundle.message(t.paused() ? "action.resume" : "action.pause"));
        box.place(r.pause, pauseX, cy - BUTTON / 2, BUTTON, BUTTON);

        r.percent.text(percentText(t.fraction()));
        double pw = r.percent.fitWidth();
        box.place(r.percent, pauseX - GAP - pw, barCy - 8, pw, LINE1_H);
        double barX = x + ROW_NAME_W + GAP;
        box.place(r.bar, barX, barCy - ROW_BAR_PT / 2, PillGeometry.rowBarWidthPt(W), ROW_BAR_PT);
    }

    /** A wrapped row name spans from the left padding to the Pause button's slot (always reserved). */
    private static double wideNameWidth(double W) {
        return Math.max(0, W - PAD_LEFT - PAD_RIGHT - 3 * BUTTON - 2 * ROW_BTN_GAP - GAP);
    }

    private void cancelButton(V b, TaskRow t) {
        b.tag(t.id());
        b.tip(t.cancelTooltip() == null || t.cancelTooltip().isBlank() ? IslandBundle.message("action.cancel") : t.cancelTooltip());
        b.enabled(!t.stopping());
        if (palette != null) b.tint(t.stopping() ? palette.cancelDisabled() : palette.cancel());
    }

    private static String nameOf(TaskRow t) { return t.name() == null || t.name().isBlank() ? IslandBundle.message("task.working") : t.name(); }

    private static String percentText(@Nullable Double fraction) {
        return Percent.format(fraction); // indeterminate: empty, the bar/ring alone signals it
    }

    private double[] lastPanelFrame;

    private void panelFrame(PillGeometry.Rect r) {
        double[] f = {r.x(), r.y(), r.w(), r.h()};
        if (java.util.Arrays.equals(f, lastPanelFrame)) return;
        lastPanelFrame = f;
        v(panel, "setFrame:display:", rect(r.x(), r.y(), r.w(), r.h()), true);
    }

    /** Converts top-left layout (y down from the panel's top edge) into the content view's bottom-left frames. */
    private record Box(double height) {
        void place(V v, double left, double top, double w, double h) { v.frame(left, height - top - h, w, h); }
    }

    // ---------------------------------------------------------------- colours

    private void applyColours() {
        PillPalette p = palette;
        if (p == null || panel == null) return;
        MemorySegment appearance = p(cls("NSAppearance"), "appearanceNamed:",
                nsString(p.dark() ? "NSAppearanceNameDarkAqua" : "NSAppearanceNameAqua"));
        v(panel, "setAppearance:", appearance); // system symbols and controls render for the matching appearance
        content.background(p.background());
        name.textColour(p.text());
        percent.textColour(p.secondaryText());
        more.textColour(p.secondaryText());
        chevron.tint(p.chevron());
        if (nameChevron != null) nameChevron.tint(p.chevron());
        for (Row r : rows) {
            r.separator.background(p.separator());
            r.name.textColour(p.text());
            r.detail.textColour(p.secondaryText());
            r.percent.textColour(p.secondaryText());
            r.pause.tint(p.text());
            r.hide.tint(p.text());
        }
        chevronGlyphKey = null;
        layout(); // re-tints Cancel (stopping state) and redraws a fallback chevron
    }

    private static MemorySegment colour(int argb) {
        return p(cls("NSColor"), "colorWithSRGBRed:green:blue:alpha:", ((argb >> 16) & 0xFF) / 255.0,
                ((argb >> 8) & 0xFF) / 255.0, (argb & 0xFF) / 255.0, ((argb >>> 24) & 0xFF) / 255.0);
    }

    /** Only when the system has no {@code chevron.down} symbol: a chevron drawn in Java under a transparent button. */
    private void drawChevronFallback() {
        PillPalette p = palette;
        if (p == null || chevronGlyph == null) return;
        String key = listOpen + ":" + p.chevron();
        if (key.equals(chevronGlyphKey)) return;
        chevronGlyphKey = key;
        int px = (int) (CHEVRON_W * 2);
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(p.chevron(), true));
            g.setStroke(new BasicStroke(4.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            double c = px / 2.0, dx = 10, dy = listOpen ? -5 : 5;
            Path2D path = new Path2D.Double();
            path.moveTo(c - dx, c - dy);
            path.lineTo(c, c + dy);
            path.lineTo(c + dx, c - dy);
            g.draw(path);
        } finally { g.dispose(); }
        int[] data = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        setContents(chevronGlyph, data, px, px);
    }

    // ---------------------------------------------------------------- views with their last-set values

    private final class Row {
        @Nullable Long taskId;
        V separator, name, detail, bar, percent, pause, cancel, hide;
        private @Nullable String measuredText;
        private double measuredWidth = -1;
        private int nameLines;

        /**
         * Main thread. The wrapped name line count for this row at panel width {@code W} ({@link #layoutRow}): 0 when
         * the name fits the 120 pt column on one line, else 1..3 lines across the row. Measured natively with the
         * label's cell ({@code cellSizeForBounds:}), only when the name or the width changes.
         */
        int measureName(TaskRow t, double W) {
            String nm = nameOf(t);
            if (nm.equals(measuredText) && W == measuredWidth) return nameLines;
            name.text(nm);
            MemorySegment cell = p(name.id, "cell");
            double[] one = sizeOf(cell, "cellSizeForBounds:", rect(0, 0, 100_000, 100_000));
            if (one[0] <= ROW_NAME_W + 0.5) {
                nameLines = 0;
            } else {
                double[] wrapped = sizeOf(cell, "cellSizeForBounds:", rect(0, 0, wideNameWidth(W), 100_000));
                int k = one[1] <= 0 ? 1 : (int) Math.round(wrapped[1] / one[1]);
                nameLines = Math.clamp(k, 1, PillGeometry.MAX_NAME_LINES);
            }
            measuredText = nm;
            measuredWidth = W;
            return nameLines;
        }

        void hidden(boolean h) {
            for (V v : new V[]{separator, name, detail, bar, percent, pause, cancel, hide}) v.hidden(h);
        }
    }

    /** One native view this class owns, with the values last sent to it, so unchanged values are skipped. */
    private final class V {
        final MemorySegment id;
        @Nullable String text, tip, symbolName;
        @Nullable Integer textColour, tint, background;
        @Nullable Boolean hidden, enabled;
        long tag = Long.MIN_VALUE;
        double size;
        double @Nullable [] frame;
        double fitWidth;

        V(MemorySegment id) { this.id = id; }

        void frame(double x, double y, double w, double h) {
            double[] f = {x, y, w, h};
            if (java.util.Arrays.equals(f, frame)) return;
            frame = f;
            v(id, "setFrame:", rect(x, y, w, h));
        }

        void hidden(boolean h) { if (!Objects.equals(hidden, h)) { hidden = h; v(id, "setHidden:", h); } }

        void enabled(boolean e) { if (!Objects.equals(enabled, e)) { enabled = e; v(id, "setEnabled:", e); } }

        void tag(long t) { if (tag != t) { tag = t; v(id, "setTag:", t); } }

        void text(String s) {
            if (s.equals(text)) return;
            text = s;
            v(id, "setStringValue:", nsString(s));
            v(id, "sizeToFit");
            fitWidth = Math.ceil(geo(rectOf(id, "frame")).w());
            frame = null; // sizeToFit moved it; the next place() sets it again
        }

        double fitWidth() { return fitWidth; }

        void tip(String s) { if (!s.equals(tip)) { tip = s; v(id, "setToolTip:", nsString(s)); } }

        void textColour(int argb) {
            if (!Objects.equals(textColour, argb)) { textColour = argb; v(id, "setTextColor:", colour(argb)); }
        }

        void tint(int argb) {
            if (Objects.equals(tint, argb)) return;
            tint = argb;
            v(id, "setContentTintColor:", colour(argb));
        }

        void background(int argb) {
            if (Objects.equals(background, argb)) return;
            background = argb;
            v(p(id, "layer"), "setBackgroundColor:", p(colour(argb), "CGColor"));
        }

        /** A button's system symbol; without symbols the fallback text is shown instead. */
        void symbol(String name, @Nullable String fallback) {
            if (name.equals(symbolName)) return;
            symbolName = name;
            MemorySegment img = symbols ? symbolImage(name, size) : NIL;
            if (img.equals(NIL)) {
                if (fallback != null) v(id, "setTitle:", nsString(fallback));
                return;
            }
            v(id, "setImage:", img);
            v(id, "setImagePosition:", IMAGE_ONLY);
        }
    }
}
