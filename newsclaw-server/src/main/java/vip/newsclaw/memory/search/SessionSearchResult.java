package vip.newsclaw.memory.search;

import java.time.LocalDateTime;

/**
 * Session search result — a matched message from conversation history.
 *
 * @author NewsClaw Team
 */
public record SessionSearchResult(
        String conversationId,
        String title,
        String snippet,
        String role,
        LocalDateTime time,
        double relevance
) {
}
