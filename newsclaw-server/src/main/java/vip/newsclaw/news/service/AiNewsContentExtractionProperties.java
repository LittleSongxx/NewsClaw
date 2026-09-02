package vip.newsclaw.news.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Deployment contract for the fetch-free main-content extractor. */
@Data
@Component
@ConfigurationProperties(prefix = "newsclaw.ai-news.content-extraction")
public class AiNewsContentExtractionProperties {

    /** Use the mature extractor instead of the compatibility-only Jsoup fallback. */
    private boolean enabled = false;
    /** Fail evidence capture when the enabled primary extractor is unavailable or rejects a page. */
    private boolean required = true;
    /** Operator-owned internal endpoint; the extractor receives HTML and never fetches this URL. */
    private String endpoint = "http://localhost:8090";
    /** Exact implementation admitted into immutable evidence provenance. */
    private String expectedName = "trafilatura";
    /** Upgrades require an explicit configuration change and evaluation rerun. */
    private String expectedVersion = "2.2.0";
    /** SHA-256 of the behavior-affecting adapter options. */
    private String expectedConfigHash =
            "0235b7bf49c3c80ea6a52aee9f413fa2d4e4e1f5196af87278c48e558c7d0400";
    /** Whole extraction request timeout. */
    private int timeoutMillis = 5_000;
    /** Independent input bound even though the upstream fetch is already bounded. */
    private int maxRequestBytes = 1_048_576;
    /** Bound on the extractor JSON response. */
    private int maxResponseBytes = 1_572_864;
}
