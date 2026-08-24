package vip.newsclaw.news.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.news.model.AiNewsEvidenceEntity;
import vip.newsclaw.news.model.AiNewsEventEntity;
import vip.newsclaw.news.repository.AiNewsEvidenceMapper;
import vip.newsclaw.news.repository.AiNewsEventMapper;
import vip.newsclaw.news.service.AiNewsEventService;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Versioned policy evaluation fixtures for the AI-news operational loop.
 *
 * <p>This is deliberately a deterministic policy suite, not a claim that a
 * mocked LLM evaluation measures live discovery or editorial quality. It
 * keeps the gates that prevent an unverified event from entering production
 * stable while the radar skill and model prompts evolve.</p>
 */
class AiNewsOpsPolicyEvaluationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("固定核验样本遵循官方优先、双独立来源和冲突阻断规则")
    void verificationPolicyFixtures() throws Exception {
        List<VerificationCase> cases = read("evals/ai-news/verification-policy-cases.json",
                new TypeReference<>() { });
        int passed = 0;
        for (VerificationCase fixture : cases) {
            boolean accepted = runVerification(fixture);
            boolean expected = "verified".equals(fixture.expected());
            assertEquals(expected, accepted, "fixture=" + fixture.id());
            passed++;
        }
        System.out.printf("AI_NEWS_POLICY_EVAL verificationPolicyPass=%d/%d%n", passed, cases.size());
    }

    @Test
    @DisplayName("固定 URL 样本保持事件指纹所依赖的 canonicalization 语义")
    void canonicalUrlFixtures() throws Exception {
        List<CanonicalUrlCase> cases = read("evals/ai-news/canonical-url-cases.json",
                new TypeReference<>() { });
        for (CanonicalUrlCase fixture : cases) {
            assertEquals(fixture.expected(), AiNewsEventService.canonicalUrl(fixture.input()),
                    "fixture=" + fixture.id());
        }
        System.out.printf("AI_NEWS_POLICY_EVAL canonicalUrlPass=%d/%d%n", cases.size(), cases.size());
    }

    private boolean runVerification(VerificationCase fixture) {
        AiNewsEventMapper events = mock(AiNewsEventMapper.class);
        AiNewsEvidenceMapper evidence = mock(AiNewsEvidenceMapper.class);
        AiNewsEventService service = new AiNewsEventService(events, evidence, objectMapper);
        AiNewsEventEntity event = new AiNewsEventEntity();
        event.setId(1001L);
        event.setWorkspaceId(7L);
        event.setTitle(fixture.id());
        event.setStatus("candidate");
        event.setDeleted(0);
        event.setConflictsJson(write(fixture.conflicts()));
        when(events.selectOne(any())).thenReturn(event);
        when(evidence.selectList(any())).thenReturn(fixture.evidence().stream()
                .map(this::toEntity)
                .toList());

        try {
            service.verify(7L, event.getId(), null, null);
            return "verified".equals(event.getStatus());
        } catch (NewsClawException blocked) {
            return false;
        }
    }

    private AiNewsEvidenceEntity toEntity(EvidenceFixture fixture) {
        AiNewsEvidenceEntity evidence = new AiNewsEvidenceEntity();
        evidence.setId(Math.abs((long) fixture.url().hashCode()));
        evidence.setEventId(1001L);
        evidence.setWorkspaceId(7L);
        evidence.setSourceTier(fixture.tier());
        evidence.setSourceUrl(fixture.url());
        evidence.setClaim("fixture claim");
        evidence.setConfidence(fixture.confidence());
        evidence.setVerified(false);
        evidence.setDeleted(0);
        return evidence;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> List<T> read(String resource, TypeReference<List<T>> type) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return objectMapper.readValue(input, type);
        }
    }

    private record VerificationCase(String id, String expected, List<String> conflicts,
                                    List<EvidenceFixture> evidence) {
    }

    private record EvidenceFixture(String tier, String url, Double confidence) {
    }

    private record CanonicalUrlCase(String id, String input, String expected) {
    }
}
