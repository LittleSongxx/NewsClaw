package vip.newsclaw.news.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import vip.newsclaw.news.model.AiNewsRawCaptureEntity;
import vip.newsclaw.news.model.AiNewsSourceTimeAttestationRow;
import vip.newsclaw.news.repository.AiNewsRawCaptureMapper;
import vip.newsclaw.news.repository.AiNewsSourceItemVersionMapper;
import vip.newsclaw.news.source.AiNewsSourceGovernancePolicy;
import vip.newsclaw.news.source.NewsSourceHashing;
import vip.newsclaw.news.source.NewsSourceTimeParser;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Binds an exact publisher feed/sitemap timestamp to a separately captured
 * article body.
 *
 * <p>This is an attestation bridge, not a discovery-hint shortcut. It requires
 * an exact canonical URL, matching publisher ownership, an explicitly approved
 * endpoint, consistent timezone-bearing time fields, and a complete digested
 * transport record. Conflicting eligible attestations fail closed.</p>
 */
@Service
public class AiNewsSourceTimeAttestationService {

    private static final int MAX_MATCHES = 20;
    private static final Set<String> SUPPORTED_ADAPTERS = Set.of(
            "FEED", "NEWS_SITEMAP", "WEBSUB", "OFFICIAL_API",
            "GITHUB_RELEASES", "ARXIV");
    private static final Set<String> TERMINAL_RUNS = Set.of(
            "success", "degraded", "not_modified");

    private final AiNewsSourceItemVersionMapper versionMapper;
    private final AiNewsRawCaptureMapper rawCaptureMapper;
    private final AiNewsSourceRegistry sourceRegistry;
    private final ObjectMapper objectMapper;

    public AiNewsSourceTimeAttestationService(AiNewsSourceItemVersionMapper versionMapper,
                                              AiNewsRawCaptureMapper rawCaptureMapper,
                                              AiNewsSourceRegistry sourceRegistry,
                                              ObjectMapper objectMapper) {
        this.versionMapper = versionMapper;
        this.rawCaptureMapper = rawCaptureMapper;
        this.sourceRegistry = sourceRegistry;
        this.objectMapper = objectMapper;
    }

    public Resolution resolve(String articleUrl) {
        String canonical = AiNewsEventService.canonicalUrl(articleUrl);
        if (canonical.isBlank()) return Resolution.of("NO_MATCH");
        List<AiNewsSourceTimeAttestationRow> rows = versionMapper.selectLatestTimeAttestations(
                NewsSourceHashing.sha256(canonical), MAX_MATCHES);
        if (rows == null || rows.isEmpty()) return Resolution.of("NO_MATCH");

        List<AuditedCandidate> candidates = new ArrayList<>();
        boolean ineligible = false;
        for (AiNewsSourceTimeAttestationRow row : rows) {
            CandidateEvaluation evaluated = evaluate(row, canonical);
            if (evaluated.candidate() != null) candidates.add(evaluated.candidate());
            else if (evaluated.ineligible()) ineligible = true;
        }
        if (candidates.isEmpty()) return Resolution.of(ineligible ? "INELIGIBLE" : "INVALID");

        LinkedHashSet<Instant> instants = candidates.stream()
                .map(candidate -> utc(candidate.row().getSourcePublishedAt()))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (instants.size() != 1) return Resolution.of("CONFLICT");

        AuditedCandidate selected = candidates.stream()
                .sorted(Comparator.comparing(
                                (AuditedCandidate candidate) -> candidate.row().getObservedAt(),
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(candidate -> candidate.row().getSourceItemVersionId(),
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .findFirst().orElseThrow();
        return new Resolution("BOUND", attestation(selected));
    }

    /** Re-check immutable provenance plus the current publisher consensus before event admission. */
    public Validation validate(String articleUrl,
                               Long sourceItemVersionId,
                               String expectedHash,
                               LocalDateTime expectedPublishedAt,
                               String expectedPublishedAtRaw) {
        if (sourceItemVersionId == null || blank(expectedHash)
                || expectedPublishedAt == null || blank(expectedPublishedAtRaw)) {
            return new Validation(false, "INCOMPLETE");
        }
        String canonical = AiNewsEventService.canonicalUrl(articleUrl);
        AiNewsSourceTimeAttestationRow row =
                versionMapper.selectTimeAttestationByVersionId(sourceItemVersionId);
        CandidateEvaluation evaluated = evaluate(row, canonical);
        if (evaluated.candidate() == null) {
            return new Validation(false, evaluated.ineligible() ? "INELIGIBLE" : "INVALID");
        }
        Attestation original = attestation(evaluated.candidate());
        if (!expectedHash.equals(original.attestationHash())
                || !expectedPublishedAt.equals(original.publishedAtUtc())
                || !expectedPublishedAtRaw.equals(original.publishedAtRaw())) {
            return new Validation(false, "HASH_MISMATCH");
        }
        Resolution current = resolve(canonical);
        if (current.attestation() == null) return new Validation(false, current.status());
        // Same timestamp is not enough: a publisher may replace an item while
        // preserving its publication date. Require the current consensus to
        // point at the exact attestation version/hash that was bound to the
        // article capture.
        if (!expectedPublishedAt.equals(current.attestation().publishedAtUtc())
                || !expectedHash.equals(current.attestation().attestationHash())
                || (sourceItemVersionId != null
                    && !sourceItemVersionId.equals(current.attestation().sourceItemVersionId()))) {
            return new Validation(false, "SUPERSEDED");
        }
        return new Validation(true, "VALID");
    }

    private CandidateEvaluation evaluate(AiNewsSourceTimeAttestationRow row, String canonical) {
        if (row == null || blank(canonical)) return CandidateEvaluation.invalid();
        if (!Boolean.TRUE.equals(row.getEndpointEnabled())
                || !AiNewsSourceGovernancePolicy.evidenceEligible(
                Boolean.TRUE.equals(row.getEvidenceEligible()),
                row.getRightsStatus(), row.getRobotsStatus())) {
            return CandidateEvaluation.ineligibleResult();
        }
        String adapter = token(row.getAdapter()).toUpperCase(Locale.ROOT);
        if (!SUPPORTED_ADAPTERS.contains(adapter) || !TERMINAL_RUNS.contains(token(row.getRunStatus()))) {
            return CandidateEvaluation.invalid();
        }
        String rowCanonical = AiNewsEventService.canonicalUrl(row.getCanonicalUrl());
        if (!canonical.equals(rowCanonical)) return CandidateEvaluation.invalid();
        Optional<String> publisherKey = sourceRegistry.publisherSourceKey(canonical);
        if (publisherKey.isEmpty() || !publisherKey.get().equals(token(row.getEndpointSourceKey()))) {
            return CandidateEvaluation.invalid();
        }
        if (row.getSourcePublishedAt() == null || blank(row.getPublishedAtRaw())
                || row.getSourceItemVersionId() == null || row.getIngestionRunId() == null
                || row.getEndpointId() == null || blank(row.getVersionHash())) {
            return CandidateEvaluation.invalid();
        }
        Instant stored = utc(row.getSourcePublishedAt());
        Instant rawTime = NewsSourceTimeParser.parseExact(row.getPublishedAtRaw());
        ProvenanceFields provenance = provenance(row);
        if (rawTime == null || !stored.equals(rawTime) || provenance == null
                || !stored.equals(provenance.publishedAt())
                || !row.getPublishedAtRaw().equals(provenance.publishedAtRaw())) {
            return CandidateEvaluation.invalid();
        }
        AiNewsRawCaptureEntity transport = auditedTransport(row, provenance.transportUrl());
        if (transport == null) return CandidateEvaluation.invalid();
        return CandidateEvaluation.valid(new AuditedCandidate(row, transport, provenance));
    }

    private ProvenanceFields provenance(AiNewsSourceTimeAttestationRow row) {
        try {
            JsonNode root = objectMapper.readTree(row.getProvenanceJson());
            if (root == null || !root.isObject()) return null;
            if (!token(row.getProviderId()).equals(token(root.path("providerId").asText()))) return null;
            if (!AiNewsEventService.canonicalUrl(row.getCanonicalUrl()).equals(
                    AiNewsEventService.canonicalUrl(root.path("canonicalUrl").asText()))) return null;
            JsonNode metadata = root.path("metadata");
            if (!metadata.isObject()) return null;
            Instant published = NewsSourceTimeParser.parseExact(metadata.path("publishedAt").asText());
            String raw = metadata.path("publishedAtRaw").asText("");
            String transportUrl = firstNonBlank(metadata.path("feedUrl").asText(""),
                    metadata.path("sitemapUrl").asText(""),
                    metadata.path("apiUrl").asText(""), row.getEndpointUrl());
            if (published == null || blank(raw) || !absoluteHttpUrl(transportUrl)) return null;
            return new ProvenanceFields(published, raw, URI.create(transportUrl).normalize().toString(),
                    root.path("retrievalMethod").asText(""));
        } catch (Exception ignored) {
            return null;
        }
    }

    private AiNewsRawCaptureEntity auditedTransport(AiNewsSourceTimeAttestationRow row,
                                                     String transportUrl) {
        String urlHash = NewsSourceHashing.sha256(URI.create(transportUrl).normalize().toString());
        List<AiNewsRawCaptureEntity> captures = rawCaptureMapper.selectList(
                new LambdaQueryWrapper<AiNewsRawCaptureEntity>()
                        .eq(AiNewsRawCaptureEntity::getIngestionRunId, row.getIngestionRunId())
                        .eq(AiNewsRawCaptureEntity::getEndpointId, row.getEndpointId())
                        .eq(AiNewsRawCaptureEntity::getRequestUrlHash, urlHash)
                        .eq(AiNewsRawCaptureEntity::getDeleted, 0)
                        .orderByDesc(AiNewsRawCaptureEntity::getAttemptNo));
        if (captures == null) return null;
        return captures.stream().filter(capture -> capture.getHttpStatus() != null
                        && capture.getHttpStatus() >= 200 && capture.getHttpStatus() < 300
                        && !Boolean.TRUE.equals(capture.getTruncated())
                        && capture.getReceivedBytes() != null && capture.getReceivedBytes() > 0
                        && capture.getRepresentationDigest() != null
                        && capture.getRepresentationDigest().matches("[0-9a-f]{64}"))
                .findFirst().orElse(null);
    }

    private static Attestation attestation(AuditedCandidate candidate) {
        AiNewsSourceTimeAttestationRow row = candidate.row();
        String canonical = AiNewsEventService.canonicalUrl(row.getCanonicalUrl());
        String method = "STRUCTURED_" + token(row.getAdapter()).toUpperCase(Locale.ROOT);
        String payload = String.join("\n", "source-time-attestation-v1", canonical,
                String.valueOf(row.getSourceItemId()), String.valueOf(row.getSourceItemVersionId()),
                String.valueOf(row.getIngestionRunId()), String.valueOf(row.getEndpointId()),
                token(row.getEndpointKey()), String.valueOf(row.getCatalogVersion()),
                token(row.getEndpointSourceKey()), token(row.getRightsStatus()),
                token(row.getRobotsStatus()), token(row.getVersionHash()),
                utc(row.getSourcePublishedAt()).toString(), row.getPublishedAtRaw(),
                NewsSourceHashing.sha256(row.getProvenanceJson()),
                String.valueOf(candidate.transport().getId()),
                candidate.transport().getRepresentationDigest(),
                candidate.provenance().transportUrl());
        return new Attestation(row.getSourceItemVersionId(), row.getSourcePublishedAt(),
                row.getPublishedAtRaw(), method, NewsSourceHashing.sha256(payload));
    }

    private static Instant utc(LocalDateTime value) {
        return value.toInstant(ZoneOffset.UTC);
    }

    private static boolean absoluteHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            return uri.getHost() != null && ("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (!blank(value)) return value.trim();
        return "";
    }

    private static String token(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record Resolution(String status, Attestation attestation) {
        public static Resolution of(String status) {
            return new Resolution(status, null);
        }
    }

    public record Attestation(Long sourceItemVersionId,
                              LocalDateTime publishedAtUtc,
                              String publishedAtRaw,
                              String method,
                              String attestationHash) {
    }

    public record Validation(boolean valid, String status) {
    }

    private record AuditedCandidate(AiNewsSourceTimeAttestationRow row,
                                    AiNewsRawCaptureEntity transport,
                                    ProvenanceFields provenance) {
    }

    private record ProvenanceFields(Instant publishedAt,
                                    String publishedAtRaw,
                                    String transportUrl,
                                    String retrievalMethod) {
    }

    private record CandidateEvaluation(AuditedCandidate candidate, boolean ineligible) {
        static CandidateEvaluation valid(AuditedCandidate candidate) {
            return new CandidateEvaluation(candidate, false);
        }

        static CandidateEvaluation invalid() {
            return new CandidateEvaluation(null, false);
        }

        static CandidateEvaluation ineligibleResult() {
            return new CandidateEvaluation(null, true);
        }
    }
}
