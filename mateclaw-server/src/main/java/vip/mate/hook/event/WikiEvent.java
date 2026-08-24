package vip.mate.hook.event;

import java.time.Instant;
import java.util.Map;

/**
 * Wiki 知识库事件（wiki:processed | wiki:page_created | wiki:page_updated | wiki:reindexed）。
 *
 * @param type          事件子类型；必须以 {@code wiki:} 开头
 * @param timestamp     发生时间
 * @param knowledgeBaseId 知识库主键
 * @param rawMaterialId 原始材料主键（可为 null）
 * @param payload       pagesCreated / chunksProcessed / durationMs 等
 */
public record WikiEvent(
        String type,
        Instant timestamp,
        Long knowledgeBaseId,
        Long rawMaterialId,
        Map<String, Object> payload) implements MateHookEvent {

    public WikiEvent {
        if (type == null || !type.startsWith("wiki:")) {
            throw new IllegalArgumentException("WikiEvent.type must start with 'wiki:' but got: " + type);
        }
        if (timestamp == null) timestamp = Instant.now();
        payload = (payload == null) ? Map.of() : Map.copyOf(payload);
    }

    public static WikiEvent of(String action, Long knowledgeBaseId, Long rawMaterialId,
                               Map<String, Object> payload) {
        return new WikiEvent("wiki:" + action, Instant.now(), knowledgeBaseId, rawMaterialId, payload);
    }
}
