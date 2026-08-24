package vip.mate.tool.guard.model;

/**
 * 安全威胁分类
 */
public enum GuardCategory {

    COMMAND_INJECTION,
    DATA_EXFILTRATION,
    PATH_TRAVERSAL,
    SENSITIVE_FILE_ACCESS,
    NETWORK_ABUSE,
    CREDENTIAL_EXPOSURE,
    RESOURCE_ABUSE,
    CODE_EXECUTION,
    PRIVILEGE_ESCALATION
}
