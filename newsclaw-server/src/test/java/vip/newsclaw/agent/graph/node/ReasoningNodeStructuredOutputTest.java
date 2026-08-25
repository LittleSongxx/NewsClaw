package vip.newsclaw.agent.graph.node;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.llm.chatmodel.StructuredOutputFormat;
import vip.newsclaw.llm.chatmodel.StructuredOutputFormatHolder;
import vip.newsclaw.llm.chatmodel.ToolChoiceHolder;
import vip.newsclaw.llm.chatmodel.ToolChoicePolicy;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class ReasoningNodeStructuredOutputTest {

    @AfterEach
    void clearResponseFormat() {
        StructuredOutputFormatHolder.clear();
        ToolChoiceHolder.clear();
    }

    @Test
    void jsonObjectRequestBecomesNativeOpenAiResponseFormat() {
        ReasoningNode node = new ReasoningNode(mock(ChatModel.class), List.of());
        node.setNativeJsonObjectResponseFormatSupported(true);
        StructuredOutputFormatHolder.set(StructuredOutputFormat.JSON_OBJECT);

        ChatOptions options = node.buildChatOptions(null, List.of());

        OpenAiChatOptions openAi = (OpenAiChatOptions) options;
        assertEquals(ResponseFormat.Type.JSON_OBJECT, openAi.getResponseFormat().getType());
    }

    @Test
    void textModeDoesNotChangeExistingOpenAiRequest() {
        ReasoningNode node = new ReasoningNode(mock(ChatModel.class), List.of());
        node.setNativeJsonObjectResponseFormatSupported(true);

        OpenAiChatOptions options = (OpenAiChatOptions) node.buildChatOptions(null, List.of());

        assertNull(options.getResponseFormat());
    }

    @Test
    void unsupportedModelFailsExplicitlyInsteadOfPretendingJsonIsGuaranteed() {
        ReasoningNode node = new ReasoningNode(mock(ChatModel.class), List.of());
        node.setNativeJsonObjectResponseFormatSupported(false);
        StructuredOutputFormatHolder.set(StructuredOutputFormat.JSON_OBJECT);

        NewsClawException error = assertThrows(NewsClawException.class,
                () -> node.buildChatOptions(null, List.of()));

        assertEquals(422, error.getCode());
    }

    @Test
    void exactToolChoiceBecomesNativeOpenAiFunctionChoice() {
        ReasoningNode node = new ReasoningNode(mock(ChatModel.class), List.of());
        ToolChoiceHolder.set(ToolChoicePolicy.fromWire("function:ai_news_event"));
        org.springframework.ai.tool.ToolCallback callback = org.mockito.Mockito.mock(org.springframework.ai.tool.ToolCallback.class);
        org.springframework.ai.tool.definition.ToolDefinition definition =
                org.springframework.ai.tool.definition.ToolDefinition.builder()
                        .name("ai_news_event").description("test").inputSchema("{}").build();
        org.mockito.Mockito.when(callback.getToolDefinition()).thenReturn(definition);

        OpenAiChatOptions options = (OpenAiChatOptions) node.buildChatOptions(null, List.of(callback));
        com.fasterxml.jackson.databind.JsonNode choice = new com.fasterxml.jackson.databind.ObjectMapper()
                .valueToTree(options.getToolChoice());

        assertEquals("function", choice.path("type").asText());
        assertEquals("ai_news_event", choice.path("function").path("name").asText());
    }

    @Test
    void forcedToolAndJsonObjectUseSeparateAgentStages() {
        ReasoningNode node = new ReasoningNode(mock(ChatModel.class), List.of());
        node.setNativeJsonObjectResponseFormatSupported(true);
        StructuredOutputFormatHolder.set(StructuredOutputFormat.JSON_OBJECT);
        ToolChoiceHolder.set(ToolChoicePolicy.fromWire("function:ai_news_event"));
        org.springframework.ai.tool.ToolCallback callback = org.mockito.Mockito.mock(org.springframework.ai.tool.ToolCallback.class);
        org.springframework.ai.tool.definition.ToolDefinition definition =
                org.springframework.ai.tool.definition.ToolDefinition.builder()
                        .name("ai_news_event").description("test").inputSchema("{}").build();
        org.mockito.Mockito.when(callback.getToolDefinition()).thenReturn(definition);

        OpenAiChatOptions toolStage = (OpenAiChatOptions) node.buildChatOptions(null, List.of(callback), false);
        OpenAiChatOptions terminalStage = (OpenAiChatOptions) node.buildChatOptions(null, List.of(callback), true);

        assertNull(toolStage.getResponseFormat(),
                "provider JSON mode must not compete with the forced tool-producing step");
        assertEquals("none", terminalStage.getToolChoice(),
                "post-tool generation must not repeat the forced function");
        assertEquals(ResponseFormat.Type.JSON_OBJECT, terminalStage.getResponseFormat().getType());
    }

    @Test
    void unsupportedJsonContractFailsBeforeTheForcedToolStage() {
        ReasoningNode node = new ReasoningNode(mock(ChatModel.class), List.of());
        node.setNativeJsonObjectResponseFormatSupported(false);
        StructuredOutputFormatHolder.set(StructuredOutputFormat.JSON_OBJECT);
        ToolChoiceHolder.set(ToolChoicePolicy.fromWire("function:ai_news_event"));
        org.springframework.ai.tool.ToolCallback callback = org.mockito.Mockito.mock(org.springframework.ai.tool.ToolCallback.class);
        org.springframework.ai.tool.definition.ToolDefinition definition =
                org.springframework.ai.tool.definition.ToolDefinition.builder()
                        .name("ai_news_event").description("test").inputSchema("{}").build();
        org.mockito.Mockito.when(callback.getToolDefinition()).thenReturn(definition);

        NewsClawException error = assertThrows(NewsClawException.class,
                () -> node.buildChatOptions(null, List.of(callback), false));

        assertEquals(422, error.getCode());
    }

    @Test
    void unavailableExactToolChoiceFailsBeforeProviderInvocation() {
        ReasoningNode node = new ReasoningNode(mock(ChatModel.class), List.of());
        ToolChoiceHolder.set(ToolChoicePolicy.fromWire("function:ai_news_event"));

        NewsClawException error = assertThrows(NewsClawException.class,
                () -> node.buildChatOptions(null, List.of()));

        assertEquals(422, error.getCode());
    }
}
