package vip.newsclaw.news.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

/** Bounded client for the internal, fetch-free Trafilatura adapter. */
@Component
public class TrafilaturaContentExtractorClient {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private final AiNewsContentExtractionProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public TrafilaturaContentExtractorClient(AiNewsContentExtractionProperties properties,
                                             ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(timeout(properties)))
                .build());
    }

    TrafilaturaContentExtractorClient(AiNewsContentExtractionProperties properties,
                                      ObjectMapper objectMapper,
                                      HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    public AiNewsContentExtractionResult extract(String html, String sourceUrl) {
        byte[] htmlBytes = (html == null ? "" : html).getBytes(StandardCharsets.UTF_8);
        int maximum = Math.max(1, Math.min(properties.getMaxRequestBytes(), 8 * 1024 * 1024));
        if (htmlBytes.length == 0 || htmlBytes.length > maximum) {
            throw new AiNewsContentExtractionException("抽取输入为空或超过部署上限");
        }
        try {
            URI endpoint = endpoint(properties.getEndpoint());
            byte[] payload = objectMapper.writeValueAsBytes(Map.of(
                    "html", html,
                    "url", sourceUrl == null ? "" : sourceUrl));
            HttpRequest request = HttpRequest.newBuilder(endpoint.resolve("/v1/extract"))
                    .timeout(Duration.ofMillis(timeout(properties)))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            byte[] responseBody;
            try (InputStream input = response.body()) {
                int responseLimit = Math.max(1,
                        Math.min(properties.getMaxResponseBytes(), 12 * 1024 * 1024));
                responseBody = input.readNBytes(responseLimit + 1);
                if (responseBody.length > responseLimit) {
                    throw new AiNewsContentExtractionException("正文抽取响应超过部署上限");
                }
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AiNewsContentExtractionException(
                        "正文抽取服务返回 HTTP " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(responseBody);
            String text = requiredText(root, "text");
            String name = requiredText(root, "extractorName");
            String version = requiredText(root, "extractorVersion");
            String configHash = requiredText(root, "configHash").toLowerCase(java.util.Locale.ROOT);
            String expectedName = configured(properties.getExpectedName(), "expected name");
            String expectedVersion = configured(properties.getExpectedVersion(), "expected version");
            String expectedConfigHash = configuredHash(properties.getExpectedConfigHash());
            if (!expectedName.equals(name) || !expectedVersion.equals(version)
                    || !expectedConfigHash.equals(configHash)) {
                throw new AiNewsContentExtractionException("正文抽取服务返回了未知实现或配置摘要");
            }
            return new AiNewsContentExtractionResult(text, optionalText(root, "title"),
                    name, version, configHash, false, null);
        } catch (AiNewsContentExtractionException error) {
            throw error;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AiNewsContentExtractionException("正文抽取请求被中断", error);
        } catch (Exception error) {
            throw new AiNewsContentExtractionException("正文抽取服务不可用", error);
        }
    }

    private static URI endpoint(String raw) {
        try {
            URI value = URI.create(raw == null ? "" : raw.trim());
            if (!value.isAbsolute() || value.getHost() == null || value.getUserInfo() != null
                    || !("http".equalsIgnoreCase(value.getScheme())
                    || "https".equalsIgnoreCase(value.getScheme()))) {
                throw new IllegalArgumentException("invalid endpoint");
            }
            String normalized = value.toString();
            return URI.create(normalized.endsWith("/")
                    ? normalized.substring(0, normalized.length() - 1) : normalized);
        } catch (Exception error) {
            throw new AiNewsContentExtractionException("正文抽取服务地址无效", error);
        }
    }

    private static long timeout(AiNewsContentExtractionProperties properties) {
        return Math.max(250L, Math.min(properties.getTimeoutMillis(), 30_000));
    }

    private static String configured(String raw, String field) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new AiNewsContentExtractionException("正文抽取配置缺少 " + field);
        }
        return value;
    }

    private static String configuredHash(String raw) {
        String value = configured(raw, "expected config hash")
                .toLowerCase(java.util.Locale.ROOT);
        if (!SHA_256.matcher(value).matches()) {
            throw new AiNewsContentExtractionException("正文抽取预期配置摘要无效");
        }
        return value;
    }

    private static String requiredText(JsonNode root, String field) {
        String value = optionalText(root, field);
        if (value == null) {
            throw new AiNewsContentExtractionException("正文抽取响应缺少字段: " + field);
        }
        return value;
    }

    private static String optionalText(JsonNode root, String field) {
        if (root == null || !root.isObject()) return null;
        JsonNode value = root.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) return null;
        return value.asText().trim();
    }
}
