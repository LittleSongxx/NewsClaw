package vip.mate.news.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.news.model.AiNewsEventDetail;
import vip.mate.news.model.AiNewsEventEntity;
import vip.mate.news.model.AiNewsEvidenceCaptureRequest;
import vip.mate.news.model.AiNewsEvidenceEntity;
import vip.mate.news.model.AiNewsEventUpsertRequest;
import vip.mate.news.model.AiNewsLinkRequest;
import vip.mate.news.model.AiNewsProduceRequest;
import vip.mate.news.model.AiNewsVerifyRequest;
import vip.mate.news.service.AiNewsEventService;
import vip.mate.news.service.AiNewsProductionService;
import vip.mate.news.service.OfficialSourceEvidenceCaptureService;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

/** Workspace-scoped API for the AI-industry event evidence pipeline. */
@Tag(name = "AI 动态事件")
@RestController
@RequestMapping("/api/v1/ai-news/events")
@RequiredArgsConstructor
public class AiNewsEventController {

    private final AiNewsEventService eventService;
    private final AiNewsProductionService productionService;
    private final OfficialSourceEvidenceCaptureService officialCaptureService;

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "分页查询 AI 动态事件")
    @GetMapping
    public R<IPage<AiNewsEventEntity>> list(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return R.ok(eventService.page(workspaceId, page, size, category, status, keyword));
    }

    @RequireWorkspaceRole("viewer")
    @Operation(summary = "查看事件和来源证据")
    @GetMapping("/{id}")
    public R<AiNewsEventDetail> get(
            @PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(eventService.get(workspaceId, id));
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "写入或更新候选事件")
    @PostMapping
    public R<AiNewsEventEntity> upsert(
            @RequestBody AiNewsEventUpsertRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(eventService.upsert(workspaceId, request));
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "核验事件来源")
    @PostMapping("/{id}/verify")
    public R<AiNewsEventEntity> verify(
            @PathVariable Long id,
            @RequestBody(required = false) AiNewsVerifyRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(eventService.verify(workspaceId, id,
                request == null ? null : request.verdict(),
                request == null ? null : request.confidence()));
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "忽略候选事件")
    @PostMapping("/{id}/dismiss")
    public R<AiNewsEventEntity> dismiss(
            @PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(eventService.dismiss(workspaceId, id));
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "只读抓取官方来源并归档证据，不自动核验事件")
    @PostMapping("/{id}/capture-official")
    public R<AiNewsEvidenceEntity> captureOfficial(
            @PathVariable Long id,
            @RequestBody AiNewsEvidenceCaptureRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(officialCaptureService.capture(workspaceId, id,
                request == null ? null : request.sourceUrl(), request == null ? null : request.claim()));
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "将已核验事件送入内容生产")
    @PostMapping("/{id}/produce")
    public R<AiNewsEventEntity> produce(
            @PathVariable Long id,
            @RequestBody(required = false) AiNewsProduceRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        AiNewsEventEntity event = eventService.beginProduction(workspaceId, id);
        if (request != null && Boolean.TRUE.equals(request.startTeamRun())) {
            event = productionService.start(workspaceId, id);
        }
        return R.ok(event);
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "确认内容已交付")
    @PostMapping("/{id}/published")
    public R<AiNewsEventEntity> published(
            @PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(eventService.markPublished(workspaceId, id));
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "关联 Team Run")
    @PostMapping("/{id}/run")
    public R<AiNewsEventEntity> linkRun(
            @PathVariable Long id,
            @RequestBody AiNewsLinkRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(eventService.linkRun(workspaceId, id, request == null ? null : request.id()));
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "关联公众号或小红书内容")
    @PostMapping("/{id}/content")
    public R<AiNewsEventEntity> linkContent(
            @PathVariable Long id,
            @RequestBody AiNewsLinkRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(eventService.linkContent(workspaceId, id,
                request == null ? null : request.id(), request == null ? null : request.platform()));
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "关联 Wiki 证据页")
    @PostMapping("/{id}/wiki")
    public R<AiNewsEventEntity> linkWiki(
            @PathVariable Long id,
            @RequestBody AiNewsLinkRequest request,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(eventService.linkWiki(workspaceId, id, request == null ? null : request.id()));
    }

    @RequireWorkspaceRole("member")
    @Operation(summary = "归档事件")
    @PostMapping("/{id}/archive")
    public R<AiNewsEventEntity> archive(
            @PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(eventService.archive(workspaceId, id));
    }
}
