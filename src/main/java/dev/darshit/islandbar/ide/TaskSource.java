package dev.darshit.islandbar.ide;

import com.intellij.openapi.project.Project;
import dev.darshit.islandbar.core.TaskRow;
import java.util.Collection;
import java.util.List;

/** The one place that knows how to observe running IDE tasks; swap the implementation if the platform API changes. */
public interface TaskSource {
    /** All tasks to show for this project, oldest first. {@code id} is stable for one task run. EDT only. */
    List<TaskRow> all(Project project);

    /** Forget per-project state for every project not in {@code open}. EDT only. */
    default void retainProjects(Collection<Project> open) {}

    /** Drop all per-project state (shutdown). EDT only. */
    default void release() {}
}
