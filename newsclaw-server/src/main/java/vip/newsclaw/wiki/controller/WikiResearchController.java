package vip.newsclaw.wiki.controller;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vip.newsclaw.channel.web.ChatStreamTracker;
import vip.newsclaw.channel.web.Utf8SseEmitter;
import vip.newsclaw.common.result.R;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.wiki.model.WikiKnowledgeBaseEntity;
import vip.newsclaw.wiki.service.WikiKnowledgeBaseService;
import vip.newsclaw.wiki.service.WikiResearchService;
import vip.newsclaw.workspace.core.annotation.RequireWorkspaceRole;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.time.Duration;

/**
 * RFC-011 Phase 3: Wiki Deep Research REST + SSE 接口
 *
 * @author NewsClaw Team
 */
@Slf4j
@Tag(name = "Wiki Deep Research")
@RestController
@RequestMapping("/api/v1/wiki/research")
@RequiredArgsConstructor
public class WikiResearchController {

    private final WikiResearchService researchService;
    private final WikiKnowledgeBaseService kbService;
    private final ChatStreamTracker streamTracker;

    private static final ExecutorService RESEARCH_EXEC = Executors.newVirtualThreadPerTaskExecutor();
    private final Cache<String, Long> sessionWorkspaces = Caffeine.newBuilder()
            .maximumSize(1_000)
            .expireAfterWrite(Duration.ofMinutes(15))
            .build();

    /**
     * 启动 research。返回 sessionId，前端用它订阅 SSE 流。
     */
    @RequireWorkspaceRole("member")
    @Operation(summary = "启动 Deep Research，返回 SSE sessionId")
    @PostMapping("/start")
    public R<Map<String, Object>> startResearch(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {

        if (body == null) return R.fail(400, "request body is required");

        Long kbId = body.get("kbId") != null ? Long.valueOf(body.get("kbId").toString()) : null;
        String topic = (String) body.get("topic");
        Integer topK = body.get("topKPerQuestion") != null
                ? Integer.valueOf(body.get("topKPerQuestion").toString()) : null;

        if (kbId == null || topic == null || topic.isBlank()) {
            return R.fail("kbId and topic are required");
        }
        long wsId = workspaceId != null ? workspaceId : 1L;
        verifyKBWorkspace(kbId, wsId);

        // 生成 SSE 会话 ID
        String sessionId = "research-" + UUID.randomUUID();
        sessionWorkspaces.put(sessionId, wsId);
        streamTracker.register(sessionId);

        // 异步跑 research，事件通过 streamTracker 推送
        // 【Review Bug 4】register 后需要 incrementFlux 配平，否则 complete 永远不会清理 RunState
        streamTracker.incrementFlux(sessionId);
        try {
            RESEARCH_EXEC.submit(() -> {
                try {
                    researchService.research(kbId, topic, sessionId, topK);
                } catch (Exception e) {
                    log.error("[ResearchController] Execution failed for sessionId={}: {}", sessionId, e.getMessage(), e);
                } finally {
                    // 先发结束标记，让前端关闭 EventSource
                    try {
                        streamTracker.broadcast(sessionId, "done", "{}");
                    } catch (Exception ignored) {}
                    // 然后清理 RunState（递减 flux count，所有 flux 完成时自动 remove）
                    try {
                        streamTracker.complete(sessionId);
                    } catch (Exception ignored) {}
                }
            });
        } catch (RejectedExecutionException busy) {
            streamTracker.complete(sessionId);
            sessionWorkspaces.invalidate(sessionId);
            return R.fail(503, "research executor is busy; retry later");
        }

        return R.ok(Map.of(
                "sessionId", sessionId,
                "kbId", kbId,
                "topic", topic,
                "streamUrl", "/api/v1/wiki/research/stream/" + sessionId
        ));
    }

    /** Cooperative cancellation for the internal research session. */
    @RequireWorkspaceRole("member")
    @Operation(summary = "取消 Deep Research 会话")
    @PostMapping("/{sessionId}/cancel")
    public R<Map<String, Object>> cancel(
            @PathVariable String sessionId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        long wsId = workspaceId != null ? workspaceId : 1L;
        Long ownerWorkspace = sessionWorkspaces.getIfPresent(sessionId);
        if (ownerWorkspace == null || ownerWorkspace != wsId) {
            return R.fail(404, "research session not found");
        }
        boolean stopped = streamTracker.requestStop(sessionId);
        return R.ok(Map.of("sessionId", sessionId, "status", stopped ? "cancelling" : "completed"));
    }

    /**
     * SSE 端点：订阅指定 sessionId 的 research 事件流
     */
    @RequireWorkspaceRole("viewer")
    @Operation(summary = "订阅 Deep Research SSE 事件流")
    @GetMapping(value = "/stream/{sessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @PathVariable String sessionId,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        // 10 分钟超时（research 典型 < 1 分钟，10 分钟给重连留余地）
        // RFC-058 PR-1: Utf8SseEmitter 显式 charset=UTF-8，防止中文 SSE 乱码
        SseEmitter emitter = new Utf8SseEmitter(10 * 60 * 1000L);

        long wsId = workspaceId != null ? workspaceId : 1L;
        Long ownerWorkspace = sessionWorkspaces.getIfPresent(sessionId);
        if (ownerWorkspace == null || ownerWorkspace != wsId) {
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data("{\"message\":\"session not found or not available in this workspace\"}"));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }

        boolean attached = streamTracker.attach(sessionId, emitter);
        if (!attached) {
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data("{\"message\":\"session not found or already ended\"}"));
                emitter.complete();
            } catch (Exception ignored) {}
        }

        emitter.onCompletion(() -> streamTracker.detach(sessionId, emitter));
        emitter.onTimeout(() -> streamTracker.detach(sessionId, emitter));
        emitter.onError(err -> streamTracker.detach(sessionId, emitter));

        return emitter;
    }

    private void verifyKBWorkspace(Long kbId, Long workspaceId) {
        WikiKnowledgeBaseEntity kb = kbService.getById(kbId);
        if (kb == null) {
            throw new NewsClawException(404, "Knowledge base not found");
        }
        if (kb.getWorkspaceId() == null || !kb.getWorkspaceId().equals(workspaceId)) {
            throw new NewsClawException("err.common.wrong_workspace", 403,
                    "Resource does not belong to current workspace");
        }
    }
}
