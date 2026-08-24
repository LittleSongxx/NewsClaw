package vip.mate.news.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.exception.MateClawException;
import vip.mate.news.model.AiNewsEvidenceEntity;
import vip.mate.news.model.AiNewsEvidenceRequest;
import vip.mate.news.model.AiNewsEventEntity;
import vip.mate.news.model.AiNewsEventStatus;
import vip.mate.news.model.AiNewsEventUpsertRequest;
import vip.mate.news.repository.AiNewsEvidenceMapper;
import vip.mate.news.repository.AiNewsEventMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
        MateClawException missingTitle = assertThrows(MateClawException.class,
                () -> service.upsert(7L, new AiNewsEventUpsertRequest(null, " ", null,
                        "model", List.of(), null, null, List.of(), List.of(), List.of())));
        assertEquals(400, missingTitle.getCode());

        MateClawException missingSource = assertThrows(MateClawException.class,
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
        MateClawException insufficient = assertThrows(MateClawException.class,
                () -> service.verify(7L, 101L, null, null));
        assertEquals(409, insufficient.getCode());
        assertEquals("candidate", event.getStatus());

        AiNewsEvidenceEntity second = evidence(202L, 101L, 7L, "media", "wire.example.org");
        when(evidenceMapper.selectList(any())).thenReturn(List.of(one, second));
        MateClawException arbitrarySources = assertThrows(MateClawException.class,
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
    void officialLabelOnUnknownDomain_doesNotBypassCorroborationRule() {
        AiNewsEventEntity event = event(105L, 7L, "candidate");
        when(eventMapper.selectOne(any())).thenReturn(event);
        when(evidenceMapper.selectList(any())).thenReturn(List.of(
                evidence(205L, 105L, 7L, "official", "example.net")));

        MateClawException rejected = assertThrows(MateClawException.class,
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
        when(eventMapper.selectOne(any())).thenReturn(event);

        MateClawException missingArtifact = assertThrows(MateClawException.class,
                () -> service.markPublished(7L, 107L));
        assertEquals(409, missingArtifact.getCode());

        event.setGzhContentItemId(7001L);
        AiNewsEventEntity published = service.markPublished(7L, 107L);
        assertEquals(AiNewsEventStatus.PUBLISHED.token(), published.getStatus());
        assertTrue(published.getPublishedAt() != null);

        assertEquals(published, service.markPublished(7L, 107L));
    }

    @Test
    void publishCannotBypassProductionStateEvenWithDeliveryArtifact() {
        AiNewsEventEntity event = event(108L, 7L, "candidate");
        event.setGzhContentItemId(8001L);
        when(eventMapper.selectOne(any())).thenReturn(event);

        MateClawException rejected = assertThrows(MateClawException.class,
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

        MateClawException conflict = assertThrows(MateClawException.class,
                () -> service.verify(7L, 103L, null, null));
        assertEquals(409, conflict.getCode());
        assertEquals("candidate", event.getStatus());

        MateClawException production = assertThrows(MateClawException.class,
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
        evidence.setConfidence(0.8D);
        evidence.setVerified(false);
        evidence.setDeleted(0);
        return evidence;
    }
}
