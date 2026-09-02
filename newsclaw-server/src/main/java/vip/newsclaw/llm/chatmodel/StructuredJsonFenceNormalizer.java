package vip.newsclaw.llm.chatmodel;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognizes only a single, otherwise-unadorned Markdown fence containing one
 * strict JSON object. It is intentionally not a general "extract JSON from
 * prose" helper: prefixes, suffixes, arrays, duplicate keys, trailing tokens,
 * and multiple fences remain invalid.
 */
public final class StructuredJsonFenceNormalizer {

    private static final Pattern SINGLE_FENCE = Pattern.compile(
            "\\A```(?:json)?[ \\t]*\\R([\\s\\S]*?)\\R```\\z",
            Pattern.CASE_INSENSITIVE);
    private static final ObjectMapper STRICT_JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private StructuredJsonFenceNormalizer() {
    }

    public static Optional<String> normalizeSingleJsonObjectFence(String content) {
        if (content == null || content.isBlank()) return Optional.empty();
        Matcher matcher = SINGLE_FENCE.matcher(content.trim());
        if (!matcher.matches()) return Optional.empty();
        String candidate = matcher.group(1).trim();
        try {
            JsonNode root = STRICT_JSON.readTree(candidate);
            if (root == null || !root.isObject()) return Optional.empty();
            return Optional.of(candidate);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }
}
