package vip.newsclaw.news.model;

/** Trust tier used by the verification policy. */
public enum AiNewsSourceTier {
    OFFICIAL("official"),
    MEDIA("media"),
    COMMUNITY("community");

    private final String token;

    AiNewsSourceTier(String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }

    public static AiNewsSourceTier from(String value) {
        if (value == null || value.isBlank()) return MEDIA;
        for (AiNewsSourceTier tier : values()) {
            if (tier.token.equalsIgnoreCase(value.trim())) return tier;
        }
        throw new IllegalArgumentException("unknown source tier: " + value);
    }
}
