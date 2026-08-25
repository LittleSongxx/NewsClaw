package vip.newsclaw.news.model;

/** Immutable model-routing snapshot attached to a NewsClaw task. */
public record AiNewsModelRoute(
        AiNewsModelRole role,
        String provider,
        String modelName,
        Long modelId,
        boolean configured,
        boolean fallback,
        String reason
) {
    public boolean available() {
        return provider != null && !provider.isBlank()
                && modelName != null && !modelName.isBlank();
    }
}
