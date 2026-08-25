package vip.newsclaw.agent.graph.executor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import vip.newsclaw.agent.AgentToolSet;
import vip.newsclaw.agent.GraphEventPublisher;
import vip.newsclaw.agent.context.ChatOrigin;
import vip.newsclaw.tool.guard.ToolGuard;
import vip.newsclaw.tool.guard.ToolGuardResult;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for successful-only load_skill pinning. */
class ToolExecutionExecutorLoadSkillTest {

    @Test
    @DisplayName("failed load_skill remains retryable in the same model response")
    void failedLoadSkillIsRetriedInSameBatch() {
        AtomicInteger invocations = new AtomicInteger();
        ToolExecutionExecutor executor = executor(args -> invocations.incrementAndGet() == 1
                ? "Error: Skill 'news_radar' not found or not enabled."
                : "# AI News Radar");

        var result = executor.execute(List.of(
                        load("load-1", "news_radar"),
                        load("load-2", "news_radar")),
                "conv", "agent", false, "user", null);

        assertEquals(2, invocations.get(), "the second call must not be pre-reserved as loaded");
        assertTrue(result.responses().get(0).responseData().startsWith("Error:"));
        assertEquals("# AI News Radar", result.responses().get(1).responseData());
        assertFalse(completed(result, "load-1"), "business Error: must be a failed receipt");
        assertTrue(completed(result, "load-2"), "the real retry must be a successful receipt");
    }

    @Test
    @DisplayName("successful load_skill suppresses a duplicate in the same model response")
    void successfulLoadSkillIsSuppressedInSameBatch() {
        AtomicInteger invocations = new AtomicInteger();
        ToolExecutionExecutor executor = executor(args -> {
            invocations.incrementAndGet();
            return "# PDF Skill";
        });

        var result = executor.execute(List.of(
                        load("load-1", "pdf"),
                        load("load-2", "PDF")),
                "conv", "agent", false, "user", null);

        assertEquals(1, invocations.get(), "a successful first load makes the duplicate synthetic");
        assertEquals("# PDF Skill", result.responses().get(0).responseData());
        assertTrue(result.responses().get(1).responseData().contains("already loaded"));
        assertTrue(completed(result, "load-1"));
        assertTrue(completed(result, "load-2"));
    }

    @Test
    @DisplayName("skill loaded by an earlier ReAct round is not read again")
    void previouslyLoadedSkillIsSuppressedAcrossRounds() {
        AtomicInteger invocations = new AtomicInteger();
        ToolExecutionExecutor executor = executor(args -> {
            invocations.incrementAndGet();
            return "# Should not be returned";
        });

        var result = executor.execute(List.of(load("load-1", "ai_news_radar")),
                "conv", "agent", false, "user", null, ChatOrigin.EMPTY, Set.of("AI_NEWS_RADAR"));

        assertEquals(0, invocations.get());
        assertTrue(result.responses().get(0).responseData().contains("already loaded"));
        assertTrue(completed(result, "load-1"));
    }

    @Test
    @DisplayName("normalized LoadSkill calls emit canonical receipts and preserve provider response name")
    void normalizedLoadSkillUsesCanonicalReceiptName() {
        ToolExecutionExecutor executor = executor(args -> "# AI News Radar");

        var result = executor.execute(List.of(new AssistantMessage.ToolCall(
                        "load-alias", "function", "LoadSkill",
                        "{\"skill_name\":\"ai_news_radar\"}")),
                "conv", "agent", false, "user", null);

        assertEquals("LoadSkill", result.responses().get(0).name());
        assertEquals("# AI News Radar", result.responses().get(0).responseData());
        var completion = result.events().stream()
                .filter(event -> GraphEventPublisher.EVENT_TOOL_COMPLETE.equals(event.type()))
                .filter(event -> "load-alias".equals(event.data().get("toolCallId")))
                .findFirst().orElseThrow();
        assertEquals("load_skill", completion.data().get("toolName"));
        assertEquals(true, completion.data().get("success"));
    }

    private static ToolExecutionExecutor executor(Function<String, String> handler) {
        ToolDefinition definition = ToolDefinition.builder()
                .name("load_skill")
                .description("test load skill")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
        ToolCallback callback = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public ToolMetadata getToolMetadata() { return ToolMetadata.builder().build(); }
            @Override public String call(String arguments) { return handler.apply(arguments); }
            @Override public String call(String arguments, ToolContext context) { return handler.apply(arguments); }
        };
        ToolGuard alwaysAllow = (name, arguments) -> ToolGuardResult.allow();
        return new ToolExecutionExecutor(AgentToolSet.fromCallbacks(List.of(), List.of(callback)),
                alwaysAllow, null, null);
    }

    private static AssistantMessage.ToolCall load(String id, String skillName) {
        return new AssistantMessage.ToolCall(id, "function", "load_skill",
                "{\"skillName\":\"" + skillName + "\"}");
    }

    private static boolean completed(ToolExecutionExecutor.ToolExecutionResult result, String toolCallId) {
        return result.events().stream()
                .filter(event -> GraphEventPublisher.EVENT_TOOL_COMPLETE.equals(event.type()))
                .filter(event -> toolCallId.equals(event.data().get("toolCallId")))
                .map(event -> event.data().get("success"))
                .map(Boolean.class::cast)
                .findFirst()
                .orElseThrow();
    }
}
