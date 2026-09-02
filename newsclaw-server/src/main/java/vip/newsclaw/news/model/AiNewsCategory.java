package vip.newsclaw.news.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Canonical vertical taxonomy shared by Agent, REST and evaluation paths. */
public final class AiNewsCategory {

    public static final List<String> VALUES = List.of(
            "model", "product", "open_source", "security", "infrastructure",
            "partnership", "funding", "robotics", "industry", "policy");

    private static final Map<String, String> ALIASES = aliases();

    private AiNewsCategory() {
    }

    public static String normalize(String value) {
        if (value == null || value.isBlank()) return "industry";
        String token = value.trim().toLowerCase(Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        String canonical = ALIASES.getOrDefault(token, token);
        if (!VALUES.contains(canonical)) {
            throw new IllegalArgumentException("category must be one of " + String.join(",", VALUES));
        }
        return canonical;
    }

    private static Map<String, String> aliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("models", "model");
        aliases.put("research", "model");
        aliases.put("release", "product");
        aliases.put("opensource", "open_source");
        aliases.put("open_source_ecosystem", "open_source");
        aliases.put("safety", "security");
        aliases.put("cybersecurity", "security");
        aliases.put("vulnerability", "security");
        aliases.put("chip", "infrastructure");
        aliases.put("chips", "infrastructure");
        aliases.put("hardware", "infrastructure");
        aliases.put("cloud", "infrastructure");
        aliases.put("cooperation", "partnership");
        aliases.put("collaboration", "partnership");
        aliases.put("deal", "partnership");
        aliases.put("finance", "funding");
        aliases.put("financing", "funding");
        aliases.put("investment", "funding");
        aliases.put("venture", "funding");
        aliases.put("robot", "robotics");
        aliases.put("regulation", "policy");
        return Map.copyOf(aliases);
    }
}
