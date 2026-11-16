package dev.darshit.islandbar.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TaskRowFactoryTest {
    @Test void nameFallsBackToWorking() {
        assertEquals("Working…", TaskRowFactory.name(null, " ", ""));
    }

    @Test void nameBoundedLikeTaskNames() {
        String n = TaskRowFactory.name("x".repeat(500), null, null);
        assertEquals(TaskNames.MAX_CHARS, n.length());
    }

    @Test void detailStoppingWinsOverText() {
        assertEquals("Stopping…", TaskRowFactory.detail(true, "d", "t"));
    }

    @Test void detailFallsBackToText() {
        assertEquals("t", TaskRowFactory.detail(false, null, "t"));
        assertEquals("d", TaskRowFactory.detail(false, "d", "t"));
    }

    @Test void emojiAndRtlNamesPassThrough() {
        String s = "🚀 بناء";
        assertEquals(s, TaskRowFactory.name(s, null, null));
    }
}
