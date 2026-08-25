package vip.newsclaw.llm.chatmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import vip.newsclaw.exception.NewsClawException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolChoicePolicyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterEach
    void clearHolder() {
        ToolChoiceHolder.clear();
    }

    @Test
    void parsesOnlyTheDeclaredWireValues() {
        assertTrue(ToolChoicePolicy.fromWire(null).isAuto());
        assertEquals("none", ToolChoicePolicy.fromWire(" NONE ").wireValue());
        assertEquals("required", ToolChoicePolicy.fromWire("required").wireValue());
        assertEquals("function:ai_news_event",
                ToolChoicePolicy.fromWire("function:ai_news_event").wireValue());
        assertThrows(IllegalArgumentException.class,
                () -> ToolChoicePolicy.fromWire("ai_news_event"));
        assertThrows(IllegalArgumentException.class,
                () -> ToolChoicePolicy.fromWire("function:ai news event"));
    }

    @Test
    void exactFunctionChoiceCannotEscapeActiveToolScope() throws Exception {
        ToolChoicePolicy policy = ToolChoicePolicy.fromWire("function:ai_news_event");
        Object providerValue = policy.toOpenAiToolChoice(List.of("ai_news_event", "tool_call"));

        assertEquals("function", JSON.valueToTree(providerValue).path("type").asText());
        assertEquals("ai_news_event", JSON.valueToTree(providerValue)
                .path("function").path("name").asText());

        NewsClawException error = assertThrows(NewsClawException.class,
                () -> policy.toOpenAiToolChoice(List.of("tool_call")));
        assertEquals(422, error.getCode());
    }

    @Test
    void noneUsesTheNativeProviderLiteralWithoutRequiringAnActiveTool() {
        assertEquals("none", ToolChoicePolicy.NONE.toOpenAiToolChoice(List.of()));
        assertFalse(ToolChoicePolicy.NONE.requiresInitialToolCall());
        assertTrue(ToolChoicePolicy.REQUIRED.requiresInitialToolCall());
        assertTrue(ToolChoicePolicy.fromWire("function:ai_news_event").requiresInitialToolCall());
    }

    @Test
    void holderDoesNotLeakAnExplicitPolicyAcrossTurns() {
        assertTrue(ToolChoiceHolder.get().isAuto());
        ToolChoiceHolder.set(ToolChoicePolicy.fromWire("function:ai_news_event"));
        assertFalse(ToolChoiceHolder.get().isAuto());
        ToolChoiceHolder.set(ToolChoicePolicy.AUTO);
        assertTrue(ToolChoiceHolder.get().isAuto());
    }
}
