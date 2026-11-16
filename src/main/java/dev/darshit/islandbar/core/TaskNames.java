package dev.darshit.islandbar.core;

import org.jetbrains.annotations.Nullable;

/** Picks and bounds the task name shown on the Touch Bar. Titles come from the IDE and other plugins: treat as untrusted. */
public final class TaskNames {
    public static final int MAX_CHARS = 200;

    private static final java.util.regex.Pattern CONTROLS =
            java.util.regex.Pattern.compile("[\\p{Cntrl}\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]");

    private TaskNames() {}

    /** The first non-blank candidate, stripped and capped at {@link #MAX_CHARS} (ending in an ellipsis); null if none. */
    public static @Nullable String pick(String... candidates) {
        for (String c : candidates) {
            if (c == null) continue;
            String s = CONTROLS.matcher(c).replaceAll(" ").strip();
            if (s.isEmpty()) continue;
            if (s.length() <= MAX_CHARS) return s;
            int end = MAX_CHARS - 1;
            if (Character.isHighSurrogate(s.charAt(end - 1))) end--; // do not split a surrogate pair
            return s.substring(0, end) + "…";
        }
        return null;
    }
}
