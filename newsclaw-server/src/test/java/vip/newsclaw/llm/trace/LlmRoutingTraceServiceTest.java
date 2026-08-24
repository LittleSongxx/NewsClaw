package vip.newsclaw.llm.trace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import vip.newsclaw.agent.model.AgentEntity;
import vip.newsclaw.agent.repository.AgentMapper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Durable model-routing traces must remain best effort and never affect chat availability. */
class LlmRoutingTraceServiceTest {

    private LlmRoutingTraceMapper traceMapper;
    private LlmRoutingTraceService service;

    @BeforeEach
    void setUp() {
        traceMapper = mock(LlmRoutingTraceMapper.class);
        service = new LlmRoutingTraceService(traceMapper);
    }

    @Test
    @DisplayName("主模型成功会持久化可审计的 provider、model 和阶段")
    void recordsPrimarySuccess() {
        service.record(new LlmRoutingTraceService.RoutingTrace(
                7L, 3L, "conv-1", "reasoning", "PRIMARY", "bailian-team", "qwen3.7-plus",
                0, 0, "SUCCEEDED", null, 42L, "{\"source\":\"ai-news\"}"));

        ArgumentCaptor<LlmRoutingTraceEntity> captor = ArgumentCaptor.forClass(LlmRoutingTraceEntity.class);
        verify(traceMapper).insert(captor.capture());
        LlmRoutingTraceEntity trace = captor.getValue();
        assertEquals(7L, trace.getWorkspaceId());
        assertEquals("PRIMARY", trace.getRouteRole());
        assertEquals("bailian-team", trace.getProviderId());
        assertEquals("qwen3.7-plus", trace.getModelName());
        assertEquals("SUCCEEDED", trace.getOutcome());
        assertEquals(42L, trace.getDurationMs());
    }

    @Test
    @DisplayName("没有显式 workspace 时从 agent 归属解析，避免跨 workspace 观测混淆")
    void resolvesWorkspaceFromAgent() {
        AgentMapper agentMapper = mock(AgentMapper.class);
        AgentEntity agent = new AgentEntity();
        agent.setWorkspaceId(9L);
        when(agentMapper.selectById(3L)).thenReturn(agent);
        ReflectionTestUtils.setField(service, "agentMapper", agentMapper);

        service.record(new LlmRoutingTraceService.RoutingTrace(
                null, 3L, "conv-2", "reasoning", "FALLBACK", "deepseek", "deepseek-v4-flash",
                0, 1, "SUCCEEDED", null, 17L, null));

        ArgumentCaptor<LlmRoutingTraceEntity> captor = ArgumentCaptor.forClass(LlmRoutingTraceEntity.class);
        verify(traceMapper).insert(captor.capture());
        assertEquals(9L, captor.getValue().getWorkspaceId());
        assertEquals("FALLBACK", captor.getValue().getRouteRole());
        assertEquals(1, captor.getValue().getFallbackOrdinal());
    }

    @Test
    @DisplayName("路由 trace 落库异常不阻断主模型或备用模型响应")
    void persistenceFailureIsBestEffort() {
        doThrow(new IllegalStateException("table unavailable")).when(traceMapper)
                .insert(any(LlmRoutingTraceEntity.class));

        assertDoesNotThrow(() -> service.record(new LlmRoutingTraceService.RoutingTrace(
                7L, 3L, "conv-3", "reasoning", "PRIMARY", "bailian-team", "qwen3.7-plus",
                0, 0, "FAILED", "RATE_LIMIT", 10L, null)));
        verify(traceMapper).insert(any(LlmRoutingTraceEntity.class));
    }
}
