package vip.newsclaw.memory.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vip.newsclaw.channel.web.Utf8SseEmitter;
import vip.newsclaw.memory.event.DreamCompletedEvent;
import vip.newsclaw.memory.event.DreamFailedEvent;
import vip.newsclaw.memory.service.DreamReport;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Broadcasts dream events to connected SSE clients.
 * Clients subscribe per agentId via GET /dream/events.
 *
 * @author NewsClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DreamEventBroadcaster {

    private final ObjectMapper objectMapper;
    private final List<EmitterEntry> emitters = new CopyOnWriteArrayList<>();

    record EmitterEntry(Long agentId, String ownerKey, SseEmitter emitter) {}

    /**
     * Register a new SSE emitter for an agent.
     */
    public SseEmitter register(Long agentId) {
        return register(agentId, null);
    }

    public SseEmitter register(Long agentId, String ownerKey) {
        // RFC-058 PR-1: Utf8SseEmitter 显式 charset=UTF-8，防止中文 SSE 乱码
        SseEmitter emitter = new Utf8SseEmitter(300_000L); // 5 min timeout
        EmitterEntry entry = new EmitterEntry(agentId, ownerKey, emitter);
        emitters.add(entry);
        emitter.onCompletion(() -> emitters.remove(entry));
        emitter.onTimeout(() -> emitters.remove(entry));
        emitter.onError(e -> emitters.remove(entry));
        log.debug("[DreamSSE] Client connected for agent={}, total={}", agentId, emitters.size());
        return emitter;
    }

    @Async
    @EventListener
    public void onDreamCompleted(DreamCompletedEvent event) {
        broadcast(event.report(), "dream.completed");
    }

    @Async
    @EventListener
    public void onDreamFailed(DreamFailedEvent event) {
        broadcast(event.report(), "dream.failed");
    }

    private void broadcast(DreamReport report, String eventType) {
        Long agentId = report.agentId();
        String json;
        try {
            json = objectMapper.writeValueAsString(Map.of(
                    "type", eventType,
                    "agentId", agentId,
                    "mode", report.mode().name(),
                    "topic", report.topic() != null ? report.topic() : "",
                    "status", report.status().name(),
                    "promotedCount", report.promotedCount(),
                    "rejectedCount", report.rejectedCount()
            ));
        } catch (Exception e) {
            log.warn("[DreamSSE] Failed to serialize event: {}", e.getMessage());
            return;
        }

        List<EmitterEntry> dead = new java.util.ArrayList<>();
        for (EmitterEntry entry : emitters) {
            if (!entry.agentId().equals(agentId)
                    || !java.util.Objects.equals(entry.ownerKey(), report.ownerKey())) continue;
            try {
                entry.emitter().send(SseEmitter.event()
                        .name(eventType)
                        .data(json));
            } catch (Exception e) {
                dead.add(entry);
            }
        }
        emitters.removeAll(dead);
        log.debug("[DreamSSE] Broadcast {} to {} clients for agent={}", eventType, emitters.size(), agentId);
    }
}
