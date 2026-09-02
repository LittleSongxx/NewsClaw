package vip.newsclaw.news.tool;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import vip.newsclaw.agent.context.ChatOrigin;
import vip.newsclaw.news.model.AiNewsCandidateEntity;
import vip.newsclaw.news.model.AiNewsScanRunEntity;
import vip.newsclaw.news.service.AiNewsCandidatePipelineService;
import vip.newsclaw.news.service.AiNewsScanOrchestrator;
import vip.newsclaw.workspace.conversation.ConversationService;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiNewsCandidateToolTest {

    private final AiNewsScanOrchestrator orchestrator = mock(AiNewsScanOrchestrator.class);
    private final AiNewsCandidatePipelineService pipeline =
            mock(AiNewsCandidatePipelineService.class);
    private AiNewsCandidateTool tool;
    private ToolContext context;

    @BeforeEach
    void setUp() {
        tool = new AiNewsCandidateTool(orchestrator, pipeline,
                mock(ConversationService.class), new ObjectMapper());
        context = ChatOrigin.web("conv", "user", 77L, "/workspace/77", null, 7701L).toToolContext();
    }

    @Test
    void scanReturnsBusinessCountersWithoutCaptureIds() {
        Instant start = Instant.parse("2026-08-27T00:00:00Z");
        Instant end = Instant.parse("2026-08-28T00:00:00Z");
        AiNewsScanRunEntity run = new AiNewsScanRunEntity();
        run.setId(900L);
        run.setRunStatus("COMPLETED");
        run.setRawResultCount(40);
        run.setUniqueCandidateCount(25);
        run.setSelectedCandidateCount(10);
        run.setCaptureSuccessCount(8);
        run.setCaptureFailureCount(2);
        run.setProviderDisabledCount(1);
        when(orchestrator.run(77L, "AI", start, end, 20, "agent"))
                .thenReturn(summary(run));

        String output = tool.scan("AI", start.toString(), end.toString(), 20, context);

        assertTrue(output.contains("\"scanId\":900"));
        assertTrue(output.contains("\"uniqueCandidates\":25"));
        assertFalse(output.contains("captureId"));
    }

    @Test
    void queryIsWorkspaceScopedAndReturnsCompactReviewHandle() {
        AiNewsCandidateEntity candidate = new AiNewsCandidateEntity();
        candidate.setId(123L);
        candidate.setTitle("模型发布");
        candidate.setCanonicalUrl("https://example.com/news");
        candidate.setProviderId("tavily");
        candidate.setQueryLane("global_model");
        candidate.setProviderRank(1);
        candidate.setSelectionStatus("SELECTED");
        candidate.setCaptureStatus("SUCCESS");
        candidate.setCaptureId(999999L);
        candidate.setReviewStatus("PENDING");
        Page<AiNewsCandidateEntity> page = new Page<>(1, 20, 1);
        page.setRecords(List.of(candidate));
        AiNewsScanRunEntity run = new AiNewsScanRunEntity();
        run.setId(902L);
        run.setRunStatus("COMPLETED");
        run.setWindowEnd(LocalDateTime.now());
        when(pipeline.latestRun(77L)).thenReturn(summary(run));
        when(pipeline.candidates(eq(77L), eq(1), eq(20), eq(902L), eq("tavily"),
                eq(null), eq(null), eq(null), eq(true), eq(null), eq(null)))
                .thenReturn(page);

        String output = tool.query(null, "tavily", null, null, null,
                true, null, null, 1, 20, context);

        assertTrue(output.contains("\"candidateId\":123"));
        assertTrue(output.contains("模型发布"));
        assertFalse(output.contains("999999"));
        verify(pipeline).latestRun(77L);
        verify(pipeline).candidates(77L, 1, 20, 902L, "tavily",
                null, null, null, true, null, null);
    }

    @Test
    void queryWithoutScanIdReportsLatestRunForSchedulerReuse() {
        Page<AiNewsCandidateEntity> page = new Page<>(1, 20, 0);
        when(pipeline.candidates(eq(77L), eq(1), eq(20), eq(901L), eq(null),
                eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
                .thenReturn(page);
        AiNewsScanRunEntity run = new AiNewsScanRunEntity();
        run.setId(901L);
        run.setRunStatus("COMPLETED");
        run.setWindowEnd(LocalDateTime.now());
        when(pipeline.latestRun(77L)).thenReturn(summary(run));

        String output = tool.query(null, null, null, null, null,
                null, null, null, 1, 20, context);

        assertTrue(output.contains("\"latestRun\""));
        assertTrue(output.contains("\"scanId\":901"));
        assertTrue(output.contains("\"windowEnd\":\""));
        assertTrue(output.contains("Z\""));
        assertTrue(output.contains("\"inProgress\":false"));
        assertTrue(output.contains("\"fresh\":true"));
        assertTrue(output.contains("\"candidatePipelineEnabled\":false"));
        verify(pipeline).latestRun(77L);
        verify(pipeline).candidates(77L, 1, 20, 901L, null,
                null, null, null, null, null, null);
    }

    @Test
    void inProgressRunIsExplicitAndNotFresh() {
        Page<AiNewsCandidateEntity> page = new Page<>(1, 20, 0);
        when(pipeline.candidates(eq(77L), eq(1), eq(20), eq(903L), eq(null),
                eq(null), eq(null), eq(null), eq(null), eq(null), eq(null)))
                .thenReturn(page);
        AiNewsScanRunEntity run = new AiNewsScanRunEntity();
        run.setId(903L);
        run.setRunStatus("RUNNING");
        run.setWindowEnd(LocalDateTime.now());
        when(pipeline.latestRun(77L)).thenReturn(summary(run));

        String output = tool.query(null, null, null, null, null,
                null, null, null, 1, 20, context);

        assertTrue(output.contains("\"inProgress\":true"));
        assertTrue(output.contains("\"fresh\":false"));
        verify(pipeline).candidates(77L, 1, 20, 903L, null,
                null, null, null, null, null, null);
    }

    @Test
    void queryWithoutAnyRunReturnsAnEmptyPage() {
        when(pipeline.latestRun(77L)).thenReturn(null);

        String output = tool.query(null, null, null, null, null,
                null, null, null, 1, 20, context);

        assertTrue(output.contains("\"total\":0"));
        assertTrue(output.contains("\"candidates\":[]"));
        verify(pipeline).latestRun(77L);
    }

    @Test
    void reviewDelegatesOnlyTheHumanDecision() {
        AiNewsCandidateEntity candidate = new AiNewsCandidateEntity();
        candidate.setId(123L);
        candidate.setTitle("模型发布");
        candidate.setReviewStatus("ACCEPTED");
        when(pipeline.review(77L, 123L, "ACCEPTED", "相关", "user:7701", "HUMAN_WEB"))
                .thenReturn(candidate);

        String output = tool.review(123L, "ACCEPTED", "相关", context);

        assertTrue(output.contains("\"reviewStatus\":\"ACCEPTED\""));
        verify(pipeline).review(77L, 123L, "ACCEPTED", "相关", "user:7701", "HUMAN_WEB");
    }

    private static AiNewsCandidatePipelineService.RunSummary summary(AiNewsScanRunEntity run) {
        var empty = new AiNewsCandidatePipelineService.Metric("empty", 0, 0, null, "empty");
        var scorecard = new AiNewsCandidatePipelineService.Scorecard(
                empty, empty, empty, empty, 0);
        return new AiNewsCandidatePipelineService.RunSummary(run, List.of(), scorecard,
                new ObjectMapper().createObjectNode());
    }
}
