package vip.newsclaw.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentConfigTest {

    private static final String[] PROPERTIES = {
            "NEWSCLAW_ENV_CONFIG_ENABLED",
            "NEWSCLAW_PRIMARY_MODEL_PROVIDER",
            "NEWSCLAW_PRIMARY_MODEL",
            "NEWSCLAW_FALLBACK_MODEL_CHAIN",
            "NEWSCLAW_MODEL_CHAIN",
            "NEWSCLAW_PROVIDER_CUSTOM_RADAR_API_KEY",
            "NEWSCLAW_PROVIDER_DASHSCOPE_API_KEY",
            "DASHSCOPE_API_KEY",
            "NEWSCLAW_PROVIDER_CUSTOM_RADAR_BASE_URL",
            "BAILIAN_API_KEY",
            "BAILIAN_BASE_URL",
            "MODELSCOPE_API_TOKEN",
            "GOOGLE_IMAGEN_API_KEY",
            "GOOGLE_BASE_URL",
            "OPENAI_BASE_URL",
            "AZURE_OPENAI_ENDPOINT",
            "ALIYUN_CODINGPLAN_INTL_BASE_URL",
            "MINIMAX_BASE_URL_CN",
            "XAI_BASE_URL",
            "OPENROUTER_BASE_URL",
            "SILICONFLOW_BASE_URL_INTL",
            "HUNYUAN_BASE_URL",
            "TENCENTCLOUD_SECRET_ID",
            "TENCENTCLOUD_SECRET_KEY",
            "XIAOMI_BASE_URL",
            "MIMO_BASE_URL",
            "OPENCODE_BASE_URL",
            "SERPER_API_KEY",
            "TAVILY_API_KEYS",
            "TAVILY_API_KEY",
            "FEISHU_APP_ID",
            "FEISHU_APP_SECRET",
            "NEWSCLAW_CHANNEL_FEISHU_APP_ID",
            "DINGTALK_CLIENT_ID",
            "WECOM_BOT_ID",
            "QQ_BOT_APP_ID",
            "WEIXIN_BOT_TOKEN"
    };

    @AfterEach
    void clearProperties() {
        for (String property : PROPERTIES) {
            System.clearProperty(property);
        }
    }

    @Test
    void providerAliasesAndCustomNamesResolveFromSystemProperties() {
        System.setProperty("NEWSCLAW_ENV_CONFIG_ENABLED", "true");
        System.setProperty("GOOGLE_IMAGEN_API_KEY", "imagen-test-key");
        System.setProperty("OPENAI_BASE_URL", "https://openai.example.test/v1");
        System.setProperty("AZURE_OPENAI_ENDPOINT", "https://azure.example.test/openai");
        System.setProperty("ALIYUN_CODINGPLAN_INTL_BASE_URL", "https://coding-intl.example.test/v1");
        System.setProperty("NEWSCLAW_PROVIDER_CUSTOM_RADAR_API_KEY", "custom-test-key");
        System.setProperty("BAILIAN_API_KEY", "bailian-test-key");
        System.setProperty("MODELSCOPE_API_TOKEN", "modelscope-token");
        System.setProperty("GOOGLE_BASE_URL", "https://google.example.test");
        System.setProperty("MINIMAX_BASE_URL_CN", "https://minimax.example.test");
        System.setProperty("XAI_BASE_URL", "https://xai.example.test/v1");
        System.setProperty("OPENROUTER_BASE_URL", "https://router.example.test/v1");
        System.setProperty("SILICONFLOW_BASE_URL_INTL", "https://silicon.example.test/v1");
        System.setProperty("HUNYUAN_BASE_URL", "https://hunyuan.example.test");
        System.setProperty("XIAOMI_BASE_URL", "https://xiaomi.example.test/v1");
        System.setProperty("MIMO_BASE_URL", "https://mimo.example.test/v1");
        System.setProperty("OPENCODE_BASE_URL", "https://opencode.example.test/v1");

        assertEquals("imagen-test-key", EnvironmentConfig.providerApiKey("google"));
        assertEquals("https://openai.example.test/v1", EnvironmentConfig.providerBaseUrl("openai"));
        assertEquals("https://azure.example.test/openai", EnvironmentConfig.providerBaseUrl("azure-openai"));
        assertEquals("https://coding-intl.example.test/v1", EnvironmentConfig.providerBaseUrl("aliyun-codingplan-intl"));
        assertEquals("custom-test-key", EnvironmentConfig.providerApiKey("custom-radar"));
        assertEquals("bailian-test-key", EnvironmentConfig.providerApiKey("bailian-team"));
        assertEquals("modelscope-token", EnvironmentConfig.providerApiKey("modelscope"));
        assertEquals("https://google.example.test", EnvironmentConfig.providerBaseUrl("google"));
        assertEquals("https://minimax.example.test", EnvironmentConfig.providerBaseUrl("minimax-cn"));
        assertEquals("https://xai.example.test/v1", EnvironmentConfig.providerBaseUrl("xai"));
        assertEquals("https://router.example.test/v1", EnvironmentConfig.providerBaseUrl("openrouter"));
        assertEquals("https://silicon.example.test/v1", EnvironmentConfig.providerBaseUrl("siliconflow-intl"));
        assertEquals("https://hunyuan.example.test", EnvironmentConfig.providerBaseUrl("hunyuan-3d"));
        assertEquals("https://xiaomi.example.test/v1", EnvironmentConfig.providerBaseUrl("xiaomi-mimo"));
        System.clearProperty("XIAOMI_BASE_URL");
        assertEquals("https://mimo.example.test/v1", EnvironmentConfig.providerBaseUrl("xiaomi-mimo"));
        assertEquals("https://opencode.example.test/v1", EnvironmentConfig.providerBaseUrl("opencode"));
    }

    @Test
    void systemSettingAndChannelOverridesUseEnvironmentFirst() {
        System.setProperty("SERPER_API_KEY", "serper-test-key");
        System.setProperty("FEISHU_APP_ID", "env-app-id");
        System.setProperty("FEISHU_APP_SECRET", "env-app-secret");

        assertEquals("serper-test-key", EnvironmentConfig.systemSetting("serperApiKey"));

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("app_id", "database-app-id");
        EnvironmentConfig.applyChannelOverrides("feishu", config);

        assertEquals("env-app-id", config.get("app_id"));
        assertEquals("env-app-secret", config.get("app_secret"));
    }

    @Test
    void tavilyPoolVariableTakesPriorityWithLegacySingleKeyFallback() {
        System.setProperty("TAVILY_API_KEY", "legacy-key");
        assertEquals("legacy-key", EnvironmentConfig.systemSetting("tavilyApiKey"));

        System.setProperty("TAVILY_API_KEYS", "pool-key-a,pool-key-b");
        assertEquals("pool-key-a,pool-key-b", EnvironmentConfig.systemSetting("tavilyApiKey"));
    }

    @Test
    void disablingEnvironmentConfigRestoresDatabaseFallback() {
        System.setProperty("NEWSCLAW_ENV_CONFIG_ENABLED", "false");
        System.setProperty("GOOGLE_IMAGEN_API_KEY", "should-not-be-used");

        assertNull(EnvironmentConfig.providerApiKey("google"));
        assertNull(EnvironmentConfig.systemSetting("serperApiKey"));

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("app_id", "database-app-id");
        EnvironmentConfig.applyChannelOverrides("feishu", config);
        assertEquals("database-app-id", config.get("app_id"));
    }

    @Test
    void dashscopeBootstrapSentinelDoesNotOverrideDatabaseCredential() {
        System.setProperty("DASHSCOPE_API_KEY", "configure-in-admin-ui");
        assertNull(EnvironmentConfig.providerApiKey("dashscope"));
    }

    @Test
    void dashscopeBootstrapSentinelDoesNotHideBailianAlias() {
        System.setProperty("DASHSCOPE_API_KEY", "configure-in-admin-ui");
        System.setProperty("BAILIAN_API_KEY", "bailian-real-key");

        assertEquals("bailian-real-key", EnvironmentConfig.providerApiKey("dashscope"));
    }

    @Test
    void tencentCredentialAliasesPackSecretIdAndSecretKey() {
        System.setProperty("TENCENTCLOUD_SECRET_ID", "secret-id");
        System.setProperty("TENCENTCLOUD_SECRET_KEY", "secret-key");

        assertEquals("secret-id:secret-key", EnvironmentConfig.providerApiKey("hunyuan-3d"));
    }

    @Test
    void effectiveChannelConfigIsMutableAndOverlaysAllDomesticChannelCredentials() {
        System.setProperty("FEISHU_APP_ID", "feishu-env");
        System.setProperty("DINGTALK_CLIENT_ID", "ding-env");
        System.setProperty("WECOM_BOT_ID", "wecom-env");
        System.setProperty("QQ_BOT_APP_ID", "qq-env");
        System.setProperty("WEIXIN_BOT_TOKEN", "weixin-env");

        Map<String, Object> feishu = EnvironmentConfig.effectiveChannelConfig("feishu", Map.of());
        Map<String, Object> dingtalk = EnvironmentConfig.effectiveChannelConfig("dingtalk", Map.of());
        Map<String, Object> wecom = EnvironmentConfig.effectiveChannelConfig("wecom", Map.of());
        Map<String, Object> qq = EnvironmentConfig.effectiveChannelConfig("qq", Map.of());
        Map<String, Object> weixin = EnvironmentConfig.effectiveChannelConfig("weixin", Map.of());

        assertEquals("feishu-env", feishu.get("app_id"));
        assertEquals("ding-env", dingtalk.get("client_id"));
        assertEquals("wecom-env", wecom.get("bot_id"));
        assertEquals("qq-env", qq.get("app_id"));
        assertEquals("weixin-env", weixin.get("bot_token"));
        assertTrue(feishu instanceof LinkedHashMap);
        feishu.put("local_option", "safe-to-mutate");
        assertEquals("safe-to-mutate", feishu.get("local_option"));
    }

    @Test
    void configuredModelChainReadsPrimaryAndOrderedFallbacks() {
        System.setProperty("NEWSCLAW_PRIMARY_MODEL_PROVIDER", "bailian-team");
        System.setProperty("NEWSCLAW_PRIMARY_MODEL", "qwen3.7-plus");
        System.setProperty("NEWSCLAW_FALLBACK_MODEL_CHAIN",
                "deepseek::deepseek-v4-flash, malformed,volcengine:deepseek-v4-pro");

        List<EnvironmentConfig.ModelSelection> chain = EnvironmentConfig.configuredModelChain();

        assertEquals(List.of(
                new EnvironmentConfig.ModelSelection("bailian-team", "qwen3.7-plus"),
                new EnvironmentConfig.ModelSelection("deepseek", "deepseek-v4-flash"),
                new EnvironmentConfig.ModelSelection("volcengine", "deepseek-v4-pro")), chain);
    }

    @Test
    void compactModelChainCanBeUsedWithoutExplicitPrimary() {
        System.setProperty("NEWSCLAW_MODEL_CHAIN",
                "bailian-team::qwen3.7-plus,deepseek::deepseek-v4-flash");

        assertEquals(List.of(
                new EnvironmentConfig.ModelSelection("bailian-team", "qwen3.7-plus"),
                new EnvironmentConfig.ModelSelection("deepseek", "deepseek-v4-flash")),
                EnvironmentConfig.configuredModelChain());
    }
}
