package dev.darshit.islandbar.ide;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;

class IslandControllerTest {
    @Test void pollsWhileActive() {
        assertTrue(IslandController.shouldPoll(true, false));
        assertTrue(IslandController.shouldPoll(true, true));
    }

    /** A task that ends while another app is in front must still give the IDE its bar back. */
    @Test void keepsPollingWhileInactiveOnlyWhileTheIdeBarIsHidden() {
        assertTrue(IslandController.shouldPoll(false, true));
        assertFalse(IslandController.shouldPoll(false, false));
    }

    static class InlineProgressPanel extends JPanel {}

    /**
     * final-review M4: at shutdown, bars the coordinator would have restored through a queued EDT task (after a
     * disable on the AppKit thread) must come back before the hiders are dropped.
     */
    @Test void restoreAllShowsEveryHiddenBarAndSkipsTheRest() {
        JPanel root1 = new JPanel(), root2 = new JPanel();
        InlineProgressPanel a = new InlineProgressPanel(), b = new InlineProgressPanel();
        root1.add(a);
        root2.add(b);
        IdeBarHider h1 = new IdeBarHider(), h2 = new IdeBarHider(), idle = new IdeBarHider();
        h1.hide(root1, true);
        h2.hide(root2, true);
        assertFalse(a.isVisible());
        assertFalse(b.isVisible());

        IslandController.restoreAll(List.of(h1, idle, h2));

        assertTrue(a.isVisible());
        assertTrue(b.isVisible());
        assertFalse(h1.isHidden());
        assertFalse(h2.isHidden());
        IslandController.restoreAll(List.of(h1, idle, h2)); // idempotent
        assertTrue(a.isVisible());
    }
}
