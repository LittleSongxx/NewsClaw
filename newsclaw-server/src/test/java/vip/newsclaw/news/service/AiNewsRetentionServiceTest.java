package vip.newsclaw.news.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiNewsRetentionServiceTest {

    @Test
    void purgesOnlyExpiredUnpromotedLineageAndUnreferencedCaptures() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:ai_news_retention;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE mate_ai_news_scan_run (id BIGINT PRIMARY KEY, discovery_run_id BIGINT, "
                + "run_status VARCHAR(32), finished_at TIMESTAMP, update_time TIMESTAMP, deleted INT)");
        jdbc.execute("CREATE TABLE mate_ai_news_candidate (id BIGINT PRIMARY KEY, scan_run_id BIGINT, "
                + "event_id BIGINT, capture_id BIGINT, deleted INT)");
        jdbc.execute("CREATE TABLE mate_ai_news_candidate_observation (id BIGINT PRIMARY KEY, "
                + "scan_run_id BIGINT, candidate_id BIGINT)");
        jdbc.execute("CREATE TABLE mate_ai_news_discovery_run (id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE mate_ai_news_source_capture (id BIGINT PRIMARY KEY, fetched_at TIMESTAMP, "
                + "create_time TIMESTAMP, deleted INT)");
        jdbc.execute("CREATE TABLE mate_ai_news_event_evidence (id BIGINT PRIMARY KEY, "
                + "source_capture_id BIGINT, deleted INT)");
        LocalDateTime old = LocalDateTime.now().minusDays(100);
        jdbc.update("INSERT INTO mate_ai_news_discovery_run VALUES (?)", 10L);
        jdbc.update("INSERT INTO mate_ai_news_discovery_run VALUES (?)", 20L);
        jdbc.update("INSERT INTO mate_ai_news_scan_run VALUES (?,?,?,?,?,0)",
                1L, 10L, "COMPLETED", old, old);
        jdbc.update("INSERT INTO mate_ai_news_scan_run VALUES (?,?,?,?,?,0)",
                2L, 20L, "COMPLETED", old, old);
        jdbc.update("INSERT INTO mate_ai_news_candidate VALUES (?,?,?,?,0)", 11L, 1L, null, null);
        jdbc.update("INSERT INTO mate_ai_news_candidate VALUES (?,?,?,?,0)", 21L, 2L, 900L, null);
        jdbc.update("INSERT INTO mate_ai_news_candidate_observation VALUES (?,?,?)", 101L, 1L, 11L);
        jdbc.update("INSERT INTO mate_ai_news_candidate_observation VALUES (?,?,?)", 201L, 2L, 21L);
        jdbc.update("INSERT INTO mate_ai_news_source_capture VALUES (?,?,?,0)", 31L, old, old);
        jdbc.update("INSERT INTO mate_ai_news_source_capture VALUES (?,?,?,0)", 32L, old, old);
        jdbc.update("INSERT INTO mate_ai_news_event_evidence VALUES (?,?,0)", 301L, 32L);
        AiNewsCandidatePipelineProperties properties = new AiNewsCandidatePipelineProperties();
        properties.setRetentionDays(90);
        properties.setCaptureRetentionDays(30);
        AiNewsRetentionService service = new AiNewsRetentionService(jdbc, properties);

        AiNewsRetentionService.RetentionSummary result = service.purgeExpired();

        assertEquals(1, result.scanRuns());
        assertEquals(1, result.candidates());
        assertEquals(1, result.observations());
        assertEquals(1, result.discoverySnapshots());
        assertEquals(1, result.sourceCaptures());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mate_ai_news_scan_run", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mate_ai_news_source_capture", Integer.class));
    }
}
