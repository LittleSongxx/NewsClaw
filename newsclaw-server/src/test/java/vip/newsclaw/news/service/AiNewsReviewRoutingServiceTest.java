package vip.newsclaw.news.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.news.model.AiNewsCaptureAttemptEntity;
import vip.newsclaw.news.model.AiNewsEvidenceEntity;
import vip.newsclaw.news.model.AiNewsEventEntity;
import vip.newsclaw.news.model.AiNewsReviewTaskEntity;
import vip.newsclaw.news.model.AiNewsReviewTaskStatus;
import vip.newsclaw.news.repository.AiNewsCaptureAttemptMapper;
import vip.newsclaw.news.repository.AiNewsEvidenceMapper;
import vip.newsclaw.news.repository.AiNewsEventMapper;
import vip.newsclaw.news.repository.AiNewsReviewTaskMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiNewsReviewRoutingServiceTest {

    @Mock
    private AiNewsReviewTaskMapper taskMapper;
    @Mock
    private AiNewsEventMapper eventMapper;
    @Mock
    private AiNewsEvidenceMapper evidenceMapper;
    @Mock
    private AiNewsCaptureAttemptMapper captureAttemptMapper;

    private AiNewsReviewRoutingService service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        service = new AiNewsReviewRoutingService(taskMapper, eventMapper, evidenceMapper,
                captureAttemptMapper,
                new AiNewsReviewPolicy(objectMapper, new AiNewsSourceRegistry()), objectMapper);
    }

    @Test
    void createsPendingTaskAndProjectsDeterministicReasons() {
        when(taskMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            AiNewsReviewTaskEntity task = invocation.getArgument(0);
            task.setId(9001L);
            return 1;
        }).when(taskMapper).insert(any(AiNewsReviewTaskEntity.class));
        AiNewsEventEntity event = event("verified");

        AiNewsReviewTaskEntity task = service.sync(event,
                List.of(official(false, "quote")), List.of());

        assertEquals(AiNewsReviewTaskStatus.PENDING.name(), task.getStatus());
        assertEquals(AiNewsReviewPolicy.VERSION, task.getPolicyVersion());
        assertEquals("DETERMINISTIC_POLICY", task.getRouteSource());
        assertEquals(64, task.getRiskFingerprint().length());
        assertTrue(task.getReasonsJson().contains("UNCAPTURED_OFFICIAL_SOURCE"));
        assertTrue(Boolean.TRUE.equals(event.getReviewRequired()));
        assertEquals(9001L, event.getReviewTaskId());
    }

    @Test
    void resolvedTaskStaysResolvedUntilEvidenceFingerprintChangesThenReopens() {
        AiNewsEventEntity event = event("verified");
        AiNewsEvidenceEntity initial = official(false, "quote-v1");
        AiNewsReviewPolicy policy = new AiNewsReviewPolicy(new ObjectMapper(), new AiNewsSourceRegistry());
        AiNewsReviewTaskEntity resolved = task(AiNewsReviewTaskStatus.RESOLVED,
                policy.evaluate(event, List.of(initial), List.of()).fingerprint());
        when(taskMapper.selectOne(any())).thenReturn(resolved);

        AiNewsReviewTaskEntity unchanged = service.sync(event, List.of(initial), List.of());
        assertEquals(AiNewsReviewTaskStatus.RESOLVED.name(), unchanged.getStatus());
        verify(taskMapper, never()).updateById(any(AiNewsReviewTaskEntity.class));

        AiNewsEvidenceEntity changed = official(false, "quote-v2");
        AiNewsReviewTaskEntity reopened = service.sync(event, List.of(changed), List.of());
        assertEquals(AiNewsReviewTaskStatus.PENDING.name(), reopened.getStatus());
        assertEquals(null, reopened.getResolvedAt());
        assertEquals(null, reopened.getResolvedBy());
        verify(taskMapper).updateById(resolved);
    }

    @Test
    void productionGateRecomputesCurrentInputsAndRejectsPendingRisk() {
        AiNewsEventEntity event = event("verified");
        when(evidenceMapper.selectList(any())).thenReturn(List.of(official(false, "quote")));
        when(captureAttemptMapper.selectList(any())).thenReturn(List.of());
        when(taskMapper.selectOne(any())).thenReturn(task(AiNewsReviewTaskStatus.PENDING, "stale"));

        NewsClawException rejected = assertThrows(NewsClawException.class,
                () -> service.requireClearForProduction(event));

        assertEquals(409, rejected.getCode());
        assertTrue(rejected.getMessage().contains("UNCAPTURED_OFFICIAL_SOURCE"));
    }

    @Test
    void resolveRequiresVerifiedEventAndExplicitOperatorConclusion() {
        AiNewsEventEntity candidate = event("candidate");
        AiNewsReviewTaskEntity pending = task(AiNewsReviewTaskStatus.PENDING, "old");
        when(eventMapper.selectOne(any())).thenReturn(candidate);
        when(evidenceMapper.selectList(any())).thenReturn(List.of(official(false, "quote")));
        when(captureAttemptMapper.selectList(any())).thenReturn(List.of());
        when(taskMapper.selectOne(any())).thenReturn(pending);

        NewsClawException rejected = assertThrows(NewsClawException.class,
                () -> service.resolve(7L, 101L, "reviewer", "checked source"));
        assertEquals(409, rejected.getCode());

        candidate.setStatus("verified");
        NewsClawException missingOperator = assertThrows(NewsClawException.class,
                () -> service.resolve(7L, 101L, " ", "checked source"));
        assertEquals(400, missingOperator.getCode());
        NewsClawException missingConclusion = assertThrows(NewsClawException.class,
                () -> service.resolve(7L, 101L, "reviewer", " "));
        assertEquals(400, missingConclusion.getCode());

        AiNewsReviewTaskEntity resolved = service.resolve(7L, 101L, "reviewer", "checked source");
        assertEquals(AiNewsReviewTaskStatus.RESOLVED.name(), resolved.getStatus());
        assertEquals("reviewer", resolved.getResolvedBy());
        assertEquals("checked source", resolved.getResolutionNote());
        assertNotNull(resolved.getResolvedAt());
    }

    @Test
    void terminalEventClosesStalePendingTask() {
        AiNewsEventEntity archived = event("archived");
        AiNewsReviewTaskEntity pending = task(AiNewsReviewTaskStatus.PENDING, "old");
        when(taskMapper.selectOne(any())).thenReturn(pending);

        AiNewsReviewTaskEntity closed = service.sync(archived, List.of(), List.of());

        assertEquals(AiNewsReviewTaskStatus.NO_LONGER_REQUIRED.name(), closed.getStatus());
        ArgumentCaptor<AiNewsReviewTaskEntity> updated = ArgumentCaptor.forClass(AiNewsReviewTaskEntity.class);
        verify(taskMapper).updateById(updated.capture());
        assertEquals("[]", updated.getValue().getReasonsJson());
    }

    private static AiNewsEventEntity event(String status) {
        AiNewsEventEntity event = new AiNewsEventEntity();
        event.setId(101L);
        event.setWorkspaceId(7L);
        event.setTitle("OpenAI release");
        event.setStatus(status);
        event.setConflictsJson("[]");
        event.setDeleted(0);
        return event;
    }

    private static AiNewsEvidenceEntity official(boolean captured, String quote) {
        AiNewsEvidenceEntity evidence = new AiNewsEvidenceEntity();
        evidence.setId(201L);
        evidence.setEventId(101L);
        evidence.setWorkspaceId(7L);
        evidence.setSourceTier("official");
        evidence.setSourceUrl("https://openai.com/news/model");
        evidence.setClaim("released model");
        evidence.setQuote(quote);
        evidence.setDeleted(0);
        if (captured) {
            evidence.setFinalUrl(evidence.getSourceUrl());
            evidence.setFetchedAt(LocalDateTime.of(2026, 8, 25, 8, 0));
            evidence.setContentHash("b".repeat(64));
            evidence.setHttpStatus(200);
            evidence.setCaptureMethod("READ_ONLY_HTTP");
        }
        return evidence;
    }

    private static AiNewsReviewTaskEntity task(AiNewsReviewTaskStatus status, String fingerprint) {
        AiNewsReviewTaskEntity task = new AiNewsReviewTaskEntity();
        task.setId(9001L);
        task.setWorkspaceId(7L);
        task.setEventId(101L);
        task.setStatus(status.name());
        task.setReasonsJson("[\"UNCAPTURED_OFFICIAL_SOURCE\"]");
        task.setPolicyVersion(AiNewsReviewPolicy.VERSION);
        task.setRiskFingerprint(fingerprint);
        task.setRouteSource("DETERMINISTIC_POLICY");
        task.setResolvedAt(LocalDateTime.of(2026, 8, 25, 9, 0));
        task.setResolvedBy("reviewer");
        task.setResolutionNote("checked");
        task.setDeleted(0);
        return task;
    }
}
