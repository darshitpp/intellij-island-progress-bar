package dev.darshit.islandbar.ide;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressModel;
import com.intellij.openapi.progress.TaskInfo;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.openapi.wm.ex.StatusBarEx;
import dev.darshit.islandbar.core.IdentityIds;
import dev.darshit.islandbar.core.TaskControls;
import dev.darshit.islandbar.core.TaskNames;
import dev.darshit.islandbar.core.TaskRow;
import dev.darshit.islandbar.core.TaskRowFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import kotlin.Pair;

/**
 * Reads the status bar's own list of running background tasks (platform-impl {@code StatusBarEx}, internal): the
 * same tasks and fractions the status bar shows, including coroutine-based ones. EDT only.
 */
public final class StatusBarTaskSource implements TaskSource {
    private static final Logger LOG = Logger.getInstance(StatusBarTaskSource.class);
    private final Map<Project, IdentityIds<ProgressModel>> ids = new WeakHashMap<>();

    @Override
    public synchronized void retainProjects(Collection<Project> open) { ids.keySet().retainAll(open); }

    @Override
    public synchronized void release() { ids.clear(); }

    /** Test seam: registers a project without an IDE. */
    synchronized void track(Project p) { ids.computeIfAbsent(p, _ -> new IdentityIds<>()); }

    synchronized int trackedProjects() { return ids.size(); }

    @Override
    public synchronized List<TaskRow> all(Project project) {
        StatusBar bar = WindowManager.getInstance().getStatusBar(project);
        if (!(bar instanceof StatusBarEx ex)) return List.of();
        List<Pair<TaskInfo, ProgressModel>> running = ex.getBackgroundProcessModels();
        IdentityIds<ProgressModel> projectIds = ids.computeIfAbsent(project, _ -> new IdentityIds<>());
        projectIds.retainOnly(running.stream().map(Pair::getSecond).toList());
        List<TaskRow> rows = new ArrayList<>();
        for (Pair<TaskInfo, ProgressModel> p : running) { // oldest first
            ProgressModel m = p.getSecond();
            if (!m.isRunning() || !m.getVisibleInStatusBar()) continue;
            rows.add(row(projectIds.idFor(m), m, p.getFirst()));
        }
        return rows;
    }

    private static TaskRow row(long id, ProgressModel m, TaskInfo info) {
        boolean stopping = m.isStopping(info);
        TaskControls controls = () -> guarded("cancel", m::cancel);
        return new TaskRow(id, m.isIndeterminate() ? null : m.getFraction(),
                TaskRowFactory.name(m.getTitle(), info.getTitle(), m.getText()),
                TaskRowFactory.detail(stopping, m.getDetails(), m.getText()),
                m.isCancellable(), TaskNames.pick(m.getCancelTooltipText()), stopping, controls);
    }

    private static void guarded(String what, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            LOG.warn("Task " + what + " failed", t);
        }
    }
}
