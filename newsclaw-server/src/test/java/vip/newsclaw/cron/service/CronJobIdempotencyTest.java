package vip.newsclaw.cron.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.newsclaw.agent.AgentService;
import vip.newsclaw.cron.CronChatOriginFactory;
import vip.newsclaw.cron.CronConversationResolver;
import vip.newsclaw.cron.model.CronJobEntity;
import vip.newsclaw.dashboard.model.CronJobRunEntity;
import vip.newsclaw.dashboard.repository.CronJobRunMapper;
import vip.newsclaw.i18n.I18nService;
import vip.newsclaw.memory.event.ConversationCompletionPublisher;
import vip.newsclaw.wiki.service.WikiProcessingService;
import vip.newsclaw.workspace.conversation.ConversationService;
import vip.newsclaw.workspace.conversation.model.MessageEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Scheduler recovery tests for the durable {@code cron_job_id + idempotency_key} fence. */
class CronJobIdempotencyTest {

    @BeforeAll
    static void initializeLambdaMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                CronJobRunEntity.class);
    }

    @Test
    @DisplayName("重复 scheduled tick 复用原 run，不再写第二条用户消息")
    void lifecycleReturnsExistingRunForSameScheduledKey() {
        CronJobRunMapper runMapper = mock(CronJobRunMapper.class);
        ConversationService conversations = mock(ConversationService.class);
        CronJobRunEntity existing = run(5001L);
        existing.setIdempotencyKey("scheduled:11:2026-08-24T08:00");
        when(runMapper.selectOne(any())).thenReturn(existing);
        CronJobLifecycleService lifecycle = lifecycle(runMapper, conversations);

        CronJobLifecycleService.StartResult result = lifecycle.startRun(
                job(), "追踪今天 AI 动态", "scheduled", "tasks_7", existing.getIdempotencyKey());

        assertFalse(result.created());
        assertEquals(existing, result.run());
        verify(runMapper, never()).insert(any(CronJobRunEntity.class));
        verify(conversations, never()).saveMessage(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("唯一键竞争时重新读取既有 run，而非让第二个节点执行 Agent")
    void duplicateInsertRaceReturnsExistingRun() {
        CronJobRunMapper runMapper = mock(CronJobRunMapper.class);
        ConversationService conversations = mock(ConversationService.class);
        CronJobRunEntity existing = run(5002L);
        existing.setIdempotencyKey("scheduled:11:2026-08-24T08:00");
        when(runMapper.selectOne(any())).thenReturn(null, existing);
        org.springframework.dao.DuplicateKeyException duplicate =
                new org.springframework.dao.DuplicateKeyException("uk_cron_run_idempotency");
        org.mockito.Mockito.doThrow(duplicate).when(runMapper)
                .insert((CronJobRunEntity) org.mockito.ArgumentMatchers.any());
        CronJobLifecycleService lifecycle = lifecycle(runMapper, conversations);

        CronJobLifecycleService.StartResult result = lifecycle.startRun(
                job(), "追踪今天 AI 动态", "scheduled", "tasks_7", existing.getIdempotencyKey());

        assertFalse(result.created());
        assertEquals(5002L, result.run().getId());
        verify(conversations, never()).saveMessage(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("重复 scheduled tick 在 LLM 调用前被短路")
    void duplicateRunNeverCallsAgent() {
        CronJobLifecycleService lifecycle = mock(CronJobLifecycleService.class);
        AgentService agentService = mock(AgentService.class);
        CronConversationResolver resolver = mock(CronConversationResolver.class);
        CronJobEntity job = job();
        CronJobRunEntity existing = run(5003L);
        when(resolver.resolve(job)).thenReturn("tasks_7");
        when(lifecycle.startRun(eq(job), eq("追踪今天 AI 动态"), eq("scheduled"), eq("tasks_7"), anyString()))
                .thenReturn(new CronJobLifecycleService.StartResult(existing, null, false));
        CronJobRunner runner = new CronJobRunner(lifecycle, agentService, mock(CronChatOriginFactory.class),
                resolver, mock(WikiProcessingService.class), new ObjectMapper());

        runner.executeJob(job, "scheduled");

        verify(agentService, never()).chatWithUsage(any(), anyString(), anyString(), any());
        verify(lifecycle, never()).finishRunAndPublish(any(), any(), anyString(), any(), anyString(), anyBoolean(), any());
    }

    @Test
    @DisplayName("scheduled key 在同一分钟稳定，manual key 始终保留显式重复执行语义")
    void scheduledAndManualKeysHaveIntentionalSemantics() {
        CronJobEntity job = job();
        String scheduledOne = CronJobRunner.buildRunIdempotencyKey(job, "scheduled");
        String scheduledTwo = CronJobRunner.buildRunIdempotencyKey(job, "scheduled");
        String manualOne = CronJobRunner.buildRunIdempotencyKey(job, "manual");
        String manualTwo = CronJobRunner.buildRunIdempotencyKey(job, "manual");

        assertEquals(scheduledOne, scheduledTwo);
        assertTrue(scheduledOne.startsWith("scheduled:11:"));
        assertNotEquals(manualOne, manualTwo);
        assertTrue(manualOne.startsWith("manual:11:"));
    }

    private static CronJobLifecycleService lifecycle(CronJobRunMapper runMapper, ConversationService conversations) {
        return new CronJobLifecycleService(runMapper, conversations,
                mock(ConversationCompletionPublisher.class), mock(org.springframework.context.ApplicationEventPublisher.class),
                mock(I18nService.class));
    }

    private static CronJobRunEntity run(Long id) {
        CronJobRunEntity run = new CronJobRunEntity();
        run.setId(id);
        run.setStatus("running");
        return run;
    }

    private static CronJobEntity job() {
        CronJobEntity job = new CronJobEntity();
        job.setId(11L);
        job.setAgentId(21L);
        job.setWorkspaceId(7L);
        job.setTaskType("text");
        job.setTriggerMessage("追踪今天 AI 动态");
        job.setTimezone("Asia/Shanghai");
        return job;
    }
}
