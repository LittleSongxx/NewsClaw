package vip.newsclaw.news.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.audit.service.AuditEventService;
import vip.newsclaw.news.model.AiNewsCandidateEntity;
import vip.newsclaw.news.model.AiNewsCandidateObservationEntity;
import vip.newsclaw.news.model.AiNewsProviderYieldRow;
import vip.newsclaw.news.model.AiNewsScanRunEntity;
import vip.newsclaw.news.repository.AiNewsCandidateMapper;
import vip.newsclaw.news.repository.AiNewsCandidateObservationMapper;
import vip.newsclaw.news.repository.AiNewsScanRunMapper;
import vip.newsclaw.news.source.NewsSourceHashing;
import vip.newsclaw.tool.search.SearchResult;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Transactional state owner for run-scoped scans, candidates and observations. */
@Service
public class AiNewsCandidatePipelineService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> ACTIVE_RUN_STATUSES =
            Set.of("RUNNING", "CANDIDATES_PERSISTED", "CAPTURE_PENDING");
    private static final Set<String> TERMINAL_RUN_STATUSES =
            Set.of("COMPLETED", "FAILED", "CANCELLED");

    private final AiNewsScanRunMapper scanMapper;
    private final AiNewsCandidateMapper candidateMapper;
    private final AiNewsCandidateObservationMapper observationMapper;
    private final AiNewsSourceRegistry sourceRegistry;
    private final ObjectMapper objectMapper;
    private final AiNewsCandidatePipelineProperties properties;

    @Autowired(required = false)
    private AuditEventService auditEventService;

    @Autowired
    public AiNewsCandidatePipelineService(AiNewsScanRunMapper scanMapper,
                                          AiNewsCandidateMapper candidateMapper,
                                          AiNewsCandidateObservationMapper observationMapper,
                                          AiNewsSourceRegistry sourceRegistry,
                                          ObjectMapper objectMapper,
                                          AiNewsCandidatePipelineProperties properties) {
        this.scanMapper = scanMapper;
        this.candidateMapper = candidateMapper;
        this.observationMapper = observationMapper;
        this.sourceRegistry = sourceRegistry;
        this.objectMapper = objectMapper;
        this.properties = properties == null ? new AiNewsCandidatePipelineProperties() : properties;
    }

    /** Compatibility constructor for small extension tests that do not use Spring binding. */
    public AiNewsCandidatePipelineService(AiNewsScanRunMapper scanMapper,
                                          AiNewsCandidateMapper candidateMapper,
                                          AiNewsCandidateObservationMapper observationMapper,
                                          AiNewsSourceRegistry sourceRegistry,
                                          ObjectMapper objectMapper) {
        this(scanMapper, candidateMapper, observationMapper, sourceRegistry, objectMapper,
                new AiNewsCandidatePipelineProperties());
    }

    @Transactional
    public AiNewsScanRunEntity startScan(Long workspaceId,
                                         String triggerType,
                                         String topic,
                                         Instant windowStart,
                                         Instant windowEnd,
                                         String configVersion) {
        return insertScan(workspaceId, triggerType, topic, windowStart, windowEnd,
                configVersion, null, null);
    }

    /** Start one immutable window once; concurrent callers receive the same durable run. */
    public ScanStart startOrReuseScan(Long workspaceId,
                                     String triggerType,
                                     String topic,
                                     Instant windowStart,
                                     Instant windowEnd,
                                     String configVersion,
                                     String idempotencyKey) {
        String key = trim(idempotencyKey, 64);
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("scan idempotency key is required");
        }
        long ws = workspace(workspaceId);
        AiNewsScanRunEntity existing = findByIdempotencyKey(ws, key);
        if (existing != null) return new ScanStart(existing, true);
        AiNewsScanRunEntity active = findActiveScan(ws);
        if (active != null) {
            throw new NewsClawException(409,
                    "当前 workspace 已有候选扫描进行中: " + active.getId());
        }
        try {
            return new ScanStart(insertScan(ws, triggerType, topic, windowStart, windowEnd,
                    configVersion, key, 1), false);
        } catch (DataIntegrityViolationException duplicate) {
            existing = findByIdempotencyKey(ws, key);
            if (existing != null) return new ScanStart(existing, true);
            active = findActiveScan(ws);
            if (active != null) {
                throw new NewsClawException(409,
                        "当前 workspace 已有候选扫描进行中: " + active.getId());
            }
            throw duplicate;
        }
    }

    private AiNewsScanRunEntity insertScan(Long workspaceId,
                                            String triggerType,
                                            String topic,
                                            Instant windowStart,
                                            Instant windowEnd,
                                            String configVersion,
                                            String idempotencyKey,
                                            Integer activeSlot) {
        if (windowStart == null || windowEnd == null || !windowStart.isBefore(windowEnd)) {
            throw new NewsClawException(400, "scan window must be a non-empty interval");
        }
        // A scheduler/Agent clock skew of a few minutes is harmless, but a
        // materially future window would make the scan and its evaluation
        // claim knowledge that did not exist yet.
        if (windowEnd.isAfter(Instant.now().plus(Duration.ofMinutes(5)))) {
            throw new NewsClawException(400, "scan window end cannot be materially in the future");
        }
        if (Duration.between(windowStart, windowEnd).compareTo(Duration.ofDays(31)) > 0) {
            throw new NewsClawException(400, "scan window cannot exceed 31 days");
        }
        LocalDateTime now = now();
        AiNewsScanRunEntity run = new AiNewsScanRunEntity();
        run.setWorkspaceId(workspace(workspaceId));
        run.setTriggerType(token(triggerType, "manual", 32));
        run.setTopic(trim(topic == null || topic.isBlank() ? "artificial intelligence" : topic, 1000));
        run.setWindowStart(utc(windowStart));
        run.setWindowEnd(utc(windowEnd));
        run.setRunStatus("RUNNING");
        run.setConfigVersion(trim(configVersion == null || configVersion.isBlank()
                ? "candidate-pipeline-v1" : configVersion, 128));
        run.setIdempotencyKey(idempotencyKey);
        run.setActiveSlot(activeSlot);
        run.setStartedAt(now);
        run.setProviderCount(0);
        run.setProviderDisabledCount(0);
        run.setRawResultCount(0);
        run.setInvalidResultCount(0);
        run.setUniqueCandidateCount(0);
        run.setSelectedCandidateCount(0);
        run.setCaptureSuccessCount(0);
        run.setCaptureFailureCount(0);
        run.setReviewedCount(0);
        run.setAcceptedCount(0);
        run.setCreateTime(now);
        run.setUpdateTime(now);
        run.setDeleted(0);
        if (scanMapper.insert(run) != 1 || run.getId() == null) {
            throw new IllegalStateException("candidate scan insert did not produce an id");
        }
        return run;
    }

    private AiNewsScanRunEntity findByIdempotencyKey(long workspaceId, String key) {
        return scanMapper.selectOne(new LambdaQueryWrapper<AiNewsScanRunEntity>()
                .eq(AiNewsScanRunEntity::getWorkspaceId, workspaceId)
                .eq(AiNewsScanRunEntity::getIdempotencyKey, key)
                .eq(AiNewsScanRunEntity::getDeleted, 0)
                .last("LIMIT 1"));
    }

    private AiNewsScanRunEntity findActiveScan(long workspaceId) {
        return scanMapper.selectOne(new LambdaQueryWrapper<AiNewsScanRunEntity>()
                .eq(AiNewsScanRunEntity::getWorkspaceId, workspaceId)
                .eq(AiNewsScanRunEntity::getActiveSlot, 1)
                .eq(AiNewsScanRunEntity::getDeleted, 0)
                .last("LIMIT 1"));
    }

    /**
     * Persist every valid snapshot result before applying the legacy selection outcome.
     * Invalid URLs are the only rows without an observation and remain visible in the run counter.
     */
    @Transactional
    public PersistenceSummary persistDiscovery(
            Long scanRunId, AiNewsDiscoverySearchService.DiscoveryBatch batch) {
        AiNewsScanRunEntity run = requireMutableRun(scanRunId);
        if (batch == null) throw new IllegalArgumentException("discovery batch is required");
        Instant observed = instant(batch.observedAt(), Instant.now());
        Instant windowStart = Instant.parse(batch.windowStart());
        Instant windowEnd = Instant.parse(batch.windowEnd());
        /*
         * Discovery fuses delivery aliases (www/m/wap and http/https) under
         * discoveryUrlAliasKey, while snapshots intentionally retain every raw
         * observation.  Looking up selection by the exact canonical URL here
         * made an alias row downgrade an already selected candidate as the
         * snapshots were replayed.  Selection is a decision about the fused
         * identity, not about one spelling of its URL.
         */
        Map<String, AiNewsDiscoverySearchService.DiscoveryCandidate> selectedByAlias =
                batch.candidates().stream()
                        .map(item -> Map.entry(
                                AiNewsDiscoverySearchService.discoveryUrlAliasKey(
                                        AiNewsDiscoverySearchService.canonicalDiscoveryUrl(item.url())),
                                item))
                        .filter(entry -> !entry.getKey().isBlank())
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                                (left, right) -> left.rrfScore() >= right.rrfScore() ? left : right,
                                LinkedHashMap::new));

        int rawResults = 0;
        int invalidResults = 0;
        Set<Long> candidateIds = new LinkedHashSet<>();
        Set<Long> selectedIds = new LinkedHashSet<>();
        Set<String> providers = new LinkedHashSet<>();
        Set<String> disabledProviders = batch.executions().stream()
                .filter(item -> item.failureMessage() != null
                        && item.failureMessage().startsWith("DISABLED_"))
                .map(AiNewsDiscoverySearchService.QueryExecution::providerId)
                .filter(item -> item != null && !item.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        batch.executions().stream().map(AiNewsDiscoverySearchService.QueryExecution::providerId)
                .filter(item -> item != null && !item.isBlank()).forEach(providers::add);
        for (AiNewsDiscoverySearchService.QuerySnapshot snapshot : batch.querySnapshots()) {
            String lane = token(snapshot.family(), "unknown_lane", 128);
            for (AiNewsDiscoverySearchService.SnapshotResult row : snapshot.results()) {
                rawResults++;
                String rawUrl = row == null ? null : row.url();
                String canonical = AiNewsDiscoverySearchService.canonicalDiscoveryUrl(rawUrl);
                if (canonical.isBlank()) {
                    invalidResults++;
                    continue;
                }
                String providerId = token(firstNonBlank(row.providerId(), snapshot.providerId()),
                        "unknown", 64);
                providers.add(providerId);
                AiNewsDiscoverySearchService.DiscoveryCandidate selected = selectedByAlias.get(
                        AiNewsDiscoverySearchService.discoveryUrlAliasKey(canonical));
                String rejection = selected == null
                        ? rejectionReason(row, canonical, windowStart, windowEnd) : null;
                String selectionStatus = selected != null ? "SELECTED"
                        : rejection == null ? "NOT_SELECTED" : "REJECTED";
                String selectionReason = selected != null
                        ? firstNonBlank(selected.selectionLane(), "RRF_TOP_K")
                        : rejection == null ? "LEGACY_ADMISSION_OR_BUDGET" : rejection;
                AiNewsCandidateEntity candidate = upsertCandidate(run, row, canonical, providerId,
                        lane, selected, rejection, selectionStatus, selectionReason, observed);
                candidateIds.add(candidate.getId());
                if (selected != null) selectedIds.add(candidate.getId());
                persistObservation(run.getId(), candidate.getId(), row, providerId, lane,
                        selected != null, selectionReason, observed);
            }
        }

        run.setDiscoveryRunId(batch.discoveryRunId());
        run.setRunStatus("CANDIDATES_PERSISTED");
        run.setProviderCount(providers.size());
        run.setProviderDisabledCount(disabledProviders.size());
        run.setRawResultCount(rawResults);
        run.setInvalidResultCount(invalidResults);
        run.setUniqueCandidateCount(candidateIds.size());
        run.setSelectedCandidateCount(selectedIds.size());
        run.setSummaryJson(json(Map.of(
                "snapshotHash", nullToEmpty(batch.snapshotHash()),
                "rankingHash", nullToEmpty(batch.rankingHash()),
                "rankingPolicyVersion", nullToEmpty(batch.rankingPolicyVersion()),
                "rankingDiagnostics", batch.diagnostics() == null ? Map.of() : batch.diagnostics(),
                "rawResultCount", rawResults,
                "invalidResultCount", invalidResults,
                "uniqueCandidateCount", candidateIds.size(),
                "selectedCandidateCount", selectedIds.size(),
                "providerStatus", providerStatuses(batch.executions()))));
        run.setUpdateTime(now());
        scanMapper.updateById(run);
        return new PersistenceSummary(rawResults, invalidResults, candidateIds.size(),
                selectedIds.size(), providers.size());
    }

    private AiNewsCandidateEntity upsertCandidate(
            AiNewsScanRunEntity run,
            AiNewsDiscoverySearchService.SnapshotResult row,
            String canonical,
            String providerId,
            String lane,
            AiNewsDiscoverySearchService.DiscoveryCandidate selected,
            String rejection,
            String selectionStatus,
            String selectionReason,
            Instant observedAt) {
        String canonicalHash = NewsSourceHashing.sha256(canonical);
        AiNewsCandidateEntity candidate = candidateMapper.selectOne(
                new LambdaQueryWrapper<AiNewsCandidateEntity>()
                        .eq(AiNewsCandidateEntity::getWorkspaceId, run.getWorkspaceId())
                        .eq(AiNewsCandidateEntity::getScanRunId, run.getId())
                        .eq(AiNewsCandidateEntity::getCanonicalUrlHash, canonicalHash)
                        .eq(AiNewsCandidateEntity::getDeleted, 0));
        if (candidate == null) {
            /*
             * The discovery layer intentionally folds delivery aliases
             * (www/m/wap/http) while retaining the raw URL in observations.
             * The run-scoped candidate key still needs to fold delivery aliases
             * (www/m/wap/http) within the same run; otherwise one story could
             * create multiple capture targets in a single discovery result.
             * This is O(run candidates) on the rare alias-miss; a future
             * generated identity column can make it an indexed lookup.
             */
            String alias = AiNewsDiscoverySearchService.discoveryUrlAliasKey(canonical);
            candidate = candidateMapper.selectList(new LambdaQueryWrapper<AiNewsCandidateEntity>()
                            .eq(AiNewsCandidateEntity::getWorkspaceId, run.getWorkspaceId())
                            .eq(AiNewsCandidateEntity::getScanRunId, run.getId())
                            .eq(AiNewsCandidateEntity::getDeleted, 0)).stream()
                    .filter(item -> alias.equals(AiNewsDiscoverySearchService.discoveryUrlAliasKey(
                            item.getCanonicalUrl())))
                    .findFirst().orElse(null);
        }
        LocalDateTime observed = utc(observedAt);
        boolean fresh = candidate == null;
        if (fresh) {
            candidate = new AiNewsCandidateEntity();
            candidate.setWorkspaceId(run.getWorkspaceId());
            candidate.setCanonicalUrl(canonical);
            candidate.setCanonicalUrlHash(canonicalHash);
            candidate.setFirstSeenAt(observed);
            candidate.setCaptureStatus("NOT_QUEUED");
            candidate.setNormalizationStatus("NOT_STARTED");
            candidate.setReviewStatus("PENDING");
            candidate.setCaptureAttempts(0);
            candidate.setCreateTime(observed);
            candidate.setDeleted(0);
        }
        boolean sameRun = run.getId().equals(candidate.getScanRunId());
        candidate.setScanRunId(run.getId());
        boolean alreadySelectedInThisRun = sameRun && "SELECTED".equals(candidate.getSelectionStatus());
        boolean effectiveSelected = selected != null || alreadySelectedInThisRun;
        String effectiveRejection = effectiveSelected ? null : rejection;
        String effectiveSelectionStatus = effectiveSelected ? "SELECTED" : selectionStatus;
        String effectiveSelectionReason = alreadySelectedInThisRun && selected == null
                ? firstNonBlank(candidate.getSelectionReason(), selectionReason) : selectionReason;
        boolean incomingSelected = selected != null;
        boolean betterObservation = candidate.getProviderRank() == null
                || (incomingSelected && !alreadySelectedInThisRun)
                || (incomingSelected == alreadySelectedInThisRun
                && row.rank() < candidate.getProviderRank());
        if (betterObservation) {
            candidate.setOriginalUrl(trim(row.url(), 4096));
            candidate.setTitle(trimToNull(row.title(), 512));
            candidate.setSnippet(trimToNull(row.snippet(), 10_000));
            candidate.setProviderId(providerId);
            candidate.setQueryLane(lane);
            candidate.setProviderRank(Math.max(1, row.rank()));
            candidate.setPublishedAtHint(trimToNull(row.publishedAtHint(), 512));
        }
        candidate.setSourceKey(sourceRegistry.publisherSourceKey(canonical).orElse(null));
        candidate.setSourceClass(sourceClass(canonical));
        if (selected != null) {
            candidate.setStoryId(AiNewsDiscoveryStoryDeduplicator.stableStoryId(selected, sourceRegistry));
        }
        candidate.setTimeConfidence(alreadySelectedInThisRun && selected == null
                ? firstNonBlank(candidate.getTimeConfidence(), timeConfidence(row, null, rejection))
                : timeConfidence(row, selected, rejection));
        candidate.setLastSeenAt(observed);
        candidate.setAcquisitionStatus(effectiveRejection == null ? "DISCOVERED" : "REJECTED");
        candidate.setSelectionStatus(effectiveSelectionStatus);
        candidate.setSelectionReason(trim(effectiveSelectionReason, 512));
        if (selected != null) {
            BigDecimal incomingScore = BigDecimal.valueOf(selected.rrfScore());
            if (candidate.getSelectionScore() == null || incomingScore.compareTo(candidate.getSelectionScore()) > 0
                    || !alreadySelectedInThisRun) {
                candidate.setSelectionScore(incomingScore);
            }
        } else if (!alreadySelectedInThisRun) {
            candidate.setSelectionScore(null);
        }
        candidate.setRejectReason(trimToNull(effectiveRejection, 512));
        candidate.setConfigVersion(run.getConfigVersion());
        candidate.setUpdateTime(now());
        boolean queueCapture = effectiveSelected && properties.isCaptureEnabled()
                && !"SUCCESS".equals(candidate.getCaptureStatus());
        if (fresh) {
            candidateMapper.insert(candidate);
        } else {
            /*
             * Do not use updateById here.  Discovery can be replayed from a
             * stale snapshot while promotion/capture/review is committing;
             * the generated full-row UPDATE would then restore event_id or a
             * newer operational state to its old value.  The mapper method
             * writes only discovery-owned columns and fences on event_id.
             */
            int updated = candidateMapper.updateDiscovery(candidate);
            if (updated != 1) {
                AiNewsCandidateEntity current = candidateMapper.selectOne(
                        new LambdaQueryWrapper<AiNewsCandidateEntity>()
                                .eq(AiNewsCandidateEntity::getId, candidate.getId())
                                .eq(AiNewsCandidateEntity::getWorkspaceId, run.getWorkspaceId())
                                .eq(AiNewsCandidateEntity::getDeleted, 0));
                if (current == null) {
                    throw new NewsClawException(409,
                            "候选在 discovery replay 期间已被删除或移出当前扫描");
                }
                candidate = current;
            }
        }
        if (queueCapture) {
            // This update is also fenced.  In-flight/SUCCESS work is never
            // reset, and an already-promoted candidate cannot re-enter the
            // capture queue even when discovery was based on stale state.
            int queued = candidateMapper.queueSelected(candidate.getId(), run.getWorkspaceId(),
                    run.getId(), now());
            if (queued == 1) {
                candidate.setCaptureStatus("PENDING");
                candidate.setNormalizationStatus("NOT_STARTED");
                candidate.setNextCaptureAt(null);
                candidate.setFailureReason(null);
            }
        }
        return candidate;
    }

    private void persistObservation(Long scanRunId,
                                    Long candidateId,
                                    AiNewsDiscoverySearchService.SnapshotResult row,
                                    String providerId,
                                    String lane,
                                    boolean selected,
                                    String selectionReason,
                                    Instant observedAt) {
        String observedHash = NewsSourceHashing.sha256(nullToEmpty(row.url()).trim());
        long existing = observationMapper.selectCount(
                new LambdaQueryWrapper<AiNewsCandidateObservationEntity>()
                        .eq(AiNewsCandidateObservationEntity::getScanRunId, scanRunId)
                        .eq(AiNewsCandidateObservationEntity::getProviderId, providerId)
                        .eq(AiNewsCandidateObservationEntity::getQueryLane, lane)
                        .eq(AiNewsCandidateObservationEntity::getProviderRank, Math.max(1, row.rank()))
                        .eq(AiNewsCandidateObservationEntity::getObservedUrlHash, observedHash)
                        .eq(AiNewsCandidateObservationEntity::getDeleted, 0));
        if (existing > 0) return;
        AiNewsCandidateObservationEntity observation = new AiNewsCandidateObservationEntity();
        observation.setCandidateId(candidateId);
        observation.setScanRunId(scanRunId);
        observation.setProviderId(providerId);
        observation.setQueryLane(lane);
        observation.setProviderRank(Math.max(1, row.rank()));
        observation.setOriginalUrl(trim(row.url(), 4096));
        observation.setObservedUrlHash(observedHash);
        observation.setTitle(trimToNull(row.title(), 512));
        observation.setSnippet(trimToNull(row.snippet(), 10_000));
        observation.setPublishedAtHint(trimToNull(row.publishedAtHint(), 512));
        observation.setProviderScore(row.relevanceScore() == null ? null
                : BigDecimal.valueOf(row.relevanceScore()));
        observation.setSelected(selected);
        observation.setSelectionReason(trim(selectionReason, 512));
        observation.setObservedAt(utc(observedAt));
        observation.setCreateTime(now());
        observation.setDeleted(0);
        observationMapper.insert(observation);
    }

    @Transactional
    public void completeScan(Long scanRunId) {
        AiNewsScanRunEntity run = requireRun(scanRunId);
        String expectedStatus = run.getRunStatus();
        if (!ACTIVE_RUN_STATUSES.contains(expectedStatus)) return;
        List<AiNewsCandidateEntity> selected = selectedCandidates(scanRunId);
        int captureSuccess = count(selected, AiNewsCandidateEntity::getCaptureStatus, "SUCCESS");
        int captureFailure = count(selected, AiNewsCandidateEntity::getCaptureStatus, "FAILED");
        List<AiNewsCandidateEntity> all = candidatesForScan(scanRunId);
        int reviewed = (int) all.stream().filter(item -> Set.of("ACCEPTED", "REJECTED")
                .contains(item.getReviewStatus())).count();
        int accepted = count(all, AiNewsCandidateEntity::getReviewStatus, "ACCEPTED");
        run.setCaptureSuccessCount(captureSuccess);
        run.setCaptureFailureCount(captureFailure);
        run.setReviewedCount(reviewed);
        run.setAcceptedCount(accepted);
        // A candidate-only shadow run intentionally leaves the outbound
        // capture queue untouched. It is complete when capture is disabled;
        // otherwise the queue must drain before the run gets a terminal
        // scorecard. This keeps an unexecuted 0/0 stage from masquerading as
        // a failed capture and avoids permanently stuck shadow runs.
        // A capture already in flight must be allowed to drain even if a
        // runtime flag is toggled off while the scan is running. Pending work
        // that was never claimed remains a deliberate candidate-only shadow.
        boolean capturePending = selected.stream().anyMatch(AiNewsCandidatePipelineService::captureInFlight)
                || properties.isCaptureEnabled()
                && selected.stream().anyMatch(AiNewsCandidatePipelineService::captureWaiting);
        // A scan is not complete while selected candidates still await an
        // outbound capture. This keeps 0/0 and pending work out of the final
        // scorecard; the capture callback closes the run once the queue drains.
        String nextStatus = capturePending ? "CAPTURE_PENDING" : "COMPLETED";
        Integer nextActiveSlot = capturePending && run.getIdempotencyKey() != null ? 1 : null;
        LocalDateTime finishedAt = capturePending ? null : now();
        LocalDateTime updatedAt = now();
        String summary = json(summaryMap(run, all, selected));
        // Keep the entity handed to narrow callers/tests coherent with the
        // CAS write below.  The database predicate remains authoritative when
        // another worker wins the transition concurrently.
        run.setRunStatus(nextStatus);
        run.setActiveSlot(nextActiveSlot);
        run.setFinishedAt(finishedAt);
        run.setSummaryJson(summary);
        run.setUpdateTime(updatedAt);
        // Terminal transitions are compare-and-set operations. A late error
        // from an older orchestrator must not resurrect a completed/failed
        // run or overwrite its frozen scorecard.
        scanMapper.update(null, new LambdaUpdateWrapper<AiNewsScanRunEntity>()
                .eq(AiNewsScanRunEntity::getId, scanRunId)
                .eq(AiNewsScanRunEntity::getDeleted, 0)
                .eq(AiNewsScanRunEntity::getRunStatus, expectedStatus)
                .set(AiNewsScanRunEntity::getCaptureSuccessCount, captureSuccess)
                .set(AiNewsScanRunEntity::getCaptureFailureCount, captureFailure)
                .set(AiNewsScanRunEntity::getReviewedCount, reviewed)
                .set(AiNewsScanRunEntity::getAcceptedCount, accepted)
                .set(AiNewsScanRunEntity::getRunStatus, nextStatus)
                .set(AiNewsScanRunEntity::getActiveSlot, nextActiveSlot)
                .set(AiNewsScanRunEntity::getFinishedAt, finishedAt)
                .set(AiNewsScanRunEntity::getUpdateTime, updatedAt)
                .set(AiNewsScanRunEntity::getSummaryJson, summary));
    }

    @Transactional
    public void failScan(Long scanRunId, Throwable error) {
        AiNewsScanRunEntity run = requireRun(scanRunId);
        String expectedStatus = run.getRunStatus();
        if (!ACTIVE_RUN_STATUSES.contains(expectedStatus)) return;
        String message = trim(error == null ? "unknown scan failure"
                : firstNonBlank(error.getMessage(), error.getClass().getSimpleName()), 2000);
        LocalDateTime current = now();
        int updated = scanMapper.update(null, new LambdaUpdateWrapper<AiNewsScanRunEntity>()
                .eq(AiNewsScanRunEntity::getId, scanRunId)
                .eq(AiNewsScanRunEntity::getDeleted, 0)
                .eq(AiNewsScanRunEntity::getRunStatus, expectedStatus)
                .set(AiNewsScanRunEntity::getRunStatus, "FAILED")
                .set(AiNewsScanRunEntity::getIdempotencyKey, null)
                .set(AiNewsScanRunEntity::getActiveSlot, null)
                .set(AiNewsScanRunEntity::getErrorMessage, message)
                .set(AiNewsScanRunEntity::getFinishedAt, current)
                .set(AiNewsScanRunEntity::getUpdateTime, current));
        if (updated == 1) {
            candidateMapper.failUnfinishedForRun(scanRunId, "SCAN_FAILED", current);
        }
    }

    /**
     * Reconcile scans abandoned by a process crash.  The scheduler calls this
     * before opening a new window, so a stale RUNNING row cannot make the
     * Agent wait forever and a persisted-but-unclosed row gets one final CAS
     * transition.  The bounded batch keeps maintenance work predictable.
     */
    @Transactional
    public int recoverStaleRuns(Duration staleAge) {
        long minutes = Math.max(5, Math.min(24 * 60,
                staleAge == null ? 120 : staleAge.toMinutes()));
        LocalDateTime current = now();
        LocalDateTime cutoff = current.minusMinutes(minutes);
        List<AiNewsScanRunEntity> stale = scanMapper.selectList(
                new LambdaQueryWrapper<AiNewsScanRunEntity>()
                        .in(AiNewsScanRunEntity::getRunStatus, ACTIVE_RUN_STATUSES)
                        .eq(AiNewsScanRunEntity::getDeleted, 0)
                        .lt(AiNewsScanRunEntity::getUpdateTime, cutoff)
                        .orderByAsc(AiNewsScanRunEntity::getUpdateTime)
                        .last("LIMIT 100"));
        int changed = 0;
        for (AiNewsScanRunEntity run : stale == null ? List.<AiNewsScanRunEntity>of() : stale) {
            if (run == null || run.getId() == null) continue;
            String status = run.getRunStatus();
            if ("RUNNING".equals(status)) {
                int updated = scanMapper.update(null, new LambdaUpdateWrapper<AiNewsScanRunEntity>()
                        .eq(AiNewsScanRunEntity::getId, run.getId())
                        .eq(AiNewsScanRunEntity::getDeleted, 0)
                        .eq(AiNewsScanRunEntity::getRunStatus, "RUNNING")
                        .lt(AiNewsScanRunEntity::getUpdateTime, cutoff)
                        .set(AiNewsScanRunEntity::getRunStatus, "FAILED")
                        .set(AiNewsScanRunEntity::getIdempotencyKey, null)
                        .set(AiNewsScanRunEntity::getActiveSlot, null)
                        .set(AiNewsScanRunEntity::getErrorMessage, "STALE_SCAN_RECOVERED")
                        .set(AiNewsScanRunEntity::getFinishedAt, current)
                        .set(AiNewsScanRunEntity::getUpdateTime, current));
                if (updated == 1) {
                    candidateMapper.failUnfinishedForRun(run.getId(), "STALE_SCAN_RECOVERED", current);
                }
                changed += updated;
            } else if ("CANDIDATES_PERSISTED".equals(status)) {
                completeScan(run.getId());
                changed++;
            } else if ("CAPTURE_PENDING".equals(status)) {
                AiNewsScanRunEntity beforeRow = scanMapper.selectById(run.getId());
                if (beforeRow == null) continue;
                String before = beforeRow.getRunStatus();
                refreshRunCounts(run.getId());
                AiNewsScanRunEntity after = scanMapper.selectById(run.getId());
                if (after != null && !java.util.Objects.equals(before, after.getRunStatus())) changed++;
            }
        }
        return changed;
    }

    public List<AiNewsCandidateEntity> captureQueue(Long scanRunId, int limit) {
        return candidateMapper.selectCaptureQueue(scanRunId, null, now(), Math.min(Math.max(1, limit), 100));
    }

    /** Queue candidates for one workspace without crossing tenant boundaries. */
    public List<AiNewsCandidateEntity> captureQueueForWorkspace(Long workspaceId, int limit) {
        return candidateMapper.selectCaptureQueue(null, workspace(workspaceId), now(),
                Math.min(Math.max(1, limit), 100));
    }

    @Transactional
    public boolean claimCapture(Long candidateId) {
        return candidateMapper.claimCapture(candidateId, now()) == 1;
    }

    /**
     * Claim a capture and return its fencing token.  Keeping the token at the
     * service boundary lets the worker reject a late completion after stale
     * recovery has handed the candidate to another attempt.
     */
    @Transactional
    public CaptureLease claimCaptureLease(Long candidateId) {
        if (candidateId == null) return null;
        // The UPDATE is the claim authority.  Read the fencing token only
        // after it succeeds; predicting attempts from a pre-claim snapshot
        // can return a stale token if recovery/reclaim wins between SELECT
        // and UPDATE.
        if (candidateMapper.claimCapture(candidateId, now()) != 1) return null;
        AiNewsCandidateEntity claimed = candidateMapper.selectById(candidateId);
        if (claimed == null || claimed.getCaptureAttempts() == null) {
            throw new IllegalStateException("capture claim succeeded but candidate token is missing: "
                    + candidateId);
        }
        return new CaptureLease(candidateId, claimed.getCaptureAttempts());
    }

    /** Return a terminal reason when a fetched page cannot enter this scan's frozen window. */
    public String captureWindowFailure(Long candidateId, String sourcePublishedAtUtc) {
        AiNewsCandidateEntity candidate = candidateId == null ? null : candidateMapper.selectById(candidateId);
        if (candidate == null || candidate.getScanRunId() == null) return "CAPTURE_SCAN_MISSING";
        AiNewsScanRunEntity run = scanMapper.selectById(candidate.getScanRunId());
        if (run == null || run.getWindowStart() == null || run.getWindowEnd() == null) {
            return "CAPTURE_WINDOW_MISSING";
        }
        if (sourcePublishedAtUtc == null || sourcePublishedAtUtc.isBlank()) {
            return "SOURCE_PUBLISHED_AT_MISSING";
        }
        try {
            Instant published = Instant.parse(sourcePublishedAtUtc);
            Instant start = run.getWindowStart().toInstant(ZoneOffset.UTC);
            Instant end = run.getWindowEnd().toInstant(ZoneOffset.UTC);
            return !published.isBefore(start) && published.isBefore(end)
                    ? null : "SOURCE_PUBLISHED_AT_OUTSIDE_WINDOW";
        } catch (RuntimeException invalid) {
            return "SOURCE_PUBLISHED_AT_INVALID";
        }
    }

    @Transactional
    public void captureSucceeded(Long candidateId, Long captureId) {
        AiNewsCandidateEntity candidate = requireCandidate(candidateId);
        ensureNotPromoted(candidate);
        int expectedAttempt = candidate.getCaptureAttempts() == null
                ? 0 : candidate.getCaptureAttempts();
        // Route the compatibility overload through the same fenced SQL as the
        // worker callback.  Updating a stale entity with updateById could wait
        // for promotion and then write its old event_id=null back over the
        // newly-created lineage.
        if (!captureSucceeded(candidateId, captureId, expectedAttempt)) {
            rejectStaleCaptureCompletion(candidateId);
        }
    }

    /** Complete a specific claimed attempt; stale workers are harmless. */
    @Transactional
    public boolean captureSucceeded(Long candidateId, Long captureId, int expectedAttempt) {
        if (!lockCaptureOwner(candidateId)) return false;
        boolean updated = candidateMapper.completeCaptureSucceeded(candidateId, expectedAttempt,
                captureId, now()) == 1;
        if (updated) refreshRelatedRunCounts(candidateId);
        return updated;
    }

    @Transactional
    public void captureFailed(Long candidateId,
                              String reason,
                              boolean retryable,
                              int maxAttempts,
                              Duration retryDelay) {
        AiNewsCandidateEntity candidate = requireCandidate(candidateId);
        ensureNotPromoted(candidate);
        int attempts = candidate.getCaptureAttempts() == null ? 0 : candidate.getCaptureAttempts();
        // As with success, use the fenced path so a late legacy callback
        // cannot overwrite a candidate after it has been promoted.
        if (!captureFailed(candidateId, reason, retryable, maxAttempts, retryDelay, attempts)) {
            rejectStaleCaptureCompletion(candidateId);
        }
    }

    /** Fenced failure counterpart for workers carrying a {@link CaptureLease}. */
    @Transactional
    public boolean captureFailed(Long candidateId,
                                 String reason,
                                 boolean retryable,
                                 int maxAttempts,
                                 Duration retryDelay,
                                 int expectedAttempt) {
        if (!lockCaptureOwner(candidateId)) return false;
        LocalDateTime current = now();
        boolean retry = retryable && expectedAttempt < Math.max(1, maxAttempts);
        LocalDateTime next = retry ? current.plusSeconds(Math.max(1,
                retryDelay == null ? 900 : retryDelay.toSeconds())) : null;
        String status = retry ? "RETRYABLE" : "FAILED";
        boolean updated = candidateMapper.completeCaptureFailed(candidateId, expectedAttempt,
                status, trim(reason, 2000), next, current) == 1;
        if (updated) refreshRelatedRunCounts(candidateId);
        return updated;
    }

    @Transactional
    public int recoverStaleCaptures(Duration staleAge) {
        return recoverStaleCaptures(null, null, staleAge);
    }

    /** Recover only the workspace/run owned by the caller's maintenance job. */
    @Transactional
    public int recoverStaleCaptures(Long workspaceId, Long scanRunId, Duration staleAge) {
        long seconds = Math.max(60, staleAge == null ? 1800 : staleAge.toSeconds());
        LocalDateTime current = now();
        return candidateMapper.recoverStaleCaptures(
                workspaceId == null ? null : workspace(workspaceId), scanRunId,
                current.minusSeconds(seconds), current);
    }

    public IPage<AiNewsScanRunEntity> scans(Long workspaceId, int page, int size, String status) {
        return scanMapper.selectPage(page(page, size), new LambdaQueryWrapper<AiNewsScanRunEntity>()
                .eq(AiNewsScanRunEntity::getWorkspaceId, workspace(workspaceId))
                .eq(AiNewsScanRunEntity::getDeleted, 0)
                .eq(status != null && !status.isBlank(), AiNewsScanRunEntity::getRunStatus,
                        status == null ? null : status.trim().toUpperCase(Locale.ROOT))
                .orderByDesc(AiNewsScanRunEntity::getStartedAt)
                .orderByDesc(AiNewsScanRunEntity::getId));
    }

    /**
     * Return the newest workspace scan with its compact scorecard.  The
     * Agent query facade uses this when the caller omits {@code scanRunId},
     * so a scheduled Agent invocation can reuse the scheduler-owned run
     * instead of starting a duplicate scan every day.
     */
    public RunSummary latestRun(Long workspaceId) {
        IPage<AiNewsScanRunEntity> page = scans(workspaceId, 1, 1, null);
        if (page == null || page.getRecords() == null || page.getRecords().isEmpty()) {
            return null;
        }
        AiNewsScanRunEntity run = page.getRecords().getFirst();
        return run == null || run.getId() == null ? null : inspectRun(workspaceId, run.getId());
    }

    public IPage<AiNewsCandidateEntity> candidates(Long workspaceId,
                                                    int page,
                                                    int size,
                                                    Long scanRunId,
                                                    String providerId,
                                                    String selectionStatus,
                                                    String captureStatus,
                                                    String reviewStatus,
                                                    Boolean marginalOnly,
                                                    Instant seenAfter,
                                                    Instant seenBefore) {
        if (seenAfter != null && seenBefore != null && !seenAfter.isBefore(seenBefore)) {
            throw new NewsClawException(400, "seenAfter must be before seenBefore");
        }
        LambdaQueryWrapper<AiNewsCandidateEntity> query =
                new LambdaQueryWrapper<AiNewsCandidateEntity>()
                        .eq(AiNewsCandidateEntity::getWorkspaceId, workspace(workspaceId))
                        .eq(AiNewsCandidateEntity::getDeleted, 0);
        if (scanRunId != null) {
            // V214 makes candidate state run-owned. Keep the row predicate in
            // addition to the observation join so legacy V213 rows whose
            // projection was later overwritten cannot leak into a historical
            // run query.
            query.eq(AiNewsCandidateEntity::getScanRunId, scanRunId);
            query.inSql(AiNewsCandidateEntity::getId,
                    "SELECT candidate_id FROM mate_ai_news_candidate_observation "
                            + "WHERE deleted = 0 AND scan_run_id = " + numeric(scanRunId));
        }
        if (providerId != null && !providerId.isBlank()) {
            query.inSql(AiNewsCandidateEntity::getId,
                    "SELECT candidate_id FROM mate_ai_news_candidate_observation WHERE deleted = 0"
                            + " AND provider_id = '" + sqlToken(providerId, 64) + "'"
                            + (scanRunId == null ? "" : " AND scan_run_id = " + numeric(scanRunId)));
        }
        query
                        .eq(selectionStatus != null && !selectionStatus.isBlank(),
                                AiNewsCandidateEntity::getSelectionStatus,
                                upper(selectionStatus))
                        .eq(captureStatus != null && !captureStatus.isBlank(),
                                AiNewsCandidateEntity::getCaptureStatus,
                                upper(captureStatus))
                        .eq(reviewStatus != null && !reviewStatus.isBlank(),
                                AiNewsCandidateEntity::getReviewStatus,
                                upper(reviewStatus))
                        .ge(seenAfter != null, AiNewsCandidateEntity::getLastSeenAt,
                                seenAfter == null ? null : utc(seenAfter))
                        .lt(seenBefore != null, AiNewsCandidateEntity::getLastSeenAt,
                                seenBefore == null ? null : utc(seenBefore))
                        .apply(Boolean.TRUE.equals(marginalOnly),
                                "(SELECT COUNT(DISTINCT o.provider_id) "
                                        + "FROM mate_ai_news_candidate_observation o "
                                        + "WHERE o.candidate_id = mate_ai_news_candidate.id "
                                        + "AND o.deleted = 0"
                                        + (scanRunId == null ? "" : " AND o.scan_run_id = " + numeric(scanRunId))
                                        + ") = 1")
                        .orderByDesc(AiNewsCandidateEntity::getSelectionScore)
                        .orderByDesc(AiNewsCandidateEntity::getLastSeenAt)
                        .orderByDesc(AiNewsCandidateEntity::getId);
        return candidateMapper.selectPage(page(page, size), query);
    }

    public RunSummary inspectRun(Long workspaceId, Long scanRunId) {
        AiNewsScanRunEntity run = requireRun(scanRunId);
        if (!run.getWorkspaceId().equals(workspace(workspaceId))) {
            throw new NewsClawException(404, "candidate scan not found");
        }
        List<AiNewsCandidateEntity> all = candidatesForScan(scanRunId);
        List<AiNewsCandidateEntity> selected = selectedCandidates(scanRunId);
        List<ProviderYield> providers = observationMapper.selectProviderYields(scanRunId).stream()
                .map(row -> new ProviderYield(row.getProviderId(), value(row.getCandidateCount()),
                        value(row.getSelectedCount()), value(row.getMarginalUniqueCount())))
                .toList();
        String stored = scanMapper.selectSummaryJson(scanRunId);
        JsonNode audit = stored == null || stored.isBlank() ? objectMapper.createObjectNode()
                : readJson(stored);
        Scorecard visibleScorecard = "COMPLETED".equals(run.getRunStatus())
                ? visibleCompletedScorecard(audit, all, selected) : scorecard(all, selected);
        return new RunSummary(run, providers, visibleScorecard, audit);
    }

    @Transactional
    public AiNewsCandidateEntity review(Long workspaceId,
                                        Long candidateId,
                                        String decision,
                                        String reason) {
        return review(workspaceId, candidateId, decision, reason, null, "SYSTEM");
    }

    /**
     * Record a review with an explicit actor.  The four-argument overload is
     * retained for source compatibility but is deliberately marked SYSTEM;
     * such a label can never satisfy the promotion gate.
     */
    @Transactional
    public AiNewsCandidateEntity review(Long workspaceId,
                                        Long candidateId,
                                        String decision,
                                        String reason,
                                        String reviewer,
                                        String reviewOrigin) {
        long ws = workspace(workspaceId);
        // Keep the same run -> candidate lock order as scan failure/recovery
        // and promotion.  The first read only discovers the owning run; all
        // gates below use the row reloaded under the locks.
        AiNewsCandidateEntity candidate = candidateId == null ? null
                : candidateMapper.selectOne(new LambdaQueryWrapper<AiNewsCandidateEntity>()
                        .eq(AiNewsCandidateEntity::getId, candidateId)
                        .eq(AiNewsCandidateEntity::getWorkspaceId, ws)
                        .eq(AiNewsCandidateEntity::getDeleted, 0));
        if (candidate == null) {
            throw new NewsClawException(404, "candidate not found");
        }
        Long owningRunId = candidate.getScanRunId();
        if (owningRunId != null) {
            AiNewsScanRunEntity run = scanMapper.selectForUpdate(owningRunId, ws);
            if (run == null) throw new NewsClawException(404, "candidate scan not found");
        }
        candidate = candidateMapper.selectForUpdate(candidateId, ws);
        if (candidate == null) throw new NewsClawException(404, "candidate not found");
        if (!java.util.Objects.equals(owningRunId, candidate.getScanRunId())) {
            throw new NewsClawException(409, "候选所属扫描在审核期间发生变化");
        }
        String normalized = upper(decision);
        if (!Set.of("ACCEPTED", "REJECTED").contains(normalized)) {
            throw new NewsClawException(400, "review decision must be ACCEPTED or REJECTED");
        }
        // Once a candidate has been promoted, its acceptance is the editorial
        // authorization that created the event.  Allowing the generic review
        // endpoint to flip it later would leave an already verified/published
        // event with a contradictory candidate decision and no revocation
        // audit.  A future withdrawal flow can explicitly dismiss the event.
        if (candidate.getEventId() != null) {
            throw new NewsClawException(409,
                    "候选已形成事件，不能通过普通 review 改写采用结论；请撤回对应事件");
        }
        LocalDateTime updatedAt = now();
        String reviewReason = trimToNull(reason, 1000);
        String actor = trimToNull(reviewer, 256);
        String origin = normalizeReviewOrigin(reviewOrigin);
        String previousReviewStatus = candidate.getReviewStatus();
        int updated = candidateMapper.updateReview(candidateId, ws, normalized, reviewReason,
                actor, updatedAt, origin, updatedAt);
        if (updated != 1) {
            throw new NewsClawException(409,
                    "候选审核状态已变化，请重新读取后再提交");
        }
        candidate.setReviewStatus(normalized);
        candidate.setReviewReason(reviewReason);
        candidate.setReviewedBy(actor);
        candidate.setReviewedAt(updatedAt);
        candidate.setReviewOrigin(origin);
        candidate.setUpdateTime(updatedAt);
        if (auditEventService != null) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("from", previousReviewStatus);
            detail.put("to", normalized);
            detail.put("reason", reviewReason);
            detail.put("reviewedBy", actor);
            detail.put("reviewOrigin", origin);
            auditEventService.recordSync("ai-news.candidate.reviewed", "AI_NEWS_CANDIDATE",
                    String.valueOf(candidateId), candidate.getTitle(), json(detail), ws);
        }
        refreshRelatedRunCounts(candidateId);
        return candidate;
    }

    private static String normalizeReviewOrigin(String value) {
        if (value == null || value.isBlank()) return "SYSTEM";
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return normalized.length() <= 32 ? normalized : normalized.substring(0, 32);
    }

    private void refreshRelatedRunCounts(Long candidateId) {
        List<Long> runIds = observationMapper.selectScanRunIds(candidateId);
        if (runIds == null) return;
        for (Long runId : runIds) refreshRunCounts(runId);
    }

    private void refreshRunCounts(Long scanRunId) {
        AiNewsScanRunEntity run = scanMapper.selectById(scanRunId);
        if (run == null || Integer.valueOf(1).equals(run.getDeleted())) return;
        String expectedStatus = run.getRunStatus();
        List<AiNewsCandidateEntity> selected = selectedCandidates(scanRunId);
        List<AiNewsCandidateEntity> all = candidatesForScan(scanRunId);
        // The discovery/capture scorecard stored in summary_json is a frozen
        // evaluation artifact. The run row's operational counters, however,
        // must continue to reflect a later human review (review normally
        // happens after the capture queue drains). Updating only these scalar
        // counters keeps both use cases honest without rewriting the frozen
        // snapshot or ranking hashes.
        if (!TERMINAL_RUN_STATUSES.contains(expectedStatus)) {
            run.setCaptureSuccessCount(count(selected, AiNewsCandidateEntity::getCaptureStatus, "SUCCESS"));
            run.setCaptureFailureCount(count(selected, AiNewsCandidateEntity::getCaptureStatus, "FAILED"));
        }
        run.setReviewedCount((int) all.stream().filter(item -> Set.of("ACCEPTED", "REJECTED")
                .contains(item.getReviewStatus())).count());
        run.setAcceptedCount(count(all, AiNewsCandidateEntity::getReviewStatus, "ACCEPTED"));
        boolean capturePending = selected.stream().anyMatch(AiNewsCandidatePipelineService::captureInFlight)
                || properties.isCaptureEnabled()
                && selected.stream().anyMatch(AiNewsCandidatePipelineService::captureWaiting);
        LocalDateTime updatedAt = now();
        LambdaUpdateWrapper<AiNewsScanRunEntity> update = new LambdaUpdateWrapper<AiNewsScanRunEntity>()
                .eq(AiNewsScanRunEntity::getId, scanRunId)
                .eq(AiNewsScanRunEntity::getDeleted, 0)
                .eq(AiNewsScanRunEntity::getRunStatus, expectedStatus)
                .set(AiNewsScanRunEntity::getReviewedCount, run.getReviewedCount())
                .set(AiNewsScanRunEntity::getAcceptedCount, run.getAcceptedCount())
                .set(AiNewsScanRunEntity::getUpdateTime, updatedAt);
        if (!TERMINAL_RUN_STATUSES.contains(expectedStatus)) {
            update.set(AiNewsScanRunEntity::getCaptureSuccessCount, run.getCaptureSuccessCount())
                    .set(AiNewsScanRunEntity::getCaptureFailureCount, run.getCaptureFailureCount());
        }
        if ("CAPTURE_PENDING".equals(expectedStatus) && !capturePending) {
            LocalDateTime finishedAt = updatedAt;
            run.setRunStatus("COMPLETED");
            run.setActiveSlot(null);
            run.setFinishedAt(finishedAt);
            run.setSummaryJson(json(summaryMap(run, all, selected)));
            update.set(AiNewsScanRunEntity::getRunStatus, "COMPLETED")
                    .set(AiNewsScanRunEntity::getActiveSlot, null)
                    .set(AiNewsScanRunEntity::getFinishedAt, finishedAt)
                    .set(AiNewsScanRunEntity::getSummaryJson, run.getSummaryJson());
        }
        // A stale refresh must never move a run backwards.  If another
        // completion/review won the status CAS, this refresh is simply
        // retried by the next queue event.
        scanMapper.update(null, update);
    }

    private Scorecard persistedScorecard(JsonNode audit,
                                         List<AiNewsCandidateEntity> all,
                                         List<AiNewsCandidateEntity> selected) {
        JsonNode stored = audit == null ? null : audit.path("scorecard");
        if (stored == null || stored.isMissingNode() || stored.isNull()) {
            return scorecard(all, selected);
        }
        try {
            return objectMapper.treeToValue(stored, Scorecard.class);
        } catch (Exception ignored) {
            // Old summaries predate the immutable scorecard shape; keep the
            // endpoint readable and use a best-effort live projection.
            return scorecard(all, selected);
        }
    }

    /**
     * Keep discovery/capture metrics immutable while exposing later human
     * adjudication in the read projection.  A completed run is commonly
     * reviewed after the queue drains; returning the frozen whole scorecard
     * made the UI permanently report zero reviewer acceptance.
     */
    private Scorecard visibleCompletedScorecard(JsonNode audit,
                                                List<AiNewsCandidateEntity> all,
                                                List<AiNewsCandidateEntity> selected) {
        Scorecard frozen = persistedScorecard(audit, all, selected);
        Scorecard live = scorecard(all, selected);
        return new Scorecard(frozen.candidateRecall(), live.relevantPrecision(),
                frozen.usableCaptureRate(), live.reviewerAcceptance(),
                live.acceptedUniqueStoryCount());
    }

    private static boolean capturePending(AiNewsCandidateEntity candidate) {
        String status = candidate == null ? null : candidate.getCaptureStatus();
        return "PENDING".equals(status) || "RETRYABLE".equals(status) || "CAPTURING".equals(status);
    }

    private static boolean captureInFlight(AiNewsCandidateEntity candidate) {
        return candidate != null && "CAPTURING".equals(candidate.getCaptureStatus());
    }

    private static boolean captureWaiting(AiNewsCandidateEntity candidate) {
        if (candidate == null) return false;
        String status = candidate.getCaptureStatus();
        return "PENDING".equals(status) || "RETRYABLE".equals(status);
    }

    private Map<String, Object> summaryMap(AiNewsScanRunEntity run,
                                           List<AiNewsCandidateEntity> all,
                                           List<AiNewsCandidateEntity> selected) {
        Map<String, Object> summary = new LinkedHashMap<>();
        String discovery = scanMapper.selectSummaryJson(run.getId());
        if (discovery != null && !discovery.isBlank()) {
            JsonNode previous = readJson(discovery);
            summary.put("discovery", previous.has("discovery")
                    ? previous.get("discovery") : previous);
        }
        summary.put("rawResultCount", value(run.getRawResultCount()));
        summary.put("invalidResultCount", value(run.getInvalidResultCount()));
        summary.put("uniqueCandidateCount", all.size());
        summary.put("selectedCandidateCount", selected.size());
        List<AiNewsProviderYieldRow> providerYield = observationMapper.selectProviderYields(run.getId());
        summary.put("providerYield", providerYield);
        int providerCandidateTouches = providerYield.stream()
                .map(AiNewsProviderYieldRow::getCandidateCount).mapToInt(AiNewsCandidatePipelineService::value)
                .sum();
        summary.put("duplicateProviderTouches", Math.max(0, providerCandidateTouches - all.size()));
        summary.put("selectionStatus", statusCounts(all, AiNewsCandidateEntity::getSelectionStatus));
        summary.put("captureStatus", statusCounts(all, AiNewsCandidateEntity::getCaptureStatus));
        summary.put("timeConfidence", statusCounts(all, AiNewsCandidateEntity::getTimeConfidence));
        summary.put("reviewStatus", statusCounts(all, AiNewsCandidateEntity::getReviewStatus));
        summary.put("scorecard", scorecard(all, selected));
        return summary;
    }

    private static List<Map<String, Object>> providerStatuses(
            List<AiNewsDiscoverySearchService.QueryExecution> executions) {
        if (executions == null) return List.of();
        return executions.stream().map(item -> {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("providerId", nullToEmpty(item.providerId()));
            status.put("queryLane", nullToEmpty(item.family()));
            status.put("resultCount", item.resultCount());
            status.put("status", item.failureMessage() == null || item.failureMessage().isBlank()
                    ? "SUCCESS" : item.failureMessage());
            return Map.copyOf(status);
        }).toList();
    }

    private Scorecard scorecard(List<AiNewsCandidateEntity> all,
                                List<AiNewsCandidateEntity> selected) {
        int captureSuccess = count(selected, AiNewsCandidateEntity::getCaptureStatus, "SUCCESS");
        int reviewed = (int) all.stream().filter(item -> Set.of("ACCEPTED", "REJECTED")
                .contains(item.getReviewStatus())).count();
        int accepted = count(all, AiNewsCandidateEntity::getReviewStatus, "ACCEPTED");
        int selectedReviewed = (int) selected.stream()
                .filter(item -> Set.of("ACCEPTED", "REJECTED").contains(item.getReviewStatus()))
                .count();
        int selectedAccepted = count(selected, AiNewsCandidateEntity::getReviewStatus, "ACCEPTED");
        long acceptedStories = all.stream()
                .filter(item -> "ACCEPTED".equals(item.getReviewStatus()))
                .map(item -> item.getStoryId() != null
                        ? "story:" + item.getStoryId()
                        : item.getEventId() == null ? null : "event:" + item.getEventId())
                .filter(java.util.Objects::nonNull).distinct().count();
        boolean captureEnabled = properties.isCaptureEnabled();
        return new Scorecard(
                new Metric("找得全", 0, 0, null,
                        "需要外部参考事件集；代码完成不能替代真实 Recall@24h"),
                new Metric("找得准", selectedAccepted, selectedReviewed,
                        ratio(selectedAccepted, selectedReviewed),
                        "首版以已审核 selected 候选的人工相关率作为 Precision 基线"),
                new Metric("抓得到", captureEnabled ? captureSuccess : 0,
                        captureEnabled ? selected.size() : 0,
                        captureEnabled ? ratio(captureSuccess, selected.size()) : null,
                        captureEnabled ? "selected → usable capture"
                                : "capture 未启用；本轮为 N/A，不把未执行计为失败"),
                new Metric("用得住", accepted, reviewed, ratio(accepted, reviewed),
                        "reviewer acceptance；有 story_id 或 promotion event_id 时报告独立故事数"),
                Math.toIntExact(acceptedStories));
    }

    private List<AiNewsCandidateEntity> candidatesForScan(Long scanRunId) {
        List<Long> ids = observationMapper.selectCandidateIds(scanRunId);
        return ids == null || ids.isEmpty() ? List.of() : candidateMapper.selectBatchIds(ids).stream()
                // V214 makes scan ownership explicit on the candidate row. The
                // observation query remains for provider/selection semantics, but
                // this second fence prevents a legacy mutable projection from
                // leaking a row from another run into scorecards or completion.
                .filter(item -> item != null && scanRunId != null && scanRunId.equals(item.getScanRunId()))
                .toList();
    }

    private List<AiNewsCandidateEntity> selectedCandidates(Long scanRunId) {
        List<Long> ids = observationMapper.selectSelectedCandidateIds(scanRunId);
        return ids == null || ids.isEmpty() ? List.of() : candidateMapper.selectBatchIds(ids).stream()
                .filter(item -> item != null && scanRunId != null && scanRunId.equals(item.getScanRunId()))
                .toList();
    }

    private AiNewsScanRunEntity requireMutableRun(Long id) {
        AiNewsScanRunEntity run = requireRun(id);
        // Discovery writes candidates and then freezes the run summary.  Hold
        // the owning run row first so it follows the same run -> candidate
        // lock order as promotion/review/failScan; otherwise a replay could
        // deadlock after taking a candidate row and waiting on this run.
        AiNewsScanRunEntity locked = scanMapper.selectForUpdate(run.getId(),
                workspace(run.getWorkspaceId()));
        if (locked == null || Integer.valueOf(1).equals(locked.getDeleted())) {
            throw new NewsClawException(409, "candidate scan changed during discovery persistence");
        }
        run = locked;
        if (!Set.of("RUNNING", "CANDIDATES_PERSISTED").contains(run.getRunStatus())) {
            throw new IllegalStateException("candidate scan is terminal: " + run.getRunStatus());
        }
        return run;
    }

    /**
     * Acquire capture's owning run before its candidate row.  Completion also
     * re-reads the candidate under that lock: a stale worker must not finish a
     * candidate after it moved to another run or was promoted.
     */
    private boolean lockCaptureOwner(Long candidateId) {
        if (candidateId == null) return false;
        AiNewsCandidateEntity snapshot = candidateMapper.selectById(candidateId);
        if (snapshot == null || Integer.valueOf(1).equals(snapshot.getDeleted())) return false;
        Long runId = snapshot.getScanRunId();
        Long workspaceId = snapshot.getWorkspaceId();
        if (runId == null || workspaceId == null) return true;
        AiNewsScanRunEntity run = scanMapper.selectForUpdate(runId, workspace(workspaceId));
        if (run == null || Integer.valueOf(1).equals(run.getDeleted())) return false;
        AiNewsCandidateEntity locked = candidateMapper.selectForUpdate(candidateId,
                workspace(workspaceId));
        return locked != null && !Integer.valueOf(1).equals(locked.getDeleted())
                && java.util.Objects.equals(runId, locked.getScanRunId());
    }

    private AiNewsScanRunEntity requireRun(Long id) {
        if (id == null) throw new NewsClawException(400, "candidate scan id is required");
        AiNewsScanRunEntity run = scanMapper.selectById(id);
        if (run == null || Integer.valueOf(1).equals(run.getDeleted())) {
            throw new NewsClawException(404, "candidate scan not found");
        }
        return run;
    }

    private AiNewsCandidateEntity requireCandidate(Long id) {
        if (id == null) throw new NewsClawException(400, "candidate id is required");
        AiNewsCandidateEntity candidate = candidateMapper.selectById(id);
        if (candidate == null || Integer.valueOf(1).equals(candidate.getDeleted())) {
            throw new NewsClawException(404, "candidate not found");
        }
        return candidate;
    }

    /**
     * The legacy non-fenced callbacks are retained for extension compatibility,
     * but they must not mutate the capture that is already part of an event's
     * evidence lineage.  Fenced mapper callbacks carry the same guard in SQL.
     */
    private static void ensureNotPromoted(AiNewsCandidateEntity candidate) {
        if (candidate != null && candidate.getEventId() != null) {
            throw new NewsClawException(409,
                    "候选已形成事件，不能再覆盖其 capture 状态");
        }
    }

    private void rejectStaleCaptureCompletion(Long candidateId) {
        AiNewsCandidateEntity current = requireCandidate(candidateId);
        if (current.getEventId() != null) {
            throw new NewsClawException(409,
                    "候选已形成事件，不能再覆盖其 capture 状态");
        }
        throw new NewsClawException(409,
                "capture completion is stale or candidate is no longer capturing");
    }

    private String rejectionReason(AiNewsDiscoverySearchService.SnapshotResult row,
                                   String canonical,
                                   Instant windowStart,
                                   Instant windowEnd) {
        if (AiNewsDiscoverySearchService.hasExplicitUrlDateOutsideWindow(
                canonical, windowStart, windowEnd)) return "URL_DATE_OUTSIDE_WINDOW";
        if (AiNewsDiscoverySearchService.isObviousNonNewsUrl(canonical)) return "NON_ARTICLE_URL";
        SearchResult result = SearchResult.builder().title(row.title()).url(canonical)
                .snippet(row.snippet()).date(row.publishedAtHint()).source(row.source())
                .providerId(row.providerId()).relevanceScore(row.relevanceScore()).build();
        if (AiNewsDiscoverySearchService.isObviousPromotion(result)) return "PROMOTIONAL_CONTENT";
        if (AiNewsDiscoverySearchService.isObviousNonEventContent(result)) return "NON_NEWS_CONTENT";
        if ((row.publishedAtHint() == null || row.publishedAtHint().isBlank())
                && AiNewsDiscoverySearchService.isObviousUndatedLandingUrl(canonical)) {
            return "UNDATED_LANDING_PAGE";
        }
        return null;
    }

    private String timeConfidence(AiNewsDiscoverySearchService.SnapshotResult row,
                                  AiNewsDiscoverySearchService.DiscoveryCandidate selected,
                                  String rejection) {
        if ("URL_DATE_OUTSIDE_WINDOW".equals(rejection)) return "OUTSIDE_WINDOW";
        if (selected != null) return switch (selected.temporalStatus()) {
            case IN_WINDOW -> "IN_WINDOW_HINT";
            case OUTSIDE_WINDOW -> "OUTSIDE_WINDOW";
            case UNKNOWN -> "UNKNOWN";
        };
        return row.publishedAtHint() == null || row.publishedAtHint().isBlank()
                ? "UNKNOWN" : "UNVERIFIED_HINT";
    }

    private String sourceClass(String url) {
        if (sourceRegistry.isOfficialUrl(url)) return "OFFICIAL";
        if (sourceRegistry.isTrustedMediaUrl(url)) return "MEDIA";
        return "OPEN_WEB";
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception error) {
            throw new NewsClawException(500, "candidate scan summary is unreadable");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("failed to serialize candidate scan summary", error);
        }
    }

    private static <T> int count(List<T> rows, Function<T, String> getter, String expected) {
        return (int) rows.stream().filter(row -> expected.equals(getter.apply(row))).count();
    }

    private static <T> Map<String, Long> statusCounts(List<T> rows, Function<T, String> getter) {
        return rows.stream().map(getter).map(value -> value == null || value.isBlank()
                        ? "UNKNOWN" : value)
                .collect(Collectors.groupingBy(Function.identity(), java.util.TreeMap::new,
                        Collectors.counting()));
    }

    private static Double ratio(int numerator, int denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    private static <T> Page<T> page(int page, int size) {
        return new Page<>(Math.max(1, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE));
    }

    private static String upper(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static long numeric(Long value) {
        return value == null || value <= 0 ? -1L : value;
    }

    private static String sqlToken(String value, int max) {
        String normalized = token(value, "", max);
        if (!normalized.matches("[A-Za-z0-9_.:-]{1," + max + "}")) {
            throw new NewsClawException(400, "invalid provider filter");
        }
        return normalized;
    }

    private static String token(String value, String fallback, int max) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) normalized = fallback;
        normalized = normalized.replaceAll("[^A-Za-z0-9_.:-]", "_");
        return trim(normalized, max);
    }

    private static String trim(String value, int max) {
        if (value == null) return "";
        String normalized = value.trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    private static String trimToNull(String value, int max) {
        String normalized = trim(value, max);
        return normalized.isBlank() ? null : normalized;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return "";
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static long workspace(Long workspaceId) {
        return workspaceId == null || workspaceId <= 0 ? 1L : workspaceId;
    }

    private static LocalDateTime utc(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static LocalDateTime now() {
        // Candidate/run tables use TIMESTAMP(3); truncating at the service
        // boundary prevents an immediate retry from being rounded into the
        // future by a database that stores milliseconds.
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }

    private static Instant instant(String value, Instant fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Instant.parse(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    /**
     * Fencing token returned by a successful capture claim.  The attempt is
     * persisted on the candidate row and must be supplied when the worker
     * records a terminal result.
     */
    public record CaptureLease(Long candidateId, int attempt) {
    }

    public record PersistenceSummary(int rawResultCount,
                                     int invalidResultCount,
                                     int uniqueCandidateCount,
                                     int selectedCandidateCount,
                                     int providerCount) {
    }

    public record ScanStart(AiNewsScanRunEntity run, boolean reused) {
    }

    public record ProviderYield(String providerId,
                                int candidateCount,
                                int selectedCount,
                                int marginalUniqueCount) {
    }

    public record Metric(String name,
                         int numerator,
                         int denominator,
                         Double rate,
                         String note) {
    }

    public record Scorecard(Metric candidateRecall,
                            Metric relevantPrecision,
                            Metric usableCaptureRate,
                            Metric reviewerAcceptance,
                            int acceptedUniqueStoryCount) {
    }

    public record RunSummary(AiNewsScanRunEntity run,
                             List<ProviderYield> providers,
                             Scorecard scorecard,
                             JsonNode audit) {
        public RunSummary {
            providers = providers == null ? List.of() : List.copyOf(providers);
        }
    }
}
