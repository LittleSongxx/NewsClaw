package vip.newsclaw.news.model;

import lombok.Data;

/** Aggregated provider contribution for one candidate scan. */
@Data
public class AiNewsProviderYieldRow {
    private String providerId;
    private Integer candidateCount;
    private Integer selectedCount;
    private Integer marginalUniqueCount;
}
