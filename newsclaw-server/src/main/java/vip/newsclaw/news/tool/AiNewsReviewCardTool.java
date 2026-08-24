package vip.newsclaw.news.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import vip.newsclaw.agent.context.ChatOrigin;
import vip.newsclaw.channel.ChannelAdapter;
import vip.newsclaw.channel.ChannelManager;
import vip.newsclaw.channel.ChannelSessionStore;
import vip.newsclaw.channel.feishu.FeishuChannelAdapter;
import vip.newsclaw.channel.feishu.cards.ai_news.AiNewsReviewCardPayload;
import vip.newsclaw.channel.model.ChannelSessionEntity;
import vip.newsclaw.news.model.AiNewsEventDetail;
import vip.newsclaw.news.model.AiNewsEventEntity;
import vip.newsclaw.news.model.AiNewsEvidenceEntity;
import vip.newsclaw.news.service.AiNewsEventService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Structured bridge from an agent turn to Feishu event-review cards. */
@Slf4j
@Component("aiNewsReviewCardTool")
@RequiredArgsConstructor
public class AiNewsReviewCardTool {

    private static final int MAX_EVENTS_PER_CALL = 10;

    private final AiNewsEventService eventService;
    private final ChannelSessionStore channelSessionStore;
    private final ChannelManager channelManager;
    private final ObjectMapper objectMapper;

    @Tool(name = "ai_news_review_card", description = "将已经写入 ai_news_event 的候选事件发送为飞书交互复核卡。"
            + "必须直接使用 ai_news_event 返回的事件 ID，不得从自然语言猜测 ID。"
            + "仅适用于当前飞书人工会话；每个事件单独一张卡，按钮由后端状态机校验，候选事件不能直接启动 Team Run。")
    public String ai_news_review_card(
            @ToolParam(description = "事件 ID，多个 ID 用英文逗号分隔；必须来自 ai_news_event 的结构化返回")
            String eventIds,
            @Nullable ToolContext ctx) {
        try {
            ChatOrigin origin = ChatOrigin.from(ctx);
            validateHumanFeishuOrigin(origin);
            Long workspaceId = origin.workspaceId() == null ? 1L : origin.workspaceId();
            ChannelSessionEntity session = channelSessionStore.getSession(origin.conversationId());
            if (session == null || session.getTargetId() == null || session.getTargetId().isBlank()) {
                return error("当前飞书会话尚未建立可回复目标，请先在该会话发送一条消息");
            }
            if (!Objects.equals(session.getChannelId(), origin.channelId())) {
                return error("当前会话与飞书渠道不匹配");
            }
            ChannelAdapter active = channelManager.getAdapter(origin.channelId()).orElse(null);
            if (!(active instanceof FeishuChannelAdapter feishu)) {
                return error("当前飞书渠道未运行");
            }

            Set<Long> ids = parseIds(eventIds);
            List<String> sent = new ArrayList<>();
            List<Map<String, String>> failed = new ArrayList<>();
            for (Long id : ids) {
                try {
                    AiNewsEventDetail detail = eventService.get(workspaceId, id);
                    AiNewsReviewCardPayload payload = payload(detail, origin.requesterId());
                    if (feishu.sendAiNewsReviewCard(session.getTargetId(), payload)) {
                        sent.add(String.valueOf(id));
                    } else {
                        failed.add(Map.of("eventId", String.valueOf(id), "reason", "飞书发送失败"));
                    }
                } catch (Exception e) {
                    failed.add(Map.of("eventId", String.valueOf(id), "reason", safe(e.getMessage())));
                }
            }
            return objectMapper.writeValueAsString(Map.of(
                    "sent", sent,
                    "failed", failed,
                    "targetConversation", origin.conversationId()));
        } catch (Exception e) {
            log.debug("ai_news_review_card failed: {}", e.getMessage());
            return error(e.getMessage());
        }
    }

    private static AiNewsReviewCardPayload payload(AiNewsEventDetail detail, String requester) {
        AiNewsEventEntity event = detail.event();
        List<AiNewsEvidenceEntity> evidence = detail.evidence() == null ? List.of() : detail.evidence();
        int verified = (int) evidence.stream().filter(item -> Boolean.TRUE.equals(item.getVerified())).count();
        String primaryTier = evidence.stream().map(AiNewsEvidenceEntity::getSourceTier)
                .filter(Objects::nonNull).min((left, right) -> Integer.compare(rank(left), rank(right)))
                .orElse(null);
        return new AiNewsReviewCardPayload(event.getWorkspaceId(), requester, event.getId(),
                event.getTitle(), event.getSummary(), event.getCategory(), event.getStatus(),
                event.getConfidence(), evidence.size(), verified, primaryTier);
    }

    private static int rank(String tier) {
        if ("official".equalsIgnoreCase(tier)) return 0;
        if ("media".equalsIgnoreCase(tier)) return 1;
        if ("community".equalsIgnoreCase(tier)) return 2;
        return 3;
    }

    private static Set<Long> parseIds(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("eventIds 不能为空");
        Set<Long> ids = new LinkedHashSet<>();
        for (String token : raw.replace("[", "").replace("]", "").split("[,\\s]+")) {
            if (token.isBlank()) continue;
            long id = Long.parseLong(token.trim().replace("\"", ""));
            if (id <= 0) throw new IllegalArgumentException("eventId 必须为正整数");
            ids.add(id);
            if (ids.size() > MAX_EVENTS_PER_CALL) {
                throw new IllegalArgumentException("单次最多发送 " + MAX_EVENTS_PER_CALL + " 个事件");
            }
        }
        if (ids.isEmpty()) throw new IllegalArgumentException("eventIds 不能为空");
        return ids;
    }

    private static void validateHumanFeishuOrigin(ChatOrigin origin) {
        if (origin == null || !"feishu".equalsIgnoreCase(origin.channelType())
                || origin.channelId() == null || origin.conversationId() == null) {
            throw new IllegalArgumentException("ai_news_review_card 只能从当前飞书会话调用");
        }
        if (origin.requesterId() == null || origin.requesterId().isBlank()
                || "system".equalsIgnoreCase(origin.requesterId())) {
            throw new IllegalArgumentException("飞书复核卡必须绑定明确的人工请求者");
        }
    }

    private String error(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("error", safe(message)));
        } catch (Exception ignored) {
            return "{\"error\":\"ai_news_review_card failed\"}";
        }
    }

    private static String safe(String message) {
        if (message == null || message.isBlank()) return "未知错误";
        return message.length() <= 300 ? message : message.substring(0, 300);
    }
}
