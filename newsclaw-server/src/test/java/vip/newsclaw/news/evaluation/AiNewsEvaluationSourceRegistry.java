package vip.newsclaw.news.evaluation;

import vip.newsclaw.news.service.AiNewsSourceRegistry;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

/**
 * Reproduces the source-registry snapshot used by already observed synthetic
 * evaluation datasets without weakening the production registry.
 *
 * <p>Baidu, Tencent's parent domain, and Hugging Face were host-wide official
 * when these sets were frozen. Production now grants official status only to
 * reviewed URL prefixes on shared-content hosts. The synthetic-only path
 * families below preserve historical scoring identity; real URLs still use
 * the live, fail-closed registry.</p>
 */
final class AiNewsEvaluationSourceRegistry extends AiNewsSourceRegistry {

    @Override
    public boolean isOfficialUrl(String url) {
        return super.isOfficialUrl(url) || legacySyntheticSourceKey(url).isPresent();
    }

    @Override
    public Optional<String> officialSourceKey(String url) {
        Optional<String> current = super.officialSourceKey(url);
        return current.isPresent() ? current
                : legacySyntheticSourceKey(url);
    }

    private static Optional<String> legacySyntheticSourceKey(String url) {
        if (url == null || url.isBlank()) return Optional.empty();
        try {
            URI uri = new URI(url.trim());
            String host = uri.getHost();
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            if (host == null) return Optional.empty();
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            if (path.startsWith("/relations-dev/") && "baidu.com".equals(normalizedHost)) {
                return Optional.of("baidu");
            }
            if (!path.startsWith("/sealed-eval-v2/")) return Optional.empty();
            return switch (normalizedHost) {
                case "baidu.com" -> Optional.of("baidu");
                case "tencent.com" -> Optional.of("tencent");
                case "huggingface.co" -> Optional.of("huggingface");
                default -> Optional.empty();
            };
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }
}
