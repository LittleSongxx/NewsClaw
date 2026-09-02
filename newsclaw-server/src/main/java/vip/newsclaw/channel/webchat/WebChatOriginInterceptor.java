package vip.newsclaw.channel.webchat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import vip.newsclaw.channel.model.ChannelEntity;
import vip.newsclaw.channel.service.ChannelService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;

/** Enforces the per-channel browser Origin allow-list for public WebChat calls. */
@Component
@RequiredArgsConstructor
final class WebChatOriginInterceptor implements HandlerInterceptor, WebMvcConfigurer {
    private static final ObjectMapper CONFIG_MAPPER = new ObjectMapper();
    private final ChannelService channelService;
    private final ObjectMapper objectMapper;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/v1/channels/webchat/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // CORS preflight has no API key yet; the actual request is checked below.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) return true;
        String origin = request.getHeader("Origin");
        // Non-browser clients do not send Origin and remain source-compatible.
        if (origin == null || origin.isBlank()) return true;
        String apiKey = request.getHeader("X-MC-Key");
        ChannelEntity channel = findChannel(apiKey);
        if (channel == null || !originAllowed(origin, channel.getConfigJson())) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Origin is not allowed for this WebChat channel");
            return false;
        }
        return true;
    }

    private ChannelEntity findChannel(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return null;
        for (ChannelEntity channel : channelService.listChannelsByType("webchat")) {
            if (!Boolean.TRUE.equals(channel.getEnabled())) continue;
            JsonNode config = parse(channel.getConfigJson());
            if (apiKey.equals(text(config, "api_key"))) return channel;
        }
        return null;
    }

    static boolean originAllowed(String origin, String configJson) {
        if (origin == null || origin.isBlank()) return false;
        List<String> allowed = parseAllowedOrigins(configJson);
        if (allowed.isEmpty()) return false;
        String normalized = normalize(origin);
        return allowed.stream().anyMatch(value -> "*".equals(value) || normalize(value).equals(normalized));
    }

    private static List<String> parseAllowedOrigins(String configJson) {
        if (configJson == null || configJson.isBlank()) return List.of();
        try {
            JsonNode root = CONFIG_MAPPER.readTree(configJson);
            JsonNode value = root.get("allowed_origins");
            if (value == null || value.isNull()) return List.of();
            List<String> out = new ArrayList<>();
            if (value.isArray()) value.forEach(n -> { if (n.isTextual()) out.add(n.asText()); });
            else if (value.isTextual()) {
                for (String item : value.asText().split(",")) if (!item.isBlank()) out.add(item.trim());
            }
            return out;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private JsonNode parse(String json) {
        try { return json == null ? objectMapper.createObjectNode() : objectMapper.readTree(json); }
        catch (Exception ignored) { return objectMapper.createObjectNode(); }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static String normalize(String origin) {
        String value = origin.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }
}
