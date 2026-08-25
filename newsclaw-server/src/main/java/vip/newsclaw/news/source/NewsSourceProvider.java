package vip.newsclaw.news.source;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * SPI for pluggable discovery/fetch sources. Implementations must preserve
 * provenance and must never mark a result as verified by themselves.
 */
public interface NewsSourceProvider {

    String providerId();

    List<NewsSourceResult> search(NewsSourceQuery query);

    Optional<NewsSourceResult> fetch(URI url);

    NewsSourceHealth health();
}
