package vip.newsclaw.news.service;

import java.time.LocalDateTime;
import java.util.List;

/** Narrow transport contract: read-only GET capture with an inspectable redirect trail. */
public interface OfficialSourceHttpFetcher {

    FetchResult fetch(String sourceUrl, int maxBytes, int timeoutSeconds, int maxRedirects) throws Exception;

    record FetchResult(String finalUrl, int httpStatus, String body, String contentType,
                       LocalDateTime fetchedAt, List<String> redirectChain,
                       boolean bodyComplete, String retryAfter, String transportRoute) {

        /** Compatibility constructor for callers that do not provide an egress route. */
        public FetchResult(String finalUrl, int httpStatus, String body, String contentType,
                           LocalDateTime fetchedAt, List<String> redirectChain,
                           boolean bodyComplete, String retryAfter) {
            this(finalUrl, httpStatus, body, contentType, fetchedAt, redirectChain,
                    bodyComplete, retryAfter, "direct");
        }

        /** Compatibility constructor for callers with Retry-After and a complete body. */
        public FetchResult(String finalUrl, int httpStatus, String body, String contentType,
                           LocalDateTime fetchedAt, List<String> redirectChain, String retryAfter) {
            this(finalUrl, httpStatus, body, contentType, fetchedAt, redirectChain,
                    true, retryAfter, "direct");
        }

        /** Compatibility constructor for deterministic/test fetchers without response headers. */
        public FetchResult(String finalUrl, int httpStatus, String body, String contentType,
                           LocalDateTime fetchedAt, List<String> redirectChain) {
            this(finalUrl, httpStatus, body, contentType, fetchedAt, redirectChain,
                    true, null, "direct");
        }
    }
}
