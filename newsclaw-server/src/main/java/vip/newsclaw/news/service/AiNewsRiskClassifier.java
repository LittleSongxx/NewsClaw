package vip.newsclaw.news.service;

import vip.newsclaw.news.model.AiNewsEvidenceEntity;
import vip.newsclaw.news.model.AiNewsEventEntity;

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Conservative, deterministic routing classifier for claims needing an original source. */
public final class AiNewsRiskClassifier {

    private static final Pattern HIGH_RISK = Pattern.compile(
            "(?:安全认证|安全审查|安全评估|安全合规|关键基础设施|漏洞|隐私|监管批准|法律合规|"
                    + "security\\s+(?:certification|review|assessment|compliance)|"
                    + "safety\\s+(?:certification|review|assessment|approval)|"
                    + "critical\\s+infrastructure|vulnerabilit(?:y|ies)|privacy\\s+(?:audit|compliance)|"
                    + "regulatory\\s+approval|legal\\s+compliance|compliance\\s+(?:audit|certification))",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private AiNewsRiskClassifier() {
    }

    public static boolean isHighRisk(AiNewsEventEntity event,
                                     Collection<AiNewsEvidenceEntity> evidence) {
        StringBuilder text = new StringBuilder();
        if (event != null) {
            append(text, event.getTitle());
            append(text, event.getSummary());
            append(text, event.getClaimsJson());
        }
        if (evidence != null) {
            evidence.stream().filter(Objects::nonNull)
                    .map(AiNewsEvidenceEntity::getClaim)
                    .forEach(value -> append(text, value));
        }
        return HIGH_RISK.matcher(text.toString().toLowerCase(Locale.ROOT)).find();
    }

    private static void append(StringBuilder text, String value) {
        if (value != null && !value.isBlank()) text.append('\n').append(value.trim());
    }
}
