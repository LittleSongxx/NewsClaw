package vip.newsclaw.agent.graph.node;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReasoningNodeLongFormPolicyTest {

    @Test
    void recognizesChineseAndNumericLongFormRequests() {
        assertEquals(10_000, ReasoningNode.requestedLongFormChars("请写一万字的行业报告").orElseThrow());
        assertEquals(3_500, ReasoningNode.requestedLongFormChars("请输出 3,500 字说明").orElseThrow());
        assertTrue(ReasoningNode.requestedLongFormChars("写 800 字摘要").isEmpty());
    }

    @Test
    void onlyAffirmativeArtifactRequestsPermitFileDelivery() {
        assertTrue(ReasoningNode.explicitlyRequestsArtifactDelivery("请将报告导出为 PDF 文件"));
        assertTrue(ReasoningNode.explicitlyRequestsArtifactDelivery("给我一份 Word 文档"));
        assertFalse(ReasoningNode.explicitlyRequestsArtifactDelivery("不要导出 PDF，直接在聊天中回复"));
        assertFalse(ReasoningNode.explicitlyRequestsArtifactDelivery("不用 Word 文档，直接输出正文"));
    }

    @Test
    void blocksArtifactToolCallForDirectLongFormChatRequest() {
        List<AssistantMessage.ToolCall> calls = List.of(
                new AssistantMessage.ToolCall("call-1", "function", "renderPdf", "{}"));

        assertTrue(ReasoningNode.hasDisallowedLongFormArtifactCall(
                "请直接写 3000 字分析，不要生成文件", calls));
        assertFalse(ReasoningNode.hasDisallowedLongFormArtifactCall(
                "请写 3000 字分析并导出为 PDF 文件", calls));
    }
}
