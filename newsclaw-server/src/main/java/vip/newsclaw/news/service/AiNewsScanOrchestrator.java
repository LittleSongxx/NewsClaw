package vip.newsclaw.news.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.news.model.AiNewsScanRunEntity;
import vip.newsclaw.news.source.NewsSourceHashing;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Backend-owned vertical slice: refresh sources, discover, persist, capture and close. */
@Service
@Slf4j
public class AiNewsScanOrchestrator {

    private final AiNewsCandidatePipelineProperties properties;
    private final AiNewsCandidatePipelineService pipelineService;
    private final AiNewsStructuredIngestionService ingestionService;
    private final AiNewsDiscoverySearchService discoveryService;
    private final AiNewsDiscoveryRunLedger discoveryRunLedger;
    private final BochaAiNewsSearchClient chinaSearchClient;
    private final AiNewsCandidateCaptureWorker captureWorker;
    private AiNewsDiscoveryProperties discoveryProperties;

    public AiNewsScanOrchestrator(AiNewsCandidatePipelineProperties properties,
                                  AiNewsCandidatePipelineService pipelineService,
                                  AiNewsStructuredIngestionService ingestionService,
                                  AiNewsDiscoverySearchService discoveryService,
                                  AiNewsDiscoveryRunLedger discoveryRunLedger,
                                  BochaAiNewsSearchClient chinaSearchClient,
                                  AiNewsCandidateCaptureWorker captureWorker) {
        this.properties = properties;
        this.pipelineService = pipelineService;
        this.ingestionService = ingestionService;
        this.discoveryService = discoveryService;
        this.discoveryRunLedger = discoveryRunLedger;
        this.chinaSearchClient = chinaSearchClient;
        this.captureWorker = captureWorker;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDiscoveryProperties(AiNewsDiscoveryProperties discoveryProperties) {
        this.discoveryProperties = discoveryProperties;
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    /** Reconcile process-dead scans before the next scheduled window. */
    public int recoverStaleRuns() {
        return pipelineService.recoverStaleRuns(Duration.ofMinutes(
                Math.max(5, properties.getStaleRunMinutes())));
    }

    public AiNewsCandidatePipelineService.RunSummary runDefault(String triggerType) {
        // Scheduler invocations are not a clock. Quantise the window end so a
        // delayed node/retry observes the same half-open UTC window instead of
        // creating a stream of almost-identical runs.
        Instant now = Instant.now();
        Instant minute = now.truncatedTo(ChronoUnit.MINUTES);
        Instant end = minute.minusSeconds(Math.floorMod(minute.getEpochSecond(), 15L * 60L));
        int hours = Math.min(Math.max(1, properties.getLookbackHours()), 31 * 24);
        return run(properties.getDefaultWorkspaceId(), properties.getTopic(),
                end.minus(Duration.ofHours(hours)), end, properties.getMaxCandidates(), triggerType);
    }

    public AiNewsCandidatePipelineService.RunSummary run(Long workspaceId,
                                                         String topic,
                                                         Instant windowStart,
                                                         Instant windowEnd,
                                                         Integer maxCandidates,
                                                         String triggerType) {
        if (!properties.isEnabled()) {
            throw new NewsClawException(409,
                    "AI 新闻候选流水线未启用；请显式开启 shadow feature flag");
        }
        int limit = maxCandidates == null ? properties.getMaxCandidates()
                : Math.min(Math.max(1, maxCandidates), 50);
        String providerPolicy = discoveryProperties == null ? ""
                : String.join(",", discoveryProperties.normalizedProviderIds());
        var chinaConfig = properties.getChinaSearch();
        String chinaPolicy = chinaConfig == null ? "" : "enabled=" + chinaConfig.isEnabled()
                + "|baseUrl=" + String.valueOf(chinaConfig.getBaseUrl())
                + "|count=" + chinaConfig.getCount()
                + "|queries=" + String.valueOf(chinaConfig.getQueries());
        String scanKey = NewsSourceHashing.sha256(workspaceId + "|"
                + (topic == null || topic.isBlank() ? "artificial intelligence" : topic.trim()) + "|"
                + windowStart + "|" + windowEnd + "|" + limit + "|" + properties.getConfigVersion()
                + "|providers=" + providerPolicy + "|china=" + chinaPolicy);
        AiNewsCandidatePipelineService.ScanStart started = pipelineService.startOrReuseScan(
                workspaceId, triggerType, topic, windowStart, windowEnd,
                properties.getConfigVersion(), scanKey);
        AiNewsScanRunEntity run = started.run();
        if (started.reused()) {
            return pipelineService.inspectRun(run.getWorkspaceId(), run.getId());
        }
        try {
            if (ingestionService.persistentMainlineEnabled()) {
                // Structured feeds are an enrichment source, not a prerequisite
                // for the web-search lane. A single broken RSS/API endpoint must
                // degrade the source ledger while leaving the candidate scan and
                // its audit trail usable.
                try {
                    AiNewsStructuredIngestionService.CycleSummary cycle =
                            ingestionService.runDueCycle("candidate_scan");
                    if (!cycle.errors().isEmpty()) {
                        log.warn("AI-news structured ingestion degraded during scan {}: {}",
                                run.getId(), cycle.errors());
                    }
                } catch (RuntimeException ingestionError) {
                    log.warn("AI-news structured ingestion unavailable during scan {}; "
                                    + "continuing with web discovery: {}",
                            run.getId(), ingestionError.getMessage());
                }
            }
            AiNewsDiscoverySearchService.DiscoveryBatch global = discoveryService.discoverUnpersisted(
                    run.getWorkspaceId(), run.getTopic(), windowStart, windowEnd, limit);
            BochaAiNewsSearchClient.CollectionResult china =
                    chinaSearchClient.collect(windowStart, windowEnd);
            AiNewsDiscoverySearchService.DiscoveryBatch batch = fuseAndPersist(
                    run, global, china, limit);
            pipelineService.persistDiscovery(run.getId(), batch);
            requireSuccessfulSource(batch);
            captureWorker.run(run.getId(), properties.getMaxCapturesPerScan());
            pipelineService.completeScan(run.getId());
            return pipelineService.inspectRun(run.getWorkspaceId(), run.getId());
        } catch (RuntimeException error) {
            try {
                pipelineService.failScan(run.getId(), error);
            } catch (RuntimeException markError) {
                error.addSuppressed(markError);
            }
            throw error;
        }
    }

    private static void requireSuccessfulSource(AiNewsDiscoverySearchService.DiscoveryBatch batch) {
        boolean providerSucceeded = batch.executions().stream().anyMatch(execution ->
                execution.failureMessage() == null || execution.failureMessage().isBlank());
        if (!providerSucceeded && batch.structuredSourceCount() <= 0) {
            throw new NewsClawException(503,
                    "AI 新闻发现源全部失败；本次扫描已保留诊断快照并标记失败");
        }
    }

    private AiNewsDiscoverySearchService.DiscoveryBatch fuseAndPersist(
            AiNewsScanRunEntity run,
            AiNewsDiscoverySearchService.DiscoveryBatch global,
            BochaAiNewsSearchClient.CollectionResult china,
            int limit) {
        var snapshots = new java.util.ArrayList<>(global.querySnapshots());
        snapshots.addAll(china.snapshots());
        var executions = new java.util.ArrayList<>(global.executions());
        executions.addAll(china.executions());
        var frozenUnion = new AiNewsDiscoverySearchService.DiscoveryBatch(
                global.mode(), global.evidenceEligible(), global.windowStart(), global.windowEnd(),
                global.queryCount(), global.uniqueUrlCount(), global.candidates(), executions,
                global.structuredSourceCount(), global.message(), global.observedAt(),
                global.rankingPolicyVersion(), null, null,
                global.diagnostics(), snapshots, null, false);
        AiNewsDiscoverySearchService.DiscoveryBatch ranked = discoveryService.replay(
                run.getTopic(), frozenUnion, limit);
        var output = new AiNewsDiscoverySearchService.DiscoveryBatch(
                ranked.mode(), ranked.evidenceEligible(), ranked.windowStart(), ranked.windowEnd(),
                ranked.queryCount(), ranked.uniqueUrlCount(), ranked.candidates(), ranked.executions(),
                ranked.structuredSourceCount(), ranked.message(), ranked.observedAt(),
                ranked.rankingPolicyVersion(), ranked.snapshotHash(), ranked.rankingHash(),
                ranked.diagnostics(), ranked.querySnapshots(), null, false);
        Long discoveryRunId = discoveryRunLedger.persist(run.getWorkspaceId(), run.getTopic(), limit, output);
        return output.withPersistence(discoveryRunId, true);
    }
}
