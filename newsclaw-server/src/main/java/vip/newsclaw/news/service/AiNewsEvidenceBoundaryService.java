package vip.newsclaw.news.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vip.newsclaw.news.model.AiNewsEventDetail;
import vip.newsclaw.news.model.AiNewsEventEntity;
import vip.newsclaw.news.model.AiNewsEvidenceEntity;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enforces the provenance boundary for content produced from an AI-news event.
 *
 * <p>Event evidence is the source of truth.  This guard intentionally checks
 * deterministic, inspectable text at the two final boundaries: article/note
 * packaging and HTML-to-card rendering.  It is not an attempt to infer claims
 * from a raster image; generated binary imagery remains a review concern.</p>
 */
@Service
@RequiredArgsConstructor
public class AiNewsEvidenceBoundaryService {

    private static final Pattern HTTP_URL = Pattern.compile("https?://[^\\s<>()\\[\\]{}\\\"']+", Pattern.CASE_INSENSITIVE);
    private static final Pattern OFFICIAL_X = Pattern.compile("(?:官方\\s*)?(?:X|Twitter)\\s*(?:账号|帳號|帐户|帳戶|account)", Pattern.CASE_INSENSITIVE);
    private static final Pattern OFFICIAL_WEIBO = Pattern.compile("官方\\s*微博", Pattern.CASE_INSENSITIVE);
    private static final Pattern OFFICIAL_WECHAT = Pattern.compile("官方\\s*(?:微信|公众号)", Pattern.CASE_INSENSITIVE);

    private final AiNewsEventService eventService;

    /**
     * Validate content bound to an {@code ai-news-event-*} execution.  It must
     * only cite URLs and named official social channels that exist in the
     * event's archived evidence packet.
     */
    public ValidationResult validate(Long workspaceId, Long eventId, String content) {
        AiNewsEventDetail detail = eventService.get(workspaceId, eventId);
        AiNewsEventEntity event = detail.event();
        List<AiNewsEvidenceEntity> evidence = detail.evidence() == null ? List.of() : detail.evidence();
        List<AiNewsEvidenceEntity> verifiedEvidence = evidence.stream()
                .filter(item -> Boolean.TRUE.equals(item.getVerified()))
                .toList();
        List<String> violations = new ArrayList<>();

        if (!"in_production".equals(event.getStatus())) {
            violations.add("事件尚未进入内容生产状态");
        }
        if (verifiedEvidence.isEmpty()) {
            violations.add("事件没有已核验的归档证据");
        }

        Set<String> allowedUrls = new LinkedHashSet<>();
        Set<String> allowedDomains = new LinkedHashSet<>();
        List<String> sourceLabels = new ArrayList<>();
        for (AiNewsEvidenceEntity item : verifiedEvidence) {
            addUrl(item.getSourceUrl(), allowedUrls, allowedDomains);
            addUrl(item.getFinalUrl(), allowedUrls, allowedDomains);
            String label = sourceLabel(item);
            if (!sourceLabels.contains(label)) {
                sourceLabels.add(label);
            }
        }

        String text = content == null ? "" : content;
        Matcher urls = HTTP_URL.matcher(text);
        Set<String> unexpectedUrls = new LinkedHashSet<>();
        while (urls.find()) {
            if (isVisualAssetUrl(text, urls.start())) {
                continue;
            }
            String cited = trimTrailingUrlPunctuation(urls.group());
            String canonical = AiNewsEventService.canonicalUrl(cited);
            if (!canonical.isBlank() && !allowedUrls.contains(canonical)) {
                unexpectedUrls.add(cited);
            }
        }
        if (!unexpectedUrls.isEmpty()) {
            violations.add("引用了未归档来源 URL：" + String.join("、", unexpectedUrls));
        }

        requireDomainWhenMentioned(text, OFFICIAL_X, Set.of("x.com", "twitter.com"), allowedDomains,
                "提到官方 X/Twitter 账号，但事件证据未归档对应来源", violations);
        requireDomainWhenMentioned(text, OFFICIAL_WEIBO, Set.of("weibo.com"), allowedDomains,
                "提到官方微博，但事件证据未归档对应来源", violations);
        requireDomainWhenMentioned(text, OFFICIAL_WECHAT, Set.of("mp.weixin.qq.com", "weixin.qq.com"), allowedDomains,
                "提到官方微信/公众号，但事件证据未归档对应来源", violations);

        return new ValidationResult(violations.isEmpty(), List.copyOf(violations), List.copyOf(sourceLabels));
    }

    private static void requireDomainWhenMentioned(String text, Pattern marker, Collection<String> requiredDomains,
                                                    Set<String> allowedDomains, String violation,
                                                    List<String> violations) {
        if (!marker.matcher(text).find()) return;
        boolean allowed = requiredDomains.stream().anyMatch(required -> allowedDomains.stream()
                .anyMatch(domain -> domain.equals(required) || domain.endsWith("." + required)));
        if (!allowed) {
            violations.add(violation);
        }
    }

    private static void addUrl(String value, Set<String> urls, Set<String> domains) {
        if (value == null || value.isBlank()) return;
        String canonical = AiNewsEventService.canonicalUrl(value);
        if (!canonical.isBlank()) {
            urls.add(canonical);
        }
        try {
            String host = new URI(canonical).getHost();
            if (host != null && !host.isBlank()) {
                domains.add(host.toLowerCase(Locale.ROOT));
            }
        } catch (Exception ignored) {
            // URL validity was already checked on evidence insertion.
        }
    }

    private static String sourceLabel(AiNewsEvidenceEntity item) {
        String title = item.getSourceTitle() == null ? "" : item.getSourceTitle().trim();
        String host = host(item.getFinalUrl());
        if (host.isBlank()) host = host(item.getSourceUrl());
        return title.isBlank() ? host : host.isBlank() ? title : title + " (" + host + ")";
    }

    private static String host(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            String host = new URI(value).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String trimTrailingUrlPunctuation(String url) {
        int end = url.length();
        while (end > 0 && ".,;:!?，。；：！？）)]}".indexOf(url.charAt(end - 1)) >= 0) {
            end--;
        }
        return url.substring(0, end);
    }

    /**
     * Image/background URLs are delivery assets, not factual citations.  The
     * text checker must not reject a generated cover simply because its CDN is
     * not an event-evidence domain.  Citation URLs in anchors/plain Markdown
     * remain checked above.
     */
    private static boolean isVisualAssetUrl(String text, int urlStart) {
        int from = Math.max(0, urlStart - 512);
        String prefix = text.substring(from, urlStart).toLowerCase(Locale.ROOT);
        int lastTagStart = prefix.lastIndexOf("<img");
        if (lastTagStart >= 0 && lastTagStart > prefix.lastIndexOf(">")
                && prefix.substring(lastTagStart).contains("src")) {
            return true;
        }
        if (prefix.lastIndexOf("url(") > prefix.lastIndexOf(")")) {
            return true;
        }
        int markdownOpen = prefix.lastIndexOf("](");
        if (markdownOpen >= 0 && markdownOpen > prefix.lastIndexOf('\n')) {
            return prefix.lastIndexOf("![", markdownOpen) >= 0;
        }
        return false;
    }

    /** A small, user-facing explanation suitable for tool output and task prompts. */
    public record ValidationResult(boolean allowed, List<String> violations, List<String> sourceLabels) {
        public String sourceSummary() {
            return sourceLabels.isEmpty() ? "无" : String.join("；", sourceLabels);
        }

        public String violationSummary() {
            return violations.isEmpty() ? "" : String.join("；", violations);
        }
    }
}
