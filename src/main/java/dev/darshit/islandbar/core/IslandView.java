package dev.darshit.islandbar.core;

import java.util.List;
import org.jetbrains.annotations.Nullable;

public record IslandView(@Nullable TaskRow primary, List<TaskRow> rows, boolean showChevron) {
    public static final IslandView HIDDEN = new IslandView(null, List.of(), false);
}
