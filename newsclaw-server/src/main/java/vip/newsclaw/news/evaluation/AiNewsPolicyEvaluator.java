package vip.newsclaw.news.evaluation;

import vip.newsclaw.news.service.AiNewsEventService;
import vip.newsclaw.news.service.AiNewsSourceRegistry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure, deterministic evaluator for the news evidence boundary. It measures
 * policy fixtures only; it intentionally makes no claim about live web or
 * LLM accuracy.
 */
public final class AiNewsPolicyEvaluator {

    private final AiNewsSourceRegistry sourceRegistry;

    public AiNewsPolicyEvaluator(AiNewsSourceRegistry sourceRegistry) {
        this.sourceRegistry = sourceRegistry;
    }

    public EvaluationReport evaluate(List<EvidenceCase> cases, String gitCommit, String testCommand) {
        List<EvidenceCase> input = cases == null ? List.of() : cases;
        List<AiNewsEvidenceManifest.Badcase> badcases = new ArrayList<>();
        int tierPass = 0;
        int claimPass = 0;
        int conflictPass = 0;
        int citationPass = 0;
        int verifiedExpected = 0;
        int verifiedCorrect = 0;
        int rejectionExpected = 0;
        int rejectionCorrect = 0;
        int duplicateUrlCandidates = 0;
        int duplicateRemoved = 0;

        for (EvidenceCase item : input) {
            String expectedTier = item.expectedTier() == null ? "community" : item.expectedTier();
            String actualTier = classify(item.url());
            if (expectedTier.equalsIgnoreCase(actualTier)) tierPass++;
            else badcases.add(new AiNewsEvidenceManifest.Badcase(item.id(), "source-tier",
                    expectedTier, actualTier, item.url()));

            boolean claimSupported = claimSupported(item.claim(), item.quote());
            if (claimSupported) claimPass++;
            else badcases.add(new AiNewsEvidenceManifest.Badcase(item.id(), "claim-quote",
                    "supported", "unsupported", "claim is not supported by quote"));

            boolean conflictDetected = item.conflicts() != null && !item.conflicts().isEmpty();
            if (conflictDetected == item.expectConflict()) conflictPass++;
            else badcases.add(new AiNewsEvidenceManifest.Badcase(item.id(), "conflict",
                    String.valueOf(item.expectConflict()), String.valueOf(conflictDetected), "conflict flag"));

            boolean citationAllowed = citationAllowed(item.citedUrls(), item.allowedUrls());
            if (citationAllowed == item.expectCitationAllowed()) citationPass++;
            else badcases.add(new AiNewsEvidenceManifest.Badcase(item.id(), "citation-boundary",
                    String.valueOf(item.expectCitationAllowed()), String.valueOf(citationAllowed), "citation URL set"));

            if (item.evidenceUrls() != null && item.evidenceUrls().size() > 1) {
                duplicateUrlCandidates += item.evidenceUrls().size();
                int distinct = canonicalDistinct(item.evidenceUrls()).size();
                duplicateRemoved += item.evidenceUrls().size() - distinct;
            }

            boolean verified = verificationGate(item);
            boolean expectedVerified = "verified".equalsIgnoreCase(item.expectedDecision());
            if (expectedVerified) {
                verifiedExpected++;
                if (verified) verifiedCorrect++;
            } else {
                rejectionExpected++;
                if (!verified) rejectionCorrect++;
            }
            if (verified != expectedVerified) {
                badcases.add(new AiNewsEvidenceManifest.Badcase(item.id(), "verification-gate",
                        expectedVerified ? "verified" : "blocked",
                        verified ? "verified" : "blocked", "official/corroboration/conflict policy"));
            }
        }

        int total = input.size();
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("total", total);
        counts.put("expectedVerified", verifiedExpected);
        counts.put("expectedRejected", rejectionExpected);
        counts.put("badcases", badcases.size());
        Map<String, Double> metrics = new LinkedHashMap<>();
        putRatio(metrics, "sourceTierClassificationAccuracy", tierPass, total);
        putRatio(metrics, "claimQuoteFixturePassRate", claimPass, total);
        putRatio(metrics, "conflictFlagAccuracy", conflictPass, total);
        putRatio(metrics, "citationBoundaryAccuracy", citationPass, total);
        putRatio(metrics, "verificationEligibleRecall", verifiedCorrect, verifiedExpected);
        putRatio(metrics, "verificationRejectionSpecificity", rejectionCorrect, rejectionExpected);
        putRatio(metrics, "canonicalUrlRemovalFraction", duplicateRemoved, duplicateUrlCandidates);
        AiNewsEvidenceManifest manifest = new AiNewsEvidenceManifest(
                "1.1", "deterministic-policy-fixtures", Instant.now().toString(),
                gitCommit == null || gitCommit.isBlank() ? "unknown" : gitCommit,
                counts, metrics, badcases, testCommand);
        return new EvaluationReport(manifest, total > 0 && badcases.isEmpty());
    }

    public String classify(String url) {
        if (sourceRegistry.isOfficialUrl(url)) return "official";
        if (sourceRegistry.isTrustedMediaUrl(url)) return "media";
        return "community";
    }

    public boolean verificationGate(EvidenceCase item) {
        if (item == null || (item.conflicts() != null && !item.conflicts().isEmpty())) return false;
        if (item.evidenceUrls() == null || item.evidenceUrls().isEmpty()) return false;
        boolean official = item.evidenceUrls().stream().anyMatch(sourceRegistry::isOfficialUrl);
        Set<String> media = item.evidenceUrls().stream()
                .map(sourceRegistry::trustedMediaSourceKey)
                .flatMap(java.util.Optional::stream)
                .collect(java.util.stream.Collectors.toSet());
        return official || media.size() >= 2;
    }

    public static boolean claimSupported(String claim, String quote) {
        if (claim == null || claim.isBlank() || quote == null || quote.isBlank()) return false;
        String normalizedClaim = normalize(claim);
        String normalizedQuote = normalize(quote);
        if (normalizedQuote.contains(normalizedClaim)) return true;
        String[] tokens = normalizedClaim.split("\\s+");
        int meaningful = 0;
        int matched = 0;
        for (String token : tokens) {
            if (token.length() < 2) continue;
            meaningful++;
            if (normalizedQuote.contains(token)) matched++;
        }
        return meaningful > 0 && matched * 2 >= meaningful;
    }

    public static boolean citationAllowed(List<String> cited, List<String> allowed) {
        if (cited == null || cited.isEmpty()) return true;
        Set<String> allowedCanonical = canonicalDistinct(allowed);
        return cited.stream().map(AiNewsEventService::canonicalUrl)
                .allMatch(allowedCanonical::contains);
    }

    public static Set<String> canonicalDistinct(List<String> urls) {
        Set<String> result = new LinkedHashSet<>();
        if (urls != null) {
            for (String url : urls) {
                String canonical = AiNewsEventService.canonicalUrl(url);
                if (!canonical.isBlank()) result.add(canonical);
            }
        }
        return result;
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("\\p{Punct}", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private static void putRatio(Map<String, Double> metrics, String name, int numerator, int denominator) {
        if (denominator > 0) {
            metrics.put(name, ((double) numerator) / denominator);
        }
    }

    public record EvidenceCase(
            String id,
            String expectedDecision,
            String expectedTier,
            String url,
            List<String> evidenceUrls,
            List<String> conflicts,
            boolean expectConflict,
            String claim,
            String quote,
            List<String> citedUrls,
            List<String> allowedUrls,
            boolean expectCitationAllowed
    ) {
        public EvidenceCase {
            evidenceUrls = evidenceUrls == null || evidenceUrls.isEmpty() ? List.of(url) : List.copyOf(evidenceUrls);
            conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
            citedUrls = citedUrls == null ? List.of() : List.copyOf(citedUrls);
            allowedUrls = allowedUrls == null ? evidenceUrls : List.copyOf(allowedUrls);
        }
    }

    public record EvaluationReport(AiNewsEvidenceManifest manifest, boolean passed) {
    }
}
