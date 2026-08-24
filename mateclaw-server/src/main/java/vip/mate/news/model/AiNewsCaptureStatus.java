package vip.mate.news.model;

/** Outcome of one read-only attempt to capture an official source page. */
public enum AiNewsCaptureStatus {
    SUCCESS("success"),
    BLOCKED("blocked"),
    NOT_FOUND("not_found"),
    TIMEOUT("timeout"),
    EMPTY_CONTENT("empty_content"),
    REDIRECT_REJECTED("redirect_rejected"),
    NETWORK_ERROR("network_error");

    private final String token;

    AiNewsCaptureStatus(String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }
}
