package vip.newsclaw.news.source;

import java.util.Locale;
import java.util.Set;

/**
 * Closed vocabulary for structured endpoints that may attest publication time.
 *
 * <p>The endpoint's boolean flag is necessary but not sufficient. An operator
 * must also record an explicitly reviewed rights and robots outcome. This
 * prevents a database toggle or catalog typo from turning a discovery-only
 * feed into evidence.</p>
 */
public final class AiNewsSourceGovernancePolicy {

    private static final Set<String> ALLOWED_RIGHTS = Set.of(
            "approved", "licensed", "publisher_authorized", "public_metadata");
    private static final Set<String> ALLOWED_ROBOTS = Set.of(
            "allowed", "not_applicable", "publisher_authorized");

    private AiNewsSourceGovernancePolicy() {
    }

    public static boolean evidenceEligible(boolean declaredEligible,
                                           String rightsStatus,
                                           String robotsStatus) {
        return declaredEligible
                && ALLOWED_RIGHTS.contains(token(rightsStatus))
                && ALLOWED_ROBOTS.contains(token(robotsStatus));
    }

    public static Set<String> allowedRightsStatuses() {
        return ALLOWED_RIGHTS;
    }

    public static Set<String> allowedRobotsStatuses() {
        return ALLOWED_ROBOTS;
    }

    private static String token(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
