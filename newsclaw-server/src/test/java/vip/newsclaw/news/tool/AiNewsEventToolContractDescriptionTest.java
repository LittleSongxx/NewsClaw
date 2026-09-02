package vip.newsclaw.news.tool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import vip.newsclaw.tool.ConcurrencyUnsafe;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AiNewsEventToolContractDescriptionTest {

    @Test
    void sourceHealthSchemaStatesItsExactMinimalArguments() {
        Method method = java.util.Arrays.stream(AiNewsEventTool.class.getDeclaredMethods())
                .filter(item -> item.getName().equals("ai_news_event"))
                .findFirst().orElseThrow();
        Tool tool = method.getAnnotation(Tool.class);
        ConcurrencyUnsafe concurrencyUnsafe = method.getAnnotation(ConcurrencyUnsafe.class);
        ToolParam action = method.getParameters()[0].getAnnotation(ToolParam.class);

        assertTrue(concurrencyUnsafe.value().contains("capture and event mutations"));
        assertTrue(tool.description().contains("{\"action\":\"source_health\"}"));
        assertTrue(tool.description().contains("不需要也不要提供 source ID"));
        assertTrue(action.description().contains("source_health 只传本字段"));
        assertTrue(tool.description().contains("capture_source(sourceUrl)"));
        assertTrue(tool.description().contains("quote"));
        assertTrue(tool.description().contains("[windowStart,windowEnd)"));
        assertTrue(tool.description().contains("discover 必须同时提供 windowStart 和 windowEnd"));
        assertTrue(tool.description().contains("window_summary"));
        assertTrue(tool.description().contains("每个 URL 必须串行完成"));
        assertTrue(tool.description().contains("captureId 必须从成功响应逐字复制"));
        assertTrue(tool.description().contains("excerpt 已是可直接引用的精确正文"));
        ToolParam captureId = method.getParameters()[26].getAnnotation(ToolParam.class);
        assertTrue(captureId.description().contains("禁止推算或改写"));
    }
}
