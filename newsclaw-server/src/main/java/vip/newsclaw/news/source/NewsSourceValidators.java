package vip.newsclaw.news.source;

/** Persistable HTTP validators supplied to one endpoint poll. */
public record NewsSourceValidators(String etag, String lastModified) {

    public static final NewsSourceValidators EMPTY = new NewsSourceValidators("", "");

    public NewsSourceValidators {
        etag = etag == null ? "" : etag.trim();
        lastModified = lastModified == null ? "" : lastModified.trim();
    }

    public boolean empty() {
        return etag.isBlank() && lastModified.isBlank();
    }
}
