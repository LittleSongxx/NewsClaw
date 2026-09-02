package vip.newsclaw.news.service;

import org.junit.jupiter.api.Test;
import vip.newsclaw.news.model.AiNewsEvidenceEntity;
import vip.newsclaw.news.model.AiNewsEventEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AiNewsEventRankingPolicyTest {

    @Test
    void ranksCapturedAttestedOfficialEvidenceAboveUnregisteredModelOnlyEvidence() {
        AiNewsSourceRegistry registry = new AiNewsSourceRegistry();
        AiNewsEventEntity event = new AiNewsEventEntity();
        event.setClaimsJson("[\"claim\"]");

        AiNewsEvidenceEntity official = evidence("https://openai.com/index/update",
                "DETERMINISTIC_EXTRACTIVE");
        official.setSourceCaptureId(10L);
        official.setFetchedAt(LocalDateTime.now());
        official.setHttpStatus(200);
        official.setContentHash("a".repeat(64));
        official.setFinalUrl(official.getSourceUrl());
        official.setSourcePublishedAt(LocalDateTime.now());
        AiNewsEvidenceEntity weak = evidence("https://unknown.example/story", "MODEL");

        assertTrue(AiNewsEventRankingPolicy.score(event, List.of(official), registry)
                > AiNewsEventRankingPolicy.score(event, List.of(weak), registry));
    }

    private static AiNewsEvidenceEntity evidence(String url, String origin) {
        AiNewsEvidenceEntity value = new AiNewsEvidenceEntity();
        value.setSourceUrl(url);
        value.setClaim("The company released Model X.");
        value.setQuote("The company released Model X.");
        value.setSemanticRelation("entails");
        value.setRelationOrigin(origin);
        value.setDeleted(0);
        return value;
    }
}
