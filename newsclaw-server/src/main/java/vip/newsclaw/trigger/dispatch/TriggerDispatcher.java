package vip.newsclaw.trigger.dispatch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vip.newsclaw.agent.context.ChannelTarget;
import vip.newsclaw.agent.context.ChatOrigin;
import vip.newsclaw.config.EnvironmentConfig;
import vip.newsclaw.trigger.ingest.TriggerEventEnvelope;
import vip.newsclaw.trigger.model.TriggerEntity;
import vip.newsclaw.workflow.compiler.PebbleSubsetEvaluator;
import vip.newsclaw.workflow.runtime.WorkflowRunRequest;
import vip.newsclaw.workflow.runtime.WorkflowRunResult;
import vip.newsclaw.workflow.runtime.WorkflowRunner;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;

/**
 * Translates a fired trigger into a workflow run. Renders the trigger's
 * {@code payloadTemplate} as JSON via Pebble, parses the result into the
 * input map, and asks the runner to execute the latest revision of the
 * target workflow. Logs and swallows failures so a bad trigger never takes
 * the scheduler thread down.
 */
@Slf4j
@Component
public class TriggerDispatcher {

    public static final int MAX_TRIGGER_DEPTH = 8;

    private static final TypeReference<Map<String, Object>> MAP_REF = new TypeReference<>() {};

    private final WorkflowGraphLoader graphLoader;
    private final WorkflowRunner runner;
    private final PebbleSubsetEvaluator pebble;
    private final ObjectMapper objectMapper;

    public TriggerDispatcher(WorkflowGraphLoader graphLoader,
                             WorkflowRunner runner,
                             PebbleSubsetEvaluator pebble,
                             ObjectMapper objectMapper) {
        this.graphLoader = graphLoader;
        this.runner = runner;
        this.pebble = pebble;
        this.objectMapper = objectMapper;
    }

    /**
     * Dispatch a single fire of {@code trigger}. {@code event} is the
     * source-event context (cron tick metadata, channel message, etc.) —
     * its top-level fields are exposed to the payload template under
     * {@code event.*}. Returns a {@link DispatchResult} so the caller
     * can distinguish a real fire from a pre-flight skip or a runner
     * failure and update {@code fireCount} / {@code lastFiredAt} /
     * {@code lastError} accordingly.
     */
    public DispatchResult dispatch(TriggerEntity trigger, Map<String, Object> event) {
        if (trigger == null) {
            return DispatchResult.failed("trigger is required");
        }
        // Keep this overload for the cron scheduler and older callers. New
        // event sources use the typed envelope overload so provenance is not
        // discarded at the dispatch boundary.
        TriggerEventEnvelope envelope = new TriggerEventEnvelope(
                trigger.getWorkspaceId() == null ? 0L : trigger.getWorkspaceId(),
                trigger.getPatternType(), null, null, event);
        return dispatch(trigger, envelope);
    }

    /** Dispatch a fired trigger while retaining source/origin metadata. */
    public DispatchResult dispatch(TriggerEntity trigger, TriggerEventEnvelope envelope) {
        if (trigger == null) {
            return DispatchResult.failed("trigger is required");
        }
        if (!"workflow".equalsIgnoreCase(trigger.getTargetType())) {
            log.warn("Trigger {} target_type {} not supported in v0; skipping fire",
                    trigger.getId(), trigger.getTargetType());
            return DispatchResult.skipped(
                    "unsupported target_type: " + trigger.getTargetType());
        }
        if (envelope != null && (envelope.triggerDepth() >= MAX_TRIGGER_DEPTH
                || envelope.triggerAncestry().contains(trigger.getId()))) {
            return DispatchResult.skipped("trigger loop/depth guard rejected chain");
        }
        // Workspace-scoped lookup so a workspace A trigger can never fire
        // a workspace B workflow even if fixture data / manual imports /
        // a service-bypass path somehow planted a cross-workspace
        // targetId. The loader returns missing() on mismatch.
        if (trigger.getWorkspaceId() == null || trigger.getWorkspaceId() <= 0) {
            return DispatchResult.failed("trigger workspace is required");
        }
        long workspaceId = trigger.getWorkspaceId();
        WorkflowGraphLoader.Loaded loaded = graphLoader.load(trigger.getTargetId(), workspaceId);
        if (loaded.graph() == null) {
            log.info("Trigger {} dispatch skipped: no published revision for workflow {} in workspace {}",
                    trigger.getId(), trigger.getTargetId(), workspaceId);
            return DispatchResult.skipped(
                    "no published revision for workflow " + trigger.getTargetId());
        }

        Map<String, Object> inputs;
        try {
            inputs = renderInputs(trigger, envelope == null ? Map.of() : envelope.data());
        } catch (Exception e) {
            return DispatchResult.failed("payload render failed: " + e.getMessage());
        }
        ChatOrigin origin = originFor(trigger, envelope, workspaceId);
        List<Long> ancestry = new ArrayList<>(
                envelope == null ? List.of() : envelope.triggerAncestry());
        ancestry.add(trigger.getId());
        int depth = envelope == null ? 1 : envelope.triggerDepth() + 1;
        WorkflowRunRequest req = new WorkflowRunRequest(
                trigger.getTargetId(),
                loaded.revisionId(),
                workspaceId,
                "trigger:" + trigger.getId(),
                withDailyWindow(trigger, inputs),
                origin,
                ancestry,
                depth);
        try {
            WorkflowRunResult result = runner.run(loaded.graph(), req);
            if (result == null) {
                return DispatchResult.failed("runner returned null result");
            }
            // The runner's state taxonomy: succeeded / paused / running /
            // failed. Anything other than failed counts as a real fire — a
            // paused run still consumed the trigger and produced a
            // workflow_run row that the operator can resume.
            if ("failed".equalsIgnoreCase(result.state())) {
                return DispatchResult.failed(result.runId(),
                        "workflow run failed: "
                                + (result.errorMessage() == null ? "(no message)" : result.errorMessage()));
            }
            return DispatchResult.fired(result.runId());
        } catch (Exception e) {
            log.error("Trigger {} dispatch failed for workflow {}: {}",
                    trigger.getId(), trigger.getTargetId(), e.getMessage(), e);
            return DispatchResult.failed("runner threw: " + e.getMessage());
        }
    }

    /**
     * Build a durable origin for the source that fired a trigger. Channel and
     * webhook runs are deliberately non-cron so actor/target policy remains
     * enforceable after an async step or a pause/resume.
     */
    private static ChatOrigin originFor(TriggerEntity trigger,
                                        TriggerEventEnvelope envelope,
                                        long workspaceId) {
        String pattern = trigger == null || trigger.getPatternType() == null
                ? "system"
                : trigger.getPatternType().trim().toLowerCase(java.util.Locale.ROOT);
        Map<String, Object> data = envelope == null ? Map.of() : envelope.data();
        String conversationId = firstText(envelope == null ? null : envelope.conversationId(),
                data.get("conversationId"));
        String senderId = firstText(envelope == null ? null : envelope.senderId(),
                data.get("senderId"));
        String senderName = firstText(envelope == null ? null : envelope.senderName(),
                data.get("senderName"));
        String channelType = firstText(envelope == null ? null : envelope.channelType(),
                data.get("channelType"));
        String chatId = firstText(envelope == null ? null : envelope.chatId(),
                data.get("chatId"));
        Long channelId = envelope == null ? null : envelope.channelId();
        if (channelId == null) channelId = longValue(data.get("channelId"));
        // Mirror ChannelChatOriginFactory: group messages target chatId,
        // while private messages fall back to the stable sender id.
        String targetId = (chatId != null && !chatId.isBlank()) ? chatId : senderId;
        ChannelTarget channelTarget = (targetId == null || targetId.isBlank())
                ? null
                : new ChannelTarget(targetId, null, null);

        if ("cron".equals(pattern)) {
            return ChatOrigin.cron(conversationId, workspaceId, null, channelId, channelTarget);
        }
        if ("webhook".equals(pattern)) {
            // A generic webhook is an integration event, not proof of a
            // human editorial identity.  Never promote its caller-supplied
            // senderId into the durable requester field.
            return new ChatOrigin(null, conversationId,
                    "system",
                    workspaceId, null, null, null, false,
                    senderName, channelType == null ? "webhook" : channelType,
                    chatId, null, null, null);
        }
        if ("channel_message".equals(pattern) || "content_match".equals(pattern)) {
            // Only the channel bridge can assert that an external sender id
            // came from an authenticated channel adapter.  REST-created
            // envelopes use the compatibility constructors (trustedSource=
            // false), so a workspace member cannot impersonate an IM sender
            // and drive an editorial mutation through a workflow.
            String actor = envelope != null && envelope.trustedSource()
                    && senderId != null && !senderId.isBlank()
                    ? senderId : "system";
            return new ChatOrigin(null, conversationId, actor, workspaceId,
                    null, channelId, channelTarget, false, senderName, channelType,
                    chatId, null, null, null);
        }
        // agent_lifecycle / workflow_completion and future system events are
        // explicit non-cron origins.  Preserve an actor only when the source
        // adapter authenticated it.  Compatibility/REST envelopes are
        // untrusted even if their payload happens to contain a senderId;
        // otherwise a member could route a lifecycle event into an editorial
        // mutation and satisfy the "human origin" check by impersonation.
        String actor = envelope != null && envelope.trustedSource()
                && senderId != null && !senderId.isBlank()
                ? senderId : "system";
        return new ChatOrigin(null, conversationId,
                actor,
                workspaceId, null, null, null, false, senderName,
                channelType, chatId, null, null, null);
    }

    private static String firstText(String preferred, Object fallback) {
        if (preferred != null && !preferred.isBlank()) return preferred;
        if (fallback instanceof String value && !value.isBlank()) return value;
        return null;
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * Render the trigger's payload template into the workflow's input map.
     *
     * <p><b>Failure mode is strict.</b> If the template fails to parse,
     * fails to render, or produces output that isn't a JSON object, this
     * method throws and {@link #dispatch} returns
     * {@link DispatchResult#failed(String)} so the trigger row records a
     * non-null {@code last_error} and the operator can see why this fire
     * didn't run. The previous "fall back to raw event" behaviour is the
     * exact silent-failure trap the design forbade — a typo'd template
     * would keep firing the workflow with the wrong inputs and lastError
     * would stay clean.
     *
     * <p>An empty / null {@code payloadTemplate} is the explicit
     * opt-in to "use the raw event as inputs" — that path stays
     * supported because it's intentional, not accidental.
     */
    private Map<String, Object> renderInputs(TriggerEntity trigger, Map<String, Object> event) {
        if (trigger.getPayloadTemplate() == null || trigger.getPayloadTemplate().isBlank()) {
            return event == null ? Map.of() : event;
        }
        var compiled = pebble.parseTemplate(trigger.getPayloadTemplate());
        String rendered = pebble.evaluateAsString(compiled,
                Map.of("event", event == null ? Map.of() : event,
                        "trigger", Map.of(
                                "id", trigger.getId(),
                                "name", trigger.getName() == null ? "" : trigger.getName())));
        try {
            return objectMapper.readValue(rendered, MAP_REF);
        } catch (Exception e) {
            // Wrap so the dispatcher's catch surfaces the JSON parse failure
            // distinctly from a Pebble parse / evaluate failure.
            throw new RuntimeException("payloadTemplate produced non-JSON output: " + e.getMessage(), e);
        }
    }

    /**
     * Cron payloads must carry a server-frozen window; asking the model to
     * infer it makes daily runs non-reproducible and invites temporal leakage.
     */
    private Map<String, Object> withDailyWindow(TriggerEntity trigger,
                                                 Map<String, Object> inputs) {
        if (trigger == null || !"cron".equalsIgnoreCase(trigger.getPatternType())
                || !isDailyRadarTrigger(trigger)) {
            return inputs == null ? Map.of() : inputs;
        }
        java.time.Instant end = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        java.time.Instant start = end.minus(java.time.Duration.ofHours(24));
        Map<String, Object> result = new java.util.LinkedHashMap<>(inputs == null ? Map.of() : inputs);
        // Ignore model/template supplied values: the scheduler owns the
        // temporal boundary and must be the only clock used for evaluation.
        result.put("windowStart", start.toString());
        result.put("windowEnd", end.toString());
        return java.util.Collections.unmodifiableMap(result);
    }

    private boolean isDailyRadarTrigger(TriggerEntity trigger) {
        if (trigger == null) return false;
        if ("ai-news.template.v1.daily-radar".equals(trigger.getName())) return true;
        try {
            return EnvironmentConfig.AI_NEWS_DAILY_RADAR_MANAGED_KEY.equals(
                    objectMapper.readTree(trigger.getPatternJson()).path("managedKey").asText(null));
        } catch (Exception ignored) {
            return false;
        }
    }
}
