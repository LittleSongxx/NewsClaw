package vip.newsclaw.news.model;

/** Request to create a read-only source snapshot before evidence insertion. */
public record AiNewsSourceCaptureRequest(String sourceUrl) {
}
