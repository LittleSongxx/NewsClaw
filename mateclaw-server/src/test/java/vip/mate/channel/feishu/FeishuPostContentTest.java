package vip.mate.channel.feishu;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.mate.channel.ChannelMessageRouter;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.workspace.conversation.model.MessageContentPart;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class FeishuPostContentTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FeishuChannelAdapter adapter;

    @BeforeEach
    void setUp() {
        ChannelEntity channel = new ChannelEntity();
        channel.setId(1L);
        channel.setChannelType("feishu");
        channel.setConfigJson("{\"app_id\":\"test\",\"app_secret\":\"test\"}");
        adapter = new FeishuChannelAdapter(channel, mock(ChannelMessageRouter.class), objectMapper);
    }

    @Test
    void parsesFlatPostShapeUsedByLongConnectionEvents() throws Exception {
        Map<String, Object> payload = objectMapper.readValue("""
                {
                  "title": "AI 动态",
                  "content": [
                    [{"tag":"text","text":"请汇总今天的最新动态"}],
                    [{"tag":"a","text":"官方来源","href":"https://example.com/news"}]
                  ]
                }
                """, new TypeReference<>() {});
        List<MessageContentPart> parts = new ArrayList<>();

        String text = adapter.parsePostContent("om_test", payload, parts);

        assertEquals("AI 动态\n请汇总今天的最新动态\n[官方来源](https://example.com/news)", text);
        assertEquals(1, parts.size());
        assertEquals(text, parts.getFirst().getText());
    }

    @Test
    void keepsLocalizedPostShapeCompatible() throws Exception {
        Map<String, Object> payload = objectMapper.readValue("""
                {"zh_cn":{"title":"","content":[[{"tag":"text","text":"候选事件"}]]}}
                """, new TypeReference<>() {});
        List<MessageContentPart> parts = new ArrayList<>();

        String text = adapter.parsePostContent("om_test", payload, parts);

        assertEquals("候选事件", text);
        assertEquals("候选事件", parts.getFirst().getText());
    }
}
