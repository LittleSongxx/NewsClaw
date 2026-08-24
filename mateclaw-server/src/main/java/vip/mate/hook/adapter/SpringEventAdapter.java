package vip.mate.hook.adapter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vip.mate.hook.event.MemoryEvent;
import vip.mate.hook.event.SessionEvent;
import vip.mate.hook.event.WikiEvent;
import vip.mate.memory.event.ConversationCompletedEvent;
import vip.mate.wiki.event.WikiProcessingEvent;

import java.util.Map;

/**
 * 把现有的 Spring {@code ApplicationEvent} 适配为 {@code MateHookEvent} 重新发布。
 *
 * <p>这样既不侵入现有 listener（它们仍订阅原事件），又让 hook bus 看到统一类型，
 * 避免每个域 listener 都要改代码。新增事件源时只需在此增加一个 {@code @EventListener} 方法。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpringEventAdapter {

    private final ApplicationEventPublisher publisher;

    @EventListener
    public void onWikiProcessing(WikiProcessingEvent e) {
        publisher.publishEvent(WikiEvent.of(
                "processed",
                e.getKbId(),
                e.getRawMaterialId(),
                Map.of("source", "WikiProcessingEvent")));
    }

    @EventListener
    public void onConversationCompleted(ConversationCompletedEvent e) {
        publisher.publishEvent(SessionEvent.of(
                "message",
                null,                              // 现有事件不含 numeric conversationId；已用字符串
                null,
                Map.of(
                        "conversationId", safe(e.conversationId()),
                        "agentId", safe(e.agentId()),
                        "messageCount", e.messageCount(),
                        "trigger", safe(e.triggerSource()))));

        // 同时作为 memory 域事件转发（触发记忆/skill 合成等下游）
        publisher.publishEvent(MemoryEvent.of(
                "written",
                "post-conversation",
                e.agentId(),
                Map.of(
                        "conversationId", safe(e.conversationId()),
                        "messageCount", e.messageCount())));
    }

    private static Object safe(Object v) { return v == null ? "" : v; }
}
