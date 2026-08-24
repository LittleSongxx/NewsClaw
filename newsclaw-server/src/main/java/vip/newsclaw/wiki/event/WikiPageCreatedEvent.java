package vip.newsclaw.wiki.event;

/**
 * Published after a wiki page is created during ingest (already committed,
 * since page creation is its own transaction). Consumed asynchronously to
 * evaluate count-threshold pipeline triggers without blocking ingest.
 *
 * @author NewsClaw Team
 */
public record WikiPageCreatedEvent(Long kbId, String pageType, Long pageId) {
}
