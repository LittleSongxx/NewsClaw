package vip.mate.news.model;

/** User or Agent request to capture one official source as an evidence packet. */
public record AiNewsEvidenceCaptureRequest(String sourceUrl, String claim) {
}
