package vip.newsclaw.agent.graph.plan.state;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.springframework.ai.chat.messages.Message;
import vip.newsclaw.agent.GraphEventPublisher;
import vip.newsclaw.agent.graph.NodeStreamingChatHelper;
import vip.newsclaw.agent.graph.state.NewsClawStateKeys;

import java.util.*;

import static vip.newsclaw.agent.graph.plan.state.PlanStateKeys.*;

/**
 * Plan-Execute 类型安全的状态访问器
 * <p>
 * 参照 {@link vip.newsclaw.agent.graph.state.NewsClawStateAccessor} 的模式，
 * 为 Plan-Execute 特有的状态字段提供类型安全读取和 fluent 输出构建。
 *
 * @author NewsClaw Team
 */
public final class PlanStateAccessor {

    private final OverAllState state;

    public PlanStateAccessor(OverAllState state) {
        this.state = Objects.requireNonNull(state, "state must not be null");
    }

    // ===== 输入 =====

    public String goal() {
        return state.value(GOAL, "");
    }

    // ===== 计划 =====

    public Long planId() {
        return state.value(PLAN_ID, 0L);
    }

    @SuppressWarnings("unchecked")
    public List<String> planSteps() {
        return state.<List<String>>value(PLAN_STEPS).orElse(List.of());
    }

    public boolean planValid() {
        return state.value(PLAN_VALID, false);
    }

    public boolean needsPlanning() {
        // Default to false: an unset triage flag means the request was not
        // classified as requiring a plan. See PlanGenerationDispatcher for the
        // rationale and RFC-008 for the full discussion.
        return state.value(NEEDS_PLANNING, false);
    }

    // ===== 步骤控制 =====

    public int currentStepIndex() {
        return state.value(CURRENT_STEP_INDEX, 0);
    }

    public String currentStepTitle() {
        return state.value(CURRENT_STEP_TITLE, "");
    }

    public String currentStepResult() {
        return state.value(CURRENT_STEP_RESULT, "");
    }

    @SuppressWarnings("unchecked")
    public List<String> completedResults() {
        return state.<List<String>>value(COMPLETED_RESULTS).orElse(List.of());
    }

    /** Re-plans already performed this run (0 at run start). */
    public int replanCount() {
        return state.value(PLAN_REPLAN_COUNT, 0);
    }

    // ===== 终止 =====

    public String finalSummary() {
        return state.value(FINAL_SUMMARY, "");
    }

    public String directAnswer() {
        return state.value(DIRECT_ANSWER, "");
    }

    // ===== Thinking =====

    public String finalSummaryThinking() {
        return state.value(FINAL_SUMMARY_THINKING, "");
    }

    public String planThinking() {
        return state.value(PLAN_THINKING, "");
    }

    public String currentStepThinking() {
        return state.value(CURRENT_STEP_THINKING, "");
    }

    // ===== 共享键 =====

    public String systemPrompt() {
        return state.value(NewsClawStateKeys.SYSTEM_PROMPT, "你是一个有帮助的AI助手。");
    }

    public String conversationId() {
        return state.value(NewsClawStateKeys.CONVERSATION_ID, "");
    }

    public String traceId() {
        return state.value(NewsClawStateKeys.TRACE_ID, "");
    }

    /**
     * The {@link vip.newsclaw.agent.context.ChatOrigin} forwarded into graph
     * state by {@code NewsClawStateAccessor.OutputBuilder.chatOrigin}.
     * Returns {@link vip.newsclaw.agent.context.ChatOrigin#EMPTY} when nothing
     * was injected (legacy callers / non-channel entry points).
     */
    public vip.newsclaw.agent.context.ChatOrigin chatOrigin() {
        return state.<vip.newsclaw.agent.context.ChatOrigin>value(NewsClawStateKeys.CHAT_ORIGIN)
                .orElse(vip.newsclaw.agent.context.ChatOrigin.EMPTY);
    }

    // ===== 会话消息（复用 NewsClawStateKeys.MESSAGES）=====

    @SuppressWarnings("unchecked")
    public List<Message> messages() {
        return state.<List<Message>>value(NewsClawStateKeys.MESSAGES).orElse(List.of());
    }

    // ===== 工作上下文 =====

    public String workingContext() {
        return state.value(WORKING_CONTEXT, "");
    }

    @SuppressWarnings("unchecked")
    public Set<String> loadedSkills() {
        return state.<Set<String>>value(NewsClawStateKeys.LOADED_SKILLS).orElse(Set.of());
    }

    // ===== 输出构建器 =====

    public static OutputBuilder output() {
        return new OutputBuilder();
    }

    /**
     * Fluent 输出构建器
     */
    public static final class OutputBuilder {
        private final Map<String, Object> map = new HashMap<>();

        private OutputBuilder() {}

        public OutputBuilder put(String key, Object value) {
            map.put(key, value);
            return this;
        }

        // ---- 输入 ----
        public OutputBuilder goal(String goal) {
            return put(GOAL, goal);
        }

        // ---- 会话消息（写入共享键 NewsClawStateKeys.MESSAGES）----
        public OutputBuilder messages(List<Message> msgs) {
            return put(NewsClawStateKeys.MESSAGES, msgs);
        }

        // ---- 工作上下文 ----
        public OutputBuilder workingContext(String ctx) {
            return put(WORKING_CONTEXT, ctx);
        }

        // ---- 计划 ----
        public OutputBuilder planId(Long id) {
            return put(PLAN_ID, id);
        }

        public OutputBuilder planSteps(List<String> steps) {
            return put(PLAN_STEPS, steps);
        }

        public OutputBuilder planValid(boolean valid) {
            return put(PLAN_VALID, valid);
        }

        public OutputBuilder needsPlanning(boolean needs) {
            return put(NEEDS_PLANNING, needs);
        }

        // ---- 步骤控制 ----
        public OutputBuilder currentStepIndex(int index) {
            return put(CURRENT_STEP_INDEX, index);
        }

        public OutputBuilder replanCount(int count) {
            return put(PLAN_REPLAN_COUNT, count);
        }

        public OutputBuilder currentStepTitle(String title) {
            return put(CURRENT_STEP_TITLE, title);
        }

        public OutputBuilder currentStepResult(String result) {
            return put(CURRENT_STEP_RESULT, result);
        }

        /**
         * 追加到 COMPLETED_RESULTS（APPEND 策略，传入单条结果包装为 List）
         */
        public OutputBuilder completedResults(String result) {
            return put(COMPLETED_RESULTS, List.of(result));
        }

        // ---- 终止 ----
        public OutputBuilder finalSummary(String summary) {
            return put(FINAL_SUMMARY, summary);
        }

        public OutputBuilder directAnswer(String answer) {
            return put(DIRECT_ANSWER, answer);
        }

        // ---- Thinking ----
        public OutputBuilder finalSummaryThinking(String thinking) {
            return put(FINAL_SUMMARY_THINKING, thinking);
        }

        public OutputBuilder planThinking(String thinking) {
            return put(PLAN_THINKING, thinking);
        }

        public OutputBuilder currentStepThinking(String thinking) {
            return put(CURRENT_STEP_THINKING, thinking);
        }

        // ---- 流式防重（写入共享键）----
        public OutputBuilder contentStreamed(boolean streamed) {
            return put(NewsClawStateKeys.CONTENT_STREAMED, streamed);
        }

        public OutputBuilder thinkingStreamed(boolean streamed) {
            return put(NewsClawStateKeys.THINKING_STREAMED, streamed);
        }

        // ---- 事件流（写入共享键 NewsClawStateKeys.PENDING_EVENTS）----
        public OutputBuilder events(List<GraphEventPublisher.GraphEvent> events) {
            return put(NewsClawStateKeys.PENDING_EVENTS, events);
        }

        public OutputBuilder loadedSkills(Set<String> names) {
            return put(NewsClawStateKeys.LOADED_SKILLS, names);
        }

        // ---- 阶段标记（写入共享键 NewsClawStateKeys.CURRENT_PHASE）----
        public OutputBuilder currentPhase(String phase) {
            return put(NewsClawStateKeys.CURRENT_PHASE, phase);
        }

        // ---- Token Usage（写入共享键）----

        /** 将本次 LLM 调用的 usage 累加到 state 已有值上 */
        public OutputBuilder mergeUsage(OverAllState currentState,
                                        NodeStreamingChatHelper.StreamResult result) {
            int existingPrompt = currentState.value(NewsClawStateKeys.PROMPT_TOKENS, 0);
            int existingCompletion = currentState.value(NewsClawStateKeys.COMPLETION_TOKENS, 0);
            int existingLlmCalls = currentState.value(NewsClawStateKeys.LLM_CALL_COUNT, 0);
            map.put(NewsClawStateKeys.PROMPT_TOKENS, existingPrompt + result.promptTokens());
            map.put(NewsClawStateKeys.COMPLETION_TOKENS, existingCompletion + result.completionTokens());
            map.put(NewsClawStateKeys.CACHE_READ_TOKENS,
                    currentState.value(NewsClawStateKeys.CACHE_READ_TOKENS, 0) + result.cacheReadTokens());
            map.put(NewsClawStateKeys.CACHE_WRITE_TOKENS,
                    currentState.value(NewsClawStateKeys.CACHE_WRITE_TOKENS, 0) + result.cacheWriteTokens());
            map.put(NewsClawStateKeys.REASONING_TOKENS,
                    currentState.value(NewsClawStateKeys.REASONING_TOKENS, 0) + result.reasoningTokens());
            map.put(NewsClawStateKeys.LLM_CALL_COUNT, existingLlmCalls + 1);
            return this;
        }

        /**
         * 将一个 step 的累计 usage（含 cache / reasoning 分项）加到 state 已有值上。
         * StepExecutionNode 在多个出口路径上写回同一组键，统一走这里避免漏项。
         */
        public OutputBuilder addStepUsage(OverAllState currentState,
                                          int promptTokens, int completionTokens,
                                          int cacheReadTokens, int cacheWriteTokens,
                                          int reasoningTokens) {
            map.put(NewsClawStateKeys.PROMPT_TOKENS,
                    currentState.value(NewsClawStateKeys.PROMPT_TOKENS, 0) + promptTokens);
            map.put(NewsClawStateKeys.COMPLETION_TOKENS,
                    currentState.value(NewsClawStateKeys.COMPLETION_TOKENS, 0) + completionTokens);
            map.put(NewsClawStateKeys.CACHE_READ_TOKENS,
                    currentState.value(NewsClawStateKeys.CACHE_READ_TOKENS, 0) + cacheReadTokens);
            map.put(NewsClawStateKeys.CACHE_WRITE_TOKENS,
                    currentState.value(NewsClawStateKeys.CACHE_WRITE_TOKENS, 0) + cacheWriteTokens);
            map.put(NewsClawStateKeys.REASONING_TOKENS,
                    currentState.value(NewsClawStateKeys.REASONING_TOKENS, 0) + reasoningTokens);
            return this;
        }

        public Map<String, Object> build() {
            return map;
        }
    }
}
