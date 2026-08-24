package vip.newsclaw.wiki.service;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import vip.newsclaw.wiki.WikiProperties;
import vip.newsclaw.wiki.model.WikiKnowledgeBaseEntity;

/**
 * Watches each KB's configured source directory and auto-ingests new files.
 *
 * <p>Implemented as a periodic, single-owner scan rather than per-node OS file
 * watchers: {@link SchedulerLock} (ShedLock) ensures exactly one instance runs
 * a cycle, so a multi-instance deployment never double-ingests, and a periodic
 * scan is inherently restart-safe (it picks up anything missed while down). The
 * underlying {@link WikiDirectoryScanService} dedups by source path, so a
 * re-scan only ingests genuinely new files; deletes are never propagated.
 * Path validation (symlink resolution + allowed roots) is enforced by the
 * shared validator inside the scan.
 *
 * @author NewsClaw Team
 */
@Slf4j
@Service
public class WikiSourceWatcherService {

    private final WikiKnowledgeBaseService kbService;
    private final WikiProperties properties;
    private final java.util.List<vip.newsclaw.wiki.source.WikiIngestSourceProvider> sourceProviders;

    public WikiSourceWatcherService(WikiKnowledgeBaseService kbService,
                                    WikiProperties properties,
                                    java.util.List<vip.newsclaw.wiki.source.WikiIngestSourceProvider> sourceProviders) {
        this.kbService = kbService;
        this.properties = properties;
        this.sourceProviders = sourceProviders;
    }

    /** The registered source-provider types (filesystem ships; api/mq pluggable later). */
    public java.util.List<String> availableSourceTypes() {
        return sourceProviders.stream().map(vip.newsclaw.wiki.source.WikiIngestSourceProvider::sourceType).toList();
    }

    /** Scheduled entry point — gated by config, serialized across instances. */
    @Scheduled(fixedDelayString = "${newsclaw.wiki.watcher-interval-ms:300000}", initialDelay = 60_000)
    @SchedulerLock(name = "wiki-source-watcher", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void scheduledScan() {
        if (!properties.isWatcherEnabled()) {
            return;
        }
        int added = runScanCycle();
        if (added > 0) {
            log.info("[WikiWatcher] scan cycle ingested {} new file(s)", added);
        }
    }

    /**
     * Scan every KB that has a source directory configured and return the total
     * number of new files ingested. Per-KB failures are logged and skipped so
     * one bad directory cannot stall the others.
     */
    public int runScanCycle() {
        int totalAdded = 0;
        for (WikiKnowledgeBaseEntity kb : kbService.listAll()) {
            // Per-KB opt-in: the global master switch (checked in scheduledScan)
            // gates the scheduler at all; this flag gates each KB. AND semantics —
            // a KB is auto-scanned only when both are on. Manual scans bypass this.
            if (kb.getWatcherEnabled() == null || kb.getWatcherEnabled() != 1) {
                continue;
            }
            vip.newsclaw.wiki.source.WikiIngestSourceProvider provider = providerFor(kb);
            if (provider == null) {
                continue;
            }
            try {
                WikiDirectoryScanService.ScanResult result = provider.sync(kb);
                totalAdded += result.added();
                if (!result.errors().isEmpty()) {
                    log.warn("[WikiWatcher] KB {} ({}) sync reported issues: {}",
                            kb.getId(), provider.sourceType(), result.errors());
                }
            } catch (Exception e) {
                log.warn("[WikiWatcher] sync failed for KB {} ({}): {}",
                        kb.getId(), provider.sourceType(), e.getMessage());
            }
        }
        return totalAdded;
    }

    /** The first registered provider that supports the KB, or null. */
    public vip.newsclaw.wiki.source.WikiIngestSourceProvider providerFor(WikiKnowledgeBaseEntity kb) {
        for (vip.newsclaw.wiki.source.WikiIngestSourceProvider p : sourceProviders) {
            if (p.supports(kb)) {
                return p;
            }
        }
        return null;
    }
}
