package vip.newsclaw.news.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.news.model.AiNewsEvidenceEntity;
import vip.newsclaw.news.model.AiNewsEvidenceRequest;
import vip.newsclaw.news.model.AiNewsEvidenceRelation;
import vip.newsclaw.news.model.AiNewsEventEntity;
import vip.newsclaw.news.model.AiNewsEventStatus;
import vip.newsclaw.news.model.AiNewsEventUpsertRequest;
import vip.newsclaw.news.model.AiNewsSourceCaptureEntity;
import vip.newsclaw.news.repository.AiNewsEvidenceMapper;
import vip.newsclaw.news.repository.AiNewsEventMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiNewsEventServiceTest {

    @Mock
    private AiNewsEventMapper eventMapper;
    @Mock
    private AiNewsEvidenceMapper evidenceMapper;

    private AiNewsEventService service;

    @BeforeEach
    void setUp() {
        service = new AiNewsEventService(eventMapper, evidenceMapper, new ObjectMapper());
    }

    @Test
    void canonicalUrl_normalizesHostSlashAndFragment() {
        assertEquals("https://example.com/releases/model?ref=home",
                AiNewsEventService.canonicalUrl("HTTPS://Example.COM//releases/model/?ref=home#intro"));
        assertEquals("https://example.com/releases/model?id=7",
                AiNewsEventService.canonicalUrl(
                        "https://example.com/releases/model?utm_source=rss&id=7&fbclid=x"));
    }

    @Test
    void page_projectsActualEvidenceVerificationInsteadOfInferringLifecycleState() {
        AiNewsEventEntity inProduction = event(120L, 7L, "in_production");
        AiNewsEventEntity candidate = event(121L, 7L, "candidate");
        Page<AiNewsEventEntity> storedPage = new Page<>(1, 20);
        storedPage.setRecords(List.of(inProduction, candidate));
        when(eventMapper.selectPage(any(), any())).thenReturn(storedPage);

        AiNewsEvidenceEntity officialVerified = evidence(220L, 120L, 7L, "official", "deepseek.com");
        officialVerified.setVerified(true);
        AiNewsEvidenceEntity mediaPending = evidence(221L, 121L, 7L, "media", "news.example.com");
        when(evidenceMapper.selectList(any())).thenReturn(List.of(officialVerified, mediaPending));

        IPage<AiNewsEventEntity> result = service.page(7L, 1, 20, null, null, null);

        assertEquals(2, result.getRecords().size());
        assertEquals(1, inProduction.getEvidenceCount());
        assertEquals(1, inProduction.getVerifiedEvidenceCount());
        assertEquals("official", inProduction.getPrimaryEvidenceTier());
        assertEquals(1, candidate.getEvidenceCount());
        assertEquals(0, candidate.getVerifiedEvidenceCount());
        assertEquals("media", candidate.getPrimaryEvidenceTier());
    }

    @Test
    void upsert_requiresTitleAndTraceableSource() {
        NewsClawException missingTitle = assertThrows(NewsClawException.class,
                () -> service.upsert(7L, new AiNewsEventUpsertRequest(null, " ", null,
                        "model", List.of(), null, null, List.of(), List.of(), List.of())));
        assertEquals(400, missingTitle.getCode());

        NewsClawException missingSource = assertThrows(NewsClawException.class,
                () -> service.upsert(7L, new AiNewsEventUpsertRequest(null, "A model update", null,
                        "model", List.of(), null, null, List.of(), List.of(), List.of())));
        assertEquals(400, missingSource.getCode());
    }

    @Test
    void verify_blocksArbitraryMediaDomainsEvenWhenTheyAreIndependent() {
        AiNewsEventEntity event = event(101L, 7L, "candidate");
        when(eventMapper.selectOne(any())).thenReturn(event);

        AiNewsEvidenceEntity one = evidence(201L, 101L, 7L, "media", "news.example.com");
        when(evidenceMapper.selectList(any())).thenReturn(List.of(one));
        NewsClawException insufficient = assertThrows(NewsClawException.class,
                () -> service.verify(7L, 101L, null, null));
        assertEquals(409, insufficient.getCode());
        assertEquals("candidate", event.getStatus());

        AiNewsEvidenceEntity second = evidence(202L, 101L, 7L, "media", "wire.example.org");
        when(evidenceMapper.selectList(any())).thenReturn(List.of(one, second));
        NewsClawException arbitrarySources = assertThrows(NewsClawException.class,
                () -> service.verify(7L, 101L, null, null));
        assertEquals(409, arbitrarySources.getCode());
        assertTrue(arbitrarySources.getMessage().contains("未注册来源只能作为线索"));
        verify(evidenceMapper, never()).updateById(any(AiNewsEvidenceEntity.class));
    }

    @Test
    void verify_acceptsTwoIndependentRegisteredMediaPublishers() {
        AiNewsEventEntity event = event(111L, 7L, "candidate");
        when(eventMapper.selectOne(any())).thenReturn(event);
        when(evidenceMapper.selectList(any())).thenReturn(List.of(
                evidence(211L, 111L, 7L, "media", "www.reuters.com"),
                evidence(212L, 111L, 7L, "media", "techcrunch.com")));

        AiNewsEventEntity verified = service.verify(7L, 111L, null, null);

        assertEquals(AiNewsEventStatus.VERIFIED.token(), verified.getStatus());
        assertTrue(verified.getConfidence() >= 0.6D);
        verify(evidenceMapper, org.mockito.Mockito.times(2)).updateById(any(AiNewsEvidenceEntity.class));
    }

    @Test
    void officialEvidence_canVerifyAndProductionRequiresVerifiedState() {
        AiNewsEventEntity candidate = event(102L, 7L, "candidate");
        when(eventMapper.selectOne(any())).thenReturn(candidate);
        when(evidenceMapper.selectList(any())).thenReturn(List.of(evidence(203L, 102L, 7L,
                "official", "openai.com")));

        service.verify(7L, 102L, null, 0.91D);
        assertEquals("verified", candidate.getStatus());
        service.beginProduction(7L, 102L);
        assertEquals("in_production", candidate.getStatus());
    }

    @Test
    void productionUsesLockedLifecycleStateInsteadOfAnEarlierSnapshot() {
        AiNewsEventEntity locked = event(112L, 7L, "verified");
        when(eventMapper.selectForUpdate(7L, 112L)).thenReturn(locked);

        AiNewsEventEntity result = service.beginProduction(7L, 112L);

        assertEquals("in_production", result.getStatus());
        verify(eventMapper).selectForUpdate(7L, 112L);
        verify(eventMapper, never()).selectOne(any());
    }

    @Test
    void productionCannotBypassPendingDeterministicReviewTask() {
        AiNewsReviewRoutingService reviewRouting = org.mockito.Mockito.mock(AiNewsReviewRoutingService.class);
        AiNewsEventService guarded = new AiNewsEventService(eventMapper, evidenceMapper,
                new ObjectMapper(), new AiNewsSourceRegistry(), reviewRouting);
        AiNewsEventEntity verified = event(104L, 7L, "verified");
        when(eventMapper.selectOne(any())).thenReturn(verified);
        org.mockito.Mockito.doThrow(new NewsClawException(409,
                        "事件仍有待处理的人工复核风险: UNCAPTURED_OFFICIAL_SOURCE"))
                .when(reviewRouting).requireClearForProduction(verified);

        NewsClawException rejected = assertThrows(NewsClawException.class,
                () -> guarded.beginProduction(7L, 104L));

        assertEquals(409, rejected.getCode());
        assertEquals("verified", verified.getStatus());
        verify(reviewRouting).requireClearForProduction(verified);
        verify(eventMapper, never()).updateById(verified);
    }

    @Test
    void officialLabelOnUnknownDomain_doesNotBypassCorroborationRule() {
        AiNewsEventEntity event = event(105L, 7L, "candidate");
        when(eventMapper.selectOne(any())).thenReturn(event);
        when(evidenceMapper.selectList(any())).thenReturn(List.of(
                evidence(205L, 105L, 7L, "official", "example.net")));

        NewsClawException rejected = assertThrows(NewsClawException.class,
                () -> service.verify(7L, 105L, null, null));
        assertEquals(409, rejected.getCode());
        assertTrue(rejected.getMessage().contains("独立可信媒体"));
    }

    @Test
    void explicitConflictsPersistConflictedState() {
        when(eventMapper.selectOne(any())).thenReturn(null);
        when(evidenceMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            AiNewsEventEntity row = invocation.getArgument(0);
            row.setId(109L);
            return 1;
        }).when(eventMapper).insert(any(AiNewsEventEntity.class));
        doAnswer(invocation -> {
            AiNewsEvidenceEntity row = invocation.getArgument(0);
            row.setId(609L);
            return 1;
        }).when(evidenceMapper).insert(any(AiNewsEvidenceEntity.class));

        AiNewsEventEntity saved = service.upsert(7L, new AiNewsEventUpsertRequest(
                null, "模型发布存在版本争议", "不同来源给出不同发布日期", "model", List.of("Example"),
                null, null, List.of("发布日期为周一"), List.of("两个来源的发布日期不一致"),
                List.of(new AiNewsEvidenceRequest("https://example.com/release", "官方公告", null,
                        "official", "发布日期为周一", "原文", 0.5D))));

        assertEquals(AiNewsEventStatus.CONFLICTED.token(), saved.getStatus());
        assertEquals(0.0D, saved.getConfidence());
    }

    @Test
    void partialUpdateDoesNotClearOrUnblockPersistedConflicts() {
        AiNewsEventEntity event = event(110L, 7L, "conflicted");
        event.setConflictsJson("[\"release dates disagree\"]");
        when(eventMapper.selectOne(any())).thenReturn(event);

        AiNewsEventEntity saved = service.upsert(7L, new AiNewsEventUpsertRequest(
                "event-110", "同一事件补充证据", "补充一条证据", "model", List.of(),
                null, null, null, null, List.of()));

        assertEquals("[\"release dates disagree\"]", saved.getConflictsJson());
        assertEquals(AiNewsEventStatus.CONFLICTED.token(), saved.getStatus());
        verify(eventMapper).updateById(event);
    }

    @Test
    void explicitEmptyConflictListIsTheOnlyClearOperation() {
        AiNewsEventEntity event = event(111L, 7L, "conflicted");
        event.setConflictsJson("[\"release dates disagree\"]");
        when(eventMapper.selectOne(any())).thenReturn(event);

        AiNewsEventEntity saved = service.upsert(7L, new AiNewsEventUpsertRequest(
                "event-111", "冲突已处理", "补充结论", "model", List.of(),
                null, null, null, List.of(), List.of()));

        assertEquals("[]", saved.getConflictsJson());
        assertEquals(AiNewsEventStatus.RESEARCHING.token(), saved.getStatus());
        verify(eventMapper).updateById(event);
    }

    @Test
    void publishedEvent_canBeArchivedWithoutReopeningVerification() {
        AiNewsEventEntity event = event(106L, 7L, "published");
        when(eventMapper.selectOne(any())).thenReturn(event);

        AiNewsEventEntity archived = service.archive(7L, 106L);

        assertEquals(AiNewsEventStatus.ARCHIVED.token(), archived.getStatus());
        verify(eventMapper).updateById(event);
    }

    @Test
    void publishRequiresLinkedDeliveryArtifactAndIsIdempotent() {
        AiNewsEventEntity event = event(107L, 7L, "in_production");
        LocalDateTime sourcePublishedAt = LocalDateTime.of(2026, 8, 26, 4, 30);
        event.setSourcePublishedAt(sourcePublishedAt);
        when(eventMapper.selectOne(any())).thenReturn(event);

        NewsClawException missingArtifact = assertThrows(NewsClawException.class,
                () -> service.markPublished(7L, 107L));
        assertEquals(409, missingArtifact.getCode());

        event.setGzhContentItemId(7001L);
        AiNewsEventEntity published = service.markPublished(7L, 107L);
        assertEquals(AiNewsEventStatus.PUBLISHED.token(), published.getStatus());
        assertTrue(published.getPublishedAt() != null);
        assertEquals(sourcePublishedAt, published.getSourcePublishedAt());

        assertEquals(published, service.markPublished(7L, 107L));
    }

    @Test
    void publishCannotBypassProductionStateEvenWithDeliveryArtifact() {
        AiNewsEventEntity event = event(108L, 7L, "candidate");
        event.setGzhContentItemId(8001L);
        when(eventMapper.selectOne(any())).thenReturn(event);

        NewsClawException rejected = assertThrows(NewsClawException.class,
                () -> service.markPublished(7L, 108L));

        assertEquals(409, rejected.getCode());
        assertEquals("candidate", event.getStatus());
        verify(eventMapper, never()).updateById(event);
    }

    @Test
    void conflictedClaims_blockVerificationAndUnverifiedProduction() {
        AiNewsEventEntity event = event(103L, 7L, "candidate");
        event.setConflictsJson("[\"release date differs\"]");
        when(eventMapper.selectOne(any())).thenReturn(event);
        when(evidenceMapper.selectList(any())).thenReturn(List.of(evidence(204L, 103L, 7L,
                "official", "deepseek.com")));

        NewsClawException conflict = assertThrows(NewsClawException.class,
                () -> service.verify(7L, 103L, null, null));
        assertEquals(409, conflict.getCode());
        assertEquals("candidate", event.getStatus());

        NewsClawException production = assertThrows(NewsClawException.class,
                () -> service.beginProduction(7L, 103L));
        assertEquals(409, production.getCode());
        verify(eventMapper, never()).updateById(event);
    }

    @Test
    void upsertPersistsWorkspaceAndCanonicalEvidenceUrl() {
        when(eventMapper.selectOne(any())).thenReturn(null);
        when(evidenceMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            AiNewsEventEntity row = invocation.getArgument(0);
            row.setId(501L);
            return 1;
        }).when(eventMapper).insert(any(AiNewsEventEntity.class));
        doAnswer(invocation -> {
            AiNewsEvidenceEntity row = invocation.getArgument(0);
            row.setId(601L);
            return 1;
        }).when(evidenceMapper).insert(any(AiNewsEvidenceEntity.class));

        service.upsert(42L, new AiNewsEventUpsertRequest(null, "DeepSeek 发布新模型", "摘要",
                "model", List.of("DeepSeek"), null, null, List.of("发布模型"), List.of(),
                List.of(new AiNewsEvidenceRequest("HTTPS://DeepSeek.com/news/?utm=1#top", "官方公告",
                        null, "official", "发布模型", "原文摘录", 0.9D))));

        ArgumentCaptor<AiNewsEventEntity> eventCaptor = ArgumentCaptor.forClass(AiNewsEventEntity.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(42L, eventCaptor.getValue().getWorkspaceId());
        assertEquals("candidate", eventCaptor.getValue().getStatus());

        ArgumentCaptor<AiNewsEvidenceEntity> evidenceCaptor = ArgumentCaptor.forClass(AiNewsEvidenceEntity.class);
        verify(evidenceMapper).insert(evidenceCaptor.capture());
        assertEquals(42L, evidenceCaptor.getValue().getWorkspaceId());
        assertEquals("https://deepseek.com/news?utm=1", evidenceCaptor.getValue().getSourceUrl());
        assertEquals(64, evidenceCaptor.getValue().getSourceUrlHash().length());
        assertEquals("unknown", evidenceCaptor.getValue().getSemanticRelation());
        assertEquals("UNKNOWN", evidenceCaptor.getValue().getRelationOrigin());
    }

    @Test
    void sameUrlDifferentAtomicPacketDoesNotOverwriteLegacyEvidence() {
        AiNewsEventEntity event = event(502L, 42L, "candidate");
        when(eventMapper.selectOne(any())).thenReturn(event);
        when(evidenceMapper.selectOne(any())).thenReturn(null);

        AiNewsEvidenceEntity legacy = new AiNewsEvidenceEntity();
        legacy.setId(602L);
        legacy.setEventId(502L);
        legacy.setWorkspaceId(42L);
        legacy.setSourceUrl("https://example.com/story");
        legacy.setClaim("旧原子事实");
        legacy.setQuote("旧逐字引文");
        legacy.setSourceTier("media");
        legacy.setSourceCaptureId(null);
        legacy.setEvidenceIdentityHash(null);
        legacy.setDeleted(0);
        when(evidenceMapper.selectList(any())).thenReturn(List.of(legacy));
        doAnswer(invocation -> {
            AiNewsEvidenceEntity row = invocation.getArgument(0);
            row.setId(603L);
            return 1;
        }).when(evidenceMapper).insert(any(AiNewsEvidenceEntity.class));

        service.upsert(42L, new AiNewsEventUpsertRequest(
                "event-502", "同一文章的另一条事实", "摘要", "model", List.of(),
                null, null, List.of("新原子事实"), List.of(),
                List.of(new AiNewsEvidenceRequest(
                        "https://example.com/story", "文章", null, "media",
                        "新原子事实", "新逐字引文", 0.8D))));

        ArgumentCaptor<AiNewsEvidenceEntity> inserted =
                ArgumentCaptor.forClass(AiNewsEvidenceEntity.class);
        verify(evidenceMapper).insert(inserted.capture());
        assertEquals("新原子事实", inserted.getValue().getClaim());
        assertEquals("新逐字引文", inserted.getValue().getQuote());
        assertTrue(inserted.getValue().getEvidenceIdentityHash() != null
                && inserted.getValue().getEvidenceIdentityHash().length() == 64);
        verify(evidenceMapper, never()).updateById(legacy);
        assertEquals("旧原子事实", legacy.getClaim());
        assertEquals("旧逐字引文", legacy.getQuote());
    }

    @Test
    void strictAgentUpsertDerivesUrlTimestampAndProvenanceFromBoundCapture() {
        AiNewsSourceCaptureService captures = org.mockito.Mockito.mock(AiNewsSourceCaptureService.class);
        service.setSourceCaptureService(captures);
        when(eventMapper.selectOne(any())).thenReturn(null);
        when(evidenceMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            AiNewsEventEntity row = invocation.getArgument(0);
            row.setId(701L);
            return 1;
        }).when(eventMapper).insert(any(AiNewsEventEntity.class));
        doAnswer(invocation -> {
            AiNewsEvidenceEntity row = invocation.getArgument(0);
            row.setId(702L);
            return 1;
        }).when(evidenceMapper).insert(any(AiNewsEvidenceEntity.class));

        String quote = "OpenAI released Model X to developers worldwide.";
        AiNewsSourceCaptureEntity capture = new AiNewsSourceCaptureEntity();
        capture.setId(900L);
        capture.setWorkspaceId(42L);
        capture.setFinalUrl("https://openai.com/index/model-x");
        capture.setSourceTitle("Model X official release");
        capture.setSourcePublishedAt(LocalDateTime.of(2026, 8, 26, 4, 30));
        capture.setSourceTier("official");
        capture.setFetchedAt(LocalDateTime.of(2026, 8, 26, 5, 0));
        capture.setContentHash("c".repeat(64));
        capture.setHttpStatus(200);
        capture.setCaptureMethod("READ_ONLY_HTTP");
        capture.setRedirectChainJson("[]");
        when(captures.bind(eq(42L), eq(900L), eq(quote), any(), any())).thenReturn(
                new AiNewsSourceCaptureService.BoundCapture(
                        capture, quote, 8, 8 + quote.length(), "NORMALIZED_EXACT"));

        service.upsertCaptured(42L, new AiNewsEventUpsertRequest(
                        null, "Model X 发布", "摘要", "model", List.of("OpenAI"),
                        null, LocalDateTime.of(2000, 1, 1, 0, 0), List.of(quote), List.of(),
                        List.of(new AiNewsEvidenceRequest(
                                "https://attacker.invalid/fake", "伪造标题", null, "community",
                                quote, quote, 0.9D, "entails", 0.95D, 900L))),
                Instant.parse("2026-08-26T03:00:00Z"),
                Instant.parse("2026-08-26T05:00:00Z"));

        ArgumentCaptor<AiNewsEventEntity> eventCaptor =
                ArgumentCaptor.forClass(AiNewsEventEntity.class);
        verify(eventMapper).insert(eventCaptor.capture());
        assertEquals(LocalDateTime.of(2026, 8, 26, 4, 30),
                eventCaptor.getValue().getSourcePublishedAt());
        assertNull(eventCaptor.getValue().getPublishedAt());

        ArgumentCaptor<AiNewsEvidenceEntity> evidenceCaptor =
                ArgumentCaptor.forClass(AiNewsEvidenceEntity.class);
        verify(evidenceMapper).insert(evidenceCaptor.capture());
        AiNewsEvidenceEntity stored = evidenceCaptor.getValue();
        assertEquals("https://openai.com/index/model-x", stored.getSourceUrl());
        assertEquals("Model X official release", stored.getSourceTitle());
        assertEquals(LocalDateTime.of(2026, 8, 26, 4, 30), stored.getSourcePublishedAt());
        assertEquals(900L, stored.getSourceCaptureId());
        assertEquals("c".repeat(64), stored.getContentHash());
        assertEquals("NORMALIZED_EXACT", stored.getQuoteMatchMethod());
    }

    @Test
    void strictAgentUpsertRejectsMissingFrozenWindowAtServiceBoundary() {
        NewsClawException rejected = assertThrows(NewsClawException.class,
                () -> service.upsertCaptured(42L, null, null, null));

        assertEquals(400, rejected.getCode());
        assertTrue(rejected.getMessage().contains("windowStart/windowEnd"));
    }

    @Test
    void officialSourceWithoutSemanticAssessmentFailsClosed() {
        AiNewsEventEntity event = event(130L, 7L, "candidate");
        when(eventMapper.selectOne(any())).thenReturn(event);
        AiNewsEvidenceEntity official = evidence(230L, 130L, 7L, "official", "openai.com");
        official.setSemanticRelation("unknown");
        official.setRelationOrigin("UNKNOWN");
        when(evidenceMapper.selectList(any())).thenReturn(List.of(official));

        NewsClawException rejected = assertThrows(NewsClawException.class,
                () -> service.verify(7L, 130L, null, null));

        assertEquals(409, rejected.getCode());
        assertTrue(rejected.getMessage().contains("语义关系判定"));
        verify(evidenceMapper, never()).updateById(any(AiNewsEvidenceEntity.class));
    }

    @Test
    void onlyEntailingEvidenceIsMarkedVerified() {
        AiNewsEventEntity event = event(131L, 7L, "candidate");
        when(eventMapper.selectOne(any())).thenReturn(event);
        AiNewsEvidenceEntity support = evidence(231L, 131L, 7L, "official", "openai.com");
        AiNewsEvidenceEntity distractor = evidence(232L, 131L, 7L, "media", "reuters.com");
        distractor.setSemanticRelation("unrelated");
        when(evidenceMapper.selectList(any())).thenReturn(List.of(support, distractor));

        service.verify(7L, 131L, null, null);

        assertTrue(support.getVerified());
        assertEquals(false, distractor.getVerified());
        verify(evidenceMapper).updateById(support);
    }

    @Test
    void authenticatedHumanRelationReviewInvalidatesPriorVerification() {
        AiNewsEventEntity event = event(132L, 7L, "verified");
        AiNewsEvidenceEntity evidence = evidence(233L, 132L, 7L, "official", "openai.com");
        evidence.setVerified(true);
        when(eventMapper.selectOne(any())).thenReturn(event);
        when(evidenceMapper.selectOne(any())).thenReturn(evidence);

        AiNewsEvidenceEntity reviewed = service.reviewEvidenceRelation(
                7L, 132L, 233L, "partial", 0.95D, "reviewer@example.com", "限定条件未完全覆盖");

        assertEquals("partial", reviewed.getSemanticRelation());
        assertEquals("HUMAN", reviewed.getRelationOrigin());
        assertEquals(false, reviewed.getVerified());
        assertEquals("researching", event.getStatus());
        verify(evidenceMapper).updateById(evidence);
        verify(eventMapper).updateById(event);
    }

    @Test
    void windowSummaryUsesPersistedRowsInsteadOfModelReportedCounts() {
        AiNewsEventEntity official = event(140L, 7L, "verified");
        official.setCategory("product");
        official.setSourcePublishedAt(LocalDateTime.of(2026, 8, 26, 6, 0));
        AiNewsEventEntity media = event(141L, 7L, "candidate");
        media.setCategory("funding");
        media.setSourcePublishedAt(LocalDateTime.of(2026, 8, 26, 7, 0));
        media.setReviewRequired(true);
        media.setReviewReasons(List.of("UNATTESTED_SEMANTIC_ASSESSMENT"));
        when(eventMapper.selectList(any())).thenReturn(List.of(official, media));
        AiNewsEvidenceEntity officialEvidence = evidence(
                240L, 140L, 7L, "official", "openai.com");
        AiNewsEvidenceEntity mediaEvidence = evidence(
                241L, 141L, 7L, "media", "reuters.com");
        mediaEvidence.setRelationOrigin(AiNewsRelationAttestation.MODEL);
        when(evidenceMapper.selectList(any())).thenReturn(List.of(officialEvidence, mediaEvidence));

        AiNewsEventService.WindowSummary summary = service.summarizeWindow(7L,
                Instant.parse("2026-08-26T03:15:40Z"),
                Instant.parse("2026-08-27T03:15:40Z"));

        assertEquals(2, summary.persistedEventCount());
        assertEquals(1L, summary.verifiedEventCount());
        assertEquals(1L, summary.officialSourceEventCount());
        assertEquals(1L, summary.trustedMediaEventCount());
        assertEquals(1L, summary.attestedSupportEventCount());
        assertEquals(2L, summary.captureBoundEvidenceCount());
        assertEquals(1L, summary.pendingReviewEventCount());
        assertEquals(1L, summary.categoryCounts().get("funding"));
        assertTrue(summary.scopeNote().contains("rejected writes"));
    }

    private static AiNewsEventEntity event(long id, long workspaceId, String status) {
        AiNewsEventEntity event = new AiNewsEventEntity();
        event.setId(id);
        event.setWorkspaceId(workspaceId);
        event.setStatus(status);
        event.setDeleted(0);
        event.setConflictsJson("[]");
        return event;
    }

    private static AiNewsEvidenceEntity evidence(long id, long eventId, long workspaceId,
                                                  String tier, String host) {
        AiNewsEvidenceEntity evidence = new AiNewsEvidenceEntity();
        evidence.setId(id);
        evidence.setEventId(eventId);
        evidence.setWorkspaceId(workspaceId);
        evidence.setSourceTier(tier);
        evidence.setSourceUrl("https://" + host + "/story");
        evidence.setClaim("supports claim");
        evidence.setQuote("supports claim");
        evidence.setConfidence(0.8D);
        evidence.setSemanticRelation(AiNewsEvidenceRelation.ENTAILS.token());
        evidence.setRelationConfidence(0.9D);
        evidence.setRelationOrigin(AiNewsRelationAttestation.DETERMINISTIC_EXTRACTIVE);
        evidence.setSourceCaptureId(id + 10_000L);
        evidence.setSourcePublishedAt(LocalDateTime.of(2026, 8, 26, 4, 30));
        evidence.setFinalUrl(evidence.getSourceUrl());
        evidence.setFetchedAt(LocalDateTime.of(2026, 8, 26, 5, 0));
        evidence.setContentHash("a".repeat(64));
        evidence.setHttpStatus(200);
        evidence.setCaptureMethod("READ_ONLY_HTTP");
        evidence.setQuoteStart(0);
        evidence.setQuoteEnd(evidence.getQuote().length());
        evidence.setQuoteMatchMethod("NORMALIZED_EXACT");
        evidence.setVerified(false);
        evidence.setDeleted(0);
        return evidence;
    }
}
