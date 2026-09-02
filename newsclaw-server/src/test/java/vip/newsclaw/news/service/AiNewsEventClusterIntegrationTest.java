package vip.newsclaw.news.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.news.model.AiNewsEventClusterEntity;
import vip.newsclaw.news.model.AiNewsEventClusterLineageEntity;
import vip.newsclaw.news.model.AiNewsEventClusterReviewEntity;
import vip.newsclaw.news.model.AiNewsEventClusterVersionEntity;
import vip.newsclaw.news.model.AiNewsEventEntity;
import vip.newsclaw.news.repository.AiNewsEventClusterLineageMapper;
import vip.newsclaw.news.repository.AiNewsEventClusterMapper;
import vip.newsclaw.news.repository.AiNewsEventClusterReviewMapper;
import vip.newsclaw.news.repository.AiNewsEventClusterVersionMapper;
import vip.newsclaw.news.repository.AiNewsEventMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:ai_news_event_cluster;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "newsclaw.ai-news.ingestion.enabled=false",
        "newsclaw.ai-news.content-extraction.enabled=false",
        "newsclaw.ai-news.clustering.enabled=true"
})
@Transactional
class AiNewsEventClusterIntegrationTest {

    @Autowired
    private AiNewsEventClusterService clusterService;
    @Autowired
    private AiNewsEventMapper eventMapper;
    @Autowired
    private AiNewsEventClusterMapper clusterMapper;
    @Autowired
    private AiNewsEventClusterVersionMapper versionMapper;
    @Autowired
    private AiNewsEventClusterReviewMapper reviewMapper;
    @Autowired
    private AiNewsEventClusterLineageMapper lineageMapper;
    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void onlineAssignmentReviewMergeAndSplitPreserveVersionedLineage() {
        AiNewsEventEntity english = insert("event-a", "OpenAI releases GPT-6 reasoning model",
                "[\"OpenAI\",\"GPT-6\"]", LocalDateTime.of(2026, 8, 27, 8, 0));
        AiNewsEventEntity chinese = insert("event-b", "OpenAI 发布 GPT-6 推理模型",
                "[\"OpenAI\",\"GPT-6\"]", LocalDateTime.of(2026, 8, 27, 9, 0));
        AiNewsEventEntity differentProduct = insert("event-c",
                "OpenAI releases Sora 3 video model",
                "[\"OpenAI\",\"Sora 3\"]", LocalDateTime.of(2026, 8, 27, 10, 0));

        AiNewsEventClusterService.Assignment first = clusterService.assign(english);
        AiNewsEventClusterService.Assignment linked = clusterService.assign(chinese);
        AiNewsEventClusterService.Assignment review = clusterService.assign(differentProduct);

        assertEquals(first.clusterId(), linked.clusterId());
        assertNotEquals(first.clusterVersionId(), linked.clusterVersionId());
        assertFalse(first.reviewRequired());
        assertTrue(review.reviewRequired());
        assertNotEquals(first.clusterId(), review.clusterId());
        assertEquals(2, clusterService.detail(1L, first.clusterId())
                .currentVersion().getMemberCount());
        assertEquals(2, clusterService.detail(1L, first.clusterId()).versions().size());

        AiNewsEventClusterReviewEntity pending = reviewMapper.selectById(review.reviewId());
        assertEquals("PENDING", pending.getStatus());
        clusterService.resolveReview(1L, pending.getId(), "approve", "reviewer-a",
                "same vendor announcement family confirmed as one event for this fixture");

        var merged = clusterService.detail(1L, Math.min(first.clusterId(), review.clusterId()));
        assertEquals(3, merged.currentVersion().getMemberCount());
        assertEquals("APPROVED", reviewMapper.selectById(pending.getId()).getStatus());
        assertEquals(1L, lineageMapper.selectCount(
                new LambdaQueryWrapper<AiNewsEventClusterLineageEntity>()
                        .eq(AiNewsEventClusterLineageEntity::getOperationType, "MERGE")));
        assertEquals(1L, clusterMapper.selectCount(
                new LambdaQueryWrapper<AiNewsEventClusterEntity>()
                        .eq(AiNewsEventClusterEntity::getStatus, "merged")));

        var child = clusterService.split(1L, merged.cluster().getId(),
                List.of(differentProduct.getId()), "reviewer-b", "Sora is a distinct launch");
        assertEquals(1, child.currentVersion().getMemberCount());
        assertEquals(2, clusterService.detail(1L, merged.cluster().getId())
                .currentVersion().getMemberCount());
        assertEquals(1L, lineageMapper.selectCount(
                new LambdaQueryWrapper<AiNewsEventClusterLineageEntity>()
                        .eq(AiNewsEventClusterLineageEntity::getOperationType, "SPLIT")));

        List<AiNewsEventEntity> projection = List.of(english, chinese, differentProduct);
        clusterService.populateEventProjection(1L, projection);
        assertEquals(merged.cluster().getId(), english.getClusterId());
        assertEquals(merged.cluster().getId(), chinese.getClusterId());
        assertEquals(child.cluster().getId(), differentProduct.getClusterId());
        assertEquals(2, english.getClusterMemberCount());
        assertNotNull(english.getClusterAssignmentOrigin());
        assertEquals(0L, versionMapper.selectCount(
                new LambdaQueryWrapper<AiNewsEventClusterVersionEntity>()
                        .ne(AiNewsEventClusterVersionEntity::getConfigHash,
                                merged.currentVersion().getConfigHash())));
    }

    @Test
    void assignmentIsIdempotentAndBackfillDoesNotCreateVersionChurn() {
        AiNewsEventEntity event = insert("event-idempotent", "Anthropic launches Claude 5",
                "[\"Anthropic\",\"Claude 5\"]", LocalDateTime.of(2026, 8, 27, 11, 0));

        AiNewsEventClusterService.Assignment first = clusterService.assign(event);
        AiNewsEventClusterService.Assignment repeated = clusterService.assign(event);
        AiNewsEventClusterService.BackfillResult backfill = clusterService.backfill(1L, 100);

        assertEquals(first.clusterId(), repeated.clusterId());
        assertEquals(first.clusterVersionId(), repeated.clusterVersionId());
        assertEquals("ALREADY_ASSIGNED", repeated.decision());
        assertEquals(0, backfill.assigned());
        assertTrue(backfill.alreadyAssigned() >= 1);
        assertEquals(1L, versionMapper.selectCount(
                new LambdaQueryWrapper<AiNewsEventClusterVersionEntity>()
                        .eq(AiNewsEventClusterVersionEntity::getClusterId, first.clusterId())));
    }

    @Test
    void identicalObservationsStayWorkspaceIsolatedAndMetricsHaveBoundedLabels() {
        AiNewsEventEntity workspaceOne = insert(1L, "shared-event-key",
                "Anthropic launches Claude 5", "[\"Anthropic\",\"Claude 5\"]",
                LocalDateTime.of(2026, 8, 27, 11, 0));
        AiNewsEventEntity workspaceTwo = insert(2L, "shared-event-key",
                "Anthropic launches Claude 5", "[\"Anthropic\",\"Claude 5\"]",
                LocalDateTime.of(2026, 8, 27, 11, 0));

        var first = clusterService.assign(workspaceOne);
        var second = clusterService.assign(workspaceTwo);

        assertNotEquals(first.clusterId(), second.clusterId());
        assertEquals(1L, clusterService.detail(1L, first.clusterId()).cluster().getWorkspaceId());
        assertEquals(2L, clusterService.detail(2L, second.clusterId()).cluster().getWorkspaceId());
        assertEquals(404, assertThrows(NewsClawException.class,
                () -> clusterService.detail(1L, second.clusterId())).getCode());
        assertTrue(meterRegistry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("newsclaw.ai_news.clustering"))
                .flatMap(meter -> meter.getId().getTags().stream())
                .allMatch(tag -> Set.of("outcome", "operation").contains(tag.getKey())));
    }

    private AiNewsEventEntity insert(String key, String title, String entities,
                                     LocalDateTime sourcePublishedAt) {
        return insert(1L, key, title, entities, sourcePublishedAt);
    }

    private AiNewsEventEntity insert(long workspaceId, String key, String title, String entities,
                                     LocalDateTime sourcePublishedAt) {
        AiNewsEventEntity event = new AiNewsEventEntity();
        event.setWorkspaceId(workspaceId);
        event.setEventKey(key);
        event.setTitle(title);
        event.setSummary(title);
        event.setCategory("model");
        event.setEntitiesJson(entities);
        event.setStatus("candidate");
        event.setConfidence(0.0D);
        event.setRankingScore(0.5D);
        event.setClaimsJson("[]");
        event.setConflictsJson("[]");
        event.setDiscoveredAt(sourcePublishedAt.plusMinutes(5));
        event.setSourcePublishedAt(sourcePublishedAt);
        event.setCreateTime(LocalDateTime.now());
        event.setUpdateTime(LocalDateTime.now());
        event.setDeleted(0);
        eventMapper.insert(event);
        return event;
    }
}
