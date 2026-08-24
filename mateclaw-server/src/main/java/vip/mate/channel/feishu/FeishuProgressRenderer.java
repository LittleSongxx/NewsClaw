package vip.mate.channel.feishu;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds the execution trace rendered inside a Feishu CardKit streaming card.
 *
 * <p>CardKit receives the complete element text on every update. The text is
 * therefore deliberately written as an append-only log: once a progress line
 * has been emitted it is never re-ordered, trimmed, or rewritten. A single
 * live line is kept at the end of the log and is first converted to history
 * when the execution moves on. This preserves the old snapshot as a prefix
 * of the next snapshot, so CardKit's typewriter renderer does not replay the
 * whole card on every status refresh.
 *
 * <p>Provisional pre-tool narration is kept in an internal pending slot but is
 * deliberately not rendered into the card. Once CardKit has seen text it cannot
 * be removed without breaking the prefix contract, so an unverified rehearsal
 * must never become part of a streamed snapshot. The visible live line remains
 * the generic preparation/tool status until grounded narration is available.
 */
final class FeishuProgressRenderer {

    private record ToolLine(String callId, String name, long startedAt,
                            Long finishedAt, boolean success) {}

    private record CompletedTool(String name, long seconds, boolean success) {}

    private enum LiveKind { STATUS, NARRATION }

    private final long startedAtMillis;
    private final boolean showThinking;
    private final boolean showToolTrace;
    private final Deque<ToolLine> toolLines = new ArrayDeque<>();
    private final Set<String> completedToolKeys = new HashSet<>();
    private final Set<String> narrationKeys = new HashSet<>();
    /** Exact text already sent to CardKit, including the stable trace header. */
    private final StringBuilder renderedHistory = new StringBuilder("**执行轨迹**");
    private final StringBuilder streamedAnswer = new StringBuilder();

    private int completedToolCount;
    private boolean thinkingSeen;
    private boolean contentSeen;
    private boolean approvalPending;
    private boolean thinkingBlockOpen;
    private boolean answerStarted;
    private String lastPlanStepLine;
    private String pendingNarration;
    private boolean pendingNarrationProvisional;
    private String liveLine;
    private LiveKind liveKind;

    FeishuProgressRenderer(long startedAtMillis, boolean showThinking, boolean showToolTrace) {
        this.startedAtMillis = startedAtMillis;
        this.showThinking = showThinking;
        this.showToolTrace = showToolTrace;
    }

    void onThinkingDelta(String delta) {
        thinkingSeen = true;
        if (!showThinking || delta == null || delta.isEmpty()) return;

        // A thinking block is streamed directly into the immutable prefix.
        // Keep the live status out of the way while chunks are arriving; this
        // avoids freezing the same "思考中" line once per token.
        if (!thinkingBlockOpen) {
            preserveGroundedNarrationBeforeAppend();
            freezeLive();
            appendRaw("\n\n> 💭 ");
            thinkingBlockOpen = true;
        }
        appendRaw(delta.replace("\n", "\n> "));
    }

    void onContentDelta(String delta) {
        contentSeen = true;
        if (delta == null || delta.isEmpty()) return;

        thinkingBlockOpen = false;
        // A final answer supersedes an unverified rehearsal, but a grounded
        // narration may already be visible in the live slot. Once CardKit has
        // seen that line it must remain in the immutable prefix; deleting it
        // here would make the next full snapshot start over from the top.
        preserveGroundedNarrationBeforeAppend();
        if (!answerStarted) {
            freezeLive();
            appendRaw("\n\n---\n\n");
            answerStarted = true;
        }
        appendRaw(delta);
        streamedAnswer.append(delta);
    }

    /** Returns true for transitions that should bypass the normal update throttle. */
    boolean onEvent(String eventType, Map<String, Object> data) {
        if (eventType == null) return false;
        switch (eventType) {
            case "tool_call_started" -> {
                thinkingBlockOpen = false;
                String callId = stringField(data, "toolCallId");
                if (callId != null && !callId.isBlank() && hasToolCall(callId)) {
                    return false; // duplicate event delivery
                }
                toolLines.addLast(new ToolLine(
                        callId,
                        stringField(data, "toolName"),
                        System.currentTimeMillis(), null, false));
                refreshLive();
                return true;
            }
            case "tool_call_completed" -> {
                thinkingBlockOpen = false;
                String callId = stringField(data, "toolCallId");
                String toolName = stringField(data, "toolName");
                boolean success = data == null || !Boolean.FALSE.equals(data.get("success"));
                CompletedTool completed = markToolCompleted(callId, toolName, success);
                if (completed == null) return false; // duplicate completion

                // The previous live line is now a historical step. A
                // provisional narration is discarded here, while a grounded
                // narration is retained as a completed line.
                freezeLive();
                appendHistoryLine(formatCompletedTool(completed));
                refreshLive();
                return true;
            }
            case "plan_step_started" -> {
                thinkingBlockOpen = false;
                Object index = data != null ? data.get("index") : null;
                String title = stringField(data, "title");
                String line = "📋 步骤" + (index != null ? " " + index : "")
                        + (title != null && !title.isBlank() ? "：" + title : "");
                if (Objects.equals(line, lastPlanStepLine)) return false;
                lastPlanStepLine = line;
                freezeLive();
                appendHistoryLine(line);
                refreshLive();
                return true;
            }
            case "tool_approval_requested" -> {
                thinkingBlockOpen = false;
                if (approvalPending) return false;
                approvalPending = true;
                freezeLive();
                refreshLive();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    void onPendingNarration(String text) {
        onPendingNarration(text, false);
    }

    /** Set the current narration and retain whether it is a pre-tool rehearsal. */
    void onPendingNarration(String text, boolean provisional) {
        String normalized = normalize(text);
        if (normalized == null) {
            discardPendingNarration();
            return;
        }
        if (narrationKeys.contains(normalized)) {
            // The model sometimes emits the same stage narration in successive
            // rounds. It is already visible in history; do not resurrect it.
            discardPendingNarration();
            return;
        }
        if (Objects.equals(normalized, pendingNarration)) {
            pendingNarrationProvisional = provisional;
            refreshLive();
            return;
        }
        if (pendingNarration != null) freezeLive();
        thinkingBlockOpen = false;
        pendingNarration = normalized;
        pendingNarrationProvisional = provisional;
        refreshLive();
    }

    /**
     * Move a narration from the live slot to immutable history. Duplicate
     * text is ignored for the lifetime of this stream.
     */
    void commitNarration(String text) {
        String normalized = normalize(text);
        if (normalized == null) return;
        boolean firstOccurrence = narrationKeys.add(normalized);
        if (Objects.equals(normalized, pendingNarration)) {
            // A tracker promotion makes a provisional line permanent.
            pendingNarrationProvisional = false;
            if (liveKind == LiveKind.NARRATION) {
                freezeLive();
            } else {
                pendingNarration = null;
                freezeLive();
                appendHistoryLine("• " + normalized);
            }
            return;
        }
        if (firstOccurrence) {
            // A promoted narration may arrive after a status line was already
            // rendered. Freeze that line before appending the new history row;
            // otherwise the row would be inserted in the middle of the old
            // snapshot and CardKit would have to replay the card.
            freezeLive();
            appendHistoryLine("• " + normalized);
        }
    }

    void clearPendingNarration() {
        discardPendingNarration();
    }

    /** Package-private for the adapter's provisional-narration bridge. */
    boolean hasPendingNarration() {
        return pendingNarration != null;
    }

    /** Drop the live line without adding it to the permanent trace. */
    void discardPendingNarration() {
        pendingNarration = null;
        pendingNarrationProvisional = false;
        if (liveKind == LiveKind.NARRATION) {
            liveLine = null;
            liveKind = null;
        }
    }

    boolean isApprovalPending() {
        return approvalPending;
    }

    String snapshot() {
        if (liveLine == null && !thinkingBlockOpen && !answerStarted) refreshLive();
        return composeSnapshot();
    }

    String completedSnapshot(String finalAnswer) {
        String answer = finalAnswer == null ? "" : finalAnswer.trim();
        thinkingBlockOpen = false;
        // Keep a grounded narration that was already rendered in the card.
        // Only an unverified pre-tool rehearsal may be discarded at terminal
        // assembly time.
        preserveGroundedNarrationBeforeAppend();

        if (!answer.isEmpty()) {
            if (!answerStarted) {
                freezeLive();
                appendRaw("\n\n---\n\n");
                appendRaw(answer);
                streamedAnswer.append(answer);
                answerStarted = true;
            } else {
                appendMissingFinalAnswer(answer);
            }
        }

        freezeLive();
        appendHistoryLine(approvalPending
                ? "⏸️ 等待工具审批（" + elapsed() + "）"
                : "✅ 已完成（" + elapsed() + "）");
        if (answer.isEmpty() && !approvalPending) {
            appendHistoryLine("（本轮没有产生回复内容）");
        }
        return renderedHistory.toString();
    }

    /**
     * Preserve visible, evidence-backed narration before appending a later
     * immutable block (thinking or the final answer). CardKit receives the
     * complete text on every update and requires old text to remain a prefix.
     */
    private void preserveGroundedNarrationBeforeAppend() {
        if (pendingNarration != null && !pendingNarrationProvisional) {
            commitNarration(pendingNarration);
        } else {
            discardPendingNarration();
        }
    }

    private void refreshLive() {
        if (thinkingBlockOpen) return;
        if (answerStarted) {
            // The answer is already an append-only tail. Do not put a mutable
            // status/narration line after it and then move that line on every
            // subsequent event. A terminal answer is the end of the trace.
            liveLine = null;
            liveKind = null;
            return;
        }
        // A pre-tool narration may be fabricated by the model. It is safe to
        // retain it for the tracker, but unsafe to put it in a CardKit frame:
        // removing it after the first tool observation would make the next
        // full snapshot non-prefix and trigger a complete replay.
        boolean renderNarration = pendingNarration != null && !pendingNarrationProvisional;
        String desired = renderNarration
                ? "🔄 当前：" + pendingNarration
                : "🔄 当前：" + statusLine();
        LiveKind desiredKind = renderNarration ? LiveKind.NARRATION : LiveKind.STATUS;
        if (Objects.equals(desired, liveLine) && desiredKind == liveKind) return;
        freezeLive();
        liveLine = desired;
        liveKind = desiredKind;
    }

    private String statusLine() {
        if (approvalPending) return "⏸️ 等待工具审批";
        if (contentSeen) return "✍️ 正在回复";
        ToolLine running = lastRunningTool();
        if (running != null) {
            return showToolTrace
                    ? "🔧 正在调用 " + displayName(running) + "…"
                    : "🔧 正在执行工具…";
        }
        return thinkingSeen ? "💭 思考中" : "🤔 准备中";
    }

    private CompletedTool markToolCompleted(String callId, String toolName, boolean success) {
        ToolLine match = null;
        for (ToolLine line : toolLines) {
            if (line.finishedAt() != null) continue;
            if ((callId != null && callId.equals(line.callId()))
                    || (callId == null && toolName != null && toolName.equals(line.name()))) {
                match = line;
            }
        }
        long now = System.currentTimeMillis();
        String completionKey;
        if (match == null) {
            completionKey = callId != null ? "id:" + callId
                    : "orphan:" + (toolName == null ? "tool" : toolName) + ":" + now;
            if (!completedToolKeys.add(completionKey)) return null;
            toolLines.addLast(new ToolLine(callId, toolName, now, now, success));
            completedToolCount++;
            return new CompletedTool(toolName, 0, success);
        }
        completionKey = match.callId() != null
                ? "id:" + match.callId()
                : "started:" + match.startedAt();
        if (!completedToolKeys.add(completionKey)) return null;

        long seconds = Math.max(0, (now - match.startedAt()) / 1000);
        Deque<ToolLine> rebuilt = new ArrayDeque<>(toolLines.size());
        for (ToolLine line : toolLines) {
            rebuilt.addLast(line == match
                    ? new ToolLine(match.callId(), match.name(), match.startedAt(), now, success)
                    : line);
        }
        toolLines.clear();
        toolLines.addAll(rebuilt);
        completedToolCount++;
        return new CompletedTool(match.name(), seconds, success);
    }

    private String formatCompletedTool(CompletedTool completed) {
        if (!showToolTrace) return "✅ 已执行 " + completedToolCount + " 项工具";
        return (completed.success() ? "✅ " : "❌ ")
                + (completed.name() == null || completed.name().isBlank() ? "工具" : completed.name())
                + (completed.success() ? " 完成" : " 失败")
                + "（" + completed.seconds() + " 秒）";
    }

    private boolean hasToolCall(String callId) {
        for (ToolLine line : toolLines) {
            if (callId.equals(line.callId())) return true;
        }
        return false;
    }

    private ToolLine lastRunningTool() {
        ToolLine running = null;
        for (ToolLine line : toolLines) {
            if (line.finishedAt() == null) running = line;
        }
        return running;
    }

    private void freezeLive() {
        if (liveLine == null) return;
        if (liveKind == LiveKind.NARRATION && pendingNarration != null) {
            if (!pendingNarrationProvisional) {
                // Keep the exact line that was sent in the previous snapshot.
                // CardKit can then append the next line instead of replaying
                // the card. The last line is visually the current step; once
                // another line follows it naturally becomes history.
                appendHistoryLine(liveLine);
            }
            pendingNarration = null;
            pendingNarrationProvisional = false;
        } else {
            // Do not rewrite the line (for example, by changing "当前" to a
            // bullet) because the previous snapshot must remain a literal
            // prefix of the next one.
            appendHistoryLine(liveLine);
        }
        liveLine = null;
        liveKind = null;
    }

    private void appendHistoryLine(String line) {
        if (line == null || line.isBlank()) return;
        if (renderedHistory.length() > 0
                && renderedHistory.charAt(renderedHistory.length() - 1) != '\n') {
            renderedHistory.append('\n');
        }
        renderedHistory.append(line);
    }

    private void appendRaw(String text) {
        if (text != null && !text.isEmpty()) renderedHistory.append(text);
    }

    private String composeSnapshot() {
        if (liveLine == null) return renderedHistory.toString();
        StringBuilder sb = new StringBuilder(renderedHistory);
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
        sb.append(liveLine);
        return sb.toString();
    }

    private void appendMissingFinalAnswer(String answer) {
        String streamed = streamedAnswer.toString();
        if (answer.equals(streamed)) return;
        if (answer.startsWith(streamed)) {
            String suffix = answer.substring(streamed.length());
            appendRaw(suffix);
            streamedAnswer.append(suffix);
        } else if (!streamed.startsWith(answer)) {
            // A final outbound filter may have changed text already streamed
            // in the progress card. Keep the stream intact and add the clean
            // terminal answer under a separator instead of rewriting history.
            appendRaw("\n\n---\n\n");
            appendRaw(answer);
            streamedAnswer.setLength(0);
            streamedAnswer.append(answer);
        }
    }

    private String elapsed() {
        long seconds = Math.max(0, (System.currentTimeMillis() - startedAtMillis) / 1000);
        return seconds < 60 ? "已 " + seconds + " 秒"
                : "已 " + (seconds / 60) + " 分 " + (seconds % 60) + " 秒";
    }

    private static String displayName(ToolLine line) {
        return line.name() != null && !line.name().isBlank() ? line.name() : "工具";
    }

    private static String stringField(Map<String, Object> data, String key) {
        Object value = data != null ? data.get(key) : null;
        return value != null ? value.toString() : null;
    }

    private static String normalize(String text) {
        if (text == null || text.isBlank()) return null;
        return text.trim()
                .replaceAll("\\s+", " ")
                .replaceAll("\\s+([，。！？；：、])", "$1")
                .replaceAll("([，。！？；：、])\\s+", "$1");
    }
}
