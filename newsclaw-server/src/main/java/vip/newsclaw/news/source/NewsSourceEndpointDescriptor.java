package vip.newsclaw.news.source;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/** Versioned operator configuration for one independently scheduled source endpoint. */
public record NewsSourceEndpointDescriptor(
        String endpointKey,
        int catalogVersion,
        String sourceKey,
        String providerId,
        NewsSourceChannel channel,
        String adapter,
        URI url,
        List<String> languages,
        List<String> categories,
        int pollIntervalSeconds,
        boolean evidenceEligible,
        String rightsStatus,
        String rawRetention,
        String robotsStatus
) {

    private static final List<String> RAW_RETENTION_VALUES =
            List.of("none", "metadata_only", "digest_only", "full");

    public NewsSourceEndpointDescriptor {
        endpointKey = required(endpointKey, "endpointKey");
        sourceKey = required(sourceKey, "sourceKey");
        providerId = required(providerId, "providerId");
        channel = java.util.Objects.requireNonNull(channel, "channel");
        adapter = required(adapter, "adapter").toUpperCase(Locale.ROOT);
        if (url == null || url.getHost() == null
                || !("http".equalsIgnoreCase(url.getScheme())
                || "https".equalsIgnoreCase(url.getScheme()))) {
            throw new IllegalArgumentException("endpoint url must be absolute HTTP(S)");
        }
        languages = languages == null ? List.of() : List.copyOf(languages);
        categories = categories == null ? List.of() : List.copyOf(categories);
        if (pollIntervalSeconds < 60 || pollIntervalSeconds > 86_400) {
            throw new IllegalArgumentException("pollIntervalSeconds must be within [60,86400]");
        }
        rightsStatus = required(rightsStatus, "rightsStatus");
        rawRetention = required(rawRetention, "rawRetention").toLowerCase(Locale.ROOT);
        if (!RAW_RETENTION_VALUES.contains(rawRetention)) {
            throw new IllegalArgumentException("unsupported rawRetention: " + rawRetention);
        }
        if ("full".equals(rawRetention) && !"approved".equalsIgnoreCase(rightsStatus)) {
            throw new IllegalArgumentException("full raw retention requires rightsStatus=approved");
        }
        robotsStatus = required(robotsStatus, "robotsStatus");
        if (evidenceEligible && !AiNewsSourceGovernancePolicy.evidenceEligible(
                true, rightsStatus, robotsStatus)) {
            throw new IllegalArgumentException("evidence-eligible endpoint requires reviewed "
                    + "rights and robots allowlist statuses");
        }
    }

    public String configFingerprint() {
        return NewsSourceHashing.sha256(String.join("\u001f",
                endpointKey, Integer.toString(catalogVersion), sourceKey, providerId,
                channel.name(), adapter, url.normalize().toString(),
                String.join(",", languages), String.join(",", categories),
                Integer.toString(pollIntervalSeconds), Boolean.toString(evidenceEligible),
                rightsStatus, rawRetention, robotsStatus));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
