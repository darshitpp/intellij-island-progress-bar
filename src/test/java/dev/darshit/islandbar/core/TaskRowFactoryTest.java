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

    @Test void detailStoppingWinsOverPaused() {
        assertEquals("Stopping…", TaskRowFactory.detail(true, true, "d", "t"));
    }

    @Test void detailPausedWinsOverText() {
        assertEquals("Paused", TaskRowFactory.detail(false, true, "d", "t"));
    }

    @Test void detailFallsBackToText() {
        assertEquals("t", TaskRowFactory.detail(false, false, null, "t"));
        assertEquals("d", TaskRowFactory.detail(false, false, "d", "t"));
    }

    @Test void emojiAndRtlNamesPassThrough() {
        String s = "🚀 بناء";
        assertEquals(s, TaskRowFactory.name(s, null, null));
    }
}
