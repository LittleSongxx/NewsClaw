package vip.newsclaw.news.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Bounds for the narrow, read-only official-source capture client. */
@Data
@Component
@ConfigurationProperties(prefix = "newsclaw.ai-news.official-capture")
public class AiNewsOfficialCaptureProperties {

    /** Disable the endpoint entirely when the deployment does not allow outbound capture. */
    private boolean enabled = true;
    /** Bounded body size; page rendering, media download and archive crawling are deliberately excluded. */
    private int maxBytes = 1_048_576;
    /** Minimum Unicode code points required before an extraction can become an evidence snapshot. */
    private int minTextChars = 200;
    /**
     * Compatibility extraction is useful for local diagnostics, but it is not
     * equivalent to a main-content extractor for publishable evidence.
     */
    private boolean allowExtractionFallback = false;
    /** Per request timeout, including each redirect hop. */
    private int timeoutSeconds = 15;
    /** Redirects are manually followed so every hop receives an SSRF check. */
    private int maxRedirects = 5;
    /** Total attempts for transient timeouts/I/O errors and 408/425/429/5xx responses. */
    private int maxAttempts = 2;
    /** Initial full-jitter backoff bound when the server does not provide Retry-After. */
    private int retryBaseDelayMillis = 250;
    /** Synchronous retry budget; a longer Retry-After is not retried early. */
    private int retryMaxDelayMillis = 5_000;
    /** Reuse a successful immutable snapshot only while it is this fresh; zero disables reuse. */
    private int reuseTtlMinutes = 360;
    /** Per-workspace outbound admission bound; zero disables the rate limit. */
    private int maxCapturesPerMinute = 30;
    /** Per-workspace concurrent outbound captures. */
    private int maxConcurrentCaptures = 2;
    /** Optional deployment-owned HTTP CONNECT proxy; blank keeps direct egress. */
    private String proxyUrl;
}
