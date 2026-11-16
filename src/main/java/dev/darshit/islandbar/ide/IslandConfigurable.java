package dev.darshit.islandbar.ide;

import com.intellij.ide.ui.LafManagerListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.Configurable;
import com.intellij.ui.JBIntSpinner;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.dsl.builder.AlignX;
import com.intellij.ui.dsl.builder.BuilderKt;
import com.intellij.ui.dsl.builder.HyperlinkEventAction;
import com.intellij.ui.dsl.builder.LabelPosition;
import com.intellij.ui.dsl.builder.Row;
import com.intellij.ui.dsl.builder.UtilsKt;
import com.intellij.util.messages.MessageBusConnection;
import dev.darshit.islandbar.core.BarRenderer;
import dev.darshit.islandbar.core.PillPalette;
import dev.darshit.islandbar.mac.IslandPanel;
import java.awt.event.HierarchyEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.Timer;
import kotlin.Unit;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

/**
 * Settings | Tools | Island Progress Bar. Built with the platform's Kotlin UI DSL (called from Java): short labels,
 * explanations as word-wrapped comments.
 *
 * <p>The style is picked from a row of preview cards ({@link StylePicker}), painted from the pill's own palette and
 * re-coloured on every look-and-feel change while the page exists.
 *
 * <p>Camera notch: on a Mac with a notched screen the A card is disabled (A is drawn as B there), previews B and says
 * why; a saved A is kept. Whether a notch exists is asked of the AppKit main thread asynchronously (the EDT never
 * waits for it): once when the page is built and every 2 s while it is showing (a display may be plugged in or
 * out). The answer is cached for the session, so the page opens with the last known state.
 */
public final class IslandConfigurable implements Configurable {
    private static final Logger LOG = Logger.getInstance(IslandConfigurable.class);
    private static final int SCREEN_POLL_MS = 2_000;
    /** Last known answer to "does any connected screen have a camera notch"; false until the first answer. */
    private static volatile boolean anyNotch;
    private static final AtomicBoolean notchQueryFailed = new AtomicBoolean();

    private @Nullable StylePicker style;
    private @Nullable StyleChoice shownChoice; // what the picker currently shows; null until the page is built
    private @Nullable PillPalette palette;     // the previews' colours; rebuilt on every look-and-feel change
    private @Nullable MessageBusConnection lafConnection;
    private int lafRefreshes;                  // tests: palette rebuilds caused by look-and-feel changes
    private @Nullable Timer screenPoll;
    private JBIntSpinner delay;
    private JBIntSpinner fps;
    private JBCheckBox hideIdeBar;
    private final Supplier<IslandSettings> settings;
    private final boolean watchScreens;

    public IslandConfigurable() { this(IslandSettings::getInstance, true); }

    /** Test seam: settings without an IDE application, and no native screen queries. */
    IslandConfigurable(Supplier<IslandSettings> settings, boolean watchScreens) {
        this.settings = settings;
        this.watchScreens = watchScreens;
    }

    /** Test seam: simulates the cached "any screen has a camera notch" answer. */
    static void setAnyNotchForTests(boolean notch) { anyNotch = notch; }

    /** Test seam: the picker, or null once the page is disposed. */
    @Nullable StylePicker pickerForTests() { return style; }

    /** Test seam: whether a look-and-feel listener is subscribed, and how often it rebuilt the previews' palette. */
    boolean lafSubscribedForTests() { return lafConnection != null; }
    int lafRefreshesForTests() { return lafRefreshes; }

    @Override public @Nls String getDisplayName() { return IslandBundle.message("settings.display.name"); }

    @Override public @Nullable JComponent createComponent() {
        IslandSettings s = settings.get();
        disconnectLaf(); // createComponent without disposeUIResources in between: never two listeners
        palette = IslandController.buildPalette();
        StylePicker picker = new StylePicker(this::previewPalette);
        style = picker;
        shownChoice = null;
        showNotch(anyNotch);
        picker.select(s.getStyle());
        lafConnection = ApplicationManager.getApplication().getMessageBus().connect();
        lafConnection.subscribe(LafManagerListener.TOPIC, _ -> lafChanged());
        delay = new JBIntSpinner(s.getDelayMs(), IslandSettings.MIN_DELAY_MS, IslandSettings.MAX_DELAY_MS, 100);
        fps = new JBIntSpinner(s.getFps(), BarRenderer.MIN_FPS, BarRenderer.MAX_FPS, 5);
        hideIdeBar = new JBCheckBox(IslandBundle.message("settings.hide.ide.bar"), s.isHideIdeBar());

        JComponent root = BuilderKt.panel(p -> {
            comment(p.row((JLabel) null, r -> {
                        r.cell(picker).align(AlignX.FILL).label(IslandBundle.message("settings.style.label"), LabelPosition.TOP);
                        return Unit.INSTANCE;
                    }), IslandBundle.message("settings.style.comment"));
            comment(p.row(IslandBundle.message("settings.delay.label"), r -> { r.cell(delay); return Unit.INSTANCE; }),
                    IslandBundle.message("settings.delay.comment"));
            comment(p.row(IslandBundle.message("settings.fps.label"), r -> { r.cell(fps); return Unit.INSTANCE; }),
                    IslandBundle.message("settings.fps.comment"));
            comment(p.row((JLabel) null, r -> { r.cell(hideIdeBar); return Unit.INSTANCE; }),
                    IslandBundle.message("settings.hide.ide.bar.comment"));
            return Unit.INSTANCE;
        });
        if (watchScreens && IslandController.isSupported()) { // elsewhere the plugin is inert: never touch native code
            Timer t = new Timer(SCREEN_POLL_MS, _ -> refreshNotch());
            screenPoll = t;
            root.addHierarchyListener(e -> {
                if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) return;
                if (root.isShowing() && screenPoll == t) { refreshNotch(); t.start(); } else t.stop(); // not after dispose
            });
            refreshNotch();
        }
        return root;
    }

    /** EDT. Queues the question to the AppKit main thread; the answer comes back to the EDT. Never blocks. */
    private void refreshNotch() {
        IslandPanel.anyScreenHasNotch(
                notch -> ApplicationManager.getApplication().invokeLater(() -> {
                    anyNotch = notch;
                    showNotch(notch);
                }, ModalityState.any()),
                t -> {
                    if (notchQueryFailed.compareAndSet(false, true)) {
                        LOG.warn("Island Progress Bar: could not read the screens' safe areas", t);
                    }
                });
    }

    /** EDT: the A card disabled, previewing B, with the note, when a notch exists; a no-op once the page is gone. */
    private void showNotch(boolean notch) {
        StylePicker p = style;
        if (p == null) return;
        StyleChoice choice = StyleChoice.of(notch);
        if (choice.equals(shownChoice)) return; // the 2 s poll: nothing changed
        shownChoice = choice;
        p.setChoice(choice);
    }

    /** EDT. The previews' colours, built like the pill's ({@link IslandController#buildPalette}). */
    private PillPalette previewPalette() {
        PillPalette p = palette;
        if (p == null) palette = p = IslandController.buildPalette();
        return p;
    }

    /** EDT. Cheap (a few UIManager lookups), so no debounce: rebuild the palette and repaint the previews. */
    private void lafChanged() {
        StylePicker p = style;
        if (p == null) return;
        palette = IslandController.buildPalette();
        lafRefreshes++;
        p.repaint();
    }

    private void disconnectLaf() {
        MessageBusConnection c = lafConnection;
        lafConnection = null;
        if (c != null) c.disconnect();
    }

    private static void comment(Row row, @Nls String text) {
        row.rowComment(text, UtilsKt.MAX_LINE_LENGTH_WORD_WRAP, HyperlinkEventAction.HTML_HYPERLINK_INSTANCE);
    }

    @Override public boolean isModified() {
        IslandSettings s = settings.get();
        return style.selected() != s.getStyle() || delay.getNumber() != s.getDelayMs()
                || fps.getNumber() != s.getFps() || hideIdeBar.isSelected() != s.isHideIdeBar();
    }

    @Override public void apply() {
        IslandSettings s = settings.get();
        s.setStyle(style.selected());
        s.setDelayMs(delay.getNumber());
        s.setFps(fps.getNumber());
        s.setHideIdeBar(hideIdeBar.isSelected());
    }

    @Override public void reset() {
        IslandSettings s = settings.get();
        style.select(s.getStyle());
        delay.setNumber(s.getDelayMs());
        fps.setNumber(s.getFps());
        hideIdeBar.setSelected(s.isHideIdeBar());
    }

    @Override public void disposeUIResources() {
        Timer t = screenPoll;
        if (t != null) t.stop();
        screenPoll = null;
        disconnectLaf();
        shownChoice = null;
        palette = null;
        style = null; delay = null; fps = null; hideIdeBar = null;
    }
}
