package vip.mate.news.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Bounds for the narrow, read-only official-source capture client. */
@Data
@Component
@ConfigurationProperties(prefix = "mateclaw.ai-news.official-capture")
public class AiNewsOfficialCaptureProperties {

    /** Disable the endpoint entirely when the deployment does not allow outbound capture. */
    private boolean enabled = true;
    /** Bounded body size; page rendering, media download and archive crawling are deliberately excluded. */
    private int maxBytes = 524_288;
    /** Per request timeout, including each redirect hop. */
    private int timeoutSeconds = 15;
    /** Redirects are manually followed so every hop receives an SSRF check. */
    private int maxRedirects = 5;
}
