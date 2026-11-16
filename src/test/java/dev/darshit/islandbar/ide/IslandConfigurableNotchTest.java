package dev.darshit.islandbar.ide;

import static org.junit.jupiter.api.Assertions.*;

import com.intellij.ide.ui.LafManagerListener;
import com.intellij.mock.MockApplication;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.ExperimentalUI;
import com.intellij.ui.dsl.builder.KotlinUiDslService;
import dev.darshit.islandbar.core.PillStyle;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.swing.ButtonGroup;
import javax.swing.DefaultButtonModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JRadioButton;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The settings page's style cards (headless, no AppKit, no IDE application): builds the real Kotlin-UI-DSL page with
 * the "any screen has a notch" cache forced and checks the radio cards, the notch note, the settings round-trip and the
 * look-and-feel listener.
 */
class IslandConfigurableNotchTest {
    private static Disposable app;
    private IslandConfigurable page;

    /** The UI DSL looks up two application services; a platform MockApplication provides it (no IDE started). */
    @BeforeAll static void application() throws Exception {
        app = Disposer.newDisposable("IslandConfigurableNotchTest");
        onEdt(() -> {
            MockApplication a = MockApplication.setUp(app);
            a.registerService(KotlinUiDslService.class, dslService());
            a.registerService(ExperimentalUI.class, new ExperimentalUI() {
                @Override public void saveCurrentValueAndReapplyDefaultLaf() {}
            });
        });
    }

    /** The platform's implementation (Kotlin-internal, so created reflectively; tests only). */
    private static KotlinUiDslService dslService() {
        try {
            return (KotlinUiDslService) Class.forName("com.intellij.ui.dsl.builder.impl.KotlinUiDslServiceImpl")
                    .getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    @AfterAll static void noApplication() throws Exception { onEdt(() -> Disposer.dispose(app)); }

    @AfterEach void tearDown() throws Exception {
        if (page != null) onEdt(() -> page.disposeUIResources());
        IslandConfigurable.setAnyNotchForTests(false);
    }

    @Test void threeRadioCardsInOneGroupWithAccessibleNamesAndPreviews() throws Exception {
        Built b = build(false, PillStyle.B);
        Set<ButtonGroup> groups = new HashSet<>();
        for (PillStyle s : PillStyle.values()) {
            JRadioButton r = b.radio(s);
            groups.add(((DefaultButtonModel) r.getModel()).getGroup());
            assertTrue(isDescendant(b.root, r), "radio " + s + " is on the page");
            assertEquals(IslandBundle.message("settings.style." + s.name() + ".title"), r.getText());
            assertEquals(r.getText(), r.getAccessibleContext().getAccessibleName());
            assertEquals(IslandBundle.message("settings.style." + s.name() + ".description"),
                    r.getAccessibleContext().getAccessibleDescription());
        }
        assertEquals(1, groups.size(), "one ButtonGroup");
        assertNotNull(groups.iterator().next());
        assertTrue(b.radio(PillStyle.B).isSelected());
        List<StylePicker.Preview> previews = b.previews();
        assertEquals(PillStyle.values().length, previews.size(), "one preview per card");
        onEdt(() -> { // cheap static paint, no exceptions, in every style
            for (StylePicker.Preview p : previews) {
                p.setSize(p.getPreferredSize());
                BufferedImage img = new BufferedImage(p.getWidth(), p.getHeight(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = img.createGraphics();
                try { p.paint(g); } finally { g.dispose(); }
            }
        });
    }

    @Test void withANotchTheACardIsDisabledWithTheNoteAndBAndCAreSelectable() throws Exception {
        Built b = build(true, PillStyle.B);
        assertFalse(b.radio(PillStyle.A).isEnabled(), "A disabled");
        assertTrue(b.radio(PillStyle.B).isEnabled());
        assertTrue(b.radio(PillStyle.C).isEnabled());
        assertEquals(b.note, b.picker.descriptionOf(PillStyle.A));
        assertTrue(b.noteVisible(), "note shown on the page");
        assertEquals(b.note, b.radio(PillStyle.A).getAccessibleContext().getAccessibleDescription());
        assertEquals(PillStyle.B, b.picker.previewOf(PillStyle.A), "A previews what will appear");
        assertEquals(PillStyle.C, b.picker.previewOf(PillStyle.C));

        onEdt(() -> b.radio(PillStyle.A).doClick());
        assertTrue(b.radio(PillStyle.B).isSelected(), "a disabled radio cannot be clicked");
        onEdt(() -> click(b.previews().get(PillStyle.A.ordinal())));
        assertTrue(b.radio(PillStyle.B).isSelected(), "nor its card");
        assertFalse(page.isModified());

        onEdt(() -> click(b.previews().get(PillStyle.C.ordinal())));
        assertTrue(b.radio(PillStyle.C).isSelected(), "C selectable by clicking its card");
        assertTrue(page.isModified());
        onEdt(() -> b.radio(PillStyle.B).doClick());
        assertTrue(b.radio(PillStyle.B).isSelected(), "B selectable");
        assertFalse(page.isModified());
    }

    @Test void withANotchASavedAStaysSelected() throws Exception {
        Built b = build(true, PillStyle.A);
        assertTrue(b.radio(PillStyle.A).isSelected());
        assertFalse(b.radio(PillStyle.A).isEnabled());
        assertFalse(page.isModified(), "a saved A is not changed by opening the page");
        assertEquals(PillStyle.B, b.picker.previewOf(PillStyle.A));
        assertTrue(b.noteVisible());
        onEdt(() -> b.radio(PillStyle.C).doClick());
        assertTrue(page.isModified());
        onEdt(() -> page.reset());
        assertTrue(b.radio(PillStyle.A).isSelected(), "reset brings back a saved A");
        assertFalse(page.isModified());
    }

    @Test void withoutANotchEveryCardIsSelectableAndThereIsNoNote() throws Exception {
        Built b = build(false, PillStyle.B);
        for (PillStyle s : PillStyle.values()) {
            assertTrue(b.radio(s).isEnabled(), s + " enabled");
            assertEquals(s, b.picker.previewOf(s));
            assertEquals(IslandBundle.message("settings.style." + s.name() + ".description"), b.picker.descriptionOf(s));
        }
        assertFalse(b.noteExists(), "no notch: no note on the page");
        onEdt(() -> click(b.previews().get(PillStyle.A.ordinal())));
        assertTrue(b.radio(PillStyle.A).isSelected());
        assertTrue(page.isModified());
        onEdt(() -> page.apply());
        assertEquals(PillStyle.A, b.settings.getStyle());
        assertFalse(page.isModified());
    }

    @Test void applyAndResetRoundTrip() throws Exception {
        Built b = build(false, PillStyle.B);
        assertFalse(page.isModified());
        onEdt(() -> b.radio(PillStyle.C).doClick());
        assertTrue(page.isModified());
        onEdt(() -> page.apply());
        assertEquals(PillStyle.C, b.settings.getStyle());
        assertEquals("C", b.settings.getState().style, "persisted as the enum name, as before");
        assertFalse(page.isModified());
        b.settings.setStyle(PillStyle.A); // changed elsewhere
        assertTrue(page.isModified());
        onEdt(() -> page.reset());
        assertTrue(b.radio(PillStyle.A).isSelected());
        assertFalse(page.isModified());
    }

    @Test void theLookAndFeelListenerRecoloursAndIsUnsubscribedOnDispose() throws Exception {
        build(false, PillStyle.B);
        assertTrue(page.lafSubscribedForTests());
        onEdt(IslandConfigurableNotchTest::publishLafChange);
        assertEquals(1, page.lafRefreshesForTests(), "a theme switch rebuilds the previews' palette");
        onEdt(() -> page.disposeUIResources());
        assertFalse(page.lafSubscribedForTests(), "unsubscribed");
        assertNull(page.pickerForTests(), "the page lets go of its components");
        onEdt(IslandConfigurableNotchTest::publishLafChange);
        assertEquals(1, page.lafRefreshesForTests(), "no callback after dispose");
        page = null;
    }

    // ------------------------------------------------------------------ helpers

    private static void publishLafChange() {
        ApplicationManager.getApplication().getMessageBus().syncPublisher(LafManagerListener.TOPIC).lookAndFeelChanged(null);
    }

    private static void click(Component c) {
        c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 2, 2, 1, false,
                MouseEvent.BUTTON1));
    }

    private record Built(IslandSettings settings, JComponent root, StylePicker picker, String note) {
        JRadioButton radio(PillStyle s) { return picker.radio(s); }

        List<StylePicker.Preview> previews() {
            List<StylePicker.Preview> out = new ArrayList<>();
            for (Component c : all(picker)) if (c instanceof StylePicker.Preview p) out.add(p);
            return out;
        }

        List<JComponent> noteComponents() {
            List<JComponent> out = new ArrayList<>();
            for (Component c : all(root)) {
                String t = c instanceof JTextComponent tc ? tc.getText() : c instanceof JLabel l ? l.getText() : null;
                if (t != null && plain(t).contains(plain(note))) out.add((JComponent) c);
            }
            return out;
        }
        boolean noteExists() { return !noteComponents().isEmpty(); }
        /** Visible = the note component and all its ancestors up to the page root are visible. */
        boolean noteVisible() {
            List<JComponent> cs = noteComponents();
            assertFalse(cs.isEmpty(), "note text from the bundle not found in the page");
            for (JComponent c : cs) {
                boolean v = true;
                for (Component p = c; p != null && p != root.getParent(); p = p.getParent()) v &= p.isVisible();
                if (v) return true;
            }
            return false;
        }
    }

    private Built build(boolean notch, PillStyle saved) throws Exception {
        IslandConfigurable.setAnyNotchForTests(notch);
        IslandSettings s = new IslandSettings();
        s.setStyle(saved);
        page = new IslandConfigurable(() -> s, false);
        JComponent[] root = new JComponent[1];
        onEdt(() -> root[0] = page.createComponent());
        assertNotNull(root[0]);
        StylePicker picker = page.pickerForTests();
        assertNotNull(picker, "style picker");
        assertTrue(isDescendant(root[0], picker), "picker on the page");
        return new Built(s, root[0], picker, IslandBundle.message("settings.style.notch"));
    }

    private static boolean isDescendant(Component root, Component c) {
        return SwingUtilities.isDescendingFrom(c, root);
    }

    private static List<Component> all(Component c) {
        List<Component> out = new ArrayList<>();
        out.add(c);
        if (c instanceof Container k) for (Component ch : k.getComponents()) out.addAll(all(ch));
        return out;
    }

    /** Strips HTML tags and collapses whitespace, so a word-wrapped HTML text still matches the bundle text. */
    private static String plain(String s) {
        return s.replaceAll("<[^>]*>", " ").replace("&nbsp;", " ").replaceAll("\\s+", " ").trim();
    }

    // ---- wrapping of the cards on narrow pages

    private static StylePicker picker() throws Exception {
        StylePicker[] p = new StylePicker[1];
        onEdt(() -> p[0] = new StylePicker(IslandController::buildPalette));
        return p[0];
    }

    private static List<Component> cards(StylePicker p) { return List.of(p.getComponents()); }

    /** Sizes the picker to the width and its preferred height (as a parent filling the width would), then lays out. */
    private static int rowsAt(StylePicker p, int width) throws Exception {
        int[] rows = new int[1];
        onEdt(() -> {
            p.setSize(width, 10);
            p.doLayout();
            int h = p.getPreferredSize().height; // read at the new width
            p.setSize(width, h);
            p.doLayout();
            assertEquals(h, p.getPreferredSize().height, "stable at " + width);
            Set<Integer> ys = new HashSet<>();
            int bottom = 0;
            for (Component c : p.getComponents()) {
                ys.add(c.getY());
                bottom = Math.max(bottom, c.getY() + c.getHeight());
                assertTrue(c.getX() >= 0 && c.getX() + c.getWidth() <= width, "card within width " + width);
            }
            assertEquals(h, bottom + p.getInsets().bottom, "preferred height matches laid-out height at " + width);
            assertTrue(h > 0);
            rows[0] = ys.size();
        });
        return rows[0];
    }

    @Test void cardsStayOnOneRowWhenThereIsRoom() throws Exception {
        StylePicker p = picker();
        assertEquals(1, rowsAt(p, 2000));
        int[] s = new int[2];
        onEdt(() -> { s[0] = p.getComponent(0).getWidth(); s[1] = p.getComponent(0).getHeight(); });
        for (Component c : cards(p)) assertEquals(new java.awt.Dimension(s[0], s[1]), c.getSize(), "equal sizes");
    }

    @Test void cardsWrapWhenNarrowAndReflowBetweenWidths() throws Exception {
        StylePicker p = picker();
        int[] one = new int[1];
        onEdt(() -> one[0] = p.getComponent(0).getPreferredSize().width);
        int gap = 8;
        assertEquals(1, rowsAt(p, 3 * one[0] + 2 * gap + 50));
        assertTrue(rowsAt(p, 300) >= 2, "two or more rows at 300 px");
        assertEquals(2, rowsAt(p, 2 * one[0] + gap + 5));
        assertEquals(3, rowsAt(p, one[0] + 5));
        assertEquals(1, rowsAt(p, 3 * one[0] + 2 * gap + 50), "wraps back to one row when widened");
        int[] w = new int[3];
        rowsAt(p, 300);
        onEdt(() -> { for (int i = 0; i < 3; i++) w[i] = p.getComponent(i).getWidth(); });
        assertEquals(w[0], w[1]);
        assertEquals(w[1], w[2]);
    }

    @Test void narrowPickerNeverAsksForMoreThanOneCardOfWidth() throws Exception {
        StylePicker p = picker();
        onEdt(() -> assertEquals(p.getComponent(0).getPreferredSize().width, p.getPreferredSize().width,
                "preferred width is one card, so no horizontal scrollbar"));
    }

    private static void onEdt(Runnable r) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) r.run(); else SwingUtilities.invokeAndWait(r);
    }
}
