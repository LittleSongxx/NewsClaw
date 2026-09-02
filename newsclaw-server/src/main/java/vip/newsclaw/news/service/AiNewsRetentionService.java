package vip.newsclaw.news.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Bounded physical retention for unpromoted scans and unreferenced capture bodies. */
@Service
public class AiNewsRetentionService {

    private final JdbcTemplate jdbc;
    private final AiNewsCandidatePipelineProperties properties;

    public AiNewsRetentionService(JdbcTemplate jdbc, AiNewsCandidatePipelineProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Transactional
    public RetentionSummary purgeExpired() {
        int batch = Math.min(Math.max(1, properties.getRetentionBatchSize()), 1000);
        int observations = 0;
        int candidates = 0;
        int scans = 0;
        int snapshots = 0;
        if (properties.getRetentionDays() > 0) {
            LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC)
                    .minusDays(Math.min(properties.getRetentionDays(), 3650));
            List<RunRef> rows = jdbc.query("""
                    SELECT r.id, r.discovery_run_id
                      FROM mate_ai_news_scan_run r
                     WHERE r.deleted = 0
                       AND r.run_status IN ('COMPLETED', 'FAILED', 'CANCELLED')
                       AND COALESCE(r.finished_at, r.update_time) < ?
                       AND NOT EXISTS (
                           SELECT 1 FROM mate_ai_news_candidate c
                            WHERE c.scan_run_id = r.id AND c.event_id IS NOT NULL AND c.deleted = 0)
                     ORDER BY COALESCE(r.finished_at, r.update_time), r.id
                     LIMIT ?
                    """, (rs, rowNum) -> new RunRef(rs.getLong(1), (Long) rs.getObject(2)), cutoff, batch);
            List<Long> runIds = rows.stream().map(RunRef::runId).toList();
            if (!runIds.isEmpty()) {
                String marks = placeholders(runIds.size());
                observations = jdbc.update("DELETE FROM mate_ai_news_candidate_observation WHERE scan_run_id IN ("
                        + marks + ")", runIds.toArray());
                candidates = jdbc.update("DELETE FROM mate_ai_news_candidate WHERE scan_run_id IN ("
                        + marks + ") AND event_id IS NULL", runIds.toArray());
                scans = jdbc.update("DELETE FROM mate_ai_news_scan_run WHERE id IN (" + marks + ")",
                        runIds.toArray());
                List<Long> discoveryIds = rows.stream().map(RunRef::discoveryRunId)
                        .filter(java.util.Objects::nonNull).distinct().toList();
                if (!discoveryIds.isEmpty()) {
                    String discoveryMarks = placeholders(discoveryIds.size());
                    snapshots = jdbc.update("DELETE FROM mate_ai_news_discovery_run WHERE id IN ("
                                    + discoveryMarks + ") AND NOT EXISTS (SELECT 1 FROM mate_ai_news_scan_run r "
                                    + "WHERE r.discovery_run_id = mate_ai_news_discovery_run.id AND r.deleted = 0)",
                            discoveryIds.toArray());
                }
            }
        }

        int captures = 0;
        if (properties.getCaptureRetentionDays() > 0) {
            LocalDateTime cutoff = LocalDateTime.now()
                    .minusDays(Math.min(properties.getCaptureRetentionDays(), 3650));
            List<Long> ids = jdbc.queryForList("""
                    SELECT s.id FROM mate_ai_news_source_capture s
                     WHERE s.deleted = 0
                       AND COALESCE(s.fetched_at, s.create_time) < ?
                       AND NOT EXISTS (SELECT 1 FROM mate_ai_news_candidate c
                                        WHERE c.capture_id = s.id AND c.deleted = 0)
                       AND NOT EXISTS (SELECT 1 FROM mate_ai_news_event_evidence e
                                        WHERE e.source_capture_id = s.id AND e.deleted = 0)
                     ORDER BY COALESCE(s.fetched_at, s.create_time), s.id
                     LIMIT ?
                    """, Long.class, cutoff, batch);
            if (!ids.isEmpty()) {
                captures = jdbc.update("DELETE FROM mate_ai_news_source_capture WHERE id IN ("
                        + placeholders(ids.size()) + ")", ids.toArray());
            }
        }
        return new RetentionSummary(scans, candidates, observations, snapshots, captures);
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private record RunRef(Long runId, Long discoveryRunId) {
    }

    public record RetentionSummary(int scanRuns, int candidates, int observations,
                                   int discoverySnapshots, int sourceCaptures) {
    }
}
