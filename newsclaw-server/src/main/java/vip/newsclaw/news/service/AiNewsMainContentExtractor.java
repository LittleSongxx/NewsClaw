package vip.newsclaw.news.service;

/** Extracts main content from an already-fetched HTML representation. */
public interface AiNewsMainContentExtractor {

    AiNewsContentExtractionResult extract(String html, String sourceUrl);
}
