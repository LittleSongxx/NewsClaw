package vip.newsclaw.news.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Calibratable, provenance-bearing bounds for deterministic online clustering. */
@Data
@Component
@ConfigurationProperties(prefix = "newsclaw.ai-news.clustering")
public class AiNewsEventClusteringProperties {

    private boolean enabled = true;
    /** Candidate clusters are bounded before scoring; this is not a global all-pairs job. */
    private int maxCandidates = 300;
    /** TDT-style online link horizon for the initial AI-news business baseline. */
    private int maxCandidateAgeHours = 168;
    /** Cheap blocking threshold before the more expensive feature score. */
    private double minTitleBlockingSimilarity = 0.12D;
    /** Conservative automatic-link threshold; P0-E must recalibrate it on time-split gold. */
    private double autoLinkThreshold = 0.80D;
    /** Borderline links remain separate clusters and enter durable human review. */
    private double reviewThreshold = 0.62D;
    /** Automatic lexical links need this title overlap unless structured features are strong. */
    private double minAutoTitleSimilarity = 0.45D;
}
