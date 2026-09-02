package vip.newsclaw.memory.fact.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import vip.newsclaw.agent.context.ChatOrigin;
import vip.newsclaw.memory.MemoryProperties;
import vip.newsclaw.memory.fact.query.FactQueryService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FactQueryToolIdSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("fact tools publish agentId as a string parameter so LLM tool calls preserve precision")
    void factToolAgentIdSchemasAreString() throws Exception {
        FactQueryTool tool = new FactQueryTool(mock(FactQueryService.class), mock(MemoryProperties.class));

        assertAgentIdIsString(tool, "fact_probe");
        assertAgentIdIsString(tool, "fact_list_contradictions");
    }

    @Test
    @DisplayName("fact tools reject an agent id different from the current origin")
    void rejectsForeignAgentFromToolContext() {
        FactQueryService query = mock(FactQueryService.class);
        MemoryProperties properties = mock(MemoryProperties.class);
        MemoryProperties.FactProperties fact = mock(MemoryProperties.FactProperties.class);
        when(properties.getFact()).thenReturn(fact);
        when(fact.isProjectionEnabled()).thenReturn(true);
        FactQueryTool tool = new FactQueryTool(query, properties);
        ToolContext context = ChatOrigin.EMPTY.withAgent(99L).toToolContext();

        org.junit.jupiter.api.Assertions.assertTrue(
                tool.fact_probe("100", "secret", context).contains("does not match"));
        verify(query, never()).probe(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    private static void assertAgentIdIsString(Object tool, String name) throws Exception {
        JsonNode root = MAPPER.readTree(callback(tool, name).getToolDefinition().inputSchema());

        assertThat(root.at("/properties/agentId/type").asText()).isEqualTo("string");
    }

    private static ToolCallback callback(Object tool, String name) {
        for (ToolCallback callback : ToolCallbacks.from(tool)) {
            if (name.equals(callback.getToolDefinition().name())) {
                return callback;
            }
        }
        throw new AssertionError("Missing tool callback: " + name);
    }
}
