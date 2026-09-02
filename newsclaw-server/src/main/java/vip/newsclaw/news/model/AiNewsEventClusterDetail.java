package vip.newsclaw.news.model;

import java.util.List;

/** Auditable current snapshot plus immutable history for one event cluster. */
public record AiNewsEventClusterDetail(
        AiNewsEventClusterEntity cluster,
        AiNewsEventClusterVersionEntity currentVersion,
        List<AiNewsEventClusterMemberEntity> currentMemberships,
        List<AiNewsEventEntity> currentEvents,
        List<AiNewsEventClusterVersionEntity> versions,
        List<AiNewsEventClusterLineageEntity> lineage,
        List<AiNewsEventClusterReviewEntity> reviews) {
}
