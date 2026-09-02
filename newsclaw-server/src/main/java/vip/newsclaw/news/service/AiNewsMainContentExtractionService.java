package vip.newsclaw.news.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/** Primary mature extractor with an explicit, auditable compatibility fallback. */
@Service
public class AiNewsMainContentExtractionService implements AiNewsMainContentExtractor {

    private static final String FALLBACK_NAME = "jsoup_document_text";
    private static final String FALLBACK_VERSION = "1";
    private static final String FALLBACK_RULES = "remove:script,style,noscript,template,svg,canvas";
    private static final String FALLBACK_CONFIG_HASH = sha256(FALLBACK_RULES);

    private final AiNewsContentExtractionProperties properties;
    private final TrafilaturaContentExtractorClient client;
    private final MeterRegistry meterRegistry;

    public AiNewsMainContentExtractionService(AiNewsContentExtractionProperties properties,
                                              TrafilaturaContentExtractorClient client,
                                              MeterRegistry meterRegistry) {
        this.properties = properties;
        this.client = client;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public AiNewsContentExtractionResult extract(String html, String sourceUrl) {
        Timer.Sample timer = Timer.start(meterRegistry);
        String outcome = "fallback_disabled";
        try {
            if (!properties.isEnabled()) {
                if (properties.isRequired()) {
                    outcome = "primary_disabled_closed";
                    throw new AiNewsContentExtractionException(
                            "主正文抽取器已禁用，但 content-extraction.required=true；拒绝以兼容性文本冒充正文");
                }
                return fallback(html, sourceUrl, "primary_disabled");
            }
            try {
                AiNewsContentExtractionResult result = client.extract(html, sourceUrl);
                outcome = "primary_success";
                return result;
            } catch (AiNewsContentExtractionException error) {
                if (properties.isRequired()) {
                    outcome = "primary_failed_closed";
                    throw error;
                }
                outcome = "fallback_primary_failed";
                return fallback(html, sourceUrl, boundedWarning(error));
            }
        } finally {
            timer.stop(Timer.builder("newsclaw.ai_news.extraction.duration")
                    .description("AI-news main-content extraction latency")
                    .tag("outcome", outcome)
                    .register(meterRegistry));
            meterRegistry.counter("newsclaw.ai_news.extraction.attempts", "outcome", outcome)
                    .increment();
        }
    }

    private static AiNewsContentExtractionResult fallback(String html, String sourceUrl,
                                                           String warning) {
        Document document = Jsoup.parse(html == null ? "" : html,
                sourceUrl == null ? "" : sourceUrl);
        document.select("script,style,noscript,template,svg,canvas").remove();
        return new AiNewsContentExtractionResult(document.text(), null,
                FALLBACK_NAME, FALLBACK_VERSION, FALLBACK_CONFIG_HASH, true, warning);
    }

    private static String boundedWarning(Exception error) {
        String value = error.getMessage();
        if (value == null || value.isBlank()) value = error.getClass().getSimpleName();
        value = value.toLowerCase(Locale.ROOT).replaceAll("https?://\\S+", "[endpoint]");
        return value.substring(0, Math.min(value.length(), 256));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
