package vip.newsclaw.news.workflow;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.newsclaw.agent.model.AgentEntity;
import vip.newsclaw.agent.repository.AgentMapper;
import vip.newsclaw.channel.model.ChannelEntity;
import vip.newsclaw.channel.repository.ChannelMapper;
import vip.newsclaw.trigger.model.TriggerEntity;
import vip.newsclaw.trigger.service.TriggerService;
import vip.newsclaw.workflow.model.WorkflowEntity;
import vip.newsclaw.workflow.service.WorkflowService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * News-specific Workflow/Trigger composition on top of the generic v0
 * workflow runtime. The generated graph is a reviewable draft; it is never
 * auto-published and its prompts explicitly route factual writes through
 * {@code ai_news_event} and the evidence/status gates.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiNewsWorkflowTemplateService {

    public static final String TEMPLATE_ID = "ai-news-content-ops-v1";
    public static final String WORKFLOW_NAME = "AI 动态内容运营闭环";

    private final AgentMapper agentMapper;
    private final ChannelMapper channelMapper;
    private final ObjectMapper objectMapper;
    private final WorkflowService workflowService;
    private final TriggerService triggerService;

    public AiNewsWorkflowTemplate preview(long workspaceId) {
        List<AgentEntity> agents = enabledAgents(workspaceId);
        Map<String, AgentEntity> roleAgents = resolveRoleAgents(agents);
        String channel = enabledChannels(workspaceId).stream()
                .map(ChannelEntity::getChannelType)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("TODO_SELECT_CHANNEL");
        List<String> missing = new ArrayList<>();
        for (String role : List.of("discover", "verify", "edit", "visual", "delivery")) {
            if (!roleAgents.containsKey(role)) missing.add("agent role: " + role);
        }
        if ("TODO_SELECT_CHANNEL".equals(channel)) missing.add("enabled delivery channel");
        // A channel type is only an adapter selector; dispatch still needs a
        // concrete recipient (chat/user/open_id/etc.). Keep the draft safe and
        // make readiness honest until an operator fills that target.
        missing.add("delivery target for channel: " + channel);
        String draft = serialize(buildGraph(roleAgents, channel));
        return new AiNewsWorkflowTemplate(TEMPLATE_ID, WORKFLOW_NAME,
                "按发现、证据核验、编辑与视觉并行、合规审批、渠道交付组织 AI 动态内容生产。",
                draft, triggerDrafts(), missing, missing.isEmpty());
    }

    /**
     * Create or reuse a workspace-scoped draft and install disabled trigger
     * rows idempotently. Publishing remains an explicit WorkflowService
     * operation so ACL/compiler diagnostics are visible to the operator.
     */
    @Transactional
    public InstallationResult install(long workspaceId, Long createdBy, boolean enableTriggers) {
        AiNewsWorkflowTemplate template = preview(workspaceId);
        WorkflowEntity workflow = workflowService.listByWorkspace(workspaceId).stream()
                .filter(row -> WORKFLOW_NAME.equals(row.getName()))
                .findFirst()
                .orElseGet(() -> {
                    WorkflowEntity row = new WorkflowEntity();
                    row.setWorkspaceId(workspaceId);
                    row.setName(WORKFLOW_NAME);
                    row.setDescription(template.description());
                    row.setEnabled(true);
                    row.setCreatedBy(createdBy);
                    return workflowService.create(row);
        });
        workflowService.saveDraft(workflow.getId(), workspaceId, template.draftJson(), createdBy);
        // A trigger targeting an unpublished draft with TODO delivery data
        // would repeatedly schedule a task that cannot be delivered. Keep
        // template triggers inert until the operator has resolved every
        // readiness item and explicitly publishes the reviewed graph.
        boolean triggersEnabled = enableTriggers && template.readyForPublish();
        if (enableTriggers && !triggersEnabled) {
            log.warn("AI-news workflow template for workspace {} has unresolved fields; triggers remain disabled: {}",
                    workspaceId, template.missingFields());
        }
        List<TriggerEntity> triggers = triggerServiceTemplate(workspaceId, workflow.getId(), triggersEnabled);
        return new InstallationResult(workflow, triggers, template.missingFields(), false);
    }

    private List<TriggerEntity> triggerServiceTemplate(long workspaceId, long workflowId, boolean enabled) {
        List<TriggerEntity> current = triggerService.listByWorkspace(workspaceId);
        List<TriggerEntity> result = new ArrayList<>();
        for (AiNewsWorkflowTemplate.TriggerDraft draft : triggerDrafts()) {
            TriggerEntity existing = current.stream()
                    .filter(row -> draft.stableKey().equals(row.getName()))
                    .findFirst().orElse(null);
            TriggerEntity value = existing == null ? new TriggerEntity() : existing;
            value.setName(draft.stableKey());
            value.setPatternType(draft.patternType());
            value.setPatternJson(draft.patternJson());
            value.setTargetType("workflow");
            value.setTargetId(workflowId);
            value.setPayloadTemplate(draft.payloadTemplate());
            value.setEnabled(enabled);
            value.setDeleted(0);
            if (existing == null) {
                result.add(triggerService.create(value, workspaceId));
            } else {
                result.add(triggerService.update(existing.getId(), workspaceId, value));
            }
        }
        return List.copyOf(result);
    }

    private Map<String, Object> buildGraph(Map<String, AgentEntity> roles, String channel) {
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(agentStep("discover", roles.get("discover"), "sequential",
                "发现候选动态。必须调用 ai_news_event(action=upsert) 写入事件和逐条 Evidence Packet；只记录来源支持的 claims，不得直接把搜索结果写成 verified。",
                "discovery_packet", "json"));
        steps.add(agentStep("verify", roles.get("verify"), "sequential",
                "核验上一步事件。检查官方来源或两个独立可信媒体、claim-quote 对齐和冲突；仅在门禁通过后调用 ai_news_event(action=mark_verified, eventId=...)。冲突必须阻断，不得自行改写来源。",
                "verification_result", "json"));
        steps.add(agentStep("editorial", roles.get("edit"), "fan_out",
                "只处理已经 verified 的事件。调用 gzh_package 生成带证据引用的公众号包；事件未进入生产或证据不完整时返回阻断原因。",
                "editorial_package", "json"));
        steps.add(agentStep("visual", roles.get("visual"), "fan_out",
                "只处理已经 verified 的事件。调用 xhs_package 生成不少于 3 张真实竖版卡片并保留 Evidence Packet 来源；不得杜撰事实。",
                "visual_package", "json"));
        steps.add(Map.of("name", "collect_packages", "mode", Map.of("type", "collect")));
        steps.add(Map.of("name", "human_approval", "mode", Map.of(
                "type", "await_approval", "approvalKind", "editorial",
                "approverChannels", List.of("web"),
                "approvalMessage", "请复核事件证据、公众号包和小红书包后批准交付。",
                "timeoutSecs", 86400)));
        steps.add(Map.of("name", "deliver", "mode", Map.of(
                "type", "dispatch_channel",
                "channels", List.of(channel),
                "targets", Map.of(channel, "TODO_TARGET_ID"),
                "content", "AI 动态内容生产完成，请打开审核记录和证据链接。")));
        String employeeId = roles.get("delivery") == null
                ? "TODO_EMPLOYEE_ID" : String.valueOf(roles.get("delivery").getId());
        steps.add(Map.of("name", "write_audit", "mode", Map.of(
                "type", "write_memory", "employeeId", employeeId,
                "file", "ai-news/content-ops.md", "mergeStrategy", "append",
                "content", "事件 {{ inputs.eventId }} 已完成人工审批，保留 Evidence Packet 和交付链接。")));
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("schemaVersion", "1.0");
        graph.put("inputs", List.of(Map.of("name", "topic", "type", "text"),
                Map.of("name", "eventId", "type", "text")));
        graph.put("steps", steps);
        return graph;
    }

    private static Map<String, Object> agentStep(String name, AgentEntity agent, String mode,
                                                  String prompt,
                                                  String outputVar, String outputType) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("name", name);
        if (agent == null) step.put("agentName", "TODO_" + name.toUpperCase() + "_AGENT");
        else step.put("agentId", agent.getId());
        step.put("mode", Map.of("type", mode));
        step.put("promptTemplate", prompt + " 输入事件 ID：{{ inputs.eventId }}，主题：{{ inputs.topic }}。");
        step.put("outputVar", outputVar);
        step.put("outputContentType", outputType);
        return step;
    }

    private List<AiNewsWorkflowTemplate.TriggerDraft> triggerDrafts() {
        return List.of(
                new AiNewsWorkflowTemplate.TriggerDraft(
                        "ai-news.template.v1.daily-radar",
                        "ai-news.template.v1.daily-radar", "cron",
                        "{\"cron\":\"0 0 8 * * ?\",\"timezone\":\"Asia/Shanghai\"}",
                        "{\"topic\":\"AI 行业动态\",\"source\":\"daily-radar\"}"),
                new AiNewsWorkflowTemplate.TriggerDraft(
                        "ai-news.template.v1.content-match",
                        "ai-news.template.v1.content-match", "content_match",
                        "{\"substring\":\"AI\"}", ""),
                new AiNewsWorkflowTemplate.TriggerDraft(
                        "ai-news.template.v1.webhook",
                        "ai-news.template.v1.webhook", "webhook", "{}", "")
        );
    }

    private List<AgentEntity> enabledAgents(long workspaceId) {
        return agentMapper.selectList(new LambdaQueryWrapper<AgentEntity>()
                .eq(AgentEntity::getWorkspaceId, workspaceId)
                .eq(AgentEntity::getEnabled, true)
                .orderByAsc(AgentEntity::getId));
    }

    private List<ChannelEntity> enabledChannels(long workspaceId) {
        return channelMapper.selectList(new LambdaQueryWrapper<ChannelEntity>()
                .eq(ChannelEntity::getWorkspaceId, workspaceId)
                .eq(ChannelEntity::getEnabled, true)
                .orderByAsc(ChannelEntity::getId));
    }

    private static Map<String, AgentEntity> resolveRoleAgents(List<AgentEntity> agents) {
        Map<String, AgentEntity> result = new LinkedHashMap<>();
        for (String role : List.of("discover", "verify", "edit", "visual", "delivery")) {
            agents.stream().filter(agent -> matchesRole(agent, role)).findFirst()
                    .ifPresent(agent -> result.put(role, agent));
        }
        return result;
    }

    private static boolean matchesRole(AgentEntity agent, String role) {
        String tags = agent.getTags() == null ? "" : agent.getTags().toLowerCase();
        String name = agent.getName() == null ? "" : agent.getName().toLowerCase();
        return tags.contains("ai-news") && tags.contains(role)
                || tags.contains("content-ops," + role)
                || name.contains(role);
    }

    private String serialize(Map<String, Object> value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize AI-news workflow template", e);
        }
    }

    public record InstallationResult(WorkflowEntity workflow,
                                     List<TriggerEntity> triggers,
                                     List<String> missingFields,
                                     boolean published) {
    }
}
