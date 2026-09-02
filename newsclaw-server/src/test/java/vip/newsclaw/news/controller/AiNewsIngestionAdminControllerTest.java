package vip.newsclaw.news.controller;

import org.junit.jupiter.api.Test;
import vip.newsclaw.news.service.AiNewsIngestionAdminService;
import vip.newsclaw.workspace.core.annotation.RequireGlobalAdmin;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class AiNewsIngestionAdminControllerTest {

    @Test
    void everyLedgerReadRequiresWorkspaceAdminRole() {
        new AiNewsIngestionAdminController(mock(AiNewsIngestionAdminService.class));

        Method[] operations = Arrays.stream(AiNewsIngestionAdminController.class
                        .getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .toArray(Method[]::new);
        assertEquals(3, operations.length);
        for (Method operation : operations) {
            RequireGlobalAdmin permission = operation.getAnnotation(RequireGlobalAdmin.class);
            assertNotNull(permission, operation.getName() + " must be role guarded");
        }
    }
}
