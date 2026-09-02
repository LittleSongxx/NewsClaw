package vip.newsclaw.news.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import vip.newsclaw.news.model.AiNewsEventClusterMergeRequest;
import vip.newsclaw.news.model.AiNewsEventClusterReviewRequest;
import vip.newsclaw.news.model.AiNewsEventClusterSplitRequest;
import vip.newsclaw.workspace.core.annotation.RequireWorkspaceRole;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AiNewsEventClusterControllerTest {

    @Test
    void readsRequireViewerAndEveryMutationRequiresMember() throws Exception {
        assertGet("list", "viewer", new String[]{}, Long.class, int.class, int.class,
                String.class);
        assertGet("get", "viewer", new String[]{"/{id}"}, Long.class, Long.class);
        assertGet("reviews", "viewer", new String[]{"/reviews"}, Long.class, String.class,
                int.class);
        assertPost("merge", "member", new String[]{"/merge"}, AiNewsEventClusterMergeRequest.class,
                Long.class);
        assertPost("split", "member", new String[]{"/split"}, AiNewsEventClusterSplitRequest.class,
                Long.class);
        assertPost("resolve", "member", new String[]{"/reviews/{id}" + "/resolve"}, Long.class,
                AiNewsEventClusterReviewRequest.class, Long.class);
        assertPost("backfill", "member", new String[]{"/backfill"}, Long.class, int.class);
    }

    private static void assertGet(String name, String role, String[] paths,
                                  Class<?>... parameters) throws Exception {
        Method method = AiNewsEventClusterController.class.getMethod(name, parameters);
        RequireWorkspaceRole permission = method.getAnnotation(RequireWorkspaceRole.class);
        GetMapping mapping = method.getAnnotation(GetMapping.class);
        assertNotNull(permission);
        assertNotNull(mapping);
        assertEquals(role, permission.value());
        assertArrayEquals(paths, mapping.value());
    }

    private static void assertPost(String name, String role, String[] paths,
                                   Class<?>... parameters) throws Exception {
        Method method = AiNewsEventClusterController.class.getMethod(name, parameters);
        RequireWorkspaceRole permission = method.getAnnotation(RequireWorkspaceRole.class);
        PostMapping mapping = method.getAnnotation(PostMapping.class);
        assertNotNull(permission);
        assertNotNull(mapping);
        assertEquals(role, permission.value());
        assertArrayEquals(paths, mapping.value());
    }
}
