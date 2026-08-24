package vip.newsclaw.wiki.job;

/**
 * RFC-030: Thrown when no model is available for a wiki processing step.
 */
public class WikiModelUnavailableException extends RuntimeException {
    public WikiModelUnavailableException(String message) {
        super(message);
    }
}
