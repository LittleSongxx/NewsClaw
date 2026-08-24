package vip.newsclaw.channel.feishu.cards.ai_news;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import vip.newsclaw.audit.service.AuditEventService;
import vip.newsclaw.channel.feishu.cards.FeishuCardKind;
import vip.newsclaw.news.service.AiNewsEventService;
import vip.newsclaw.news.service.AiNewsProductionService;

/** Spring factory for the AI-news editorial review card kind. */
@Component
public class AiNewsReviewCardKindFactory {

    public static final String KIND_NAME = "ai_news_review";

    private final AiNewsEventService eventService;
    private final AiNewsProductionService productionService;
    private final AuditEventService auditEventService;
    private final ObjectMapper objectMapper;

    public AiNewsReviewCardKindFactory(AiNewsEventService eventService,
                                       AiNewsProductionService productionService,
                                       AuditEventService auditEventService,
                                       ObjectMapper objectMapper) {
        this.eventService = eventService;
        this.productionService = productionService;
        this.auditEventService = auditEventService;
        this.objectMapper = objectMapper;
    }

    public FeishuCardKind<AiNewsReviewCardPayload> create() {
        AiNewsReviewButtonValue buttonValue = new AiNewsReviewButtonValue(objectMapper);
        return new FeishuCardKind<>(KIND_NAME, AiNewsReviewButtonValue.ACTION_PREFIX,
                AiNewsReviewCardPayload.class,
                new AiNewsReviewCardRenderer(buttonValue),
                new AiNewsReviewCardHandler(buttonValue, eventService, productionService,
                        auditEventService, objectMapper));
    }
}
