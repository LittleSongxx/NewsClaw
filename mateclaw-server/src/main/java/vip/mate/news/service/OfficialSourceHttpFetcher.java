package vip.mate.news.service;

import java.time.LocalDateTime;
import java.util.List;

/** Narrow transport contract: read-only GET capture with an inspectable redirect trail. */
public interface OfficialSourceHttpFetcher {

    FetchResult fetch(String sourceUrl, int maxBytes, int timeoutSeconds, int maxRedirects) throws Exception;

    record FetchResult(String finalUrl, int httpStatus, String body, String contentType,
                       LocalDateTime fetchedAt, List<String> redirectChain) {
    }
}
