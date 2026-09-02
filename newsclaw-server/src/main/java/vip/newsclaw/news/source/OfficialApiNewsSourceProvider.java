package vip.newsclaw.news.source;

/**
 * Marker contract for first-party APIs. Credentials and platform-specific
 * signing stay outside the generic source registry; an adapter can be added
 * without changing the evidence model.
 */
public interface OfficialApiNewsSourceProvider extends NewsSourceProvider {

    @Override
    default NewsSourceChannel channel() {
        return NewsSourceChannel.OFFICIAL_API;
    }

    String officialSourceKey();
}
