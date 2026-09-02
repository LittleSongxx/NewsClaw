package vip.newsclaw.news.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import vip.newsclaw.news.model.AiNewsCaptureAttemptEntity;
import vip.newsclaw.news.model.AiNewsEvidenceEntity;
import vip.newsclaw.news.model.AiNewsEventEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Pure, deterministic high-risk routing policy for AI-news events.
 *
 * <p>The policy deliberately judges only observable provenance: event
 * conflicts, trusted-source eligibility, persisted semantic assessments,
 * evidence excerpts, immutable official captures and capture-attempt outcomes.
 * The model may supply the narrow relation, but it cannot supply or override
 * any routing decision produced here.</p>
 */
@Component
public class AiNewsReviewPolicy {

    public static final String VERSION = "ai-news-review-policy@2026.08.27-v4";

    public enum Reason {
        UNRESOLVED_CONFLICT,
        VERIFICATION_NOT_ELIGIBLE,
        LOW_TRUST_OR_UNREGISTERED_SOURCE,
        MISSING_EVIDENCE_QUOTE,
        MISSING_SEMANTIC_ASSESSMENT,
        UNATTESTED_SEMANTIC_ASSESSMENT,
        CLAIM_NOT_SUPPORTED,
        TRUSTED_SOURCE_CONTRADICTION,
        HIGH_RISK_CLAIM_REQUIRES_REVIEW,
        UNCAPTURED_SOURCE,
        MISSING_SOURCE_TIMESTAMP,
        UNCAPTURED_OFFICIAL_SOURCE,
        OFFICIAL_CAPTURE_FAILED_OR_BLOCKED
    }

    private final ObjectMapper objectMapper;
    private final AiNewsSourceRegistry sourceRegistry;
    private final AiNewsDecisionPolicy decisionPolicy;

    public AiNewsReviewPolicy(ObjectMapper objectMapper, AiNewsSourceRegistry sourceRegistry) {
        this.objectMapper = objectMapper;
        this.sourceRegistry = sourceRegistry;
        this.decisionPolicy = new AiNewsDecisionPolicy(sourceRegistry);
    }

    public Decision evaluate(AiNewsEventEntity event,
                             List<AiNewsEvidenceEntity> suppliedEvidence,
                             List<AiNewsCaptureAttemptEntity> suppliedAttempts) {
        if (event == null || terminal(event.getStatus())) {
            return new Decision(List.of(), fingerprint(event, List.of(), List.of()));
        }
        List<AiNewsEvidenceEntity> evidence = suppliedEvidence == null ? List.of()
                : suppliedEvidence.stream().filter(Objects::nonNull).toList();
        List<AiNewsCaptureAttemptEntity> attempts = suppliedAttempts == null ? List.of()
                : suppliedAttempts.stream().filter(Objects::nonNull).toList();

        List<AiNewsEvidenceEntity> official = evidence.stream()
                .filter(this::trustedOfficial).toList();
        List<AiNewsEvidenceEntity> trustedMedia = evidence.stream()
                .filter(this::trustedMedia).toList();
        Set<String> mediaPublishers = trustedMedia.stream()
                .map(item -> sourceRegistry.trustedMediaSourceKey(item.getSourceUrl()).orElse(""))
                .filter(key -> !key.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        LinkedHashSet<Reason> reasons = new LinkedHashSet<>();
        boolean declaredConflict = hasConflicts(event.getConflictsJson());
        boolean highRisk = AiNewsRiskClassifier.isHighRisk(event, evidence);
        AiNewsDecisionPolicy.Decision semanticDecision = decisionPolicy.decideEntities(
                evidence, declaredConflict, highRisk);
        if (semanticDecision.unresolvedConflict()) {
            reasons.add(Reason.UNRESOLVED_CONFLICT);
        }
        if (semanticDecision.reasons().contains(AiNewsDecisionPolicy.Reason.TRUSTED_SOURCE_CONTRADICTION)) {
            reasons.add(Reason.TRUSTED_SOURCE_CONTRADICTION);
        }
        if (!semanticDecision.verificationEligible()) {
            reasons.add(Reason.VERIFICATION_NOT_ELIGIBLE);
        }
        if (semanticDecision.reasons().contains(
                AiNewsDecisionPolicy.Reason.MISSING_CAPTURE_PROVENANCE)) {
            reasons.add(Reason.UNCAPTURED_SOURCE);
        }
        if (semanticDecision.reasons().contains(
                AiNewsDecisionPolicy.Reason.MISSING_SOURCE_TIMESTAMP)) {
            reasons.add(Reason.MISSING_SOURCE_TIMESTAMP);
        }
        if (!evidence.isEmpty() && official.isEmpty() && trustedMedia.isEmpty()) {
            reasons.add(Reason.LOW_TRUST_OR_UNREGISTERED_SOURCE);
        }
        if (missingRequiredQuote(evidence, official, trustedMedia, mediaPublishers)) {
            reasons.add(Reason.MISSING_EVIDENCE_QUOTE);
        }
        if (semanticDecision.reasons().contains(AiNewsDecisionPolicy.Reason.MISSING_SEMANTIC_ASSESSMENT)) {
            reasons.add(Reason.MISSING_SEMANTIC_ASSESSMENT);
        }
        boolean unattestedSupport = evidence.stream()
                .filter(item -> semanticDecision.supportingEvidenceIds().contains(
                        item.getId() == null ? "" : String.valueOf(item.getId())))
                .anyMatch(item -> !AiNewsRelationAttestation.isVerificationAttested(
                        item.getRelationOrigin()));
        if (unattestedSupport) {
            reasons.add(Reason.UNATTESTED_SEMANTIC_ASSESSMENT);
        }
        boolean hasTrustedQuote = official.stream().anyMatch(this::hasQuote)
                || trustedMedia.stream().anyMatch(this::hasQuote);
        boolean provenanceBlocked = semanticDecision.reasons().contains(
                AiNewsDecisionPolicy.Reason.MISSING_CAPTURE_PROVENANCE)
                || semanticDecision.reasons().contains(
                AiNewsDecisionPolicy.Reason.MISSING_SOURCE_TIMESTAMP);
        if (hasTrustedQuote && !semanticDecision.claimQuoteSupported() && !provenanceBlocked) {
            reasons.add(Reason.CLAIM_NOT_SUPPORTED);
        }
        if (highRisk) {
            reasons.add(Reason.HIGH_RISK_CLAIM_REQUIRES_REVIEW);
        }

        boolean capturedOfficial = official.stream().anyMatch(this::capturedOfficial);
        if (!official.isEmpty() && !capturedOfficial) {
            reasons.add(Reason.UNCAPTURED_OFFICIAL_SOURCE);
            if (hasFailedOfficialCapture(attempts)) {
                reasons.add(Reason.OFFICIAL_CAPTURE_FAILED_OR_BLOCKED);
            }
        }
        return new Decision(List.copyOf(reasons), fingerprint(event, evidence, attempts));
    }

    private boolean trustedOfficial(AiNewsEvidenceEntity evidence) {
        return evidence != null && "official".equalsIgnoreCase(evidence.getSourceTier())
                && sourceRegistry.isOfficialUrl(evidence.getSourceUrl());
    }

    private boolean trustedMedia(AiNewsEvidenceEntity evidence) {
        return evidence != null && "media".equalsIgnoreCase(evidence.getSourceTier())
                && sourceRegistry.isTrustedMediaUrl(evidence.getSourceUrl());
    }

    private boolean capturedOfficial(AiNewsEvidenceEntity evidence) {
        if (!trustedOfficial(evidence) || evidence.getFetchedAt() == null
                || blank(evidence.getContentHash()) || evidence.getHttpStatus() == null
                || evidence.getHttpStatus() < 200 || evidence.getHttpStatus() >= 300
                || evidence.getSourcePublishedAt() == null) {
            return false;
        }
        String finalUrl = evidence.getFinalUrl();
        return !blank(finalUrl) && sourceRegistry.isOfficialUrl(finalUrl);
    }

    private boolean missingRequiredQuote(List<AiNewsEvidenceEntity> evidence,
                                         List<AiNewsEvidenceEntity> official,
                                         List<AiNewsEvidenceEntity> trustedMedia,
                                         Set<String> mediaPublishers) {
        if (evidence.isEmpty()) return false;
        if (!official.isEmpty()) {
            return official.stream().noneMatch(this::hasQuote);
        }
        if (mediaPublishers.size() >= 2) {
            long quotedPublishers = trustedMedia.stream().filter(this::hasQuote)
                    .map(item -> sourceRegistry.trustedMediaSourceKey(item.getSourceUrl()).orElse(""))
                    .filter(key -> !key.isBlank()).distinct().count();
            return quotedPublishers < 2;
        }
        return evidence.stream().noneMatch(this::hasQuote);
    }

    private boolean hasQuote(AiNewsEvidenceEntity evidence) {
        return evidence != null && !blank(evidence.getQuote());
    }

    private boolean hasFailedOfficialCapture(List<AiNewsCaptureAttemptEntity> attempts) {
        return attempts.stream().anyMatch(attempt -> {
            boolean officialUrl = sourceRegistry.isOfficialUrl(attempt.getSourceUrl())
                    || sourceRegistry.isOfficialUrl(attempt.getFinalUrl());
            return officialUrl && !"success".equalsIgnoreCase(attempt.getCaptureStatus());
        });
    }

    private boolean hasConflicts(String conflictsJson) {
        if (blank(conflictsJson)) return false;
        try {
            JsonNode node = objectMapper.readTree(conflictsJson);
            return node != null && (!node.isArray() || !node.isEmpty());
        } catch (Exception ignored) {
            // Unparseable conflict data cannot safely be interpreted as clear.
            return true;
        }
    }

    private static boolean terminal(String status) {
        return Set.of("rejected", "archived", "published", "in_production")
                .contains(normalize(status));
    }

    private String fingerprint(AiNewsEventEntity event,
                               List<AiNewsEvidenceEntity> evidence,
                               List<AiNewsCaptureAttemptEntity> attempts) {
        List<String> parts = new ArrayList<>();
        parts.add("policy=" + VERSION);
        parts.add("event=" + value(event == null ? null : event.getId()));
        parts.add("terminal=" + terminal(event == null ? null : event.getStatus()));
        parts.add("conflicts=" + value(event == null ? null : event.getConflictsJson()));
        evidence.stream().sorted(Comparator.comparing(item -> value(item.getId())))
                .forEach(item -> parts.add("evidence=" + String.join("|",
                        value(item.getId()), value(item.getSourceUrl()), value(item.getSourceTier()),
                        value(item.getClaim()), value(item.getQuote()), value(item.getSourcePublishedAt()),
                        value(item.getSourceCaptureId()), value(item.getQuoteStart()),
                        value(item.getQuoteEnd()), value(item.getQuoteMatchMethod()), value(item.getFinalUrl()),
                        value(item.getFetchedAt()), value(item.getContentHash()), value(item.getHttpStatus()),
                        value(item.getCaptureMethod()), value(item.getSemanticRelation()),
                        value(item.getRelationConfidence()), value(item.getRelationOrigin()),
                        value(item.getRelationReviewedAt()), value(item.getRelationReviewedBy()),
                        value(item.getRelationReviewNote()))));
        attempts.stream().sorted(Comparator.comparing(item -> value(item.getId())))
                .forEach(item -> parts.add("capture=" + String.join("|",
                        value(item.getId()), value(item.getSourceUrl()), value(item.getFinalUrl()),
                        value(item.getCaptureStatus()), value(item.getHttpStatus()),
                        value(item.getCaptureError()), value(item.getAttemptedAt()))));
        return sha256(String.join("\n", parts));
    }

    private static String value(Object input) {
        if (input instanceof LocalDateTime time) return time.toString();
        return input == null ? "" : input.toString().trim();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record Decision(List<Reason> reasons, String fingerprint) {
        public boolean requiresReview() {
            return reasons != null && !reasons.isEmpty();
        }

        public List<String> reasonCodes() {
            return reasons == null ? List.of() : reasons.stream().map(Enum::name).toList();
        }
    }
}
