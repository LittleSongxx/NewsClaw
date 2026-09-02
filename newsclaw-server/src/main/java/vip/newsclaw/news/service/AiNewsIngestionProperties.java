package vip.newsclaw.news.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Operational bounds for independent structured-source polling. */
@Data
@Component
@ConfigurationProperties(prefix = "newsclaw.ai-news.ingestion")
public class AiNewsIngestionProperties {
    /** Master switch; an empty endpoint catalog remains a no-op when enabled. */
    private boolean enabled = true;
    /** Refresh due endpoints synchronously when the persisted candidate store is empty. */
    private boolean onDemandRefreshIfEmpty = false;
    /** Hard bound so a configuration mistake cannot monopolize one scheduler cycle. */
    private int maxPollsPerCycle = 50;
    /** Started runs older than this are classified as abandoned on the next cycle. */
    private int staleRunMinutes = 30;
    /** Maximum persisted-candidate lookback exposed to discovery. */
    private int candidateLookbackDays = 31;
}
