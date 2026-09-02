package vip.newsclaw.news.service;

import cn.hutool.json.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.newsclaw.agent.model.AgentEntity;
import vip.newsclaw.agent.repository.AgentMapper;
import vip.newsclaw.news.model.AiNewsEventEntity;
import vip.newsclaw.news.model.AiNewsModelRole;
import vip.newsclaw.news.model.AiNewsModelRoute;
import vip.newsclaw.team.event.TeamRunDispatchCommittedIntent;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.team.model.AgentTeamEntity;
import vip.newsclaw.team.model.AgentTeamMemberEntity;
import vip.newsclaw.team.model.TeamRole;
import vip.newsclaw.team.model.TeamRunCreateCommand;
import vip.newsclaw.team.model.TeamRunEntity;
import vip.newsclaw.team.model.TeamTaskCreateCommand;
import vip.newsclaw.team.model.TeamTaskEntity;
import vip.newsclaw.team.repository.AgentTeamMapper;
import vip.newsclaw.team.service.TeamRunService;
import vip.newsclaw.team.service.TeamService;
import vip.newsclaw.team.service.TeamTaskService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns an approved AI-news event into one durable, restartable Team Run.
 *
 * <p>The run is deliberately a small DAG rather than a fake "one prompt"
 * execution: evidence preparation precedes fact checking; both content
 * formats can then run in parallel; compliance waits for both and parks for
 * human approval.  The existing dispatcher/lease/heartbeat machinery owns
 * execution and recovery after this service commits.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiNewsProductionService {

    private static final String TEAM_NAME = "AI 动态内容生产组";

    private final AgentTeamMapper teamMapper;
    private final TeamService teamService;
    private final AgentMapper agentMapper;
    private final TeamRunService runService;
    private final TeamTaskService taskService;
    private final AiNewsEventService eventService;
    private final ApplicationEventPublisher eventPublisher;

    /** Optional setter keeps narrow service tests and legacy constructors stable. */
    private AiNewsModelRouter modelRouter;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setModelRouter(AiNewsModelRouter modelRouter) {
        this.modelRouter = modelRouter;
    }

    @Transactional
    public AiNewsEventEntity start(Long workspaceId, Long eventId) {
        AiNewsEventEntity event = eventService.findEventForUpdate(workspaceId, eventId);
        if (!"in_production".equals(event.getStatus())) {
            throw new NewsClawException(409, "只有进入内容生产的事件才能启动 Team Run");
        }
        if (event.getTeamRunId() != null) {
            return event;
        }

        AgentTeamEntity team = teamMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<AgentTeamEntity>lambdaQuery()
                        .eq(AgentTeamEntity::getWorkspaceId, event.getWorkspaceId())
                        .eq(AgentTeamEntity::getName, TEAM_NAME)
                        .eq(AgentTeamEntity::getDeleted, 0));
        if (team == null) {
            // The event remains in_production and can be retried after an
            // operator provisions the vertical team.  Do not lose the event
            // merely because a legacy database has not run the seed yet.
            log.warn("AI news event {} has no vertical team in workspace {}", eventId, workspaceId);
            return event;
        }

        TeamRunEntity run = runService.startRun(TeamRunCreateCommand.builder()
                .teamId(team.getId())
                .workspaceId(event.getWorkspaceId())
                .leadAgentId(team.getLeadAgentId())
                .leadConversationId("ai-news-event-" + eventId)
                // A stable negative origin id makes retries idempotent without
                // colliding with real chat message ids.
                .originMessageId(-Math.abs(eventId))
                .title("AI 动态内容生产：" + event.getTitle())
                .objective("围绕 AI 动态事件 #" + eventId
                        + " 完成证据核验、公众号文章、小红书卡片、合规扫描和人工审批交付。")
                .metadata(new JSONObject()
                        .set("workflow", "ai-news-content-ops")
                        .set("eventId", String.valueOf(eventId))
                        .set("eventKey", event.getEventKey())
                        .set("requiresHumanApproval", true)
                        .toString())
                .build());

        List<TeamTaskEntity> existing = taskService.listTasksByRun(run.getId());
        if (existing.isEmpty()) {
            Map<String, Long> members = resolveMembers(team);
            List<TeamTaskEntity> tasks = new ArrayList<>();
            tasks.add(createTask(team, run, members, eventId, "discover",
                    "整理事件证据包", "读取事件 #" + eventId
                            + " 的来源、claims 和引用片段，输出 NewsEvidencePacket；不得补写来源未支持的事实。"
                            + "必须实际调用 wiki_create_page（工具不可见时先 enable_tool），把结构化证据写入 AI 动态证据 Wiki，"
                            + "再调用 ai_news_event action=link_wiki 回写 pageId；仅生成 DOCX 或在结果中描述 Wiki 建议不算完成。",
                    List.of()));
            tasks.add(createTask(team, run, members, eventId, "verify",
                    "事实核验与冲突处理", "复核事件 #" + eventId
                            + " 的官方来源优先级、独立来源数量、claims 对齐和冲突状态。", ids(tasks)));
            TeamTaskEntity gzh = createTask(team, run, members, eventId, "edit",
                    "公众号文章打包交付", "仅使用已核验事件 #" + eventId
                            + " 生成带引用和事实边界的公众号文章。禁止使用首个、唯一、最强、领先、顶级、全网、史上等绝对化措辞；"
                            + "来源标签和 URL 只能来自该事件已归档 Evidence Packet，未归档 x.com/twitter.com 时不得写官方 X/Twitter。交付硬门槛：必须实际调用 gzh_package"
                            + "（工具不可见时先 enable_tool），传入 Markdown、topic、eventId='" + eventId + "' 和封面；"
                            + "工具返回后必须核对事件已出现 gzhContentItemId。若合规门禁阻断，必须替换命中词并重新调用，不能把阻断提示当作完成。"
                            + "结果中保留在线预览 URL、素材 ZIP URL 与内容日历 item id。"
                            + "DOCX、纯文本文章或仅说明后续步骤都不算完成，不得替代 gzh_package。",
                    ids(tasks));
            TeamTaskEntity xhs = createTask(team, run, members, eventId, "visual",
                    "小红书卡片打包交付", "基于已核验事件 #" + eventId
                            + " 生成小红书标题、正文和不少于 3 张真实 3:4 卡片素材。先加载 xhs_note Skill，并严格采用其中的 AI 科技媒体编辑风："
                            + "统一浅色信息基底与蓝/绿/珊瑚语义强调，禁止整页渐变、大 emoji、装饰圆球和空泛 CTA；按已确认事实、影响判断、后续观察拆卡。"
                            + "长模型名必须放入全宽正文或独占规格条，绝不放进编号/窄标签列；来源 footer 只能使用事件 Evidence Packet 已归档标题或域名，"
                            + "未归档 x.com/twitter.com 时不得出现官方 X/Twitter。"
                            + "交付硬门槛：先用 render_html_image"
                            + " 或 image_generate 得到至少 3 个可访问的竖版图片 URL，再实际调用 xhs_package"
                            + "（工具不可见时先 enable_tool），传入 topic 和 eventId='" + eventId + "'；"
                            + "工具返回后必须核对事件已出现 xhsContentItemId。若合规或画布布局门禁阻断，必须修改文案/拆卡后重新生成，不能把阻断提示当作完成。"
                            + "在结果中保留在线预览 URL、素材 ZIP URL 与内容日历 item id。"
                            + ""
                            + "DOCX、图片清单、设计说明或少于 3 张图片都不算完成。", ids(tasks));
            tasks.add(gzh);
            tasks.add(xhs);
            createTask(team, run, members, eventId, "delivery",
                    "合规扫描与人工审批", "汇总事件 #" + eventId + " 的 gzh_package 与 xhs_package 实际产物。"
                            + "必须实际调用 compliance_scan（工具不可见时先 enable_tool）扫描标题和正文，并在结果中给出命中数、风险等级、"
                            + "两个内容日历 item id 及预览/ZIP URL；调用 ai_news_event action=get 核对 gzhContentItemId、xhsContentItemId 和 wikiPageId 已回链。"
                            + "若任一打包产物缺失、少于 3 张小红书图片、事件关联缺失或命中高危词，"
                            + "明确阻断交付。公众号只允许在获得用户明确确认且账号凭证已配置后调用 gzh_publish action=draft；"
                            + "不得群发。完成检查后等待人工审批。",
                    List.of(gzh.getId(), xhs.getId()));
            runService.sealRunWithResult(run.getId(), team.getWorkspaceId());
        }

        eventService.linkRun(event.getWorkspaceId(), eventId, run.getId());
        // Dispatch is an after-commit side effect.  If the transaction rolls
        // back, neither the event link nor a half-created execution is exposed.
        eventPublisher.publishEvent(new TeamRunDispatchCommittedIntent(team.getId()));
        return eventService.findEvent(event.getWorkspaceId(), eventId);
    }

    private TeamTaskEntity createTask(AgentTeamEntity team, TeamRunEntity run,
                                      Map<String, Long> members, Long eventId, String role,
                                      String subject, String description,
                                      List<Long> blockedBy) {
        Long assignee = members.get(role);
        if (assignee == null) {
            throw new IllegalStateException("AI 动态团队缺少角色成员: " + role);
        }
        return taskService.createTask(TeamTaskCreateCommand.builder()
                .teamId(team.getId())
                .runId(run.getId())
                .subject(subject)
                .description(description)
                .assigneeAgentId(assignee)
                .createdByAgentId(team.getLeadAgentId())
                .blockedBy(blockedBy.isEmpty() ? null : blockedBy)
                .leadConversationId(run.getLeadConversationId())
                .channel("ai-news")
                // Keep the domain linkage structured.  Parsing an id back out
                // of editorial copy makes a harmless wording change corrupt
                // the event/run relationship and breaks replay tooling.
                .metadata(taskMetadata(eventId, role))
                .requireApproval("delivery".equals(role))
                .build());
    }

    /**
     * Keep the business completion contract next to the task definition. The
     * dispatcher uses this flag to reject a plausible-looking prose answer
     * when the editorial/visual member forgot to attach the actual generated
     * preview or material bundle.
     */
    private String taskMetadata(Long eventId, String role) {
        JSONObject metadata = new JSONObject()
                .set("eventId", String.valueOf(eventId))
                .set("role", role);
        AiNewsModelRole modelRole = modelRole(role);
        if (modelRouter != null && modelRole != null) {
            try {
                AiNewsModelRoute route = modelRouter.route(modelRole);
                metadata.set("modelRole", modelRole.token())
                        .set("modelProvider", route.provider())
                        .set("modelName", route.modelName())
                        .set("modelId", route.modelId())
                        .set("modelRouteConfigured", route.configured())
                        .set("modelRouteFallback", route.fallback())
                        .set("modelRouteReason", route.reason());
            } catch (Exception e) {
                // Routing metadata is observability. A broken optional
                // override must not prevent the durable news DAG from being
                // created; the worker will use its normal model fallback.
                log.warn("AI-news model route snapshot failed for role {}: {}",
                        modelRole.token(), e.getMessage());
            }
        }
        if ("edit".equals(role) || "visual".equals(role)) {
            metadata.set("deliverableRequired", true)
                    .set("deliverableKind", "content-package");
        }
        return metadata.toString();
    }

    private static AiNewsModelRole modelRole(String role) {
        if (role == null) return null;
        return switch (role) {
            case "discover" -> AiNewsModelRole.DISCOVERY;
            case "verify" -> AiNewsModelRole.VERIFICATION;
            case "edit" -> AiNewsModelRole.EDITORIAL;
            case "visual" -> AiNewsModelRole.VISUAL;
            case "delivery" -> AiNewsModelRole.DELIVERY;
            default -> null;
        };
    }

    private Map<String, Long> resolveMembers(AgentTeamEntity team) {
        Map<String, Long> result = new HashMap<>();
        for (AgentTeamMemberEntity member : teamService.listMembers(team.getId())) {
            if (TeamRole.LEAD.equals(member.getRole())) continue;
            AgentEntity agent = agentMapper.selectById(member.getAgentId());
            if (agent == null) continue;
            String tags = agent.getTags() == null ? "" : agent.getTags().toLowerCase(Locale.ROOT);
            for (String role : List.of("discover", "verify", "edit", "visual", "delivery")) {
                if (tags.contains("ai-news,content-ops," + role)
                        || tags.contains("," + role)
                        || (agent.getName() != null && agent.getName().contains(role))) {
                    result.putIfAbsent(role, agent.getId());
                }
            }
        }
        return result;
    }

    private static List<Long> ids(List<TeamTaskEntity> tasks) {
        return tasks.isEmpty() ? List.of() : List.of(tasks.get(tasks.size() - 1).getId());
    }
}
