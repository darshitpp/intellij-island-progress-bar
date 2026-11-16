package dev.darshit.islandbar.ide;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.intellij.openapi.project.Project;
import java.lang.reflect.Proxy;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatusBarTaskSourceTest {
    private static Project project() {
        return (Project) Proxy.newProxyInstance(Project.class.getClassLoader(), new Class<?>[] {Project.class},
                (proxy, m, a) -> switch (m.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == a[0];
                    default -> null;
                });
    }

    @Test void retainProjectsForgetsClosedProjectsAndReleaseClearsAll() {
        StatusBarTaskSource s = new StatusBarTaskSource();
        Project a = project(), b = project();
        s.track(a); s.track(b);
        s.retainProjects(List.of(a));
        assertEquals(1, s.trackedProjects());
        s.release();
        assertEquals(0, s.trackedProjects());
    }
}
