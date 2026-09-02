package vip.newsclaw.news.source;

/**
 * Acquisition channel used to keep deterministic structured discovery
 * separate from open-web search. Trust is still decided by the source
 * registry, never by this transport classification.
 */
public enum NewsSourceChannel {
    PUSH,
    FEED,
    SITEMAP,
    OFFICIAL_API,
    SEARCH;

    public boolean structured() {
        return this != SEARCH;
    }
}
