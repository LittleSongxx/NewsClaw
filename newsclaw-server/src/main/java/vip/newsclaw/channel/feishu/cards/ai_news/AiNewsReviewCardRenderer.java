package vip.newsclaw.channel.feishu.cards.ai_news;

import vip.newsclaw.channel.feishu.cards.FeishuCardRenderer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Renders one event per card so resolving it never removes sibling actions. */
public class AiNewsReviewCardRenderer implements FeishuCardRenderer<AiNewsReviewCardPayload> {

    private final AiNewsReviewButtonValue buttonValue;

    public AiNewsReviewCardRenderer(AiNewsReviewButtonValue buttonValue) {
        this.buttonValue = buttonValue;
    }

    @Override
    public Map<String, Object> render(AiNewsReviewCardPayload payload) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("template", headerTemplate(payload.status()));
        header.put("title", plainText("AI 动态待复核"));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("tag", "markdown");
        summary.put("content", summaryMarkdown(payload));

        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(summary);
        List<Map<String, Object>> actions = actions(payload);
        if (!actions.isEmpty()) {
            Map<String, Object> actionRow = new LinkedHashMap<>();
            actionRow.put("tag", "action");
            actionRow.put("actions", actions);
            elements.add(actionRow);
        }

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("config", Map.of("wide_screen_mode", true));
        card.put("header", header);
        card.put("elements", elements);
        return card;
    }

    private List<Map<String, Object>> actions(AiNewsReviewCardPayload payload) {
        String status = payload.status() == null ? "" : payload.status();
        List<Map<String, Object>> actions = new ArrayList<>();
        if ("candidate".equals(status) || "researching".equals(status)) {
            actions.add(button("继续跟踪", "default", AiNewsReviewButtonValue.Action.CONTINUE, payload));
            actions.add(button("核验通过", "primary", AiNewsReviewButtonValue.Action.VERIFY, payload));
            actions.add(button("标记冲突", "danger", AiNewsReviewButtonValue.Action.CONFLICT, payload));
            actions.add(button("忽略", "default", AiNewsReviewButtonValue.Action.DISMISS, payload));
        } else if ("conflicted".equals(status)) {
            actions.add(button("继续跟踪", "primary", AiNewsReviewButtonValue.Action.CONTINUE, payload));
            actions.add(button("忽略", "default", AiNewsReviewButtonValue.Action.DISMISS, payload));
        } else if ("verified".equals(status)) {
            actions.add(button("开始 Team Run", "primary", AiNewsReviewButtonValue.Action.START_RUN, payload));
        }
        return actions;
    }

    private Map<String, Object> button(String label, String type,
                                       AiNewsReviewButtonValue.Action action,
                                       AiNewsReviewCardPayload payload) {
        Map<String, Object> button = new LinkedHashMap<>();
        button.put("tag", "button");
        button.put("text", plainText(label));
        button.put("type", type);
        button.put("value", buttonValue.encode(action, payload.eventId(), payload.workspaceId(),
                payload.requesterOpenId()));
        return button;
    }

    private static String summaryMarkdown(AiNewsReviewCardPayload payload) {
        StringBuilder text = new StringBuilder();
        text.append("**").append(markdown(payload.title(), 180)).append("**\n");
        text.append("状态：").append(statusLabel(payload.status()))
                .append("　分类：").append(markdown(payload.category(), 32)).append("\n");
        text.append("证据：").append(payload.evidenceCount()).append(" 条")
                .append("（已核验 ").append(payload.verifiedEvidenceCount()).append("）")
                .append("　最高等级：").append(tierLabel(payload.primaryEvidenceTier())).append("\n");
        if (payload.confidence() != null) {
            text.append("置信度：").append(Math.round(payload.confidence() * 100)).append("%\n");
        }
        if (payload.summary() != null && !payload.summary().isBlank()) {
            text.append("\n").append(markdown(payload.summary(), 500)).append("\n");
        }
        text.append("\n事件 ID：`").append(payload.eventId()).append("`");
        return text.toString();
    }

    public static Map<String, Object> resolvedCard(String title, String detail, String template) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("config", Map.of("wide_screen_mode", true));
        card.put("header", Map.of("template", template, "title", plainText(title)));
        card.put("elements", List.of(Map.of("tag", "markdown", "content", detail)));
        return card;
    }

    private static Map<String, Object> plainText(String content) {
        return Map.of("tag", "plain_text", "content", content == null ? "" : content);
    }

    private static String markdown(String value, int max) {
        if (value == null || value.isBlank()) return "未提供";
        String clean = value.replace("\\", "\\\\").replace("`", "\\`")
                .replace("*", "\\*").replace("_", "\\_")
                .replace("[", "\\[").replace("]", "\\]").trim();
        return clean.length() <= max ? clean : clean.substring(0, max) + "...";
    }

    private static String statusLabel(String status) {
        return switch (status == null ? "" : status) {
            case "candidate" -> "候选";
            case "researching" -> "核验中";
            case "verified" -> "已核验";
            case "conflicted" -> "有冲突";
            case "in_production" -> "生产中";
            case "published" -> "已交付";
            case "rejected" -> "已忽略";
            default -> status == null ? "未知" : status;
        };
    }

    private static String tierLabel(String tier) {
        return switch (tier == null ? "" : tier) {
            case "official" -> "官方";
            case "media" -> "可信媒体";
            case "community" -> "社区";
            default -> "未分级";
        };
    }

    private static String headerTemplate(String status) {
        return switch (status == null ? "" : status) {
            case "verified" -> "green";
            case "conflicted" -> "red";
            case "researching" -> "yellow";
            default -> "blue";
        };
    }
}
