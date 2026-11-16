package dev.darshit.islandbar.ide;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Component;
import javax.swing.JLabel;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;

class IdeBarHiderTest {
    static class InlineProgressPanel extends JPanel {}
    static class InfoAndProgressPanelImpl extends JPanel {}

    private final IdeBarHider hider = new IdeBarHider();

    @Test void hidesOnlyInlineProgressPanel() {
        JPanel root = new JPanel();
        InfoAndProgressPanelImpl info = new InfoAndProgressPanelImpl();
        InlineProgressPanel inline = new InlineProgressPanel();
        JLabel label = new JLabel("x");
        info.add(inline); info.add(label); root.add(info);
        assertEquals(1, hider.hide(root, true));
        assertFalse(inline.isVisible());
        assertTrue(info.isVisible());
        assertTrue(label.isVisible());
        assertTrue(hider.isHidden());
    }

    @Test void restoreMakesSameInstancesVisible() {
        JPanel root = new JPanel();
        InlineProgressPanel inline = new InlineProgressPanel();
        root.add(inline);
        hider.hide(root, true);
        hider.restore();
        assertTrue(inline.isVisible());
        assertFalse(hider.isHidden());
    }

    @Test void restoreIsIdempotentAndSafeWithoutHide() {
        hider.restore();
        JPanel root = new JPanel();
        InlineProgressPanel inline = new InlineProgressPanel();
        root.add(inline);
        hider.hide(root, true);
        hider.restore();
        hider.restore();
        assertTrue(inline.isVisible());
    }

    @Test void hideTwiceKeepsOriginalState() {
        JPanel root = new JPanel();
        InlineProgressPanel inline = new InlineProgressPanel();
        root.add(inline);
        hider.hide(root, true);
        hider.hide(root, true);
        hider.restore();
        assertTrue(inline.isVisible());
    }

    @Test void componentThatWasInvisibleStaysInvisible() {
        JPanel root = new JPanel();
        InlineProgressPanel inline = new InlineProgressPanel();
        inline.setVisible(false);
        root.add(inline);
        hider.hide(root, true);
        hider.restore();
        assertFalse(inline.isVisible());
    }

    @Test void restoreAfterComponentRemovedFromTreeDoesNotThrow() {
        JPanel root = new JPanel();
        InlineProgressPanel inline = new InlineProgressPanel();
        root.add(inline);
        hider.hide(root, true);
        root.remove(inline);
        assertDoesNotThrow(hider::restore);
    }

    @Test void newInlinePanelAddedLaterIsHiddenOnRescan() {
        JPanel root = new JPanel();
        InlineProgressPanel a = new InlineProgressPanel();
        root.add(a);
        hider.hide(root, true);
        InlineProgressPanel b = new InlineProgressPanel();
        root.add(b);
        assertEquals(1, hider.hide(root, false));
        assertTrue(b.isVisible());
        assertEquals(2, hider.hide(root, true));
        assertFalse(b.isVisible());
    }

    @Test void reapplyWithoutRescanReHidesAPanelTheIdeShowedAgain() {
        JPanel root = new JPanel();
        InlineProgressPanel a = new InlineProgressPanel();
        root.add(a);
        hider.hide(root, true);
        a.setVisible(true);
        hider.hide(root, false);
        assertFalse(a.isVisible());
        hider.restore();
        assertTrue(a.isVisible());
    }

    @Test void detachedCachedPanelTriggersRescan() {
        JPanel root = new JPanel();
        InlineProgressPanel a = new InlineProgressPanel();
        root.add(a);
        hider.hide(root, true);
        root.remove(a);
        InlineProgressPanel b = new InlineProgressPanel();
        root.add(b);
        assertEquals(1, hider.hide(root, false));
        assertFalse(b.isVisible());
    }

    @Test void noInlinePanelReturnsZero() {
        JPanel root = new JPanel();
        root.add(new JLabel("x"));
        assertEquals(0, hider.hide(root, true));
        assertFalse(hider.isHidden());
    }

    @Test void panelDetachedAndReattachedWhileHiddenIsStillRestored() {
        JPanel root = new JPanel();
        InlineProgressPanel a = new InlineProgressPanel();
        root.add(a);
        hider.hide(root, true);
        root.remove(a);
        root.add(new JLabel("x"));
        hider.hide(root, false); // detached: rescan drops it
        root.add(a);             // same instance comes back, still hidden by us
        hider.hide(root, true);
        assertFalse(a.isVisible());
        hider.restore();
        assertTrue(a.isVisible());
    }

    @Test void panelDetachedWhileHiddenIsRestoredOnShutdownEvenIfNeverReattached() {
        JPanel root = new JPanel();
        InlineProgressPanel a = new InlineProgressPanel();
        root.add(a);
        hider.hide(root, true);
        root.remove(a);
        hider.hide(root, false);
        hider.restore();
        assertTrue(a.isVisible());
    }
}
