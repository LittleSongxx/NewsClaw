package vip.mate.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runtime configuration sourced from the process environment.
 *
 * <p>MateClaw historically kept most credentials in database rows managed by
 * the admin UI.  The AI-news operations deployment is commonly provisioned by
 * Docker, so credentials also need a deterministic, restart-safe environment
 * variable path.  This class deliberately only reads values; it never logs or
 * persists secrets.  Callers can therefore keep the existing database/UI
 * fallback without duplicating provider and channel naming conventions.</p>
 */
public final class EnvironmentConfig {

    private static final String ENABLED = "MATECLAW_ENV_CONFIG_ENABLED";

    /**
     * One environment-owned model chain shared by the default-model resolver
     * and the runtime failover builder.  The first entry is the primary model;
     * subsequent entries are tried in order after a retryable provider error.
     */
    public record ModelSelection(String providerId, String modelName) {
    }

    private EnvironmentConfig() {
    }

    /**
     * Environment configuration is enabled by default.  A system property is
     * also accepted to make focused unit tests deterministic without mutating
     * the host process environment.
     */
    public static boolean enabled() {
        String flag = value(ENABLED);
        return flag == null || !"false".equalsIgnoreCase(flag.trim());
    }

    /**
     * Read a boolean deployment flag without making malformed values fail
     * application startup.
     */
    public static boolean booleanValue(String name, boolean defaultValue) {
        String raw = value(name);
        if (raw == null) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(raw) || "1".equals(raw)) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw) || "0".equals(raw)) {
            return false;
        }
        return defaultValue;
    }

    /**
     * Whether the vertical AI-news radar is allowed to schedule discovery.
     * The readiness check (model credential + domestic IM) belongs to the
     * vertical seed because it also needs database channel state.
     */
    public static boolean aiNewsRadarEnabled() {
        return enabled() && booleanValue("MATECLAW_AI_NEWS_RADAR_ENABLED", true);
    }

    /** Return a non-blank environment value, with a system-property fallback. */
    public static String value(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String env = System.getenv(name);
        if (hasText(env)) {
            return env.trim();
        }
        String property = System.getProperty(name);
        return hasText(property) ? property.trim() : null;
    }

    public static String firstNonBlank(String... names) {
        if (names == null) {
            return null;
        }
        for (String name : names) {
            String candidate = value(name);
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Resolve the deployment model chain from environment variables.
     *
     * <p>The explicit variables are intentionally easy to find in a Docker
     * deployment:</p>
     * <ul>
     *   <li>{@code MATECLAW_PRIMARY_MODEL_PROVIDER} +
     *       {@code MATECLAW_PRIMARY_MODEL}</li>
     *   <li>{@code MATECLAW_FALLBACK_MODEL_CHAIN}, a comma-separated list of
     *       {@code provider::model} entries</li>
     * </ul>
     *
     * <p>For compact deployments, {@code MATECLAW_MODEL_CHAIN} accepts the
     * complete chain in the same format.  The DEFAULT/LLM aliases keep older
     * .env files readable.  Invalid or incomplete entries are ignored so a
     * typo cannot prevent the server from starting.</p>
     */
    public static List<ModelSelection> configuredModelChain() {
        if (!enabled()) {
            return List.of();
        }

        List<ModelSelection> result = new ArrayList<>();
        String primaryProvider = firstNonBlank(
                "MATECLAW_PRIMARY_MODEL_PROVIDER",
                "MATECLAW_DEFAULT_MODEL_PROVIDER",
                "MATECLAW_LLM_PRIMARY_PROVIDER");
        String primaryModel = firstNonBlank(
                "MATECLAW_PRIMARY_MODEL",
                "MATECLAW_DEFAULT_MODEL",
                "MATECLAW_LLM_PRIMARY_MODEL");
        addSelection(result, primaryProvider, primaryModel);

        String compact = firstNonBlank("MATECLAW_MODEL_CHAIN", "MATECLAW_LLM_MODEL_CHAIN");
        if (compact != null) {
            parseSelections(result, compact);
        }
        String fallback = firstNonBlank(
                "MATECLAW_FALLBACK_MODEL_CHAIN",
                "MATECLAW_LLM_FALLBACK_CHAIN");
        if (fallback != null) {
            parseSelections(result, fallback);
        }

        // Keep the first occurrence of an exact pair. This also makes it safe
        // to specify the primary in both the explicit and compact forms.
        return result.stream().distinct().toList();
    }

    private static void parseSelections(List<ModelSelection> target, String raw) {
        for (String token : raw.split(",")) {
            if (token == null || token.isBlank()) continue;
            String value = token.trim();
            String provider;
            String model;
            int separator = value.indexOf("::");
            if (separator >= 0) {
                provider = value.substring(0, separator);
                model = value.substring(separator + 2);
            } else {
                // A single colon is accepted for convenience. The documented
                // :: form remains unambiguous for provider/model identifiers.
                separator = value.indexOf(':');
                if (separator >= 0) {
                    provider = value.substring(0, separator);
                    model = value.substring(separator + 1);
                } else {
                    separator = value.indexOf('/');
                    if (separator < 0) continue;
                    provider = value.substring(0, separator);
                    model = value.substring(separator + 1);
                }
            }
            addSelection(target, provider, model);
        }
    }

    private static void addSelection(List<ModelSelection> target, String provider, String model) {
        if (provider == null || provider.isBlank() || model == null || model.isBlank()) return;
        target.add(new ModelSelection(provider.trim(), model.trim()));
    }

    /**
     * Resolve a model provider credential.  The generic name makes custom
     * providers possible without a code change; aliases keep the built-in
     * provider catalog ergonomic in .env files.
     */
    public static String providerApiKey(String providerId) {
        if (!enabled() || providerId == null || providerId.isBlank()) {
            return null;
        }
        String normalized = normalize(providerId);
        String generic = "MATECLAW_PROVIDER_" + normalized + "_API_KEY";
        String resolved = switch (providerId) {
            case "dashscope", "dashscope-compat" ->
                    firstApiKey(generic, "DASHSCOPE_API_KEY", "BAILIAN_API_KEY");
            case "bailian", "bailian-team" ->
                    firstApiKey(generic, "BAILIAN_API_KEY", "DASHSCOPE_API_KEY");
            case "modelscope" -> firstApiKey(generic, "MODELSCOPE_API_KEY", "MODELSCOPE_API_TOKEN");
            case "aliyun-codingplan", "aliyun-codingplan-intl" ->
                    firstApiKey(generic, "ALIYUN_CODINGPLAN_API_KEY", "DASHSCOPE_API_KEY");
            case "openai" -> firstApiKey(generic, "OPENAI_API_KEY");
            case "azure-openai" -> firstApiKey(generic, "AZURE_OPENAI_API_KEY");
            case "minimax" -> firstApiKey(generic, "MINIMAX_API_KEY_INTL", "MINIMAX_API_KEY");
            case "minimax-cn" -> firstApiKey(generic, "MINIMAX_API_KEY_CN", "MINIMAX_API_KEY");
            case "kimi-cn" -> firstApiKey(generic, "KIMI_API_KEY_CN", "MOONSHOT_API_KEY", "KIMI_API_KEY");
            case "kimi-intl" -> firstApiKey(generic, "KIMI_API_KEY_INTL", "MOONSHOT_API_KEY", "KIMI_API_KEY");
            case "kimi-code" -> firstApiKey(generic, "KIMI_CODE_API_KEY", "KIMI_API_KEY", "MOONSHOT_API_KEY");
            case "deepseek" -> firstApiKey(generic, "DEEPSEEK_API_KEY");
            case "anthropic" -> firstApiKey(generic, "ANTHROPIC_API_KEY");
            // The Google image/music sidecars use the `google` provider row;
            // keep both the conventional Google key names and the explicit
            // Imagen alias so a multimodal deployment can be provisioned only
            // from .env as well.
            case "gemini" -> firstApiKey(generic, "GEMINI_API_KEY", "GOOGLE_API_KEY",
                    "GOOGLE_IMAGEN_API_KEY");
            case "google" -> firstApiKey(generic, "GOOGLE_API_KEY", "GEMINI_API_KEY",
                    "GOOGLE_IMAGEN_API_KEY");
            case "xai" -> firstApiKey(generic, "XAI_API_KEY");
            case "openrouter" -> firstApiKey(generic, "OPENROUTER_API_KEY");
            case "zhipu-cn", "zhipu-intl", "zhipu-cn-codingplan", "zhipu-intl-codingplan" ->
                    firstApiKey(generic, "ZHIPU_API_KEY", "GLM_API_KEY");
            case "volcengine", "volcengine-plan", "volcengine-agent-plan" ->
                    firstApiKey(generic, "VOLCENGINE_API_KEY", "ARK_API_KEY");
            case "siliconflow-cn", "siliconflow-intl" -> firstApiKey(generic, "SILICONFLOW_API_KEY");
            case "hunyuan-3d" -> tencentCloudCredential(generic);
            case "xiaomi-mimo" -> firstApiKey(generic, "XIAOMI_API_KEY", "MIMO_API_KEY");
            case "opencode" -> firstApiKey(generic, "OPENCODE_API_KEY");
            default -> firstApiKey(generic);
        };
        // Compose uses this non-secret bootstrap value only to satisfy
        // Spring AI Alibaba's eager DashScope auto-configuration. It must not
        // mask a real credential stored in the provider row/UI.
        return isPlaceholderApiKey(resolved) ? null : resolved;
    }

    /** Resolve an optional provider endpoint override. */
    public static String providerBaseUrl(String providerId) {
        if (!enabled() || providerId == null || providerId.isBlank()) {
            return null;
        }
        String normalized = normalize(providerId);
        String generic = "MATECLAW_PROVIDER_" + normalized + "_BASE_URL";
        return switch (providerId) {
            case "openai" -> firstEnv(generic, "OPENAI_BASE_URL");
            case "azure-openai" -> firstEnv(generic, "AZURE_OPENAI_BASE_URL", "AZURE_OPENAI_ENDPOINT");
            case "deepseek" -> firstEnv(generic, "DEEPSEEK_BASE_URL");
            case "anthropic" -> firstEnv(generic, "ANTHROPIC_BASE_URL");
            case "gemini", "google" -> firstEnv(generic, "GEMINI_BASE_URL", "GOOGLE_BASE_URL");
            case "dashscope" -> firstEnv(generic, "DASHSCOPE_BASE_URL");
            case "dashscope-compat" -> firstEnv(generic, "DASHSCOPE_COMPAT_BASE_URL", "DASHSCOPE_BASE_URL");
            case "bailian", "bailian-team" -> firstEnv(generic, "BAILIAN_BASE_URL", "ALIYUN_BAILIAN_BASE_URL");
            case "modelscope" -> firstEnv(generic, "MODELSCOPE_BASE_URL");
            case "aliyun-codingplan" -> firstEnv(generic, "ALIYUN_CODINGPLAN_BASE_URL");
            case "aliyun-codingplan-intl" -> firstEnv(generic, "ALIYUN_CODINGPLAN_INTL_BASE_URL", "ALIYUN_CODINGPLAN_BASE_URL");
            case "volcengine", "volcengine-plan", "volcengine-agent-plan" -> firstEnv(generic, "VOLCENGINE_BASE_URL");
            case "zhipu-cn", "zhipu-intl", "zhipu-cn-codingplan", "zhipu-intl-codingplan" -> firstEnv(generic, "ZHIPU_BASE_URL");
            case "kimi-cn", "kimi-intl", "kimi-code" -> firstEnv(generic, "MOONSHOT_BASE_URL");
            case "minimax" -> firstEnv(generic, "MINIMAX_BASE_URL_INTL", "MINIMAX_BASE_URL");
            case "minimax-cn" -> firstEnv(generic, "MINIMAX_BASE_URL_CN", "MINIMAX_BASE_URL");
            case "xai" -> firstEnv(generic, "XAI_BASE_URL");
            case "openrouter" -> firstEnv(generic, "OPENROUTER_BASE_URL");
            case "siliconflow-cn" -> firstEnv(generic, "SILICONFLOW_BASE_URL_CN", "SILICONFLOW_BASE_URL");
            case "siliconflow-intl" -> firstEnv(generic, "SILICONFLOW_BASE_URL_INTL", "SILICONFLOW_BASE_URL");
            case "hunyuan-3d" -> firstEnv(generic, "HUNYUAN_BASE_URL", "TENCENTCLOUD_BASE_URL");
            case "xiaomi-mimo" -> firstEnv(generic, "XIAOMI_BASE_URL", "MIMO_BASE_URL");
            case "opencode" -> firstEnv(generic, "OPENCODE_BASE_URL");
            default -> firstEnv(generic);
        };
    }

    /**
     * Resolve an optional system-setting override.  Explicit aliases cover the
     * settings used by the AI-news workflow; the generic form is an intentional
     * escape hatch for a setting that has not yet received a named alias.
     */
    public static String systemSetting(String settingKey) {
        if (!enabled() || settingKey == null || settingKey.isBlank()) {
            return null;
        }
        String alias = switch (settingKey) {
            case "serperApiKey" -> "SERPER_API_KEY";
            case "serperBaseUrl" -> "SERPER_BASE_URL";
            case "tavilyApiKey" -> "TAVILY_API_KEY";
            case "tavilyBaseUrl" -> "TAVILY_BASE_URL";
            case "searchProvider" -> "MATECLAW_SEARCH_PROVIDER";
            case "searchEnabled" -> "MATECLAW_SEARCH_ENABLED";
            case "searchFallbackEnabled" -> "MATECLAW_SEARCH_FALLBACK_ENABLED";
            case "duckduckgoEnabled" -> "MATECLAW_DUCKDUCKGO_ENABLED";
            case "searxngBaseUrl" -> "SEARXNG_BASE_URL";
            case "weixinoa.app_id" -> "WEIXINOA_APP_ID";
            case "weixinoa.app_secret" -> "WEIXINOA_APP_SECRET";
            case "zhipuApiKey" -> "ZHIPU_API_KEY";
            case "zhipuBaseUrl" -> "ZHIPU_BASE_URL";
            case "falApiKey" -> "FAL_API_KEY";
            case "klingAccessKey" -> "KLING_ACCESS_KEY";
            case "klingSecretKey" -> "KLING_SECRET_KEY";
            case "runwayApiKey" -> "RUNWAY_API_KEY";
            case "minimaxApiKey" -> "MINIMAX_API_KEY";
            case "minimaxRegion" -> "MINIMAX_REGION";
            case "dsh.api_key" -> "DEEPSEEK_API_KEY";
            default -> null;
        };
        return firstNonBlank(alias, "MATECLAW_SETTING_" + normalize(settingKey));
    }

    /**
     * Overlay credentials for a channel row.  Non-secret channel options are
     * included where they are needed to select the transport, while arbitrary
     * UI-only fields remain database-controlled.
     */
    public static void applyChannelOverrides(String channelType, Map<String, Object> config) {
        if (!enabled() || channelType == null || config == null) {
            return;
        }
        String type = channelType.toLowerCase(Locale.ROOT);
        switch (type) {
            case "feishu" -> {
                put(config, type, "app_id", "FEISHU_APP_ID");
                put(config, type, "app_secret", "FEISHU_APP_SECRET");
                put(config, type, "encrypt_key", "FEISHU_ENCRYPT_KEY");
                put(config, type, "verification_token", "FEISHU_VERIFICATION_TOKEN");
                put(config, type, "connection_mode", "FEISHU_CONNECTION_MODE");
                put(config, type, "domain", "FEISHU_DOMAIN");
            }
            case "dingtalk" -> {
                put(config, type, "client_id", "DINGTALK_CLIENT_ID", "DINGTALK_APP_KEY");
                put(config, type, "client_secret", "DINGTALK_CLIENT_SECRET", "DINGTALK_APP_SECRET");
                put(config, type, "robot_code", "DINGTALK_ROBOT_CODE");
                put(config, type, "connection_mode", "DINGTALK_CONNECTION_MODE");
                put(config, type, "message_type", "DINGTALK_MESSAGE_TYPE");
                put(config, type, "card_template_id", "DINGTALK_CARD_TEMPLATE_ID");
            }
            case "wecom" -> {
                put(config, type, "bot_id", "WECOM_BOT_ID");
                put(config, type, "secret", "WECOM_SECRET", "WECOM_BOT_SECRET");
                put(config, type, "welcome_text", "WECOM_WELCOME_TEXT");
            }
            case "qq" -> {
                put(config, type, "app_id", "QQ_BOT_APP_ID", "QQ_APP_ID");
                put(config, type, "client_secret", "QQ_BOT_CLIENT_SECRET", "QQ_CLIENT_SECRET");
                put(config, type, "markdown_enabled", "QQ_MARKDOWN_ENABLED");
                put(config, type, "max_reconnect_attempts", "QQ_MAX_RECONNECT_ATTEMPTS");
            }
            case "weixin", "wechat" -> {
                put(config, type, "bot_token", "WEIXIN_BOT_TOKEN", "WECHAT_BOT_TOKEN");
                put(config, type, "base_url", "WEIXIN_BASE_URL", "WECHAT_BASE_URL");
            }
            default -> {
                // WebChat and non-target channels remain explicitly UI-managed.
            }
        }
    }

    /**
     * Return a mutable, effective channel configuration.  Several channel
     * paths (preflight, Feishu SDK tools, and the adapter) need the same
     * environment overlay, but their JSON parsers return immutable empty maps
     * for a missing config row.  Keeping the copy here prevents one path from
     * accidentally bypassing the Docker credential source or mutating a
     * MyBatis-owned map.
     */
    public static Map<String, Object> effectiveChannelConfig(String channelType,
                                                              Map<String, Object> source) {
        Map<String, Object> effective = new LinkedHashMap<>();
        if (source != null) {
            effective.putAll(source);
        }
        applyChannelOverrides(channelType, effective);
        return effective;
    }

    private static void put(Map<String, Object> config, String channelType,
                            String configKey, String... aliases) {
        String[] names = new String[aliases.length + 1];
        names[0] = "MATECLAW_CHANNEL_" + normalize(channelType) + "_" + normalize(configKey);
        System.arraycopy(aliases, 0, names, 1, aliases.length);
        String value = firstNonBlank(names);
        if (value != null) {
            config.put(configKey, value);
        }
    }

    /** Resolve a list of environment variable names, in precedence order. */
    private static String firstEnv(String generic, String... aliases) {
        String[] names = new String[aliases.length + 1];
        names[0] = generic;
        System.arraycopy(aliases, 0, names, 1, aliases.length);
        return firstNonBlank(names);
    }

    /**
     * Resolve API-key aliases while ignoring non-secret bootstrap placeholders.
     * Compose deliberately supplies {@code configure-in-admin-ui} to satisfy
     * eager optional SDK auto-configuration; that sentinel must not hide a real
     * alias such as {@code BAILIAN_API_KEY} or {@code MODELSCOPE_API_TOKEN}.
     */
    private static String firstApiKey(String generic, String... aliases) {
        String[] names = new String[aliases.length + 1];
        names[0] = generic;
        System.arraycopy(aliases, 0, names, 1, aliases.length);
        for (String name : names) {
            String candidate = value(name);
            if (hasText(candidate) && !isPlaceholderApiKey(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** Tencent Hunyuan 3D accepts a packed {@code SecretId:SecretKey} value. */
    private static String tencentCloudCredential(String generic) {
        String packed = firstApiKey(generic, "HUNYUAN_API_KEY", "TENCENT_API_KEY");
        if (packed != null) {
            return packed;
        }
        String secretId = firstNonBlank("TENCENTCLOUD_SECRET_ID", "TENCENT_SECRET_ID");
        String secretKey = firstNonBlank("TENCENTCLOUD_SECRET_KEY", "TENCENT_SECRET_KEY");
        return secretId != null && secretKey != null ? secretId + ":" + secretKey : null;
    }

    private static String normalize(String value) {
        return value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean isPlaceholderApiKey(String value) {
        if (!hasText(value)) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "configure-in-admin-ui".equals(normalized)
                || normalized.startsWith("replace-with-")
                || normalized.startsWith("your-")
                || normalized.startsWith("change-me-");
    }
}
