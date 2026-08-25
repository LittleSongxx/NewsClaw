package vip.newsclaw.news.model;

/**
 * Runtime responsibilities in the AI-news production loop.
 *
 * <p>The role is deliberately a NewsClaw domain concept rather than a
 * provider concept.  A deployment can map each role to a different model,
 * while the workflow and Team Run contracts remain stable.</p>
 */
public enum AiNewsModelRole {
    DISCOVERY("discovery"),
    VERIFICATION("verification"),
    EDITORIAL("editorial"),
    VISUAL("visual"),
    DELIVERY("delivery");

    private final String token;

    AiNewsModelRole(String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }
}
