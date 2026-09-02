package vip.newsclaw.news.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import vip.newsclaw.news.model.AiNewsCandidateEntity;
import vip.newsclaw.news.model.AiNewsCandidateObservationEntity;
import vip.newsclaw.news.model.AiNewsScanRunEntity;
import vip.newsclaw.news.repository.AiNewsCandidateMapper;
import vip.newsclaw.news.repository.AiNewsCandidateObservationMapper;
import vip.newsclaw.news.repository.AiNewsScanRunMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:ai_news_candidate_pipeline;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "newsclaw.ai-news.ingestion.enabled=false",
        "newsclaw.ai-news.candidate-pipeline.enabled=false",
        "newsclaw.ai-news.candidate-pipeline.capture-enabled=true"
})
@Transactional
class AiNewsCandidatePipelineIntegrationTest {

    @Autowired
    private AiNewsCandidatePipelineService service;
    @Autowired
    private AiNewsCandidateMapper candidateMapper;
    @Autowired
    private AiNewsCandidateObservationMapper observationMapper;
    @Autowired
    private AiNewsScanRunMapper scanMapper;
    @Autowired
    private AiNewsCandidatePipelineProperties pipelineProperties;

    @Test
    void identicalScanIdempotencyKeyReusesOneRun() {
        Instant start = Instant.parse("2026-08-27T00:00:00Z");
        Instant end = Instant.parse("2026-08-29T00:00:00Z");

        var first = service.startOrReuseScan(6L, "manual", "AI", start, end,
                "test-v1", "a".repeat(64));
        var second = service.startOrReuseScan(6L, "agent", "AI", start, end,
                "test-v1", "a".repeat(64));

        assertFalse(first.reused());
        assertTrue(second.reused());
        assertEquals(first.run().getId(), second.run().getId());
        assertEquals(1L, scanMapper.selectCount(new LambdaQueryWrapper<AiNewsScanRunEntity>()
                .eq(AiNewsScanRunEntity::getWorkspaceId, 6L)));
    }

    @Test
    void workspaceCannotStartTwoDifferentOrchestratedScansConcurrently() {
        Instant start = Instant.parse("2026-08-27T00:00:00Z");
        Instant end = Instant.parse("2026-08-29T00:00:00Z");
        service.startOrReuseScan(61L, "manual", "AI", start, end,
                "test-v1", "b".repeat(64));

        vip.newsclaw.exception.NewsClawException error = assertThrows(
                vip.newsclaw.exception.NewsClawException.class,
                () -> service.startOrReuseScan(61L, "agent", "AI", start.minusSeconds(60), end,
                        "test-v1", "c".repeat(64)));

        assertEquals(409, error.getCode());
        assertEquals(1L, scanMapper.selectCount(new LambdaQueryWrapper<AiNewsScanRunEntity>()
                .eq(AiNewsScanRunEntity::getWorkspaceId, 61L)));
    }

    @Test
    void persistsEveryValidObservationIdempotentlyAndReportsProviderMarginalYield() {
        Instant start = Instant.parse("2026-08-27T00:00:00Z");
        Instant end = Instant.parse("2026-08-29T00:00:00Z");
        AiNewsScanRunEntity run = service.startScan(7L, "test", "AI", start, end, "test-v1");
        AiNewsDiscoverySearchService.DiscoveryBatch batch = batch(start, end);

        var first = service.persistDiscovery(run.getId(), batch);
        var repeated = service.persistDiscovery(run.getId(), batch);

        assertEquals(4, first.rawResultCount());
        assertEquals(1, first.invalidResultCount());
        assertEquals(2, first.uniqueCandidateCount());
        assertEquals(2, first.selectedCandidateCount());
        assertEquals(first, repeated);
        assertEquals(2L, candidateMapper.selectCount(new LambdaQueryWrapper<>()));
        assertEquals(3L, observationMapper.selectCount(new LambdaQueryWrapper<>()));

        List<AiNewsCandidateEntity> candidates = candidateMapper.selectList(
                new LambdaQueryWrapper<AiNewsCandidateEntity>().orderByAsc(AiNewsCandidateEntity::getCanonicalUrl));
        assertTrue(candidates.stream().allMatch(item -> "SELECTED".equals(item.getSelectionStatus())));
        assertTrue(candidates.stream().allMatch(item -> "PENDING".equals(item.getCaptureStatus())));
        assertTrue(candidates.stream().allMatch(item -> item.getScanRunId().equals(run.getId())));
        assertTrue(candidates.stream().allMatch(item -> item.getStoryId() != null));

        var summary = service.inspectRun(7L, run.getId());
        assertEquals(2, summary.providers().size());
        var bocha = summary.providers().stream()
                .filter(item -> "bocha-web".equals(item.providerId())).findFirst().orElseThrow();
        assertEquals(2, bocha.candidateCount());
        assertEquals(1, bocha.marginalUniqueCount());
        assertEquals(2, summary.scorecard().usableCaptureRate().denominator());
        assertEquals(0.0D, summary.scorecard().usableCaptureRate().rate());
        assertEquals(2L, service.candidates(7L, 1, 20, run.getId(), null,
                null, null, null, null, end.minusSeconds(120), end).getTotal());
        assertEquals(0L, service.candidates(7L, 1, 20, run.getId(), null,
                null, null, null, null, end, end.plusSeconds(60)).getTotal());
        assertEquals(1L, service.candidates(7L, 1, 20, run.getId(), "bocha-web",
                null, null, null, true, null, null).getTotal());

        AiNewsScanRunEntity nextRun = service.startScan(7L, "test", "AI", start, end, "test-v1");
        service.persistDiscovery(nextRun.getId(), batch);
        assertEquals(4L, candidateMapper.selectCount(new LambdaQueryWrapper<>()));
        assertEquals(6L, observationMapper.selectCount(new LambdaQueryWrapper<>()));
        List<AiNewsCandidateEntity> allRuns = candidateMapper.selectList(new LambdaQueryWrapper<>());
        assertEquals(2L, allRuns.stream()
                .filter(item -> run.getId().equals(item.getScanRunId())).count());
        assertEquals(2L, allRuns.stream()
                .filter(item -> nextRun.getId().equals(item.getScanRunId())).count());
        assertTrue(allRuns.stream().collect(java.util.stream.Collectors.groupingBy(
                        AiNewsCandidateEntity::getCanonicalUrl)).values().stream()
                .allMatch(rows -> rows.stream().map(AiNewsCandidateEntity::getStoryId)
                        .distinct().count() == 1),
                "the same story URL must retain one cross-run identity");
    }

    @Test
    void selectionFollowsFusedUrlAliasAcrossRawObservations() {
        Instant start = Instant.parse("2026-08-27T00:00:00Z");
        Instant end = Instant.parse("2026-08-29T00:00:00Z");
        AiNewsScanRunEntity run = service.startScan(8L, "test", "AI", start, end, "test-v1");
        String selectedUrl = "https://www.example.com/2026/08/28/alias-story";
        String observedAlias = "http://wap.example.com/2026/08/28/alias-story?utm_source=feed";
        var snapshots = List.of(
                snapshot("alias_lane", "tavily", List.of(
                        row(1, "Alias story", observedAlias, "tavily", 0.9),
                        row(2, "Alias story mirror",
                                "https://www.example.com/2026/08/28/alias-story", "tavily", 0.8))));
        var selected = List.of(candidate(1, "Alias story", selectedUrl, "alias_lane", 0.9));
        var batch = new AiNewsDiscoverySearchService.DiscoveryBatch(
                "untrusted_fused_news_candidates", false, start.toString(), end.toString(),
                1, 1, selected, List.of(), 0, "candidate hints", end.toString(),
                "test-policy", "snapshot-hash", "ranking-hash", Map.of(), snapshots,
                501L, true);

        service.persistDiscovery(run.getId(), batch);

        List<AiNewsCandidateEntity> candidates = candidateMapper.selectList(
                new LambdaQueryWrapper<AiNewsCandidateEntity>());
        assertEquals(1, candidates.size());
        assertEquals("SELECTED", candidates.getFirst().getSelectionStatus());
        assertEquals("PENDING", candidates.getFirst().getCaptureStatus());
        assertEquals(1L, observationMapper.selectSelectedCandidateIds(run.getId()).size());
        assertEquals(2L, observationMapper.selectCount(
                new LambdaQueryWrapper<AiNewsCandidateObservationEntity>()));
    }

    @Test
    void laterSelectedObservationDoesNotOverwriteTheBetterRepresentative() {
        Instant start = Instant.parse("2026-08-27T00:00:00Z");
        Instant end = Instant.parse("2026-08-29T00:00:00Z");
        AiNewsScanRunEntity run = service.startScan(82L, "test", "AI", start, end, "test-v1");
        String url = "https://example.com/2026/08/28/best-story";
        var snapshots = List.of(snapshot("first_lane", "tavily", List.of(
                        row(1, "Best representative", url, "tavily", 0.95))),
                snapshot("later_lane", "bocha-web", List.of(
                        row(9, "Worse later alias", url, "bocha-web", 0.40))));
        var selected = List.of(candidate(1, "Best representative", url, "first_lane", 0.95));
        var batch = new AiNewsDiscoverySearchService.DiscoveryBatch(
                "untrusted_fused_news_candidates", false, start.toString(), end.toString(),
                2, 1, selected, List.of(), 0, "candidate hints", end.toString(),
                "test-policy", "snapshot-hash", "ranking-hash", Map.of(), snapshots,
                502L, true);

        service.persistDiscovery(run.getId(), batch);

        AiNewsCandidateEntity stored = candidateMapper.selectOne(
                new LambdaQueryWrapper<AiNewsCandidateEntity>()
                        .eq(AiNewsCandidateEntity::getScanRunId, run.getId()));
        assertEquals("Best representative", stored.getTitle());
        assertEquals("tavily", stored.getProviderId());
        assertEquals(1, stored.getProviderRank());
    }

    @Test
    void candidateStateIsIsolatedWhenTheSameUrlAppearsInLaterRun() {
        Instant start = Instant.parse("2026-08-27T00:00:00Z");
        Instant end = Instant.parse("2026-08-29T00:00:00Z");
        AiNewsScanRunEntity firstRun = service.startScan(81L, "test", "AI", start, end, "test-v1");
        service.persistDiscovery(firstRun.getId(), batch(start, end));
        List<AiNewsCandidateEntity> firstRows = candidateMapper.selectList(
                new LambdaQueryWrapper<AiNewsCandidateEntity>()
                        .eq(AiNewsCandidateEntity::getScanRunId, firstRun.getId()));
        assertEquals(2, firstRows.size());
        AiNewsCandidateEntity first = firstRows.getFirst();
        assertTrue(service.claimCapture(first.getId()));
        service.captureSucceeded(first.getId(), 81001L);
        service.review(81L, first.getId(), "REJECTED", "run-one decision");

        AiNewsScanRunEntity secondRun = service.startScan(81L, "test", "AI", start, end, "test-v1");
        service.persistDiscovery(secondRun.getId(), batch(start, end));
        List<AiNewsCandidateEntity> secondRows = candidateMapper.selectList(
                new LambdaQueryWrapper<AiNewsCandidateEntity>()
                        .eq(AiNewsCandidateEntity::getScanRunId, secondRun.getId()));
        assertEquals(2, secondRows.size());
        assertTrue(secondRows.stream().noneMatch(item -> first.getId().equals(item.getId())));
        assertTrue(secondRows.stream().allMatch(item -> "PENDING".equals(item.getCaptureStatus())));
        assertTrue(secondRows.stream().allMatch(item -> "PENDING".equals(item.getReviewStatus())));

        AiNewsCandidateEntity firstAfter = candidateMapper.selectById(first.getId());
        assertEquals(firstRun.getId(), firstAfter.getScanRunId());
        assertEquals("SUCCESS", firstAfter.getCaptureStatus());
        assertEquals("REJECTED", firstAfter.getReviewStatus());
        assertEquals(4L, candidateMapper.selectCount(new LambdaQueryWrapper<>()));
    }

    @Test
    void ownsCaptureAndReviewStateTransitionsWithoutAgentIds() {
        Instant start = Instant.parse("2026-08-27T00:00:00Z");
        Instant end = Instant.parse("2026-08-29T00:00:00Z");
        AiNewsScanRunEntity run = service.startScan(9L, "test", "AI", start, end, "test-v1");
        service.persistDiscovery(run.getId(), batch(start, end));
        AiNewsCandidateEntity candidate = service.captureQueue(run.getId(), 1).getFirst();

        assertTrue(service.claimCapture(candidate.getId()));
        assertFalse(service.claimCapture(candidate.getId()));
        service.captureFailed(candidate.getId(), "temporary timeout", true, 3, Duration.ofMinutes(1));
        AiNewsCandidateEntity retryable = candidateMapper.selectById(candidate.getId());
        assertEquals("RETRYABLE", retryable.getCaptureStatus());
        assertNotNull(retryable.getNextCaptureAt());

        retryable.setNextCaptureAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
                .minusMinutes(1));
        candidateMapper.updateById(retryable);
        assertTrue(service.captureQueue(null, 10).stream()
                .anyMatch(item -> candidate.getId().equals(item.getId())));
        assertTrue(service.claimCapture(candidate.getId()));

        service.completeScan(run.getId());
        service.captureSucceeded(candidate.getId(), 12345L);
        AiNewsCandidateEntity captured = candidateMapper.selectById(candidate.getId());
        assertEquals("SUCCESS", captured.getCaptureStatus());
        assertEquals("USABLE", captured.getNormalizationStatus());
        assertEquals(12345L, captured.getCaptureId());

        // A run remains CAPTURE_PENDING until every selected candidate drains
        // the queue; completing only the first row must not close its window.
        assertEquals("CAPTURE_PENDING", scanMapper.selectById(run.getId()).getRunStatus());
        AiNewsCandidateEntity remaining = service.captureQueue(run.getId(), 10).stream()
                .filter(item -> !candidate.getId().equals(item.getId())).findFirst().orElseThrow();
        assertTrue(service.claimCapture(remaining.getId()));
        service.captureSucceeded(remaining.getId(), 12346L);

        service.review(9L, candidate.getId(), "accepted", "editor approved");
        AiNewsScanRunEntity completed = scanMapper.selectById(run.getId());
        assertEquals("COMPLETED", completed.getRunStatus());
        assertEquals(2, completed.getCaptureSuccessCount());
        assertEquals(1, completed.getReviewedCount());
        assertEquals(1, completed.getAcceptedCount());
    }

    @Test
    void lateCaptureCompletionCannotOverwriteAReclaimedAttempt() {
        Instant start = Instant.parse("2026-08-27T00:00:00Z");
        Instant end = Instant.parse("2026-08-29T00:00:00Z");
        AiNewsScanRunEntity run = service.startScan(12L, "test", "AI", start, end, "test-v1");
        service.persistDiscovery(run.getId(), batch(start, end));
        AiNewsCandidateEntity candidate = service.captureQueue(run.getId(), 1).getFirst();

        AiNewsCandidatePipelineService.CaptureLease first =
                service.claimCaptureLease(candidate.getId());
        assertNotNull(first);
        assertEquals(1, first.attempt());

        // Simulate a worker that disappeared. Recovery returns the row to the
        // queue without resetting the monotonic attempt counter.
        AiNewsCandidateEntity inFlight = candidateMapper.selectById(candidate.getId());
        inFlight.setCaptureStartedAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5));
        candidateMapper.updateById(inFlight);
        assertEquals(1, service.recoverStaleCaptures(null, run.getId(), Duration.ofMinutes(1)));
        assertNull(candidateMapper.selectById(candidate.getId()).getNextCaptureAt(),
                "stale recovery must make the candidate immediately claimable");

        AiNewsCandidatePipelineService.CaptureLease second =
                service.claimCaptureLease(candidate.getId());
        assertNotNull(second);
        assertEquals(2, second.attempt());

        assertFalse(service.captureSucceeded(candidate.getId(), 12001L, first.attempt()),
                "the old worker must be fenced after recovery/reclaim");
        AiNewsCandidateEntity stillInFlight = candidateMapper.selectById(candidate.getId());
        assertEquals("CAPTURING", stillInFlight.getCaptureStatus());
        assertNull(stillInFlight.getCaptureId());

        assertTrue(service.captureSucceeded(candidate.getId(), 12002L, second.attempt()));
        AiNewsCandidateEntity captured = candidateMapper.selectById(candidate.getId());
        assertEquals("SUCCESS", captured.getCaptureStatus());
        assertEquals(12002L, captured.getCaptureId());
    }

    @Test
    void captureCallbacksCannotOverwriteAAlreadyPromotedCandidate() {
        Instant start = Instant.parse("2026-08-27T00:00:00Z");
        Instant end = Instant.parse("2026-08-29T00:00:00Z");
        AiNewsScanRunEntity run = service.startScan(13L, "test", "AI", start, end, "test-v1");
        service.persistDiscovery(run.getId(), batch(start, end));
        AiNewsCandidateEntity candidate = service.captureQueue(run.getId(), 1).getFirst();
        assertTrue(service.claimCapture(candidate.getId()));

        // Model the bridge's final link while a legacy worker still holds a
        // capture lease.  Both fenced SQL callbacks and the old compatibility
        // callbacks must leave the event's capture lineage untouched.
        AiNewsCandidateEntity promoted = candidateMapper.selectById(candidate.getId());
        promoted.setEventId(13001L);
        candidateMapper.updateById(promoted);

        assertFalse(service.captureSucceeded(candidate.getId(), 13002L, 1));
        assertFalse(service.captureFailed(candidate.getId(), "late failure", true,
                3, Duration.ofMinutes(1), 1));
        assertThrows(vip.newsclaw.exception.NewsClawException.class,
                () -> service.captureSucceeded(candidate.getId(), 13003L));
        assertThrows(vip.newsclaw.exception.NewsClawException.class,
                () -> service.captureFailed(candidate.getId(), "late failure", true,
                        3, Duration.ofMinutes(1)));

        AiNewsCandidateEntity unchanged = candidateMapper.selectById(candidate.getId());
        assertEquals(13001L, unchanged.getEventId());
        assertEquals("CAPTURING", unchanged.getCaptureStatus());
        assertNull(unchanged.getCaptureId());
    }

    @Test
    void staleDiscoveryProjectionCannotOverwritePromotionLineage() {
        Instant start = Instant.parse("2026-08-27T00:00:00Z");
        Instant end = Instant.parse("2026-08-29T00:00:00Z");
        AiNewsScanRunEntity run = service.startScan(16L, "test", "AI", start, end, "test-v1");
        service.persistDiscovery(run.getId(), batch(start, end));
        AiNewsCandidateEntity current = service.candidates(16L, 1, 10, run.getId(), null,
                null, null, null, null, null, null).getRecords().getFirst();

        // Keep a pre-promotion projection to model a discovery worker that
        // read the row before the promotion transaction acquired its lock.
        AiNewsCandidateEntity stale = candidateMapper.selectById(current.getId());
        current.setEventId(16001L);
        current.setCaptureStatus("SUCCESS");
        current.setCaptureId(16002L);
        current.setReviewStatus("ACCEPTED");
        current.setReviewedBy("editor@example.com");
        current.setReviewedAt(LocalDateTime.now(ZoneOffset.UTC));
        current.setReviewOrigin("HUMAN_WEB");
        candidateMapper.updateById(current);

        assertEquals(0, candidateMapper.updateDiscovery(stale),
                "a stale discovery projection must be fenced after promotion");
        AiNewsCandidateEntity unchanged = candidateMapper.selectById(current.getId());
        assertEquals(16001L, unchanged.getEventId());
        assertEquals("SUCCESS", unchanged.getCaptureStatus());
        assertEquals(16002L, unchanged.getCaptureId());
        assertEquals("ACCEPTED", unchanged.getReviewStatus());
        assertEquals("editor@example.com", unchanged.getReviewedBy());
    }

    @Test
    void completedRunWithLegacyPendingRowsIsNotReturnedToWorkspaceQueue() {
        boolean previousCaptureFlag = pipelineProperties.isCaptureEnabled();
        try {
            pipelineProperties.setCaptureEnabled(true);
            Instant start = Instant.parse("2026-08-27T00:00:00Z");
            Instant end = Instant.parse("2026-08-29T00:00:00Z");
            AiNewsScanRunEntity run = service.startScan(10L, "test", "AI", start, end, "test-v1");
            service.persistDiscovery(run.getId(), batch(start, end));
            assertEquals("PENDING", candidateMapper.selectList(new LambdaQueryWrapper<AiNewsCandidateEntity>())
                    .getFirst().getCaptureStatus());

            // Simulate a flag change before completion. Older rows can still
            // be PENDING, but a terminal run must not leak them into the
            // workspace-wide queue.
            pipelineProperties.setCaptureEnabled(false);
            service.completeScan(run.getId());

            assertEquals("COMPLETED", scanMapper.selectById(run.getId()).getRunStatus());
            assertTrue(candidateMapper.selectList(new LambdaQueryWrapper<AiNewsCandidateEntity>()).stream()
                    .allMatch(item -> "PENDING".equals(item.getCaptureStatus())));
            assertTrue(service.captureQueueForWorkspace(10L, 10).isEmpty());
        } finally {
            pipelineProperties.setCaptureEnabled(previousCaptureFlag);
        }
    }

    @Test
    void staleActiveScanIsReconciledInsteadOfRemainingInProgressForever() {
        Instant start = Instant.parse("2026-08-27T00:00:00Z");
        Instant end = Instant.parse("2026-08-29T00:00:00Z");
        AiNewsScanRunEntity run = service.startScan(14L, "test", "AI", start, end, "test-v1");
        run = scanMapper.selectById(run.getId());
        run.setUpdateTime(LocalDateTime.now(ZoneOffset.UTC).minusHours(3));
        scanMapper.updateById(run);

        assertEquals(1, service.recoverStaleRuns(Duration.ofMinutes(5)));
        AiNewsScanRunEntity recovered = scanMapper.selectById(run.getId());
        assertEquals("FAILED", recovered.getRunStatus());
        assertEquals("STALE_SCAN_RECOVERED", recovered.getErrorMessage());
        assertNotNull(recovered.getFinishedAt());
    }

    @Test
    void lateCaptureCannotResurrectCandidateAfterScanFailure() {
        Instant start = Instant.parse("2026-08-27T00:00:00Z");
        Instant end = Instant.parse("2026-08-29T00:00:00Z");
        AiNewsScanRunEntity run = service.startScan(15L, "test", "AI", start, end, "test-v1");
        service.persistDiscovery(run.getId(), batch(start, end));
        AiNewsCandidateEntity candidate = service.captureQueue(run.getId(), 1).getFirst();
        AiNewsCandidatePipelineService.CaptureLease lease = service.claimCaptureLease(candidate.getId());
        assertNotNull(lease);

        service.failScan(run.getId(), new IllegalStateException("worker crashed"));

        assertFalse(service.captureSucceeded(candidate.getId(), 15001L, lease.attempt()));
        AiNewsCandidateEntity after = candidateMapper.selectById(candidate.getId());
        assertEquals("FAILED", after.getCaptureStatus());
        assertEquals("SCAN_FAILED", after.getFailureReason());
        assertTrue(candidateMapper.selectList(new LambdaQueryWrapper<AiNewsCandidateEntity>()
                        .eq(AiNewsCandidateEntity::getScanRunId, run.getId())).stream()
                .allMatch(item -> "FAILED".equals(item.getCaptureStatus())),
                "a failed run must not leave unreachable pending captures");
        assertEquals("FAILED", scanMapper.selectById(run.getId()).getRunStatus());
    }

    @Test
    void laterDisabledRunDoesNotAbortOlderActiveRunQueueOwnership() {
        boolean previousCaptureFlag = pipelineProperties.isCaptureEnabled();
        try {
            Instant start = Instant.parse("2026-08-27T00:00:00Z");
            Instant end = Instant.parse("2026-08-29T00:00:00Z");
            pipelineProperties.setCaptureEnabled(true);
            AiNewsScanRunEntity older = service.startScan(11L, "test", "AI", start, end, "test-v1");
            service.persistDiscovery(older.getId(), batch(start, end));
            service.completeScan(older.getId());
            assertEquals("CAPTURE_PENDING", scanMapper.selectById(older.getId()).getRunStatus());

            // Reusing the same URL from a later candidate-only run must not
            // rewrite the older run's pending capture into NOT_QUEUED.
            pipelineProperties.setCaptureEnabled(false);
            AiNewsScanRunEntity newer = service.startScan(11L, "test", "AI", start, end, "test-v1");
            service.persistDiscovery(newer.getId(), batch(start, end));
            service.completeScan(newer.getId());

            assertEquals("COMPLETED", scanMapper.selectById(newer.getId()).getRunStatus());
            assertEquals("CAPTURE_PENDING", scanMapper.selectById(older.getId()).getRunStatus());
            assertFalse(service.captureQueueForWorkspace(11L, 10).isEmpty());
            assertFalse(service.captureQueue(older.getId(), 10).isEmpty());
        } finally {
            pipelineProperties.setCaptureEnabled(previousCaptureFlag);
        }
    }

    private static AiNewsDiscoverySearchService.DiscoveryBatch batch(Instant start, Instant end) {
        String common = "https://example.com/2026/08/28/model?utm_source=test";
        String canonicalCommon = "https://example.com/2026/08/28/model";
        String chinaOnly = "https://example.cn/2026/08/28/china-model";
        var tavilyRows = List.of(
                row(1, "Global model launch", common, "tavily", 0.9),
                row(2, "Invalid", "javascript:alert(1)", "tavily", 0.1));
        var bochaRows = List.of(
                row(1, "China model launch", chinaOnly, "bocha-web", 0.95),
                row(2, "Global model launch", canonicalCommon, "bocha-web", 0.8));
        var snapshots = List.of(
                snapshot("global_model", "tavily", tavilyRows),
                snapshot("china_model", "bocha-web", bochaRows));
        var selected = List.of(
                candidate(1, "China model launch", chinaOnly, "china_model", 0.04),
                candidate(2, "Global model launch", canonicalCommon, "global_model", 0.03));
        return new AiNewsDiscoverySearchService.DiscoveryBatch(
                "untrusted_fused_news_candidates", false, start.toString(), end.toString(),
                2, 2, selected, List.of(), 0, "candidate hints", end.minusSeconds(60).toString(),
                "test-policy", "snapshot-hash", "ranking-hash", Map.of(), snapshots,
                500L, true);
    }

    private static AiNewsDiscoverySearchService.QuerySnapshot snapshot(
            String lane,
            String provider,
            List<AiNewsDiscoverySearchService.SnapshotResult> rows) {
        return new AiNewsDiscoverySearchService.QuerySnapshot(lane, provider, false, "hash",
                lane, "news", LocalDate.of(2026, 8, 27), LocalDate.of(2026, 8, 29),
                List.of(), rows);
    }

    private static AiNewsDiscoverySearchService.SnapshotResult row(
            int rank, String title, String url, String provider, double score) {
        return new AiNewsDiscoverySearchService.SnapshotResult(rank, title, url, "snippet",
                "2026-08-28T08:00:00Z", "example", provider, score);
    }

    private static AiNewsDiscoverySearchService.DiscoveryCandidate candidate(
            int rank, String title, String url, String lane, double score) {
        return new AiNewsDiscoverySearchService.DiscoveryCandidate(rank, title, url, "example",
                "2026-08-28T08:00:00Z", score, score, false, false, List.of(lane), "snippet",
                AiNewsDiscoverySearchService.TemporalStatus.IN_WINDOW, "provider", lane);
    }
}
