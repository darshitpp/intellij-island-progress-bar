package dev.darshit.islandbar.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TaskNamesTest {
    @Test void first_non_blank_candidate_wins_and_is_stripped() {
        assertEquals("Indexing…", TaskNames.pick(null, "  ", "  Indexing…  ", "later"));
    }

    @Test void nothing_usable_is_null() {
        assertNull(TaskNames.pick());
        assertNull(TaskNames.pick(null, "", " \t "));
    }

    @Test void very_long_names_are_capped_with_an_ellipsis() {
        String n = TaskNames.pick("x".repeat(5000));
        assertEquals(TaskNames.MAX_CHARS, n.length());
        assertTrue(n.endsWith("…"));
    }

    @Test void a_name_at_the_limit_is_untouched() {
        String s = "y".repeat(TaskNames.MAX_CHARS);
        assertEquals(s, TaskNames.pick(s));
    }

    @Test void the_cut_never_splits_a_surrogate_pair() {
        String s = "a".repeat(TaskNames.MAX_CHARS - 2) + "😀😀😀";
        String n = TaskNames.pick(s);
        assertTrue(n.length() <= TaskNames.MAX_CHARS);
        assertFalse(Character.isHighSurrogate(n.charAt(n.length() - 2)));
    }

    @Test void control_and_bidi_characters_become_spaces() {
        assertEquals("a b c d e", TaskNames.pick("a\nb\u0007c\u202Ed\u2067e"));
        assertEquals("x  y", TaskNames.pick("x\u200E\u200Fy"));
    }

    @Test void only_control_characters_is_null_and_falls_through() {
        assertNull(TaskNames.pick("\n\u202E\u2066"));
        assertEquals("next", TaskNames.pick("\n\t", "next"));
    }
}
