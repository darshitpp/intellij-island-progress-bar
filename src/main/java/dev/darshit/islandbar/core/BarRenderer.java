package dev.darshit.islandbar.core;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.JProgressBar;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.plaf.ProgressBarUI;
import org.jetbrains.annotations.Nullable;

/**
 * Paints one pill/row bar frame (EDT only). Uses whatever ProgressBarUI is registered (Nyan, Mario, ... register theirs
 * globally through UIManager) on an offscreen JProgressBar, scaled evenly to fill the height. With no theme, or after
 * a theme failed once, draws the IDE's standard bar itself so it can fill the full height.
 * <p>
 * Themes advance their animation one step per paint, so the caller's frame rate sets the animation speed.
 */
public final class BarRenderer {
    private static final Color STOPPING_COLOR = new Color(0xE8A33D);
    private static final int SWEEP_W = 240, SWEEP_STEP = 24;

    private final Predicate<Class<?>> isPlatformUi;
    private final Consumer<Throwable> onThemeFailure;
    private final Supplier<@Nullable Class<?>> shownUi;
    private final int imageW, imageH;
    private final BufferedImage img;
    private final int[] data;
    private final int[] themedCopy;               // previous themed frame, for the unchanged check (reused, never cloned)
    private boolean hasThemedCopy;

    private @Nullable JProgressBar bar;
    private boolean barTried, themeBroken, themed;
    private boolean hasLastStandard;
    private @Nullable Double lastStandard;
    private @Nullable BarTint lastTint;
    private @Nullable Color lastTrack, lastProgress; // a theme (Light/Dark) switch must repaint even if progress stalls
    private long sweep;
    private boolean reduceMotion;
    private boolean hasLastStatic; // the last standard frame was the still (Reduce Motion) indeterminate bar

    /**
     * @param shownUi the ProgressBarUI class of the bar the IDE is showing right now, or null if none is. Two theme
     *                plugins overwrite the one global setting, and a bar keeps the look it was created with, so a
     *                new bar can differ from the IDE's; copying the IDE's bar keeps the pill the same.
     */
    public BarRenderer(int imageW, int imageH, Predicate<Class<?>> isPlatformUi, Consumer<Throwable> onThemeFailure,
                       Supplier<@Nullable Class<?>> shownUi) {
        this.imageW = imageW;
        this.imageH = imageH;
        this.img = new BufferedImage(imageW, imageH, BufferedImage.TYPE_INT_ARGB_PRE);
        this.data = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        this.themedCopy = new int[imageW * imageH];
        this.isPlatformUi = isPlatformUi;
        this.onThemeFailure = onThemeFailure;
        this.shownUi = shownUi;
    }

    /** The first JProgressBar below {@code root}, depth first; null if there is none. */
    public static @Nullable JProgressBar firstProgressBar(Container root) {
        for (Component c : root.getComponents()) {
            if (c instanceof JProgressBar b) return b;
            if (c instanceof Container inner && firstProgressBar(inner) instanceof JProgressBar found) return found;
        }
        return null;
    }

    public static final int MIN_FPS = 10, MAX_FPS = 60, DEFAULT_FPS = 30;

    /** Frame interval for the user's frame-rate setting, clamped to {@link #MIN_FPS}..{@link #MAX_FPS}. */
    public static int intervalForFps(int fps) {
        return (int) Math.round(1000.0 / Math.clamp(fps, MIN_FPS, MAX_FPS));
    }

    /**
     * Whether a ProgressBarUI is the IDE's own rather than a theme plugin's, judged by package.
     * ponytail: package prefix, because the plugin-lookup APIs are internal; a theme shipped in these packages would
     * be drawn as the standard bar (the once-per-bar log line shows which was picked).
     */
    public static boolean isBuiltInUi(Class<?> ui) { return isBuiltInUiName(ui.getName()); }

    static boolean isBuiltInUiName(String name) {
        return name.startsWith("com.intellij.") || name.startsWith("com.jetbrains.") || name.startsWith("javax.swing.")
                || name.startsWith("com.apple.") || name.startsWith("com.sun.");
    }

    /** Next paint uses a fresh JProgressBar (current theme) and always returns a frame. */
    public void newBar() {
        dropBar();
        barTried = false;
        hasThemedCopy = false;
        hasLastStandard = false;
        hasLastStatic = false;
    }

    /** A theme plugin is unloading: drop its bar and use the standard bar until {@link #newBar} (the next bar). */
    public void dropThemeUntilNextBar() {
        newBar();
        barTried = true; // themedBar() now returns null until newBar()
    }

    /**
     * This renderer is no longer used: leaves indeterminate mode (a theme UI may run an animation timer, which would
     * keep the bar and its listeners reachable) and drops the bar. A later paint draws the standard bar.
     */
    public void dispose() { dropThemeUntilNextBar(); }

    /** The theme's preferred bar height in pt, or -1 when the standard bar is drawn. */
    public int preferredBarHeight() {
        if (themeBroken) return -1;
        try {
            JProgressBar b = themedBar();
            return b != null ? b.getPreferredSize().height : -1;
        } catch (Throwable t) {
            themeFailed(t);
            dropBar();
            return -1;
        }
    }

    /**
     * Reduce Motion (the system accessibility setting): the standard indeterminate bar stops sweeping and is drawn as
     * a still segment in the middle, so an indeterminate bar sends a frame only when its tint or colours change. A
     * change of the setting makes the next paint return a frame. Theme bars draw their own animation and are unchanged.
     */
    public void setReduceMotion(boolean on) {
        if (on == reduceMotion) return;
        reduceMotion = on;
        hasLastStatic = false;
        sweep = 0;
    }

    public boolean isThemed() { return themed; }

    /** Class name of the theme in use, or null when the standard bar is drawn. */
    public @Nullable String themeName() {
        return themed && bar != null && bar.getUI() != null ? bar.getUI().getClass().getName() : null;
    }

    /**
     * {@code imageW * imageH} ARGB-premultiplied pixels, top row first; null if identical to the previous frame. The
     * array is this renderer's own buffer: valid only until the next paint, so consume it immediately (never clone).
     */
    public int @Nullable [] paint(@Nullable Double fraction, BarTint tint) {
        Double f = fraction != null && fraction.isNaN() ? null : fraction;
        if (!themeBroken) {
            try {
                JProgressBar b = themedBar();
                if (b != null) {
                    themed = true;
                    return paintThemed(b, f);
                }
            } catch (Throwable t) {
                themeFailed(t);
                dropBar();
            }
        }
        themed = false;
        return paintStandard(f, tint);
    }

    /** Leaves indeterminate mode first (stops a UI animation timer, if one started); theme code can throw here too. */
    private void dropBar() {
        JProgressBar b = bar;
        bar = null;
        if (b != null) {
            try { b.setIndeterminate(false); }
            catch (Throwable t) { themeFailed(t); }
        }
    }

    /** For the session: a theme that throws once will likely throw on every frame. Reported once. */
    private void themeFailed(Throwable t) {
        if (themeBroken) return;
        themeBroken = true;
        onThemeFailure.accept(t);
    }

    private @Nullable JProgressBar themedBar() {
        if (!barTried) {
            barTried = true;
            Class<?> shown = shownUiOrNull();
            if (shown != null && isPlatformUi.test(shown)) return bar = null; // the IDE draws its own bar, so do we
            JProgressBar b = new JProgressBar(SwingConstants.HORIZONTAL); // throws if the registered UI class is gone
            if (shown != null) adopt(b, shown);
            b.setOpaque(false);
            b.setDoubleBuffered(false);
            b.setBorderPainted(false);
            bar = b.getUI() == null || isPlatformUi.test(b.getUI().getClass()) ? null : b;
        }
        return bar;
    }

    private @Nullable Class<?> shownUiOrNull() {
        try { return shownUi.get(); } catch (Throwable t) { return null; } // no frame yet, or the lookup broke: use the setting
    }

    /** Gives the bar the IDE bar's UI class the way UIManager would (its static createUI); keeps the registered one if that fails. */
    private static void adopt(JProgressBar b, Class<?> ui) {
        if (b.getUI() != null && b.getUI().getClass() == ui) return;
        try {
            if (ProgressBarUI.class.isAssignableFrom(ui)
                    && ui.getMethod("createUI", JComponent.class).invoke(null, b) instanceof ProgressBarUI created
                    && created.getClass() == ui) {
                b.setUI(created);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { /* keep the registered theme */ }
    }

    private int @Nullable [] paintThemed(JProgressBar b, @Nullable Double f) {
        b.setIndeterminate(f == null);
        if (f != null) {
            b.setMaximum(1000);
            b.setValue((int) Math.round(f * 1000));
        }
        FrameGeometry geo = FrameGeometry.of(b.getPreferredSize().height, imageW, imageH);
        b.setSize(geo.componentWidth(), geo.componentHeight());
        Graphics2D g = clearedGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.scale(geo.scale(), geo.scale());
            b.paint(g);
        } finally { g.dispose(); }
        if (hasThemedCopy && Arrays.equals(data, themedCopy)) return null;
        System.arraycopy(data, 0, themedCopy, 0, data.length);
        hasThemedCopy = true;
        return data;
    }

    private int @Nullable [] paintStandard(@Nullable Double f, BarTint tint) {
        Color track = color("ProgressBar.trackColor", Color.GRAY);
        Color progress = switch (tint) {
            case NORMAL -> color("ProgressBar.progressColor", new Color(0x3574F0));
            case STOPPING -> STOPPING_COLOR;
        };
        boolean still = f == null && reduceMotion;
        boolean sameLook = tint == lastTint && track.equals(lastTrack) && progress.equals(lastProgress);
        if (f != null && hasLastStandard && f.equals(lastStandard) && sameLook) return null;
        if (still && hasLastStatic && sameLook) return null;
        Graphics2D g = clearedGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(track);
            g.fillRoundRect(0, 0, imageW, imageH, imageH, imageH);
            if (f != null) {
                g.setClip(0, 0, (int) Math.round(imageW * f), imageH);
                g.setColor(progress);
                g.fillRoundRect(0, 0, imageW, imageH, imageH, imageH);
            } else {
                int span = imageW - SWEEP_W, x;
                if (still) x = span / 2; // Reduce Motion: a still segment in the middle
                else {
                    x = (int) (sweep++ * SWEEP_STEP % (2L * span));
                    if (x > span) x = 2 * span - x;
                }
                boolean tinted = tint != BarTint.NORMAL;
                g.setPaint(new GradientPaint(x, 0, tinted ? progress : color("ProgressBar.indeterminateStartColor", progress),
                        x + SWEEP_W, 0, tinted ? progress : color("ProgressBar.indeterminateEndColor", progress)));
                g.fillRoundRect(x, 0, SWEEP_W, imageH, imageH, imageH);
            }
        } finally { g.dispose(); }
        hasLastStandard = f != null;
        hasLastStatic = still;
        lastStandard = f;
        lastTint = tint;
        lastTrack = track;
        lastProgress = progress;
        return data;
    }

    /** Transparent canvas with the normal compositing restored. */
    private Graphics2D clearedGraphics() {
        Graphics2D g = img.createGraphics();
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(0, 0, imageW, imageH);
        g.setComposite(AlphaComposite.SrcOver);
        return g;
    }

    private static Color color(String key, Color fallback) {
        Color c = UIManager.getColor(key);
        return c != null ? c : fallback;
    }
}
