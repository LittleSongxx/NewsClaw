package vip.mate.hook.event;

import java.time.Instant;
import java.util.Map;

/**
 * 渠道消息事件（channel:received | channel:sent | channel:error | channel:health_changed）。
 *
 * @param type         事件子类型；必须以 {@code channel:} 开头
 * @param timestamp    发生时间
 * @param channelType  渠道类型（web / telegram / feishu / ...）
 * @param channelId    渠道实例 ID
 * @param payload      消息内容摘要（sha256）、方向、状态码等
 */
public record ChannelEvent(
        String type,
        Instant timestamp,
        String channelType,
        Long channelId,
        Map<String, Object> payload) implements MateHookEvent {

    public ChannelEvent {
        if (type == null || !type.startsWith("channel:")) {
            throw new IllegalArgumentException("ChannelEvent.type must start with 'channel:' but got: " + type);
        }
        if (timestamp == null) timestamp = Instant.now();
        payload = (payload == null) ? Map.of() : Map.copyOf(payload);
    }

    public static ChannelEvent of(String action, String channelType, Long channelId,
                                  Map<String, Object> payload) {
        return new ChannelEvent("channel:" + action, Instant.now(), channelType, channelId, payload);
    }
}
