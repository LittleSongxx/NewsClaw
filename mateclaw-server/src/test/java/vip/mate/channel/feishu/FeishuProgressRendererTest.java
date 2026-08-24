package vip.mate.channel.feishu;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeishuProgressRendererTest {

    @Test
    @DisplayName("progress snapshots keep the previous card text as a prefix")
    void snapshotsAreAppendFriendly() {
        FeishuProgressRenderer renderer = new FeishuProgressRenderer(0, false, false);

        String first = renderer.snapshot();
        renderer.onEvent("tool_call_started", Map.of(
                "toolCallId", "c1", "toolName", "web_search"));
        String running = renderer.snapshot();
        renderer.onEvent("tool_call_completed", Map.of(
                "toolCallId", "c1", "toolName", "web_search", "success", true));
        String completed = renderer.snapshot();
        renderer.onPendingNarration("搜索结果显示今天有三条主要动态线索。", false);
        String narration = renderer.snapshot();
        renderer.onPendingNarration("现在打开关键页面阅读正文并抓取官方证据。", false);
        String nextNarration = renderer.snapshot();

        assertTrue(running.startsWith(first));
        assertTrue(completed.startsWith(running));
        assertTrue(narration.startsWith(completed));
        assertTrue(nextNarration.startsWith(narration));
    }

    @Test
    @DisplayName("identical stage narration is shown only once")
    void duplicateNarrationIsIgnored() {
        FeishuProgressRenderer renderer = new FeishuProgressRenderer(0, false, false);
        String text = "搜索结果显示今天有三条主要动态线索。";

        renderer.onPendingNarration(text, false);
        renderer.snapshot();
        renderer.onPendingNarration(text, false);
        String samePending = renderer.snapshot();
        renderer.commitNarration(text);
        renderer.onPendingNarration(text, false);
        String committed = renderer.snapshot();

        assertEquals(1, occurrences(committed, text));
        assertFalse(committed.contains(text + "\n• " + text));
        assertTrue(samePending.contains(text));
    }

    @Test
    @DisplayName("a provisional rehearsal can disappear without entering the final card")
    void provisionalNarrationIsDiscardedOnToolCompletion() {
        FeishuProgressRenderer renderer = new FeishuProgressRenderer(0, false, false);
        renderer.onPendingNarration("预测温度是 29 度。", true);
        String live = renderer.snapshot();

        // Unverified pre-tool text is intentionally held out of the card. If
        // it were rendered and later removed, CardKit would receive a
        // non-prefix snapshot and replay the whole card from the beginning.
        assertFalse(live.contains("29 度"));

        String beforeTool = live;
        renderer.onEvent("tool_call_started", Map.of(
                "toolCallId", "c1", "toolName", "query_env"));
        String running = renderer.snapshot();
        assertTrue(running.startsWith(beforeTool));
        assertTrue(running.contains("正在执行工具"));
        renderer.onEvent("tool_call_completed", Map.of(
                "toolCallId", "c1", "toolName", "query_env", "success", true));
        String afterObservation = renderer.snapshot();
        String completed = renderer.completedSnapshot("接口没有返回环境数据。");

        assertTrue(afterObservation.startsWith(running));
        assertFalse(afterObservation.contains("29 度"));
        assertFalse(completed.contains("29 度"));
        assertTrue(completed.contains("接口没有返回环境数据"));
        assertTrue(completed.startsWith(afterObservation));
    }

    @Test
    @DisplayName("repeated provisional narration never creates a non-prefix card frame")
    void repeatedProvisionalNarrationKeepsPrefix() {
        FeishuProgressRenderer renderer = new FeishuProgressRenderer(0, false, false);
        String first = renderer.snapshot();

        renderer.onPendingNarration("搜索结果显示今天有三条主要动态线索。现在打开关键页面阅读正文并抓取官方证据。", true);
        String provisional = renderer.snapshot();
        renderer.onPendingNarration("搜索结果显示今天有三条主要动态线索。现在打开关键页面阅读正文并抓取官方证据。", true);
        String repeated = renderer.snapshot();
        renderer.onEvent("tool_call_started", Map.of(
                "toolCallId", "c1", "toolName", "browser_use"));
        String running = renderer.snapshot();
        renderer.onEvent("tool_call_completed", Map.of(
                "toolCallId", "c1", "toolName", "browser_use", "success", true));
        String completed = renderer.snapshot();

        assertEquals(first, provisional,
                "a provisional rehearsal is held internally and must not be sent to CardKit");
        assertEquals(provisional, repeated,
                "repeating the same rehearsal must not trigger another card update");
        assertTrue(running.startsWith(repeated));
        assertTrue(completed.startsWith(running));
        assertEquals(0, occurrences(completed, "搜索结果显示今天有三条主要动态线索"),
                "an unverified rehearsal must never enter the streamed card");
    }

    @Test
    @DisplayName("answer chunks append without replaying earlier answer text")
    void answerChunksRemainPrefix() {
        FeishuProgressRenderer renderer = new FeishuProgressRenderer(0, false, false);
        renderer.onContentDelta("第一段");
        String first = renderer.snapshot();
        renderer.onContentDelta("第二段");
        String second = renderer.snapshot();
        String completed = renderer.completedSnapshot("第一段第二段");

        assertTrue(second.startsWith(first));
        assertTrue(completed.startsWith(second));
        assertEquals(1, occurrences(completed, "第一段"));
    }

    @Test
    @DisplayName("grounded narration remains a prefix when visible thinking follows")
    void groundedNarrationSurvivesLaterThinking() {
        FeishuProgressRenderer renderer = new FeishuProgressRenderer(0, true, false);
        renderer.onPendingNarration("关键页面已打开，正在核对官方证据。", false);
        String grounded = renderer.snapshot();

        renderer.onThinkingDelta("补充核对来源");
        String thinking = renderer.snapshot();

        assertTrue(thinking.startsWith(grounded));
        assertTrue(thinking.contains("关键页面已打开"));
        assertTrue(thinking.contains("补充核对来源"));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }
}
