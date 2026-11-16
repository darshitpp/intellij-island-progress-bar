package dev.darshit.islandbar.core;

import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/** One running task. {@code controls} is excluded from equals/hashCode: fresh lambdas each poll are not a change. */
public record TaskRow(long id, @Nullable Double fraction, @Nullable String name, @Nullable String detail,
                      boolean cancellable, @Nullable String cancelTooltip, 
                      boolean stopping, @Nullable TaskControls controls) {

    @Override public boolean equals(Object o) {
        return o instanceof TaskRow r && id == r.id && Objects.equals(fraction, r.fraction)
                && Objects.equals(name, r.name) && Objects.equals(detail, r.detail)
                && cancellable == r.cancellable && Objects.equals(cancelTooltip, r.cancelTooltip)
                && stopping == r.stopping;
    }

    @Override public int hashCode() {
        return Objects.hash(id, fraction, name, detail, cancellable, cancelTooltip, stopping);
    }
}
