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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class ReasoningNodeStructuredOutputTest {

    @AfterEach
    void clearResponseFormat() {
        StructuredOutputFormatHolder.clear();
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
}
