package vip.mate.channel.feishu.cards.ai_news;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.event.cardcallback.model.CallBackAction;
import com.lark.oapi.event.cardcallback.model.CallBackCard;
import com.lark.oapi.event.cardcallback.model.CallBackOperator;
import com.lark.oapi.event.cardcallback.model.CallBackToast;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerData;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import lombok.extern.slf4j.Slf4j;
import vip.mate.audit.service.AuditEventService;
import vip.mate.channel.feishu.FeishuChannelAdapter;
import vip.mate.channel.feishu.cards.FeishuCardHandler;
import vip.mate.exception.MateClawException;
import vip.mate.news.model.AiNewsEventEntity;
import vip.mate.news.service.AiNewsEventService;
import vip.mate.news.service.AiNewsProductionService;

import java.util.Map;
import java.util.Objects;

/** Executes one workspace-scoped editorial decision from a Feishu card click. */
@Slf4j
public class AiNewsReviewCardHandler implements FeishuCardHandler {

    private final AiNewsReviewButtonValue buttonValue;
    private final AiNewsEventService eventService;
    private final AiNewsProductionService productionService;
    private final AuditEventService auditEventService;
    private final ObjectMapper objectMapper;

    public AiNewsReviewCardHandler(AiNewsReviewButtonValue buttonValue,
                                   AiNewsEventService eventService,
                                   AiNewsProductionService productionService,
                                   AuditEventService auditEventService,
                                   ObjectMapper objectMapper) {
        this.buttonValue = buttonValue;
        this.eventService = eventService;
        this.productionService = productionService;
        this.auditEventService = auditEventService;
        this.objectMapper = objectMapper;
    }

    @Override
    public P2CardActionTriggerResponse handle(FeishuChannelAdapter adapter,
                                              P2CardActionTriggerData data) {
        CallBackAction callbackAction = data == null ? null : data.getAction();
        AiNewsReviewButtonValue.Decoded decoded = callbackAction == null
                ? null : buttonValue.decode(callbackAction.getValue());
        String clicker = operatorOpenId(data);
        if (decoded == null) return error("卡片参数无效，请刷新后重试");

        Long channelWorkspace = adapter == null || adapter.getChannelEntity() == null
                ? null : adapter.getChannelEntity().getWorkspaceId();
        if (!Objects.equals(channelWorkspace, decoded.workspaceId())) {
            log.warn("[feishu-ai-news] workspace mismatch: card={}, channel={}, event={}",
                    decoded.workspaceId(), channelWorkspace, decoded.eventId());
            audit(decoded, clicker, "workspace_rejected", "channel workspace mismatch");
            return error("该事件不属于当前工作区");
        }
        if (clicker == null || !clicker.equals(decoded.requesterOpenId())) {
            log.warn("[feishu-ai-news] requester mismatch for event={}", decoded.eventId());
            audit(decoded, clicker, "identity_rejected", "only the original requester may act");
            return warning("仅发起本次检索的用户可操作");
        }

        try {
            AiNewsEventEntity result = execute(decoded);
            audit(decoded, clicker, "succeeded", null);
            return resolved(decoded.action(), result);
        } catch (MateClawException e) {
            audit(decoded, clicker, "rejected", e.getMessage());
            return error(trim(e.getMessage(), 80));
        } catch (Exception e) {
            log.error("[feishu-ai-news] action failed for event={}: {}",
                    decoded.eventId(), e.getMessage(), e);
            audit(decoded, clicker, "failed", e.getMessage());
            return error("操作未生效，请稍后重试");
        }
    }

    private AiNewsEventEntity execute(AiNewsReviewButtonValue.Decoded decoded) {
        Long ws = decoded.workspaceId();
        Long id = decoded.eventId();
        // Resolve first so every action gets the same workspace/deleted guard.
        eventService.findEvent(ws, id);
        return switch (decoded.action()) {
            case CONTINUE -> eventService.continueResearch(ws, id);
            case VERIFY -> eventService.verify(ws, id, null, null);
            case CONFLICT -> eventService.verify(ws, id, "conflicted", null);
            case DISMISS -> eventService.dismiss(ws, id);
            case START_RUN -> {
                eventService.beginProduction(ws, id);
                yield productionService.start(ws, id);
            }
        };
    }

    private void audit(AiNewsReviewButtonValue.Decoded decoded, String clicker,
                       String outcome, String reason) {
        if (auditEventService == null || decoded == null) return;
        try {
            String detail = objectMapper.writeValueAsString(Map.of(
                    "channel", "feishu",
                    "action", decoded.action().wireValue(),
                    "outcome", outcome,
                    "reason", reason == null ? "" : trim(reason, 300)));
            auditEventService.recordAs("feishu:" + (clicker == null ? "unknown" : clicker),
                    decoded.workspaceId(), "ai-news.review-card.clicked", "AI_NEWS_EVENT",
                    String.valueOf(decoded.eventId()), "AI 动态飞书复核", detail);
        } catch (Exception ignored) {
            // Audit remains best-effort and must not change the editorial decision.
        }
    }

    private static P2CardActionTriggerResponse resolved(AiNewsReviewButtonValue.Action action,
                                                        AiNewsEventEntity event) {
        String title = switch (action) {
            case CONTINUE -> "已继续跟踪";
            case VERIFY -> "已核验通过";
            case CONFLICT -> "已标记冲突";
            case DISMISS -> "已忽略事件";
            case START_RUN -> "已启动内容生产";
        };
        String template = switch (action) {
            case VERIFY, START_RUN -> "green";
            case CONFLICT, DISMISS -> "red";
            default -> "blue";
        };
        String detail = "**事件**：" + markdown(event.getTitle()) + "\n"
                + "**事件 ID**：`" + event.getId() + "`\n"
                + "**当前状态**：" + markdown(event.getStatus())
                + (event.getTeamRunId() == null ? "" : "\n**Team Run ID**：`" + event.getTeamRunId() + "`");
        P2CardActionTriggerResponse response = new P2CardActionTriggerResponse();
        response.setToast(toast("info", title));
        CallBackCard card = new CallBackCard();
        card.setType("raw");
        card.setData(AiNewsReviewCardRenderer.resolvedCard(title, detail, template));
        response.setCard(card);
        return response;
    }

    private static P2CardActionTriggerResponse error(String message) {
        P2CardActionTriggerResponse response = new P2CardActionTriggerResponse();
        response.setToast(toast("error", message));
        return response;
    }

    private static P2CardActionTriggerResponse warning(String message) {
        P2CardActionTriggerResponse response = new P2CardActionTriggerResponse();
        response.setToast(toast("warning", message));
        return response;
    }

    private static CallBackToast toast(String type, String content) {
        CallBackToast toast = new CallBackToast();
        toast.setType(type);
        toast.setContent(content);
        return toast;
    }

    private static String operatorOpenId(P2CardActionTriggerData data) {
        CallBackOperator operator = data == null ? null : data.getOperator();
        return operator == null ? null : operator.getOpenId();
    }

    private static String markdown(String value) {
        return value == null ? "" : trim(value.replace("`", "\\`").replace("*", "\\*"), 300);
    }

    private static String trim(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
