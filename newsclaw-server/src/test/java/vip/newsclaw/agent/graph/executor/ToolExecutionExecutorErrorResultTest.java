package vip.newsclaw.agent.graph.executor;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import vip.newsclaw.agent.AgentToolSet;
import vip.newsclaw.agent.GraphEventPublisher;
import vip.newsclaw.tool.guard.ToolGuard;
import vip.newsclaw.tool.guard.ToolGuardResult;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolExecutionExecutorErrorResultTest {

    @Test
    void marksLegacyErrorPrefixAsFailed() {
        assertFalse(ToolExecutionExecutor.isSuccessfulToolResult("Error: unknown action"));
        assertFalse(ToolExecutionExecutor.isSuccessfulToolResult("  ERROR: upstream unavailable"));
    }

    @Test
    void marksJsonEncodedErrorStringAsFailed() {
        assertFalse(ToolExecutionExecutor.isSuccessfulToolResult("\"Error: unknown action\""));
        assertFalse(ToolExecutionExecutor.isSuccessfulToolResult("  \"error: invalid input\""));
    }

    @Test
    void preservesOrdinaryAndLegacyNullResultsAsSuccessful() {
        assertTrue(ToolExecutionExecutor.isSuccessfulToolResult("ok"));
        assertTrue(ToolExecutionExecutor.isSuccessfulToolResult("{\"error\":null,\"status\":\"ok\"}"));
        assertTrue(ToolExecutionExecutor.isSuccessfulToolResult(null));
    }

    @Test
    void regularExecutionEmitsFailedReceiptForBusinessError() {
        ToolExecutionExecutor executor = executor("Error: unknown action");
        AssistantMessage.ToolCall call = call("regular-error");

        var result = executor.execute(List.of(call),
                "conversation", "agent", false, "user", null);

        assertEquals("Error: unknown action", result.responses().getFirst().responseData());
        assertFalse(completionSucceeded(result.events(), "regular-error"));
    }

    @Test
    void preApprovedExecutionEmitsFailedReceiptForBusinessError() {
        ToolExecutionExecutor executor = executor("\"Error: unknown action\"");
        AssistantMessage.ToolCall call = call("approved-error");
        List<GraphEventPublisher.GraphEvent> events = new ArrayList<>();

        executor.executePreApproved(call, "{}", events, "conversation", null);

        assertFalse(completionSucceeded(events, "approved-error"));
    }

    private static ToolExecutionExecutor executor(String response) {
        ToolDefinition definition = ToolDefinition.builder()
                .name("business_tool")
                .description("business tool test double")
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
        ToolCallback callback = new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() { return definition; }
            @Override public ToolMetadata getToolMetadata() { return ToolMetadata.builder().build(); }
            @Override public String call(String arguments) { return response; }
            @Override public String call(String arguments, ToolContext context) { return response; }
        };
        ToolGuard alwaysAllow = (name, arguments) -> ToolGuardResult.allow();
        return new ToolExecutionExecutor(AgentToolSet.fromCallbacks(List.of(), List.of(callback)),
                alwaysAllow, null, null);
    }

    private static AssistantMessage.ToolCall call(String id) {
        return new AssistantMessage.ToolCall(id, "function", "business_tool", "{}");
    }

    private static boolean completionSucceeded(List<GraphEventPublisher.GraphEvent> events,
                                               String toolCallId) {
        return events.stream()
                .filter(event -> GraphEventPublisher.EVENT_TOOL_COMPLETE.equals(event.type()))
                .filter(event -> toolCallId.equals(event.data().get("toolCallId")))
                .map(event -> (Boolean) event.data().get("success"))
                .findFirst()
                .orElseThrow();
    }
}
