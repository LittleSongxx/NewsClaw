package vip.newsclaw.news.model;

import java.util.List;

public record AiNewsEventClusterSplitRequest(Long clusterId, List<Long> eventIds, String note) {
}
