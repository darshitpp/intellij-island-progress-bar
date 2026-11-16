package dev.darshit.islandbar.core;

import dev.darshit.islandbar.ide.IslandBundle;
import org.jetbrains.annotations.Nullable;

/** Pure helpers that turn raw platform strings into the name and detail shown on a row. */
public final class TaskRowFactory {
    private TaskRowFactory() {}

    public static String name(@Nullable String title, @Nullable String infoTitle, @Nullable String text) {
        String n = TaskNames.pick(title, infoTitle, text);
        return n != null ? n : IslandBundle.message("task.working");
    }

    public static @Nullable String detail(boolean stopping, @Nullable String details, @Nullable String text) {
        if (stopping) return IslandBundle.message("task.stopping");
        return TaskNames.pick(details, text);
    }
}
