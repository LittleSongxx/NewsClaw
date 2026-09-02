package vip.newsclaw.config;

import org.junit.jupiter.api.Test;
import vip.newsclaw.acp.controller.AcpEndpointController;
import vip.newsclaw.plugin.controller.PluginController;
import vip.newsclaw.system.controller.SystemSettingController;
import vip.newsclaw.tool.guard.controller.SecurityController;
import vip.newsclaw.tool.mcp.controller.McpServerController;
import vip.newsclaw.workspace.core.annotation.RequireGlobalAdmin;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalManagementAuthorizationTest {

    @Test
    void globalManagementMutationsRequireGlobalAdmin() {
        assertGlobal(McpServerController.class,
                "list", "get", "create", "update", "delete", "toggle",
                "setDisclosureTier", "test", "listTools", "refresh");
        assertGlobal(AcpEndpointController.class,
                "list", "get", "create", "update", "delete", "toggle", "test");
        assertGlobal(PluginController.class,
                "list", "get", "disable", "enable", "updateConfig");
        assertGlobal(SecurityController.class,
                "getGuardConfig", "updateGuardConfig", "getFileGuardConfig",
                "updateFileGuardConfig", "listRules", "listBuiltinRules", "createRule",
                "updateRule", "toggleRule", "deleteRule", "deleteRuleByPk",
                "exportRules", "importRules", "listAuditLogs", "getAuditStats",
                "listApprovals");
        assertGlobal(SystemSettingController.class,
                "getSettings", "saveSettings", "getSearchProviders",
                "saveLanguage", "saveSidecar");
    }

    private static void assertGlobal(Class<?> type, String... methodNames) {
        for (String name : methodNames) {
            Method method = java.util.Arrays.stream(type.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(name))
                    .findFirst().orElseThrow();
            assertThat(method.getAnnotation(RequireGlobalAdmin.class))
                    .as(type.getSimpleName() + "." + name)
                    .isNotNull();
        }
    }
}
