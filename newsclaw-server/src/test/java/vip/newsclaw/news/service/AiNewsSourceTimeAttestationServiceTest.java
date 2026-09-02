package vip.newsclaw.news.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.newsclaw.news.model.AiNewsRawCaptureEntity;
import vip.newsclaw.news.model.AiNewsSourceTimeAttestationRow;
import vip.newsclaw.news.repository.AiNewsRawCaptureMapper;
import vip.newsclaw.news.repository.AiNewsSourceItemVersionMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiNewsSourceTimeAttestationServiceTest {

    private AiNewsSourceItemVersionMapper versions;
    private AiNewsRawCaptureMapper rawCaptures;
    private AiNewsSourceTimeAttestationService service;

    @BeforeEach
    void setUp() {
        versions = mock(AiNewsSourceItemVersionMapper.class);
        rawCaptures = mock(AiNewsRawCaptureMapper.class);
        service = new AiNewsSourceTimeAttestationService(versions, rawCaptures,
                new AiNewsSourceRegistry(), new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void bindsExactPublisherOwnedGovernedAndAuditableFeedTime() {
        AiNewsSourceTimeAttestationRow row = row(91L, "2026-08-27T03:00:00Z",
                "Thu, 27 Aug 2026 03:00:00 GMT");
        when(versions.selectLatestTimeAttestations(anyString(), anyInt()))
                .thenReturn(List.of(row));
        when(rawCaptures.selectList(any())).thenReturn(List.of(rawCapture()));

        AiNewsSourceTimeAttestationService.Resolution result = service.resolve(
                "https://openai.com/index/brazil/?utm_source=test#section");

        assertEquals("BOUND", result.status());
        assertNotNull(result.attestation());
        assertEquals(91L, result.attestation().sourceItemVersionId());
        assertEquals(LocalDateTime.of(2026, 8, 27, 3, 0),
                result.attestation().publishedAtUtc());
        assertEquals("STRUCTURED_FEED", result.attestation().method());
        assertEquals(64, result.attestation().attestationHash().length());
    }

    @Test
    void rejectsDiscoveryOnlyEndpointEvenWhenItHasAnExactTimestamp() {
        AiNewsSourceTimeAttestationRow row = row(91L, "2026-08-27T03:00:00Z",
                "Thu, 27 Aug 2026 03:00:00 GMT");
        row.setRightsStatus("review_required");
        when(versions.selectLatestTimeAttestations(anyString(), anyInt()))
                .thenReturn(List.of(row));

        AiNewsSourceTimeAttestationService.Resolution result = service.resolve(
                "https://openai.com/index/brazil");

        assertEquals("INELIGIBLE", result.status());
        assertEquals(null, result.attestation());
        verify(rawCaptures, never()).selectList(any());
    }

    @Test
    void rejectsCrossPublisherEndpointOwnership() {
        AiNewsSourceTimeAttestationRow row = row(91L, "2026-08-27T03:00:00Z",
                "Thu, 27 Aug 2026 03:00:00 GMT");
        row.setEndpointSourceKey("techcrunch");
        when(versions.selectLatestTimeAttestations(anyString(), anyInt()))
                .thenReturn(List.of(row));

        assertEquals("INVALID", service.resolve(
                "https://openai.com/index/brazil").status());
        verify(rawCaptures, never()).selectList(any());
    }

    @Test
    void ownershipOnlyPublisherCanBindWhenGovernanceIsExplicitlyApproved() {
        String article = "https://www.farms.com/news/farm-equipment/"
                + "solinftec-to-launch-ag-robotics-first-amazon-parts-store-246259.aspx";
        AiNewsSourceTimeAttestationRow row = row(93L, "2026-08-27T12:14:38Z",
                "Thu, 27 Aug 2026 12:14:38 GMT");
        row.setCanonicalUrl(article);
        row.setSourceUrl(article);
        row.setSourceTier("community");
        row.setEndpointKey("farms-news-all-rss");
        row.setCatalogVersion(3);
        row.setEndpointSourceKey("farms");
        row.setEndpointUrl("https://www.farms.com/Portals/_default/RSS_Portal/News_All.xml");
        row.setProvenanceJson("{\"providerId\":\"rss\","
                + "\"canonicalUrl\":\"" + article + "\","
                + "\"retrievalMethod\":\"RSS_SEARCH\",\"metadata\":{"
                + "\"publishedAt\":\"2026-08-27T12:14:38Z\","
                + "\"publishedAtRaw\":\"Thu, 27 Aug 2026 12:14:38 GMT\","
                + "\"feedUrl\":\"https://www.farms.com/Portals/_default/"
                + "RSS_Portal/News_All.xml\"}}");
        when(versions.selectLatestTimeAttestations(anyString(), anyInt()))
                .thenReturn(List.of(row));
        when(rawCaptures.selectList(any())).thenReturn(List.of(rawCapture()));

        AiNewsSourceTimeAttestationService.Resolution result = service.resolve(article);

        assertEquals("BOUND", result.status());
        assertEquals(93L, result.attestation().sourceItemVersionId());
        assertFalse(new AiNewsSourceRegistry().isTrustedMediaUrl(article));
    }

    @Test
    void conflictingEligiblePublisherTimesFailClosed() {
        AiNewsSourceTimeAttestationRow first = row(91L, "2026-08-27T03:00:00Z",
                "Thu, 27 Aug 2026 03:00:00 GMT");
        AiNewsSourceTimeAttestationRow second = row(92L, "2026-08-27T04:00:00Z",
                "Thu, 27 Aug 2026 04:00:00 GMT");
        when(versions.selectLatestTimeAttestations(anyString(), anyInt()))
                .thenReturn(List.of(first, second));
        when(rawCaptures.selectList(any())).thenReturn(List.of(rawCapture()));

        assertEquals("CONFLICT", service.resolve(
                "https://openai.com/index/brazil").status());
    }

    @Test
    void validationDetectsAFeedCorrectionAfterCapture() {
        AiNewsSourceTimeAttestationRow original = row(91L, "2026-08-27T03:00:00Z",
                "Thu, 27 Aug 2026 03:00:00 GMT");
        when(versions.selectLatestTimeAttestations(anyString(), anyInt()))
                .thenReturn(List.of(original));
        when(rawCaptures.selectList(any())).thenReturn(List.of(rawCapture()));
        AiNewsSourceTimeAttestationService.Attestation bound = service.resolve(
                "https://openai.com/index/brazil").attestation();
        when(versions.selectTimeAttestationByVersionId(91L)).thenReturn(original);
        AiNewsSourceTimeAttestationRow corrected = row(92L, "2026-08-27T04:00:00Z",
                "Thu, 27 Aug 2026 04:00:00 GMT");
        when(versions.selectLatestTimeAttestations(anyString(), anyInt()))
                .thenReturn(List.of(corrected));

        AiNewsSourceTimeAttestationService.Validation validation = service.validate(
                "https://openai.com/index/brazil", 91L, bound.attestationHash(),
                bound.publishedAtUtc(), bound.publishedAtRaw());

        assertFalse(validation.valid());
        assertEquals("SUPERSEDED", validation.status());
    }

    private static AiNewsSourceTimeAttestationRow row(Long versionId,
                                                       String published,
                                                       String raw) {
        AiNewsSourceTimeAttestationRow row = new AiNewsSourceTimeAttestationRow();
        row.setSourceItemId(versionId + 100);
        row.setSourceItemVersionId(versionId);
        row.setIngestionRunId(71L);
        row.setVersionHash("c".repeat(64));
        row.setCanonicalUrl("https://openai.com/index/brazil");
        row.setSourceUrl("https://openai.com/index/brazil");
        row.setSourceTier("official");
        row.setSourcePublishedAt(java.time.LocalDateTime.ofInstant(
                java.time.Instant.parse(published), java.time.ZoneOffset.UTC));
        row.setPublishedAtRaw(raw);
        row.setObservedAt(LocalDateTime.of(2026, 8, 27, 5, 0));
        row.setEndpointId(61L);
        row.setEndpointKey("openai-news-rss");
        row.setCatalogVersion(2);
        row.setEndpointSourceKey("openai");
        row.setProviderId("rss");
        row.setAdapter("FEED");
        row.setEndpointUrl("https://openai.com/news/rss.xml");
        row.setEndpointEnabled(true);
        row.setEvidenceEligible(true);
        row.setRightsStatus("public_metadata");
        row.setRobotsStatus("allowed");
        row.setRunStatus("success");
        row.setProvenanceJson("{\"providerId\":\"rss\","
                + "\"canonicalUrl\":\"https://openai.com/index/brazil\","
                + "\"retrievalMethod\":\"RSS_SEARCH\",\"metadata\":{"
                + "\"publishedAt\":\"" + published + "\","
                + "\"publishedAtRaw\":\"" + raw + "\","
                + "\"feedUrl\":\"https://openai.com/news/rss.xml\"}}" );
        return row;
    }

    private static AiNewsRawCaptureEntity rawCapture() {
        AiNewsRawCaptureEntity row = new AiNewsRawCaptureEntity();
        row.setId(81L);
        row.setHttpStatus(200);
        row.setReceivedBytes(4096L);
        row.setRepresentationDigest("d".repeat(64));
        row.setTruncated(false);
        row.setDeleted(0);
        return row;
    }
}
