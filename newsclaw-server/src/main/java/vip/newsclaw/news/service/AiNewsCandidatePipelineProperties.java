package vip.newsclaw.news.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/** Feature-flagged bounds for the shadow candidate pipeline. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "newsclaw.ai-news.candidate-pipeline")
public class AiNewsCandidatePipelineProperties {

    /** Dual-run stays off until an operator explicitly enables it. */
    private boolean enabled = false;
    /** Capture is independently opt-in because it performs outbound HTTP. */
    private boolean captureEnabled = false;
    private long defaultWorkspaceId = 1L;
    private String topic = "artificial intelligence";
    private int lookbackHours = 24;
    private int maxCandidates = 30;
    private int maxCapturesPerScan = 10;
    private int maxCaptureAttempts = 3;
    private int captureRetryMinutes = 15;
    private int staleCaptureMinutes = 30;
    /** How long an active scan may be silent before crash reconciliation. */
    private int staleRunMinutes = 120;
    /** Terminal unpromoted scan/snapshot retention; zero disables automatic deletion. */
    private int retentionDays = 90;
    /** Unreferenced source-capture retention; zero disables automatic deletion. */
    private int captureRetentionDays = 30;
    private int retentionBatchSize = 100;
    private String configVersion = "candidate-pipeline-v2-bocha";

    /** Dedicated China-search credentials; never reuse the existing CogVideo fields. */
    private final ChinaSearch chinaSearch = new ChinaSearch();

    @Getter
    @Setter
    public static class ChinaSearch {
        private boolean enabled = false;
        private String apiKey = "";
        private String baseUrl = "https://api.bochaai.com/v1/web-search";
        private int count = 20;
        private int timeoutSeconds = 15;
        private List<String> queries = List.of(
                "人工智能 模型 产品 智能体 最新发布",
                "人工智能 融资 并购 政策 监管 最新消息",
                "人工智能 芯片 算力 安全 研究 最新进展");
    }
}
