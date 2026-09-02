package vip.newsclaw.news.workflow;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.newsclaw.agent.model.AgentEntity;
import vip.newsclaw.agent.repository.AgentMapper;
import vip.newsclaw.channel.model.ChannelEntity;
import vip.newsclaw.channel.repository.ChannelMapper;
import vip.newsclaw.config.EnvironmentConfig;
import vip.newsclaw.trigger.model.TriggerEntity;
import vip.newsclaw.trigger.service.TriggerService;
import vip.newsclaw.workflow.model.WorkflowEntity;
import vip.newsclaw.workflow.service.WorkflowService;
import vip.newsclaw.news.service.AiNewsCandidatePipelineProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * News-specific Workflow/Trigger composition on top of the generic v0
 * workflow runtime. The generated graph is a reviewable draft; it is never
 * auto-published and its prompts explicitly route factual writes through
 * the candidate-first {@code ai_news_scan/query/review/promote} facade, with
 * {@code ai_news_event} retained only as an explicit compatibility path for
 * deployments that have not enabled the candidate pipeline.
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

    /** Use the bound Spring property in production; the environment fallback
     * keeps lightweight template tests and embedders source-compatible. */
    @Autowired(required = false)
    private AiNewsCandidatePipelineProperties candidatePipelineProperties;

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
            // A manually installed workflow may contain a fully configured
            // trigger, but the daily radar is never allowed to route into the
            // legacy Agent discovery fallback when candidate-first is off.
            boolean dailyRadar = "ai-news.template.v1.daily-radar".equals(draft.stableKey());
            value.setEnabled(enabled && (!dailyRadar || candidatePipelineEnabled()));
            value.setDeleted(0);
            if (existing == null) {
                result.add(triggerService.create(value, workspaceId));
            } else {
                result.add(triggerService.update(existing.getId(), workspaceId, value));
            }
        }
        return List.copyOf(result);
    }

    private boolean candidatePipelineEnabled() {
        return candidatePipelineProperties != null
                ? candidatePipelineProperties.isEnabled()
                : EnvironmentConfig.aiNewsCandidatePipelineEnabled();
    }

    private Map<String, Object> buildGraph(Map<String, AgentEntity> roles, String channel) {
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(agentStep("discover", roles.get("discover"), "sequential",
                discoveryPrompt(),
                "discovery_packet", "json"));
        steps.add(agentStep("verify", roles.get("verify"), "sequential",
                "核验上一步事件。上一步 discover 的完整输出在 {{ outputs.discovery_packet }}。若返回 candidate-only（没有 eventId/Evidence Packet），不要把 candidateId 当 eventId，也不要调用 mark_verified、ai_news_review 或 ai_news_promote；输出 status=candidate-only、candidateId 和阻断原因，交由已认证人工在工作台完成 review/promote。只有 candidate 已 SELECTED、人工 ACCEPTED 且 capture SUCCESS 时，人工 promotion 才能提交一条不超过 512 字符的原子 claim、逐字 quote、category、entities 与 semanticRelation；该入口只显式创建 candidate 状态的事件并绑定快照，不代表已核验或已发布。promotion 被拒或条件未满足时返回阻断，运行时会硬停止后续生产步骤。形成真实 eventId 后，先补齐每条 quote 对 claim 的 semanticRelation；关系不明时保持 unknown。模型自报关系不能直接上线，必须经人工关系复核，或把 claim 设为与 quote 完全相同的确定性摘录。随后调用 ai_news_event(action=mark_verified, eventId=...)，由后端按 URL 注册表、独立来源数、高风险和冲突确定性裁决。",
                "verification_result", "json"));
        steps.add(agentStep("editorial", roles.get("edit"), "fan_out",
                "只处理已经 verified 的事件。核验结果在 {{ outputs.verification_result }}；不得凭空补写缺失字段。调用 gzh_package 生成带证据引用的公众号包；事件未进入生产或证据不完整时返回阻断原因。",
                "editorial_package", "json"));
        steps.add(agentStep("visual", roles.get("visual"), "fan_out",
                "只处理已经 verified 的事件。核验结果在 {{ outputs.verification_result }}；不得凭空补写缺失字段。调用 xhs_package 生成不少于 3 张真实竖版卡片并保留 Evidence Packet 来源；不得杜撰事实。",
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

    /**
     * Keep the candidate and legacy event paths mutually exclusive.  The
     * workflow graph still has a single discover node, so the prompt is the
     * boundary that prevents a successful candidate scan from being counted a
     * second time through the old event tool.
     */
    private static String discoveryPrompt() {
        return "发现候选动态。先冻结 UTC 来源时间窗 windowStart/windowEnd，并记录本次配置与排序版本。"
                + "发现任务必须二选一且严禁混用：A、候选主线（仅当 ai_news_query 返回 candidatePipelineEnabled=true）先调用 ai_news_query（省略 scanRunId）读取最近 run、inProgress、fresh 标记和记分卡；"
                + "latestRun.inProgress=true（RUNNING/CANDIDATES_PERSISTED/CAPTURE_PENDING 等）时等待后重查，不得重复扫描；仅在 latestRun 缺失/过期/失败时调用 ai_news_scan，避免与每15分钟 scheduler 重复扫描；再用 ai_news_query 分页查看候选，人工决策时只调用 ai_news_review；candidateId 不能当 eventId。"
                + "候选主线只管理 candidate/capture；候选调用成功后，只有人工 ACCEPTED 且 capture SUCCESS 的 selected 候选才能调用 ai_news_promote 形成 candidate 状态事件，不能声称已核验或发布；"
                + "不要再调用兼容 ai_news_event(action=discover)、capture_source、read_capture 或 upsert，也不要重复计数。"
                + "若没有 accepted+success 条件或 promotion 入口不可用，明确报告阻断。"
                + "B、兼容事件主线（当 candidatePipelineEnabled=false，或 ai_news_scan 返回未启用/不可用）：先记录 candidate_pipeline_fallback 原因，"
                + "再调用兼容 ai_news_event(action=discover) 执行五条分组官方检索与五条垂类新闻检索（含可信媒体限定通道）的十查询，"
                + "并合并部署方配置的零 Web 搜索额度 RSS/Atom 结构化候选后做 RRF 融合，再针对覆盖缺口补检索；所有搜索结果和 browser 只作线索。"
                + "publishedAtHint（包括 feed 时间）只能帮助把明显越界候选后置，选择时以时效和主题相关性优先、来源等级其次，最终时间仍只认 capture；"
                + "有抓取上限时，先覆盖带窗口内结构化时间且标题明确为新闻动作的候选，再把剩余额度用于无时间提示的官方页。"
                + "兼容事件主线每个 URL 必须串行完成 capture_source、必要的 read_capture 和 upsert 后再处理下一条，"
                + "禁止并行批量 capture 后汇总长数字 ID；captureId 必须逐字复制成功响应。capture 返回的 excerpt 已是可直接引用的精确正文，"
                + "仅在所需原文不在 excerpt 且 truncated=true 时才按 nextOffset read_capture。随后以 captureId、一条不超过 512 字符的原子 claim、"
                + "逐字 quote、windowStart/windowEnd 调用 upsert。卡片标题摘要由 claim 派生；后端绑定 URL/正文哈希、页面发布时间，"
                + "并拦截引文中明确窗口外的事件动作日期。每条 quote 对 claim 只判断 semanticRelation 和 relationConfidence，不自行指定策略字段。";
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
                        "{\"cron\":\"0 0 8 * * ?\",\"timezone\":\"Asia/Shanghai\",\"managedKey\":\""
                                + EnvironmentConfig.AI_NEWS_DAILY_RADAR_MANAGED_KEY + "\"}",
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
