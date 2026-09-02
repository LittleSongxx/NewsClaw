package vip.newsclaw.news.model;

import java.util.List;

public record AiNewsEventClusterMergeRequest(List<Long> clusterIds, String note) {
}
