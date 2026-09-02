package vip.newsclaw.news.service;

import vip.newsclaw.news.model.AiNewsEvidenceEntity;
import vip.newsclaw.news.model.AiNewsEvidenceRelation;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Pure deterministic aggregation of narrow claim-to-quote assessments.
 *
 * <p>No model-provided source tier or policy boolean is consumed here. The
 * registry determines publisher trust; this policy determines corroboration,
 * conflicts, refusal and citation permission. The only model-owned input is
 * the per-evidence semantic relation.</p>
 */
public final class AiNewsDecisionPolicy {

    public enum Reason {
        NO_EVIDENCE,
        MISSING_CAPTURE_PROVENANCE,
        MISSING_SOURCE_TIMESTAMP,
        MISSING_SEMANTIC_ASSESSMENT,
        NO_QUALIFYING_CLAIM_SUPPORT,
        INSUFFICIENT_INDEPENDENT_MEDIA,
        HIGH_RISK_REQUIRES_OFFICIAL_SUPPORT,
        UNRESOLVED_DECLARED_CONFLICT,
        TRUSTED_SOURCE_CONTRADICTION,
        REQUESTED_CITATION_MISSING,
        REQUESTED_CITATION_NOT_ALLOWED,
        REQUESTED_CITATION_UNSUPPORTED
    }

    private final AiNewsSourceRegistry sourceRegistry;

    public AiNewsDecisionPolicy(AiNewsSourceRegistry sourceRegistry) {
        this.sourceRegistry = Objects.requireNonNull(sourceRegistry, "sourceRegistry");
    }

    public Decision decide(Collection<EvidenceFact> suppliedEvidence,
                           Collection<String> allowedCitationIds,
                           String requestedCitationId,
                           boolean declaredConflict,
                           boolean highRisk) {
        List<EvidenceFact> evidence = suppliedEvidence == null ? List.of()
                : suppliedEvidence.stream().filter(Objects::nonNull).toList();
        Set<String> allowed = normalizeIds(allowedCitationIds);
        String requested = normalizeId(requestedCitationId);
        LinkedHashSet<Reason> reasons = new LinkedHashSet<>();
        if (evidence.isEmpty()) reasons.add(Reason.NO_EVIDENCE);

        String strongestTier = strongestTier(evidence);
        List<EvidenceFact> admissibleEvidence = evidence.stream()
                .filter(this::trusted)
                .filter(EvidenceFact::captureBound)
                .filter(EvidenceFact::sourceTimestampPresent)
                .toList();
        boolean missingCapture = evidence.stream().filter(this::trusted)
                .anyMatch(item -> !item.captureBound());
        boolean missingTimestamp = evidence.stream().filter(this::trusted)
                .filter(EvidenceFact::captureBound)
                .anyMatch(item -> !item.sourceTimestampPresent());
        if (missingCapture) reasons.add(Reason.MISSING_CAPTURE_PROVENANCE);
        if (missingTimestamp) reasons.add(Reason.MISSING_SOURCE_TIMESTAMP);

        List<EvidenceFact> trustedEntailments = admissibleEvidence.stream()
                .filter(EvidenceFact::hasQuote)
                .filter(item -> item.relation().supportsClaim())
                .filter(item -> !highRisk || isOfficial(item))
                .toList();
        boolean missingAssessment = admissibleEvidence.stream()
                .anyMatch(item -> item.relation().needsAssessment());
        if (missingAssessment) reasons.add(Reason.MISSING_SEMANTIC_ASSESSMENT);

        boolean trustedContradiction = admissibleEvidence.stream()
                .filter(EvidenceFact::hasQuote)
                .anyMatch(item -> item.relation().contradictsClaim());
        if (declaredConflict) reasons.add(Reason.UNRESOLVED_DECLARED_CONFLICT);
        if (trustedContradiction) reasons.add(Reason.TRUSTED_SOURCE_CONTRADICTION);
        boolean unresolvedConflict = declaredConflict || trustedContradiction;

        boolean officialSupport = trustedEntailments.stream().anyMatch(this::isOfficial);
        Set<String> mediaPublishers = new LinkedHashSet<>();
        trustedEntailments.stream().filter(this::isMedia)
                .map(item -> sourceRegistry.trustedMediaSourceKey(item.sourceUrl()).orElse(""))
                .filter(key -> !key.isBlank()).forEach(mediaPublishers::add);
        boolean claimQuoteSupported = !trustedEntailments.isEmpty();
        if (!claimQuoteSupported && !evidence.isEmpty()) {
            reasons.add(Reason.NO_QUALIFYING_CLAIM_SUPPORT);
        }
        if (highRisk && !officialSupport) {
            reasons.add(Reason.HIGH_RISK_REQUIRES_OFFICIAL_SUPPORT);
        } else if (!officialSupport && !trustedEntailments.isEmpty() && mediaPublishers.size() < 2) {
            reasons.add(Reason.INSUFFICIENT_INDEPENDENT_MEDIA);
        }

        boolean corroborated = officialSupport || (!highRisk && mediaPublishers.size() >= 2);
        boolean verificationEligible = corroborated && !unresolvedConflict && !missingAssessment;

        EvidenceFact requestedEvidence = requested.isBlank() ? null : evidence.stream()
                .filter(item -> requested.equals(item.id())).findFirst().orElse(null);
        if (requested.isBlank()) {
            reasons.add(Reason.REQUESTED_CITATION_MISSING);
        } else if (!allowed.contains(requested) || requestedEvidence == null) {
            reasons.add(Reason.REQUESTED_CITATION_NOT_ALLOWED);
        }
        boolean requestedSupported = requestedEvidence != null
                && trustedEntailments.stream().anyMatch(item -> requested.equals(item.id()));
        if (requestedEvidence != null && !requestedSupported) {
            reasons.add(Reason.REQUESTED_CITATION_UNSUPPORTED);
        }
        boolean citationAllowed = verificationEligible && !requested.isBlank()
                && allowed.contains(requested) && requestedSupported;
        List<String> citationIds = citationAllowed ? List.of(requested) : List.of();

        Set<String> supportingEvidenceIds = new LinkedHashSet<>();
        trustedEntailments.stream().map(EvidenceFact::id)
                .filter(id -> id != null && !id.isBlank()).forEach(supportingEvidenceIds::add);
        double confidence = decisionConfidence(trustedEntailments, officialSupport, verificationEligible);
        return new Decision(strongestTier, verificationEligible, citationAllowed,
                claimQuoteSupported, !verificationEligible, !citationAllowed, citationIds,
                unresolvedConflict, highRisk, Set.copyOf(supportingEvidenceIds),
                List.copyOf(reasons), confidence);
    }

    public Decision decideEntities(Collection<AiNewsEvidenceEntity> evidence,
                                   boolean declaredConflict,
                                   boolean highRisk) {
        List<EvidenceFact> facts = evidence == null ? List.of() : evidence.stream()
                .filter(Objects::nonNull).map(EvidenceFact::fromEntity).toList();
        List<String> ids = facts.stream().map(EvidenceFact::id)
                .filter(id -> id != null && !id.isBlank()).toList();
        // Verification has no citation request. Pick no id and consume only the
        // verification fields from the resulting decision.
        return decide(facts, ids, "", declaredConflict, highRisk);
    }

    private String strongestTier(List<EvidenceFact> evidence) {
        if (evidence.stream().anyMatch(this::isOfficial)) return "official";
        if (evidence.stream().anyMatch(this::isMedia)) return "media";
        return "community";
    }

    private boolean trusted(EvidenceFact evidence) {
        return isOfficial(evidence) || isMedia(evidence);
    }

    private boolean isOfficial(EvidenceFact evidence) {
        return evidence != null && sourceRegistry.isOfficialUrl(evidence.sourceUrl());
    }

    private boolean isMedia(EvidenceFact evidence) {
        return evidence != null && sourceRegistry.isTrustedMediaUrl(evidence.sourceUrl());
    }

    private static double decisionConfidence(List<EvidenceFact> support,
                                             boolean officialSupport,
                                             boolean eligible) {
        if (!eligible || support.isEmpty()) return 0.0D;
        double average = support.stream().mapToDouble(EvidenceFact::confidence).average().orElse(0.5D);
        double floor = officialSupport ? 0.75D : 0.6D;
        return clamp(Math.max(average, floor));
    }

    private static Set<String> normalizeIds(Collection<String> ids) {
        Set<String> out = new LinkedHashSet<>();
        if (ids != null) ids.stream().map(AiNewsDecisionPolicy::normalizeId)
                .filter(id -> !id.isBlank()).forEach(out::add);
        return Set.copyOf(out);
    }

    private static String normalizeId(String id) {
        return id == null ? "" : id.trim();
    }

    private static double clamp(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    public record EvidenceFact(String id,
                               String sourceUrl,
                               String quote,
                               AiNewsEvidenceRelation relation,
                               double confidence,
                               String relationOrigin,
                               boolean captureBound,
                               boolean sourceTimestampPresent) {
        public EvidenceFact(String id,
                            String sourceUrl,
                            String quote,
                            AiNewsEvidenceRelation relation,
                            double confidence,
                            String relationOrigin) {
            this(id, sourceUrl, quote, relation, confidence, relationOrigin, true, true);
        }

        public EvidenceFact {
            id = normalizeId(id);
            sourceUrl = sourceUrl == null ? "" : sourceUrl.trim();
            quote = quote == null ? "" : quote.trim();
            relation = relation == null ? AiNewsEvidenceRelation.UNKNOWN : relation;
            confidence = clamp(confidence);
            relationOrigin = relationOrigin == null ? "UNKNOWN"
                    : relationOrigin.trim().toUpperCase(Locale.ROOT);
        }

        public boolean hasQuote() {
            return !quote.isBlank();
        }

        public static EvidenceFact fromEntity(AiNewsEvidenceEntity entity) {
            double confidence = entity.getRelationConfidence() != null
                    ? entity.getRelationConfidence()
                    : entity.getConfidence() == null ? 0.0D : entity.getConfidence();
            return new EvidenceFact(entity.getId() == null ? "" : String.valueOf(entity.getId()),
                    entity.getSourceUrl(), entity.getQuote(),
                    AiNewsEvidenceRelation.from(entity.getSemanticRelation()), confidence,
                    entity.getRelationOrigin(), hasCaptureProvenance(entity),
                    entity.getSourcePublishedAt() != null);
        }

        private static boolean hasCaptureProvenance(AiNewsEvidenceEntity entity) {
            return entity.getFetchedAt() != null
                    && entity.getHttpStatus() != null
                    && entity.getHttpStatus() >= 200 && entity.getHttpStatus() < 300
                    && entity.getFinalUrl() != null && !entity.getFinalUrl().isBlank()
                    && entity.getContentHash() != null && entity.getContentHash().length() == 64
                    && entity.getCaptureMethod() != null && !entity.getCaptureMethod().isBlank();
        }
    }

    public record Decision(String sourceTier,
                           boolean verificationEligible,
                           boolean citationAllowed,
                           boolean claimQuoteSupported,
                           boolean refusalIssued,
                           boolean humanReviewRequested,
                           List<String> citationIds,
                           boolean unresolvedConflict,
                           boolean highRisk,
                           Set<String> supportingEvidenceIds,
                           List<Reason> reasons,
                           double confidence) {
        public Decision {
            citationIds = citationIds == null ? List.of() : List.copyOf(citationIds);
            supportingEvidenceIds = supportingEvidenceIds == null
                    ? Set.of() : Set.copyOf(supportingEvidenceIds);
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }

        public List<String> reasonCodes() {
            return reasons.stream().map(Enum::name).toList();
        }
    }
}
