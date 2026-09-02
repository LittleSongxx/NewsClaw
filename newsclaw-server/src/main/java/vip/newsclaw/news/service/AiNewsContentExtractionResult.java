package vip.newsclaw.news.service;

/** Main text plus the exact implementation/configuration that produced it. */
public record AiNewsContentExtractionResult(String text,
                                            String title,
                                            String extractorName,
                                            String extractorVersion,
                                            String extractorConfigHash,
                                            boolean fallback,
                                            String warning) {
}
