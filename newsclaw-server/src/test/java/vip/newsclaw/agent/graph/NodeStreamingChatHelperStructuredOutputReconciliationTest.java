package vip.newsclaw.agent.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import reactor.core.publisher.Flux;
import vip.newsclaw.channel.web.ChatStreamTracker;
import vip.newsclaw.llm.chatmodel.StructuredOutputSchema;
import vip.newsclaw.llm.chatmodel.StructuredOutputSchemaHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NodeStreamingChatHelperStructuredOutputReconciliationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String REASONING_DECISION = "{\"sourceTier\":\"official\","
            + "\"verificationEligible\":true,\"citationAllowed\":true,"
            + "\"claimQuoteSupported\":true,\"refusalIssued\":false,"
            + "\"humanReviewRequested\":false,\"citationIds\":[\"E1\"]}";
    private static final String TERMINAL_DECISION = "{\"sourceTier\":\"community\","
            + "\"verificationEligible\":false,\"citationAllowed\":false,"
            + "\"claimQuoteSupported\":false,\"refusalIssued\":true,"
            + "\"humanReviewRequested\":true,\"citationIds\":[]}";
    private static final String REASONING_ASSIGNMENT_AUDIT = """
            Final audit:
            - sourceTier = official.
            - claimQuoteSupported = false (B = false)
            - verificationEligible = false (D = false)
            - citationAllowed = (D AND E) = (false AND false) = false
            - refusalIssued = NOT D = NOT false = true
            - humanReviewRequested = NOT citationAllowed = NOT false = true
            - citationIds = [] (because citationAllowed = false)
            """;
    private static final String RELATIONS = "{\"relations\":["
            + "{\"evidenceId\":\"D1\",\"relation\":\"entails\",\"confidence\":0.97},"
            + "{\"evidenceId\":\"D2\",\"relation\":\"contradicts\",\"confidence\":0.91}]}";

    private ChatStreamTracker streamTracker;

    @BeforeEach
    void setUp() {
        streamTracker = mock(ChatStreamTracker.class);
        when(streamTracker.isStopRequested(any())).thenReturn(false);
    }

    @AfterEach
    void clearRequestSchema() {
        StructuredOutputSchemaHolder.clear();
    }

    @Test
    void explicitSchemaReconcilesEvenWhenPromptDoesNotContainFieldNames() {
        StructuredOutputSchemaHolder.set(StructuredOutputSchema.AI_NEWS_DECISION_V1);
        NodeStreamingChatHelper helper = helper("bailian-team", "qwen3.7-plus");
        ChatModel model = streamModel(
                thinkingChunk("Final JSON:\n" + REASONING_DECISION),
                new AssistantMessage(TERMINAL_DECISION));

        NodeStreamingChatHelper.StreamResult result = helper.streamCall(
                model, genericJsonPrompt(), "conv-explicit-schema", "reasoning");

        assertEquals(REASONING_DECISION, result.text());
        verify(streamTracker).broadcastObject(eq("conv-explicit-schema"),
                eq("structured_output_reconciled"), anyMap());
    }

    @Test
    void reconcilesDivergentBailianQwenNewsDecisionBeforeAnyContentDelta() throws Exception {
        NodeStreamingChatHelper helper = helper("bailian-team", "qwen3.7-plus");
        ChatModel model = streamModel(
                thinkingChunk("Evidence classification complete.\nFinal JSON:\n" + REASONING_DECISION),
                new AssistantMessage(TERMINAL_DECISION));

        NodeStreamingChatHelper.StreamResult result = helper.streamCall(
                model, decisionPrompt(), "conv-reconcile", "reasoning");

        assertEquals(REASONING_DECISION, result.text());
        assertEquals(REASONING_DECISION, result.assistantMessage().getText());

        org.mockito.ArgumentCaptor<String> delta = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(streamTracker, times(1)).broadcast(eq("conv-reconcile"), eq("content_delta"), delta.capture());
        assertEquals(REASONING_DECISION, JSON.readTree(delta.getValue()).path("delta").asText(),
                "the client must receive only the reconciled JSON, never the stale terminal object");
        verify(streamTracker).broadcastObject(eq("conv-reconcile"), eq("structured_output_reconciled"), anyMap());
    }

    @Test
    void reconcilesWhenThinkingEndsWithAFieldAssignmentAudit() throws Exception {
        NodeStreamingChatHelper helper = helper("bailian-team", "qwen3.7-plus");
        ChatModel model = streamModel(
                thinkingChunk(REASONING_ASSIGNMENT_AUDIT),
                new AssistantMessage(TERMINAL_DECISION));

        NodeStreamingChatHelper.StreamResult result = helper.streamCall(
                model, decisionPrompt(), "conv-assignment-reconcile", "reasoning");

        String expected = "{\"sourceTier\":\"official\",\"verificationEligible\":false,"
                + "\"citationAllowed\":false,\"claimQuoteSupported\":false,"
                + "\"refusalIssued\":true,\"humanReviewRequested\":true,\"citationIds\":[]}";
        assertEquals(expected, result.text());
        assertEquals(expected, result.assistantMessage().getText());
        org.mockito.ArgumentCaptor<String> delta = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(streamTracker, times(1)).broadcast(eq("conv-assignment-reconcile"),
                eq("content_delta"), delta.capture());
        assertEquals(expected, JSON.readTree(delta.getValue()).path("delta").asText());
        verify(streamTracker).broadcastObject(eq("conv-assignment-reconcile"),
                eq("structured_output_reconciled"), anyMap());
    }

    @Test
    void retainsTerminalDecisionWhenThinkingHasNoCompleteStrictDecision() throws Exception {
        NodeStreamingChatHelper helper = helper("bailian-team", "qwen3.7-plus");
        ChatModel model = streamModel(
                thinkingChunk("sourceTier may be official, but I did not finish the decision."),
                new AssistantMessage(TERMINAL_DECISION));

        NodeStreamingChatHelper.StreamResult result = helper.streamCall(
                model, decisionPrompt(), "conv-no-reconcile", "reasoning");

        assertEquals(TERMINAL_DECISION, result.text());
        org.mockito.ArgumentCaptor<String> delta = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(streamTracker, times(1)).broadcast(eq("conv-no-reconcile"), eq("content_delta"), delta.capture());
        assertEquals(TERMINAL_DECISION, JSON.readTree(delta.getValue()).path("delta").asText());
        verify(streamTracker, never()).broadcastObject(
                eq("conv-no-reconcile"), eq("structured_output_reconciled"), anyMap());
    }

    @Test
    void leavesMalformedTerminalDecisionForTheStructuredOutputContractToReject() throws Exception {
        String malformed = TERMINAL_DECISION.replace("\"citationIds\"", "\"citationsIds\"");
        NodeStreamingChatHelper helper = helper("bailian-team", "qwen3.7-plus");
        ChatModel model = streamModel(
                thinkingChunk("Final JSON:\n" + REASONING_DECISION),
                new AssistantMessage(malformed));

        NodeStreamingChatHelper.StreamResult result = helper.streamCall(
                model, decisionPrompt(), "conv-malformed-terminal", "reasoning");

        assertEquals(malformed, result.text());
        org.mockito.ArgumentCaptor<String> delta = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(streamTracker, times(1)).broadcast(
                eq("conv-malformed-terminal"), eq("content_delta"), delta.capture());
        assertEquals(malformed, JSON.readTree(delta.getValue()).path("delta").asText());
        verify(streamTracker, never()).broadcastObject(
                eq("conv-malformed-terminal"), eq("structured_output_reconciled"), anyMap());
    }

    @Test
    void unwrapsOneSchemaValidRelationsFenceAndReportsTheReason() {
        StructuredOutputSchemaHolder.set(StructuredOutputSchema.AI_NEWS_EVIDENCE_RELATIONS_V2);
        NodeStreamingChatHelper helper = helper("bailian-team", "qwen3.7-plus");
        ChatModel model = streamModel(new AssistantMessage("```json\n" + RELATIONS + "\n```"));

        NodeStreamingChatHelper.StreamResult result = helper.streamCall(
                model, genericJsonPrompt(), "conv-relations-fence", "reasoning");

        assertEquals(RELATIONS, result.text());
        org.mockito.ArgumentCaptor<Map<String, Object>> event =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(streamTracker).broadcastObject(eq("conv-relations-fence"),
                eq("structured_output_reconciled"), event.capture());
        assertEquals("single_json_fence_validated", event.getValue().get("reason"));
    }

    @Test
    void leavesSchemaInvalidRelationsFenceForStrictContractRejection() {
        StructuredOutputSchemaHolder.set(StructuredOutputSchema.AI_NEWS_EVIDENCE_RELATIONS_V2);
        String invalid = "```json\n" + RELATIONS.replace("\"confidence\"", "\"score\"") + "\n```";
        NodeStreamingChatHelper helper = helper("bailian-team", "qwen3.7-plus");

        NodeStreamingChatHelper.StreamResult result = helper.streamCall(
                streamModel(new AssistantMessage(invalid)), genericJsonPrompt(),
                "conv-relations-invalid-fence", "reasoning");

        assertEquals(invalid, result.text());
        verify(streamTracker, never()).broadcastObject(eq("conv-relations-invalid-fence"),
                eq("structured_output_reconciled"), anyMap());
    }

    @Test
    void keepsNormalStreamingForOtherProvidersModelsAndGenericJsonObjects() {
        assertNormalStreaming("openai", "qwen3.7-plus", decisionPrompt(), "conv-openai");
        assertNormalStreaming("bailian-team", "gpt-4.1", decisionPrompt(), "conv-non-qwen");
        assertNormalStreaming("bailian-team", "qwen3.7-plus", genericJsonPrompt(), "conv-generic-json");
    }

    private void assertNormalStreaming(String provider, String modelName, Prompt prompt, String conversationId) {
        NodeStreamingChatHelper helper = helper(provider, modelName);
        int splitAt = TERMINAL_DECISION.length() / 2;
        ChatModel model = streamModel(
                thinkingChunk("Final JSON:\n" + REASONING_DECISION),
                new AssistantMessage(TERMINAL_DECISION.substring(0, splitAt)),
                new AssistantMessage(TERMINAL_DECISION.substring(splitAt)));

        NodeStreamingChatHelper.StreamResult result = helper.streamCall(model, prompt, conversationId, "reasoning");

        assertEquals(TERMINAL_DECISION, result.text());
        verify(streamTracker, times(2)).broadcast(eq(conversationId), eq("content_delta"), any());
        verify(streamTracker, never()).broadcastObject(
                eq(conversationId), eq("structured_output_reconciled"), anyMap());
    }

    private NodeStreamingChatHelper helper(String provider, String modelName) {
        NodeStreamingChatHelper helper = new NodeStreamingChatHelper(
                streamTracker, List.of(), null, null, provider);
        helper.setPrimaryModelName(modelName);
        return helper;
    }

    private static Prompt decisionPrompt() {
        return jsonPrompt("Return exactly these fields: sourceTier, verificationEligible, citationAllowed, "
                + "claimQuoteSupported, refusalIssued, humanReviewRequested, citationIds.");
    }

    private static Prompt genericJsonPrompt() {
        return jsonPrompt("Return an object with answer and confidence.");
    }

    private static Prompt jsonPrompt(String instruction) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .responseFormat(new ResponseFormat(ResponseFormat.Type.JSON_OBJECT, null))
                .build();
        return new Prompt(List.of(new UserMessage(instruction)), options);
    }

    private static AssistantMessage thinkingChunk(String thinking) {
        return AssistantMessage.builder().content("")
                .properties(Map.of("reasoningContent", thinking))
                .build();
    }

    private static ChatModel streamModel(AssistantMessage... messages) {
        ChatResponse[] responses = new ChatResponse[messages.length];
        for (int index = 0; index < messages.length; index++) {
            Generation generation = new Generation(messages[index], ChatGenerationMetadata.NULL);
            ChatResponse response = mock(ChatResponse.class);
            when(response.getResults()).thenReturn(List.of(generation));
            when(response.getResult()).thenReturn(generation);
            when(response.getMetadata()).thenReturn(null);
            responses[index] = response;
        }
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(responses));
        return model;
    }
}
