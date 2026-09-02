package vip.newsclaw.news.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.newsclaw.news.model.AiNewsIngestedCandidateRow;
import vip.newsclaw.news.model.AiNewsIngestionRunEntity;
import vip.newsclaw.news.model.AiNewsIngestionRunItemEntity;
import vip.newsclaw.news.model.AiNewsRawCaptureEntity;
import vip.newsclaw.news.model.AiNewsSourceEndpointEntity;
import vip.newsclaw.news.model.AiNewsSourceItemEntity;
import vip.newsclaw.news.model.AiNewsSourceItemVersionEntity;
import vip.newsclaw.news.repository.AiNewsIngestionRunMapper;
import vip.newsclaw.news.repository.AiNewsIngestionRunItemMapper;
import vip.newsclaw.news.repository.AiNewsRawCaptureMapper;
import vip.newsclaw.news.repository.AiNewsSourceEndpointMapper;
import vip.newsclaw.news.repository.AiNewsSourceItemMapper;
import vip.newsclaw.news.repository.AiNewsSourceItemVersionMapper;
import vip.newsclaw.news.source.NewsSourceEndpointDescriptor;
import vip.newsclaw.news.source.NewsSourceHashing;
import vip.newsclaw.news.source.NewsSourcePollBatch;
import vip.newsclaw.news.source.NewsSourceProvenance;
import vip.newsclaw.news.source.NewsSourceResult;
import vip.newsclaw.news.source.NewsSourceTransportRecord;
import vip.newsclaw.news.source.NewsSourceValidators;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Transactional persistence boundary for the structured-source acquisition ledger.
 * Network I/O happens outside this service so no database transaction is held open
 * while a publisher is slow or unavailable.
 */
@Service
@Slf4j
public class AiNewsIngestionLedgerService {

    private static final int MAX_RECENT_RESULTS = 500;
    /** Compatibility callers may start a run without an earlier claimDue call. */
    private static final long DEFAULT_START_LEASE_SECONDS = 1_800L;
    private static final Set<String> TRANSIENT_VERSION_METADATA = Set.of(
            "etag", "lastModified", "revalidated", "revalidatedAt",
            "sourceCatalogVersion", "sourceEndpointId", "sourceEndpointOwnerKey",
            "sourceEndpointRightsStatus", "sourceEndpointRawRetention",
            "sourceEndpointRobotsStatus", "sourceEndpointEvidenceEligible");

    private final AiNewsSourceEndpointMapper endpointMapper;
    private final AiNewsIngestionRunMapper runMapper;
    private final AiNewsSourceItemMapper itemMapper;
    private final AiNewsSourceItemVersionMapper versionMapper;
    private final AiNewsIngestionRunItemMapper runItemMapper;
    private final AiNewsRawCaptureMapper rawCaptureMapper;
    private final ObjectMapper objectMapper;

    public AiNewsIngestionLedgerService(AiNewsSourceEndpointMapper endpointMapper,
                                        AiNewsIngestionRunMapper runMapper,
                                        AiNewsSourceItemMapper itemMapper,
                                        AiNewsSourceItemVersionMapper versionMapper,
                                        AiNewsIngestionRunItemMapper runItemMapper,
                                        AiNewsRawCaptureMapper rawCaptureMapper,
                                        ObjectMapper objectMapper) {
        this.endpointMapper = endpointMapper;
        this.runMapper = runMapper;
        this.itemMapper = itemMapper;
        this.versionMapper = versionMapper;
        this.runItemMapper = runItemMapper;
        this.rawCaptureMapper = rawCaptureMapper;
        this.objectMapper = objectMapper;
    }

    /** Reconcile one provider's configured endpoints and fail closed for removed rows. */
    @Transactional
    public List<AiNewsSourceEndpointEntity> reconcileProvider(
            String providerId, List<NewsSourceEndpointDescriptor> descriptors) {
        List<NewsSourceEndpointDescriptor> safeDescriptors = descriptors == null
                ? List.of() : List.copyOf(descriptors);
        LinkedHashSet<String> activeKeys = safeDescriptors.stream()
                .map(NewsSourceEndpointDescriptor::endpointKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<AiNewsSourceEndpointEntity> rows = new ArrayList<>();
        for (NewsSourceEndpointDescriptor descriptor : safeDescriptors) {
            if (!descriptor.providerId().equals(providerId)) {
                throw new IllegalArgumentException("endpoint providerId does not match reconciled provider");
            }
            rows.add(syncEndpoint(descriptor));
        }
        List<AiNewsSourceEndpointEntity> existing = endpointMapper.selectList(
                new LambdaQueryWrapper<AiNewsSourceEndpointEntity>()
                        .eq(AiNewsSourceEndpointEntity::getProviderId, providerId)
                        .eq(AiNewsSourceEndpointEntity::getDeleted, 0));
        LocalDateTime now = databaseNow();
        for (AiNewsSourceEndpointEntity row : existing) {
            if (!activeKeys.contains(row.getEndpointKey()) && Boolean.TRUE.equals(row.getEnabled())) {
                row.setEnabled(false);
                row.setNextPollAt(null);
                row.setUpdateTime(now);
                endpointMapper.updateById(row);
            }
        }
        return List.copyOf(rows);
    }

    @Transactional
    public AiNewsSourceEndpointEntity syncEndpoint(NewsSourceEndpointDescriptor descriptor) {
        AiNewsSourceEndpointEntity row = endpointMapper.selectOne(
                new LambdaQueryWrapper<AiNewsSourceEndpointEntity>()
                        .eq(AiNewsSourceEndpointEntity::getEndpointKey, descriptor.endpointKey())
                        .eq(AiNewsSourceEndpointEntity::getDeleted, 0));
        LocalDateTime now = databaseNow();
        String fingerprint = descriptor.configFingerprint();
        boolean resetCursor = row == null || !fingerprint.equals(row.getConfigFingerprint())
                || !Boolean.TRUE.equals(row.getEnabled());
        if (row == null) {
            row = new AiNewsSourceEndpointEntity();
            row.setEndpointKey(descriptor.endpointKey());
            row.setConsecutiveFailures(0);
            row.setCreateTime(now);
            row.setDeleted(0);
        }
        row.setCatalogVersion(descriptor.catalogVersion());
        row.setSourceKey(trim(descriptor.sourceKey(), 128));
        row.setProviderId(trim(descriptor.providerId(), 64));
        row.setChannel(descriptor.channel().name());
        row.setAdapter(trim(descriptor.adapter(), 32));
        row.setEndpointUrl(trim(descriptor.url().toString(), 4096));
        row.setEndpointUrlHash(NewsSourceHashing.sha256(descriptor.url().normalize().toString()));
        row.setEnabled(true);
        row.setLanguagesJson(json(descriptor.languages()));
        row.setCategoriesJson(json(descriptor.categories()));
        row.setPollIntervalSeconds(descriptor.pollIntervalSeconds());
        row.setEvidenceEligible(descriptor.evidenceEligible());
        row.setRightsStatus(trim(descriptor.rightsStatus(), 64));
        row.setRawRetention(trim(descriptor.rawRetention(), 32));
        row.setRobotsStatus(trim(descriptor.robotsStatus(), 64));
        row.setConfigFingerprint(fingerprint);
        row.setUpdateTime(now);
        if (resetCursor) {
            row.setEtag(null);
            row.setLastModified(null);
            row.setConsecutiveFailures(0);
            row.setLastError(null);
            row.setNextPollAt(now);
        }
        if (row.getId() == null) endpointMapper.insert(row);
        else endpointMapper.updateById(row);
        return row;
    }

    public boolean isDue(AiNewsSourceEndpointEntity endpoint, Instant now) {
        if (endpoint == null || !Boolean.TRUE.equals(endpoint.getEnabled())) return false;
        LocalDateTime due = endpoint.getNextPollAt();
        // Endpoint cursor columns are TIMESTAMP(3) across supported schemas;
        // quantise the compare-and-set token before both the SQL update and
        // the in-memory lease snapshot so nanosecond clock values cannot make
        // a valid claim appear lost at startRun().
        LocalDateTime reference = databaseTimestamp(utc(now == null ? Instant.now() : now));
        return due == null || !due.isAfter(reference);
    }

    public NewsSourceValidators validators(AiNewsSourceEndpointEntity endpoint) {
        return endpoint == null ? NewsSourceValidators.EMPTY
                : new NewsSourceValidators(endpoint.getEtag(), endpoint.getLastModified());
    }

    /**
     * Atomically lease a due endpoint. The conditional database update is the
     * authority; an in-memory due check is only an optimization for callers.
     */
    @Transactional
    public boolean claimDue(AiNewsSourceEndpointEntity endpoint, Instant now, Duration lease) {
        if (endpoint == null || endpoint.getId() == null
                || !Boolean.TRUE.equals(endpoint.getEnabled())) return false;
        // Endpoint cursors are TIMESTAMP(3)/DATETIME(3) on every supported
        // database.  Truncate before both the CAS predicate and the in-memory
        // token so a caller cannot lose its own lease to driver rounding.
        LocalDateTime reference = databaseTimestamp(utc(now == null ? Instant.now() : now));
        long leaseSeconds = lease == null || lease.isNegative() || lease.isZero()
                ? 1800L : lease.toSeconds();
        leaseSeconds = Math.min(Math.max(leaseSeconds, 60L), 86_400L);
        LocalDateTime leaseUntil = databaseTimestamp(reference.plusSeconds(leaseSeconds));
        if (endpointMapper.claimDue(endpoint.getId(), reference, leaseUntil) != 1) return false;
        endpoint.setLastAttemptAt(reference);
        endpoint.setNextPollAt(leaseUntil);
        endpoint.setUpdateTime(reference);
        return true;
    }

    /** Start is committed before network I/O, leaving a recoverable started row on process death. */
    @Transactional
    public AiNewsIngestionRunEntity startRun(AiNewsSourceEndpointEntity endpoint,
                                             NewsSourceEndpointDescriptor descriptor,
                                             String triggerType) {
        if (endpoint == null || endpoint.getId() == null) {
            throw new IllegalArgumentException("persisted endpoint is required");
        }
        if (!descriptor.endpointKey().equals(endpoint.getEndpointKey())) {
            throw new IllegalArgumentException("endpoint descriptor does not match persisted endpoint");
        }
        // Persist the owner marker at the same precision used by all supported
        // schemas (TIMESTAMP/DATETIME(3)).  The marker is later used in
        // conditional endpoint updates; retaining nanoseconds in the Java
        // object would make an otherwise valid equality predicate miss.
        LocalDateTime now = databaseNow();
        // claimDue() updates the database before returning.  Re-read the
        // rounded values so the compare-and-set below works on MySQL, H2 and
        // Kingbase alike (their TIMESTAMP(3) conversion may round or truncate
        // the nanoseconds carried by the caller's object).
        AiNewsSourceEndpointEntity persistedEndpoint = endpointMapper.selectById(endpoint.getId());
        if (persistedEndpoint == null || Integer.valueOf(1).equals(persistedEndpoint.getDeleted())
                || !Boolean.TRUE.equals(persistedEndpoint.getEnabled())
                || !sameDatabaseTimestamp(endpoint.getLastAttemptAt(),
                        persistedEndpoint.getLastAttemptAt())
                || !sameDatabaseTimestamp(endpoint.getNextPollAt(),
                        persistedEndpoint.getNextPollAt())) {
            throw new IllegalStateException("endpoint lease was lost before ingestion run start: "
                    + endpoint.getId());
        }
        LocalDateTime claimedAt = persistedEndpoint.getLastAttemptAt();
        LocalDateTime claimedLeaseUntil = persistedEndpoint.getNextPollAt();
        // A future next_poll_at is the persisted lease signal.  Only an
        // absent/expired cursor may be claimed implicitly at this boundary;
        // callers must not bypass a still-valid schedule/lease.
        boolean acquireLease = claimedLeaseUntil == null
                || !claimedLeaseUntil.isAfter(now);
        if (acquireLease) {
            /*
             * Direct/manual callers may arrive without going through
             * claimDue().  Install a lease with one compare-and-set that
             * includes the exact cursor values observed above.  A null owner
             * is claimable even when a caller supplied a future scheduling
             * cursor; an existing owner is claimable only after its lease has
             * expired.  This closes the window in which startRun would create
             * a run while another node could immediately claim the endpoint.
             */
            LocalDateTime leaseUntil = databaseTimestamp(now.plusSeconds(
                    startLeaseSeconds(descriptor)));
            LambdaUpdateWrapper<AiNewsSourceEndpointEntity> claim =
                    new LambdaUpdateWrapper<AiNewsSourceEndpointEntity>()
                            .eq(AiNewsSourceEndpointEntity::getId, endpoint.getId())
                            .eq(AiNewsSourceEndpointEntity::getDeleted, 0)
                            .eq(AiNewsSourceEndpointEntity::getEnabled, true)
                            .set(AiNewsSourceEndpointEntity::getLastAttemptAt, now)
                            .set(AiNewsSourceEndpointEntity::getNextPollAt, leaseUntil)
                            .set(AiNewsSourceEndpointEntity::getUpdateTime, now);
            if (claimedAt == null) claim.isNull(AiNewsSourceEndpointEntity::getLastAttemptAt);
            else claim.eq(AiNewsSourceEndpointEntity::getLastAttemptAt, claimedAt);
            if (claimedLeaseUntil == null) {
                claim.isNull(AiNewsSourceEndpointEntity::getNextPollAt);
            } else if (claimedAt != null) {
                // An existing owner may be reclaimed only after its lease is
                // due; the exact cursor value prevents a concurrent claimant
                // from being overwritten.
                claim.le(AiNewsSourceEndpointEntity::getNextPollAt, now)
                        .eq(AiNewsSourceEndpointEntity::getNextPollAt, claimedLeaseUntil);
            } else {
                // A null owner is an unclaimed configuration row even if an
                // operator supplied a future scheduling cursor.
                claim.eq(AiNewsSourceEndpointEntity::getNextPollAt, claimedLeaseUntil);
            }
            if (endpointMapper.update(null, claim) != 1) {
                throw new IllegalStateException("endpoint lease was lost before ingestion run start: "
                        + endpoint.getId());
            }
            // Read back the database representation.  Besides handling
            // dialects that round TIMESTAMP(3), this gives the run exactly
            // the owner marker used by terminal endpoint CAS updates.
            persistedEndpoint = endpointMapper.selectById(endpoint.getId());
            if (persistedEndpoint == null
                    || Integer.valueOf(1).equals(persistedEndpoint.getDeleted())
                    || !Boolean.TRUE.equals(persistedEndpoint.getEnabled())
                    || !sameDatabaseTimestamp(now, persistedEndpoint.getLastAttemptAt())
                    || persistedEndpoint.getNextPollAt() == null
                    || !persistedEndpoint.getNextPollAt().isAfter(now)
                    || !sameDatabaseTimestamp(leaseUntil, persistedEndpoint.getNextPollAt())) {
                throw new IllegalStateException("endpoint lease was lost before ingestion run start: "
                        + endpoint.getId());
            }
            // Keep the marker generated by this CAS as the fencing token.  Do
            // not copy a potentially newer marker observed by the read-back
            // into this run if another starter raced in the same millisecond.
            claimedAt = now;
            claimedLeaseUntil = persistedEndpoint.getNextPollAt();
        } else {
            /*
             * claimDue() already installed a lease in next_poll_at.  Do not
             * replace that lease with the (usually shorter) polling interval
             * before network I/O starts: doing so lets another node claim the
             * same endpoint while this run is still in flight.  The terminal
             * completion path computes the next real poll time.  We still
             * refresh last_attempt_at as the run's ownership marker, guarded
             * by both cursor columns so a concurrent claimant cannot win
             * silently between the read and this update.
             */
            LambdaUpdateWrapper<AiNewsSourceEndpointEntity> ownerUpdate =
                    new LambdaUpdateWrapper<AiNewsSourceEndpointEntity>()
                            .eq(AiNewsSourceEndpointEntity::getId, endpoint.getId())
                            .eq(AiNewsSourceEndpointEntity::getDeleted, 0)
                            .eq(AiNewsSourceEndpointEntity::getEnabled, true)
                            .set(AiNewsSourceEndpointEntity::getLastAttemptAt, now)
                            .set(AiNewsSourceEndpointEntity::getUpdateTime, now);
            if (claimedAt == null) ownerUpdate.isNull(AiNewsSourceEndpointEntity::getLastAttemptAt);
            else ownerUpdate.eq(AiNewsSourceEndpointEntity::getLastAttemptAt, claimedAt);
            ownerUpdate.eq(AiNewsSourceEndpointEntity::getNextPollAt, claimedLeaseUntil);
            if (endpointMapper.update(null, ownerUpdate) != 1) {
                throw new IllegalStateException("endpoint lease was lost before ingestion run start: "
                        + endpoint.getId());
            }
            claimedAt = now;
        }
        endpoint.setLastAttemptAt(claimedAt);
        endpoint.setNextPollAt(claimedLeaseUntil);
        endpoint.setUpdateTime(now);

        AiNewsIngestionRunEntity run = new AiNewsIngestionRunEntity();
        run.setEndpointId(endpoint.getId());
        run.setProviderId(trim(descriptor.providerId(), 64));
        run.setChannel(descriptor.channel().name());
        run.setTriggerType(trigger(triggerType));
        run.setTraceId(UUID.randomUUID().toString().replace("-", ""));
        run.setStartedAt(claimedAt);
        run.setRunStatus("started");
        run.setNotModified(false);
        run.setTransportCount(0);
        run.setItemCount(0);
        run.setNewItemCount(0);
        run.setNewVersionCount(0);
        run.setUnchangedItemCount(0);
        run.setBytesReceived(0L);
        run.setRetryCount(0);
        run.setCreateTime(now);
        run.setUpdateTime(now);
        run.setDeleted(0);
        if (runMapper.insert(run) != 1 || run.getId() == null) {
            throw new IllegalStateException("ingestion run insert did not produce an id");
        }
        return run;
    }

    /** Persist raw observations and normalized item versions, then close the run atomically. */
    @Transactional
    public Completion completeRun(AiNewsIngestionRunEntity run,
                                  AiNewsSourceEndpointEntity endpoint,
                                  NewsSourcePollBatch batch) {
        requireRun(run, endpoint, batch);
        Map<String, Integer> attempts = new LinkedHashMap<>();
        long bytes = 0L;
        for (NewsSourceTransportRecord transport : batch.transports()) {
            String hash = NewsSourceHashing.sha256(transport.requestUrl().normalize().toString());
            int attempt = attempts.merge(hash, 1, Integer::sum);
            persistRawCapture(run, endpoint, transport, hash, attempt);
            bytes += transport.receivedBytes();
        }

        int processed = 0;
        int newItems = 0;
        int newVersions = 0;
        int unchanged = 0;
        Set<String> observedIdentities = new LinkedHashSet<>();
        for (NewsSourceResult result : batch.results()) {
            String identityHash = itemIdentityHash(result);
            if (identityHash == null || !observedIdentities.add(identityHash)) continue;
            ItemOutcome outcome = persistItem(run, endpoint, result, batch.finishedAt());
            if (outcome == null) continue;
            persistRunItem(run, outcome, batch.finishedAt());
            processed++;
            if (outcome.newItem()) newItems++;
            if (outcome.newVersion()) newVersions++;
            else unchanged++;
        }

        NewsSourceTransportRecord rootTransport = batch.transports().stream()
                .filter(item -> item.requestUrl().normalize().equals(batch.endpoint().url().normalize()))
                .reduce((first, second) -> second).orElse(null);
        LocalDateTime finished = utc(batch.finishedAt());
        run.setFinishedAt(finished);
        run.setRunStatus(runStatus(batch.status()));
        run.setHttpStatus(rootTransport == null ? null : rootTransport.httpStatus());
        run.setNotModified(batch.status() == NewsSourcePollBatch.Status.NOT_MODIFIED);
        run.setTransportCount(batch.transports().size());
        run.setItemCount(processed);
        run.setNewItemCount(newItems);
        run.setNewVersionCount(newVersions);
        run.setUnchangedItemCount(unchanged);
        run.setBytesReceived(bytes);
        run.setRetryCount(Math.max(0, batch.transports().size() - attempts.size()));
        run.setErrorCode(trimToNull(batch.errorCode(), 64));
        run.setErrorMessage(trimToNull(batch.errorMessage(), 2000));
        run.setUpdateTime(LocalDateTime.now(ZoneOffset.UTC));
        updateRunIfStillStarted(run);

        updateEndpointAfterRun(run, endpoint, batch, rootTransport, finished);
        return new Completion(run.getId(), processed, newItems, newVersions, unchanged,
                batch.transports().size(), bytes, run.getRunStatus());
    }

    /** Best-effort terminal update used if the atomic completion transaction itself fails. */
    @Transactional
    public void markPersistenceFailure(AiNewsIngestionRunEntity run,
                                       AiNewsSourceEndpointEntity endpoint,
                                       Exception error) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        boolean runMarked = run == null || run.getId() == null;
        if (run != null && run.getId() != null) {
            String message = trim(error == null ? "unknown persistence error"
                    : error.getMessage(), 2000);
            runMarked = runMapper.update(null,
                    new LambdaUpdateWrapper<AiNewsIngestionRunEntity>()
                            .eq(AiNewsIngestionRunEntity::getId, run.getId())
                            .eq(AiNewsIngestionRunEntity::getRunStatus, "started")
                            .set(AiNewsIngestionRunEntity::getFinishedAt, now)
                            .set(AiNewsIngestionRunEntity::getRunStatus, "persistence_failed")
                            .set(AiNewsIngestionRunEntity::getErrorCode, "PERSISTENCE_ERROR")
                            .set(AiNewsIngestionRunEntity::getErrorMessage, message)
                            .set(AiNewsIngestionRunEntity::getUpdateTime, now)) == 1;
        }
        // If a stale-run reconciler (or another worker) already owns the
        // endpoint, do not apply this late failure to its cursor/failure
        // counters.  That would otherwise move a healthy newer poll backoff.
        if (runMarked && endpoint != null && endpoint.getId() != null) {
            int failures = value(endpoint.getConsecutiveFailures()) + 1;
            String message = trim(error == null ? "unknown persistence error"
                    : error.getMessage(), 2000);
            LocalDateTime nextPollAt = now.plusSeconds(failureDelay(endpoint, failures));
            if (run != null && run.getStartedAt() != null) {
                // A late failure must not overwrite a lease installed by a
                // newer run.  The endpoint timestamp written by startRun is
                // the small, schema-compatible ownership token for this
                // update.
                endpointMapper.recordFailureIfOwned(endpoint.getId(), run.getStartedAt(),
                        failures, message, nextPollAt, now);
            } else {
                // No run row means start-up failed before ownership could be
                // represented.  Keep the existing best-effort backoff for
                // that narrow compatibility path.
                endpoint.setConsecutiveFailures(failures);
                endpoint.setLastError(message);
                endpoint.setNextPollAt(nextPollAt);
                endpoint.setUpdateTime(now);
                endpointMapper.updateById(endpoint);
            }
        }
    }

    /** Mark interrupted started runs terminal so operators can distinguish crashes from slowness. */
    @Transactional
    public int abandonStaleRuns(Duration age) {
        Duration safeAge = age == null || age.isNegative() || age.isZero()
                ? Duration.ofMinutes(30) : age;
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minus(safeAge);
        List<AiNewsIngestionRunEntity> stale = runMapper.selectList(
                new LambdaQueryWrapper<AiNewsIngestionRunEntity>()
                        .eq(AiNewsIngestionRunEntity::getRunStatus, "started")
                        .lt(AiNewsIngestionRunEntity::getStartedAt, cutoff)
                        .eq(AiNewsIngestionRunEntity::getDeleted, 0));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int abandoned = 0;
        for (AiNewsIngestionRunEntity run : stale) {
            int updated = runMapper.update(null,
                    new LambdaUpdateWrapper<AiNewsIngestionRunEntity>()
                            .eq(AiNewsIngestionRunEntity::getId, run.getId())
                            .eq(AiNewsIngestionRunEntity::getRunStatus, "started")
                            .set(AiNewsIngestionRunEntity::getRunStatus, "abandoned")
                            .set(AiNewsIngestionRunEntity::getFinishedAt, now)
                            .set(AiNewsIngestionRunEntity::getErrorCode, "STALE_RUN")
                            .set(AiNewsIngestionRunEntity::getErrorMessage,
                                    "run did not reach a terminal state before stale-run reconciliation")
                            .set(AiNewsIngestionRunEntity::getUpdateTime, now));
            if (updated != 1) continue;
            abandoned++;
            // Do not release an endpoint unconditionally: a newer claimant may
            // have acquired it after the stale row was selected.  The mapper
            // predicate checks the old cursor timestamps and turns this into
            // a no-op when ownership has moved on.
            endpointMapper.releaseStaleLease(run.getEndpointId(), cutoff, now);
        }
        return abandoned;
    }

    /**
     * Complete a poll only if the run is still owned by this worker.  The
     * stale-run reconciler uses the same status predicate, so a late network
     * response cannot resurrect an abandoned row or overwrite a newer run.
     */
    private void updateRunIfStillStarted(AiNewsIngestionRunEntity run) {
        int updated = runMapper.update(null,
                new LambdaUpdateWrapper<AiNewsIngestionRunEntity>()
                        .eq(AiNewsIngestionRunEntity::getId, run.getId())
                        .eq(AiNewsIngestionRunEntity::getRunStatus, "started")
                        .set(AiNewsIngestionRunEntity::getFinishedAt, run.getFinishedAt())
                        .set(AiNewsIngestionRunEntity::getRunStatus, run.getRunStatus())
                        .set(AiNewsIngestionRunEntity::getHttpStatus, run.getHttpStatus())
                        .set(AiNewsIngestionRunEntity::getNotModified, run.getNotModified())
                        .set(AiNewsIngestionRunEntity::getTransportCount, run.getTransportCount())
                        .set(AiNewsIngestionRunEntity::getItemCount, run.getItemCount())
                        .set(AiNewsIngestionRunEntity::getNewItemCount, run.getNewItemCount())
                        .set(AiNewsIngestionRunEntity::getNewVersionCount, run.getNewVersionCount())
                        .set(AiNewsIngestionRunEntity::getUnchangedItemCount, run.getUnchangedItemCount())
                        .set(AiNewsIngestionRunEntity::getBytesReceived, run.getBytesReceived())
                        .set(AiNewsIngestionRunEntity::getRetryCount, run.getRetryCount())
                        .set(AiNewsIngestionRunEntity::getErrorCode, run.getErrorCode())
                        .set(AiNewsIngestionRunEntity::getErrorMessage, run.getErrorMessage())
                        .set(AiNewsIngestionRunEntity::getUpdateTime, run.getUpdateTime()));
        if (updated != 1) {
            throw new IllegalStateException("ingestion run is no longer owned by this worker: "
                    + run.getId());
        }
    }

    /** Latest persisted structured candidates for request-time RRF without network polling. */
    public List<NewsSourceResult> recentResults(Instant since, int requestedLimit) {
        Instant threshold = since == null ? Instant.now().minus(Duration.ofDays(31)) : since;
        int limit = Math.min(Math.max(requestedLimit, 1), MAX_RECENT_RESULTS);
        List<AiNewsIngestedCandidateRow> rows = versionMapper.selectRecentLatest(utc(threshold), limit);
        List<NewsSourceResult> out = new ArrayList<>();
        for (AiNewsIngestedCandidateRow row : rows) {
            try {
                Map<String, Object> serialized = objectMapper.readValue(row.getProvenanceJson(),
                        new TypeReference<>() { });
                Map<String, Object> metadata = map(serialized.get("metadata"));
                Integer httpStatus = integer(serialized.get("httpStatus"));
                String method = text(serialized.get("retrievalMethod"));
                Instant observed = row.getLastObservedAt() == null ? Instant.now()
                        : row.getLastObservedAt().toInstant(ZoneOffset.UTC);
                NewsSourceProvenance provenance = new NewsSourceProvenance(
                        row.getProviderId(), row.getSourceTier(), row.getSourceUrl(),
                        row.getCanonicalUrl(), observed, httpStatus, method, metadata);
                out.add(new NewsSourceResult(row.getTitle(), row.getSnippet(),
                        row.getContent(), provenance));
            } catch (Exception e) {
                log.warn("Skipping unreadable AI-news source item projection: {}", e.getMessage());
            }
        }
        return List.copyOf(out);
    }

    private void persistRawCapture(AiNewsIngestionRunEntity run,
                                   AiNewsSourceEndpointEntity endpoint,
                                   NewsSourceTransportRecord transport,
                                   String requestUrlHash,
                                   int attemptNo) {
        AiNewsRawCaptureEntity previous = null;
        if (transport.notModified()) {
            previous = rawCaptureMapper.selectOne(
                    new LambdaQueryWrapper<AiNewsRawCaptureEntity>()
                            .eq(AiNewsRawCaptureEntity::getEndpointId, endpoint.getId())
                            .eq(AiNewsRawCaptureEntity::getRequestUrlHash, requestUrlHash)
                            .ne(AiNewsRawCaptureEntity::getIngestionRunId, run.getId())
                            .eq(AiNewsRawCaptureEntity::getNotModified, false)
                            .isNotNull(AiNewsRawCaptureEntity::getRepresentationDigest)
                            .orderByDesc(AiNewsRawCaptureEntity::getFinishedAt)
                            .last("LIMIT 1"));
        }
        byte[] body = transport.body();
        String policy = endpoint.getRawRetention() == null
                ? "metadata_only" : endpoint.getRawRetention().toLowerCase(java.util.Locale.ROOT);
        String digest = "none".equals(policy) || body.length == 0 ? null
                : NewsSourceHashing.sha256(body);
        if (digest == null && previous != null) digest = previous.getRepresentationDigest();
        byte[] retained = "full".equals(policy) && body.length > 0 ? body : null;
        String applied = switch (policy) {
            case "none" -> "none";
            case "full" -> retained == null ? "metadata_only" : "inline_full";
            case "digest_only" -> "digest_only";
            default -> "metadata_only";
        };

        AiNewsRawCaptureEntity row = new AiNewsRawCaptureEntity();
        row.setIngestionRunId(run.getId());
        row.setEndpointId(endpoint.getId());
        row.setRequestUrl(trim(transport.requestUrl().toString(), 4096));
        row.setRequestUrlHash(requestUrlHash);
        row.setAttemptNo(attemptNo);
        row.setFinalUrl(trim(transport.finalUrl().toString(), 4096));
        row.setHttpStatus(transport.httpStatus());
        row.setContentType(trimToNull(transport.contentType(), 256));
        row.setEtag(trimToNull(transport.etag(), 1024));
        row.setLastModified(trimToNull(transport.lastModified(), 1024));
        row.setRetryAfter(trimToNull(transport.retryAfter(), 512));
        row.setDeclaredContentLength(transport.declaredContentLength());
        row.setReceivedBytes(transport.receivedBytes());
        row.setRepresentationDigest(digest);
        row.setRetentionApplied(applied);
        row.setRawBody(retained);
        row.setTruncated(transport.truncated());
        row.setNotModified(transport.notModified());
        row.setStartedAt(utc(transport.startedAt()));
        row.setFinishedAt(utc(transport.finishedAt()));
        row.setDurationMs(transport.durationMs());
        row.setErrorCode(trimToNull(transport.errorCode(), 64));
        row.setErrorMessage(trimToNull(transport.errorMessage(), 2000));
        row.setRevalidatedFromCaptureId(previous == null ? null : previous.getId());
        row.setCreateTime(LocalDateTime.now(ZoneOffset.UTC));
        row.setDeleted(0);
        rawCaptureMapper.insert(row);
    }

    private ItemOutcome persistItem(AiNewsIngestionRunEntity run,
                                    AiNewsSourceEndpointEntity endpoint,
                                    NewsSourceResult result,
                                    Instant observedAt) {
        if (result == null || result.provenance() == null) return null;
        String sourceUrl = firstNonBlank(result.sourceUrl(), result.canonicalUrl());
        if (sourceUrl.isBlank()) return null;
        Map<String, Object> metadata = result.provenance().metadata();
        String externalId = firstNonBlank(value(metadata.get("feedEntryId")),
                value(metadata.get("officialApiItemId")), value(metadata.get("githubReleaseId")),
                value(metadata.get("arxivId")));
        String canonical = firstNonBlank(result.canonicalUrl(), sourceUrl);
        String identity = !externalId.isBlank() ? "external\u001f" + externalId
                : "url\u001f" + canonical;
        String identityHash = NewsSourceHashing.sha256(identity);
        LocalDateTime observed = utc(observedAt == null ? Instant.now() : observedAt);

        AiNewsSourceItemEntity item = itemMapper.selectOne(
                new LambdaQueryWrapper<AiNewsSourceItemEntity>()
                        .eq(AiNewsSourceItemEntity::getEndpointId, endpoint.getId())
                        .eq(AiNewsSourceItemEntity::getIdentityHash, identityHash)
                        .eq(AiNewsSourceItemEntity::getDeleted, 0));
        boolean newItem = item == null;
        if (newItem) {
            item = new AiNewsSourceItemEntity();
            item.setEndpointId(endpoint.getId());
            item.setIdentityHash(identityHash);
            item.setFirstObservedAt(observed);
            item.setCreateTime(observed);
            item.setDeleted(0);
        }
        item.setExternalItemId(trimToNull(externalId, 1024));
        item.setCanonicalUrl(trimToNull(canonical, 4096));
        item.setCanonicalUrlHash(canonical.isBlank() ? null
                : NewsSourceHashing.sha256(canonical));
        item.setSourceUrl(trim(sourceUrl, 4096));
        item.setSourceTier(trim(firstNonBlank(result.provenance().sourceTier(), "community"), 16));
        item.setLastObservedAt(observed);
        item.setUpdateTime(observed);
        if (newItem) itemMapper.insert(item);
        else itemMapper.updateById(item);

        String retention = endpoint.getRawRetention() == null
                ? "metadata_only" : endpoint.getRawRetention().toLowerCase(java.util.Locale.ROOT);
        String retainedSnippet = trimToNull(result.snippet(), "full".equals(retention) ? 4096 : 512);
        String retainedContent = "full".equals(retention)
                ? trimToNull(result.content(), 2 * 1024 * 1024) : null;
        String versionHash = versionHash(result, externalId, retainedSnippet, retainedContent);
        AiNewsSourceItemVersionEntity version = versionMapper.selectOne(
                new LambdaQueryWrapper<AiNewsSourceItemVersionEntity>()
                        .eq(AiNewsSourceItemVersionEntity::getSourceItemId, item.getId())
                        .eq(AiNewsSourceItemVersionEntity::getVersionHash, versionHash)
                        .eq(AiNewsSourceItemVersionEntity::getDeleted, 0));
        boolean newVersion = version == null;
        if (newVersion) {
            version = new AiNewsSourceItemVersionEntity();
            version.setSourceItemId(item.getId());
            version.setIngestionRunId(run.getId());
            version.setVersionHash(versionHash);
            version.setTitle(trimToNull(result.title(), 512));
            version.setSnippet(retainedSnippet);
            // Endpoint rights govern persisted source text as well as the raw
            // transport body. Metadata-only/digest-only feeds must not leave a
            // second full article copy in the item-version table.
            version.setContent(retainedContent);
            version.setSourcePublishedAt(timestamp(metadata.get("publishedAt")));
            version.setPublishedAtRaw(trimToNull(value(metadata.get("publishedAtRaw")), 512));
            version.setSourceModifiedAt(timestamp(metadata.get("updatedAt")));
            version.setModifiedAtRaw(trimToNull(firstNonBlank(
                    value(metadata.get("updatedAtRaw")),
                    value(metadata.get("sourceModifiedAtRaw"))), 512));
            version.setLanguage(trimToNull(value(metadata.get("language")), 32));
            version.setProvenanceJson(provenanceJson(result.provenance()));
            version.setObservedAt(observed);
            version.setCreateTime(observed);
            version.setDeleted(0);
            versionMapper.insert(version);
        }
        item.setLatestVersionId(version.getId());
        item.setUpdateTime(observed);
        itemMapper.updateById(item);
        return new ItemOutcome(item.getId(), version.getId(), newItem, newVersion);
    }

    private static String itemIdentityHash(NewsSourceResult result) {
        if (result == null || result.provenance() == null) return null;
        String sourceUrl = firstNonBlank(result.sourceUrl(), result.canonicalUrl());
        if (sourceUrl.isBlank()) return null;
        Map<String, Object> metadata = result.provenance().metadata();
        String externalId = firstNonBlank(value(metadata.get("feedEntryId")),
                value(metadata.get("officialApiItemId")), value(metadata.get("githubReleaseId")),
                value(metadata.get("arxivId")));
        String canonical = firstNonBlank(result.canonicalUrl(), sourceUrl);
        return NewsSourceHashing.sha256(!externalId.isBlank()
                ? "external\u001f" + externalId : "url\u001f" + canonical);
    }

    private void persistRunItem(AiNewsIngestionRunEntity run, ItemOutcome outcome,
                                Instant observedAt) {
        AiNewsIngestionRunItemEntity edge = new AiNewsIngestionRunItemEntity();
        edge.setIngestionRunId(run.getId());
        edge.setSourceItemId(outcome.itemId());
        edge.setSourceItemVersionId(outcome.versionId());
        edge.setObservationOutcome(outcome.newItem() ? "new_item"
                : outcome.newVersion() ? "new_version" : "unchanged");
        edge.setObservedAt(utc(observedAt));
        edge.setCreateTime(LocalDateTime.now(ZoneOffset.UTC));
        edge.setDeleted(0);
        runItemMapper.insert(edge);
    }

    private void updateEndpointAfterRun(AiNewsIngestionRunEntity run,
                                        AiNewsSourceEndpointEntity endpoint,
                                        NewsSourcePollBatch batch,
                                        NewsSourceTransportRecord root,
                                        LocalDateTime finished) {
        boolean completeSuccess = batch.status() == NewsSourcePollBatch.Status.SUCCESS
                || batch.status() == NewsSourcePollBatch.Status.NOT_MODIFIED;
        if (root != null) {
            endpoint.setLastHttpStatus(root.httpStatus());
            if (root.succeeded()) {
                if (!root.etag().isBlank()) endpoint.setEtag(trim(root.etag(), 1024));
                if (!root.lastModified().isBlank()) {
                    endpoint.setLastModified(trim(root.lastModified(), 1024));
                }
            }
        }
        if (completeSuccess) {
            endpoint.setLastSuccessAt(finished);
            endpoint.setConsecutiveFailures(0);
            endpoint.setLastError(null);
            endpoint.setNextPollAt(finished.plusSeconds(Math.max(60,
                    value(endpoint.getPollIntervalSeconds()))));
        } else {
            int failures = value(endpoint.getConsecutiveFailures()) + 1;
            endpoint.setConsecutiveFailures(failures);
            endpoint.setLastError(trimToNull(firstNonBlank(batch.errorMessage(),
                    batch.status().name()), 2000));
            endpoint.setNextPollAt(finished.plusSeconds(failureDelay(endpoint, failures)));
        }
        endpoint.setUpdateTime(LocalDateTime.now(ZoneOffset.UTC));
        // Keep the endpoint cursor fenced by the same owner marker used for
        // run completion.  If a newer worker acquired the endpoint after the
        // lease expired, this update intentionally becomes a no-op.
        int updated = endpointMapper.update(null,
                new LambdaUpdateWrapper<AiNewsSourceEndpointEntity>()
                        .eq(AiNewsSourceEndpointEntity::getId, endpoint.getId())
                        .eq(AiNewsSourceEndpointEntity::getDeleted, 0)
                        .eq(AiNewsSourceEndpointEntity::getEnabled, true)
                        .eq(AiNewsSourceEndpointEntity::getLastAttemptAt, run.getStartedAt())
                        .set(AiNewsSourceEndpointEntity::getLastHttpStatus,
                                endpoint.getLastHttpStatus())
                        .set(AiNewsSourceEndpointEntity::getEtag, endpoint.getEtag())
                        .set(AiNewsSourceEndpointEntity::getLastModified,
                                endpoint.getLastModified())
                        .set(AiNewsSourceEndpointEntity::getLastSuccessAt,
                                endpoint.getLastSuccessAt())
                        .set(AiNewsSourceEndpointEntity::getConsecutiveFailures,
                                endpoint.getConsecutiveFailures())
                        .set(AiNewsSourceEndpointEntity::getLastError, endpoint.getLastError())
                        .set(AiNewsSourceEndpointEntity::getNextPollAt, endpoint.getNextPollAt())
                        .set(AiNewsSourceEndpointEntity::getUpdateTime, endpoint.getUpdateTime()));
        if (updated != 1) {
            log.debug("Skipping endpoint completion for stale ingestion owner: endpointId={}, runId={}",
                    endpoint.getId(), run.getId());
        }
    }

    private long failureDelay(AiNewsSourceEndpointEntity endpoint, int failures) {
        long configured = Math.max(60, value(endpoint.getPollIntervalSeconds()));
        long exponential = 60L * (1L << Math.min(10, Math.max(0, failures - 1)));
        return Math.min(configured, exponential);
    }

    private String versionHash(NewsSourceResult result, String externalId,
                               String retainedSnippet, String retainedContent) {
        Map<String, Object> semantic = new TreeMap<>();
        semantic.put("canonicalUrl", firstNonBlank(result.canonicalUrl(), result.sourceUrl()));
        semantic.put("content", retainedContent);
        semantic.put("externalItemId", externalId);
        semantic.put("metadata", semanticMetadata(result.provenance().metadata()));
        semantic.put("snippet", retainedSnippet);
        semantic.put("title", result.title());
        return NewsSourceHashing.sha256(json(semantic));
    }

    private Map<String, Object> semanticMetadata(Map<String, Object> metadata) {
        Map<String, Object> sorted = new TreeMap<>();
        if (metadata == null) return sorted;
        metadata.forEach((key, value) -> {
            if (key != null && !TRANSIENT_VERSION_METADATA.contains(key)) {
                sorted.put(key, canonical(value));
            }
        });
        return sorted;
    }

    private Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonical(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(this::canonical).toList();
        return value;
    }

    private String provenanceJson(NewsSourceProvenance provenance) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("providerId", provenance.providerId());
        value.put("sourceTier", provenance.sourceTier());
        value.put("sourceUrl", provenance.sourceUrl());
        value.put("canonicalUrl", provenance.canonicalUrl());
        value.put("fetchedAt", provenance.fetchedAt().toString());
        value.put("httpStatus", provenance.httpStatus());
        value.put("retrievalMethod", provenance.retrievalMethod());
        value.put("metadata", provenance.metadata());
        return json(value);
    }

    private void requireRun(AiNewsIngestionRunEntity run,
                            AiNewsSourceEndpointEntity endpoint,
                            NewsSourcePollBatch batch) {
        if (run == null || run.getId() == null || endpoint == null || endpoint.getId() == null
                || batch == null || !endpoint.getId().equals(run.getEndpointId())
                || !endpoint.getEndpointKey().equals(batch.endpoint().endpointKey())) {
            throw new IllegalArgumentException("run, endpoint and batch do not describe the same poll");
        }
        if (!"started".equals(run.getRunStatus())) {
            throw new IllegalStateException("ingestion run is already terminal: " + run.getRunStatus());
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize ingestion ledger value", e);
        }
    }

    private static String runStatus(NewsSourcePollBatch.Status status) {
        return switch (status) {
            case SUCCESS -> "success";
            case NOT_MODIFIED -> "not_modified";
            case DEGRADED -> "degraded";
            case FAILED -> "failed";
        };
    }

    private static String trigger(String value) {
        if (value == null || value.isBlank()) return "manual";
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.matches("[a-z0-9_]{1,32}") ? normalized : "other";
    }

    private static LocalDateTime timestamp(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return null;
        try {
            return utc(Instant.parse(String.valueOf(value)));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static LocalDateTime utc(Instant value) {
        return LocalDateTime.ofInstant(value == null ? Instant.now() : value, ZoneOffset.UTC);
    }

    private static LocalDateTime databaseNow() {
        return databaseTimestamp(LocalDateTime.now(ZoneOffset.UTC));
    }

    private static LocalDateTime databaseTimestamp(LocalDateTime value) {
        if (value == null) return null;
        return value.withNano((value.getNano() / 1_000_000) * 1_000_000);
    }

    private static long startLeaseSeconds(NewsSourceEndpointDescriptor descriptor) {
        // Keep an implicit/manual lease at least as long as the normal stale
        // run window.  A longer configured polling interval is also respected,
        // while the schema and claimDue contract cap leases at one day.
        long configured = descriptor == null ? 0L : descriptor.pollIntervalSeconds();
        return Math.min(86_400L, Math.max(DEFAULT_START_LEASE_SECONDS,
                Math.max(60L, configured)));
    }

    private static boolean sameDatabaseTimestamp(LocalDateTime left, LocalDateTime right) {
        if (left == null || right == null) return left == right;
        return Math.abs(Duration.between(left, right).toNanos()) <= 1_000_000L;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((key, item) -> out.put(String.valueOf(key), item));
        return Map.copyOf(out);
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? null : Integer.valueOf(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int value(Integer number) {
        return number == null ? 0 : number;
    }

    private static String value(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return "";
    }

    private static String trim(String value, int max) {
        if (value == null) return "";
        String text = value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String trimToNull(String value, int max) {
        String text = trim(value, max);
        return text.isBlank() ? null : text;
    }

    private record ItemOutcome(Long itemId, Long versionId,
                               boolean newItem, boolean newVersion) {
    }

    public record Completion(Long runId,
                             int itemCount,
                             int newItemCount,
                             int newVersionCount,
                             int unchangedItemCount,
                             int transportCount,
                             long bytesReceived,
                             String status) {
    }
}
