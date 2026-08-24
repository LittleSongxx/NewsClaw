package vip.newsclaw.news.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.newsclaw.news.model.AiNewsEventDetail;
import vip.newsclaw.news.model.AiNewsEventEntity;
import vip.newsclaw.news.model.AiNewsEvidenceEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Regression coverage for the source-grounding gate on AI-news content. */
class AiNewsEvidenceBoundaryServiceTest {

    private AiNewsEventService events;
    private AiNewsEvidenceBoundaryService service;

    @BeforeEach
    void setUp() {
        events = mock(AiNewsEventService.class);
        service = new AiNewsEvidenceBoundaryService(events);
        when(events.get(7L, 99L)).thenReturn(detail(true));
    }

    @Test
    @DisplayName("official archived source and generated visual asset are allowed")
    void permitsArchivedSourceAndImageAsset() {
        String content = """
                来源：https://api-docs.deepseek.com/news/news260821/
                ![封面](https://cdn.example.com/generated-cover.png)
                <img src="https://images.example.com/card.png" />
                """;

        AiNewsEvidenceBoundaryService.ValidationResult result = service.validate(7L, 99L, content);

        assertTrue(result.allowed(), result.violationSummary());
        assertTrue(result.sourceSummary().contains("api-docs.deepseek.com"));
    }

    @Test
    @DisplayName("unarchived official X account cannot be invented as a second source")
    void rejectsUnarchivedOfficialX() {
        AiNewsEvidenceBoundaryService.ValidationResult result = service.validate(7L, 99L,
                "已确认事实，基于官方 API Docs 与 X 账号。");

        assertFalse(result.allowed());
        assertTrue(result.violationSummary().contains("官方 X/Twitter"), result.violationSummary());
    }

    @Test
    @DisplayName("a citation URL outside the event evidence packet is rejected")
    void rejectsUnexpectedCitationUrl() {
        AiNewsEvidenceBoundaryService.ValidationResult result = service.validate(7L, 99L,
                "参考 https://www.example.com/unverified-report");

        assertFalse(result.allowed());
        assertTrue(result.violationSummary().contains("未归档来源 URL"), result.violationSummary());
    }

    @Test
    @DisplayName("content cannot enter a packaging path when no verified evidence exists")
    void rejectsUnverifiedEvidence() {
        when(events.get(7L, 100L)).thenReturn(detail(false));

        AiNewsEvidenceBoundaryService.ValidationResult result = service.validate(7L, 100L, "待打包内容");

        assertFalse(result.allowed());
        assertTrue(result.violationSummary().contains("没有已核验"), result.violationSummary());
    }

    private static AiNewsEventDetail detail(boolean verified) {
        AiNewsEventEntity event = new AiNewsEventEntity();
        event.setId(99L);
        event.setWorkspaceId(7L);
        event.setStatus("in_production");
        AiNewsEvidenceEntity evidence = new AiNewsEvidenceEntity();
        evidence.setId(101L);
        evidence.setWorkspaceId(7L);
        evidence.setEventId(99L);
        evidence.setVerified(verified);
        evidence.setSourceTier("official");
        evidence.setSourceTitle("DeepSeek API Docs");
        evidence.setSourceUrl("https://api-docs.deepseek.com/news/news260821/");
        return new AiNewsEventDetail(event, List.of(evidence), List.of());
    }
}
