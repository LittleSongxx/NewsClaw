package vip.newsclaw.trigger.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.newsclaw.common.result.R;
import vip.newsclaw.trigger.ingest.TriggerEventEnvelope;
import vip.newsclaw.trigger.ingest.TriggerEventIngestService;
import vip.newsclaw.trigger.model.TriggerEntity;
import vip.newsclaw.trigger.service.TriggerService;
import vip.newsclaw.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;
import java.util.Map;

/**
 * REST surface for cron / event triggers. The generic event ingest endpoint
 * exists so external systems (n8n, GitHub webhooks, ad-hoc curl) can post
 * events without going through a dedicated channel adapter — useful for
 * smoke-testing a trigger before the channel integration lands.
 */
@Tag(name = "触发器管理")
@RestController
@RequestMapping("/api/v1/triggers")
@RequiredArgsConstructor
public class TriggerController {

    private final TriggerService triggerService;
    private final TriggerEventIngestService ingestService;

    @Operation(summary = "List triggers in the caller's workspace.")
    @GetMapping
    @RequireWorkspaceRole("admin")
    public R<List<TriggerEntity>> list(@RequestHeader("X-Workspace-Id") long workspaceId) {
        return R.ok(triggerService.listByWorkspace(workspaceId));
    }

    @Operation(summary = "Get a trigger by id, scoped to the caller's workspace.")
    @GetMapping("/{id}")
    @RequireWorkspaceRole("admin")
    public R<TriggerEntity> get(@PathVariable long id,
                                @RequestHeader("X-Workspace-Id") long workspaceId) {
        TriggerEntity row = triggerService.get(id, workspaceId);
        if (row == null) return R.fail("trigger not found: " + id);
        return R.ok(row);
    }

    @Operation(summary = "Create a trigger; if enabled, registers it with the scheduler.")
    @PostMapping
    @RequireWorkspaceRole("admin")
    public R<TriggerEntity> create(@RequestBody TriggerEntity trigger,
                                   @RequestHeader("X-Workspace-Id") long workspaceId) {
        if (trigger == null) return R.fail(400, "request body is required");
        // The controller forces workspace from the trusted header — the
        // body's workspaceId is ignored so a caller can't plant a trigger
        // into another workspace by tweaking the JSON.
        try {
            return R.ok(triggerService.create(trigger, workspaceId));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "Update a trigger; pattern_version bumps when the cron expression changes.")
    @PutMapping("/{id}")
    @RequireWorkspaceRole("admin")
    public R<TriggerEntity> update(@PathVariable long id,
                                   @RequestBody TriggerEntity trigger,
                                   @RequestHeader("X-Workspace-Id") long workspaceId) {
        if (trigger == null) return R.fail(400, "request body is required");
        try {
            return R.ok(triggerService.update(id, workspaceId, trigger));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        }
    }

    @Operation(summary = "Delete a trigger and unregister its schedule.")
    @DeleteMapping("/{id}")
    @RequireWorkspaceRole("admin")
    public R<Void> delete(@PathVariable long id,
                          @RequestHeader("X-Workspace-Id") long workspaceId) {
        triggerService.delete(id, workspaceId);
        return R.ok();
    }

    /**
     * Ingest one event envelope through the dedup / rate-limit / bot-self
     * pipeline. The endpoint is the operator-facing surface — workspace
     * is taken from the trusted {@code X-Workspace-Id} header. Body
     * {@code workspaceId} is intentionally ignored so a caller in
     * workspace A can't fan-fire triggers in workspace B by hand-rolling
     * a JSON body.
     *
     * <p>External webhooks should NOT use this endpoint directly —
     * production deployments wire their own signed-token webhook
     * (e.g. Feishu / DingTalk adapters) which authenticates first and
     * publishes a {@link vip.newsclaw.channel.event.ChannelMessageReceivedEvent}
     * with a workspace fixed by the channel-token mapping. The
     * {@code ChannelMessageEventBridge} then forwards into ingest.
     */
    @Operation(summary = "Ingest one event envelope; returns per-trigger fire / drop summary.")
    @PostMapping("/events")
    @RequireWorkspaceRole("member")
    public R<List<TriggerEventIngestService.IngestResult>> ingestEvent(
            @RequestBody EventIngestRequest body,
            @RequestHeader("X-Workspace-Id") long workspaceId) {
        if (workspaceId <= 0) return R.fail("workspace id must be positive");
        if (body == null || body.patternType() == null || body.patternType().isBlank()) {
            return R.fail("patternType is required");
        }
        TriggerEventEnvelope env = new TriggerEventEnvelope(
                // Header wins — body.workspaceId is dropped on purpose.
                workspaceId,
                body.patternType(),
                body.eventId(),
                body.senderId(),
                body.data() == null ? Map.of() : body.data());
        return R.ok(ingestService.ingest(env));
    }

    /** {@code workspaceId} is retained on the request shape for backwards
     *  compatibility but ignored at the controller — the trusted header
     *  is the source of truth. */
    public record EventIngestRequest(
            long workspaceId,
            String patternType,
            String eventId,
            String senderId,
            Map<String, Object> data) {}
}
