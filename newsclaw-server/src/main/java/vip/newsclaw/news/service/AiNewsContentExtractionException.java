package vip.newsclaw.news.service;

/** An enabled, required extraction stage failed closed. */
public class AiNewsContentExtractionException extends RuntimeException {

    public AiNewsContentExtractionException(String message) {
        super(message);
    }

    public AiNewsContentExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
