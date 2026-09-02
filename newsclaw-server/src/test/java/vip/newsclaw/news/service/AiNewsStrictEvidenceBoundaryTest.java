package vip.newsclaw.news.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import vip.newsclaw.news.model.AiNewsEvidenceEntity;
import vip.newsclaw.news.model.AiNewsEventDetail;
import vip.newsclaw.news.model.AiNewsEventEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiNewsStrictEvidenceBoundaryTest {

    @Test
    void productionBoundaryRequiresEvidenceMarkerForEveryClaim() {
        AiNewsEventService events = mock(AiNewsEventService.class);
        AiNewsEvidenceBoundaryService boundary = new AiNewsEvidenceBoundaryService(events);
        ReflectionTestUtils.setField(boundary, "strictEvidenceBoundary", true);

        AiNewsEventEntity event = new AiNewsEventEntity();
        event.setId(99L);
        event.setWorkspaceId(7L);
        event.setStatus("in_production");
        AiNewsEvidenceEntity evidence = new AiNewsEvidenceEntity();
        evidence.setId(101L);
        evidence.setEventId(99L);
        evidence.setWorkspaceId(7L);
        evidence.setVerified(true);
        evidence.setClaim("Example released model v2");
        evidence.setQuote("Example released model v2 on September 1.");
        evidence.setSemanticRelation("entails");
        evidence.setSourceCaptureId(501L);
        evidence.setContentHash("a".repeat(64));
        evidence.setSourceUrl("https://example.com/news/v2");
        when(events.get(7L, 99L)).thenReturn(new AiNewsEventDetail(event, List.of(evidence), List.of()));

        assertFalse(boundary.validate(7L, 99L, "Example released model v2.").allowed());
        assertTrue(boundary.validate(7L, 99L, "Example released model v2. [evidence:101]").allowed());
    }
}
