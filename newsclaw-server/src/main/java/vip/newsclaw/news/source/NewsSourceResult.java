package vip.newsclaw.news.source;

/** One normalized result returned by a news source provider. */
public record NewsSourceResult(
        String title,
        String snippet,
        String content,
        NewsSourceProvenance provenance
) {
    public NewsSourceResult {
        title = title == null ? "" : title.trim();
        snippet = snippet == null ? "" : snippet.trim();
        content = content == null ? "" : content;
        if (provenance == null) {
            throw new IllegalArgumentException("provenance is required");
        }
    }

    public String sourceUrl() {
        return provenance.sourceUrl();
    }

    public String canonicalUrl() {
        return provenance.canonicalUrl();
    }
}
