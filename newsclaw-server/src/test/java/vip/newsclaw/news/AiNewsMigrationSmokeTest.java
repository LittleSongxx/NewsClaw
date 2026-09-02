package vip.newsclaw.news;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.api.MigrationVersion;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiNewsMigrationSmokeTest {

    @Test
    void emptyH2DatabaseMigratesThroughCandidatePipeline() throws Exception {
        String url = "jdbc:h2:mem:ai_news_migration_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";
        Flyway beforeRuntimeRebrand = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration/h2")
                .placeholderReplacement(false)
                .target(MigrationVersion.fromVersion("198"))
                .load();
        beforeRuntimeRebrand.migrate();

        String legacyTitle = "Mate" + "Claw";
        String legacyLower = "mate" + "claw";
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO mate_agent "
                    + "(id, name, agent_type, system_prompt, max_iterations, enabled, workspace_id, "
                    + "create_time, update_time, deleted, creator_user_id) VALUES "
                    + "(900000000001, 'custom-agent', 'react', '" + legacyTitle + " custom prompt', "
                    + "10, TRUE, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0, 42)");
            statement.execute("INSERT INTO mate_ai_news_event "
                    + "(id, workspace_id, event_key, title, discovered_at, create_time, update_time, deleted) "
                    + "VALUES (900000000101, 1, 'legacy-source-time', 'legacy source event', "
                    + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)");
            statement.execute("INSERT INTO mate_ai_news_event_evidence "
                    + "(id, event_id, workspace_id, source_url, source_url_hash, source_published_at, "
                    + "source_tier, claim, "
                    + "verified, create_time, update_time, deleted) VALUES "
                    + "(900000000102, 900000000101, 1, 'https://example.com/legacy-source', "
                    + "'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', "
                    + "TIMESTAMP '2026-08-26 04:30:00', 'media', 'legacy claim', FALSE, "
                    + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)");
        }

        Flyway beforeExtractorProvenance = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration/h2")
                .placeholderReplacement(false)
                .target(MigrationVersion.fromVersion("208"))
                .load();
        beforeExtractorProvenance.migrate();
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO mate_ai_news_source_capture "
                    + "(id, workspace_id, source_url, source_url_hash, final_url, http_status, "
                    + "fetched_at, content_hash, capture_method, extracted_text, "
                    + "extracted_text_hash, capture_status, create_time, update_time, deleted) VALUES "
                    + "(900000000103, 1, 'https://example.com/legacy-capture', "
                    + "'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', "
                    + "'https://example.com/legacy-capture', 200, CURRENT_TIMESTAMP, "
                    + "'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc', "
                    + "'READ_ONLY_HTTP', 'legacy body', "
                    + "'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd', "
                    + "'success', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)");
        }

        Flyway flyway = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration/h2")
                .placeholderReplacement(false)
                .load();
        flyway.migrate();

        assertTrue(Integer.parseInt(flyway.info().current().getVersion().getVersion()) >= 219);
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_name='mate_ai_news_capture_attempt'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_name='mate_ai_news_scan_run' "
                            + "AND column_name='idempotency_key'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_name='mate_ai_news_scan_run' "
                            + "AND column_name='active_slot'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_name='mate_ai_news_feedback'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_name='mate_ai_news_review_task'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_name='mate_ai_news_source_capture'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_name='mate_ai_news_source_endpoint'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_name='mate_ai_news_ingestion_run'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_name='mate_ai_news_source_item'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_name='mate_ai_news_source_item_version'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_name='mate_ai_news_ingestion_run_item'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_name='mate_ai_news_raw_capture'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_name='mate_ai_news_event_cluster'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_name='mate_ai_news_event_cluster_version'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_name='mate_ai_news_event_cluster_member'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_name='mate_ai_news_event_cluster_lineage'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_name='mate_ai_news_event_cluster_review'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_name='mate_ai_news_discovery_run'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_name='mate_ai_news_scan_run'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_name='mate_ai_news_candidate'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_name='mate_ai_news_candidate_observation'"));
            assertEquals(5L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_name='mate_ai_news_candidate' "
                            + "AND column_name IN ('selection_status','capture_status',"
                            + "'normalization_status','review_status','failure_reason')"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM mate_tool WHERE id=1000000647 "
                            + "AND name='ai_news_pipeline' AND bean_name='aiNewsCandidateTool'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_name='mate_ai_news_discovery_run' "
                            + "AND column_name='snapshot_json'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_name='mate_ai_news_raw_capture' "
                            + "AND column_name='attempt_no'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_name='mate_ai_news_source_capture' "
                            + "AND column_name='extracted_text_hash'"));
            assertEquals(5L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_name='mate_ai_news_source_capture' "
                            + "AND column_name IN ('extractor_name','extractor_version',"
                            + "'extractor_config_hash','extraction_fallback','extraction_warning')"));
            assertEquals(4L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_name='mate_ai_news_source_capture' "
                            + "AND column_name IN ('source_time_origin',"
                            + "'source_time_attestation_status','source_time_item_version_id',"
                            + "'source_time_attestation_hash')"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM mate_ai_news_source_capture "
                            + "WHERE id=900000000103 "
                            + "AND extractor_name='jsoup_document_text' "
                            + "AND extractor_version='1' "
                            + "AND extraction_fallback=1 "
                            + "AND extraction_warning='legacy_capture_backfill'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM mate_ai_news_source_capture "
                            + "WHERE id=900000000103 "
                            + "AND source_time_origin='NONE' "
                            + "AND source_time_attestation_status='LEGACY_UNRESOLVED'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM mate_tool WHERE id=1000000646 "
                            + "AND name='ai_news_review_card' AND enabled=TRUE"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM mate_tool WHERE id=1000000645 "
                            + "AND description LIKE '%结构化来源发现%'"));
            assertEquals(0L, scalar(statement,
                    "SELECT COUNT(*) FROM mate_skill WHERE builtin=TRUE "
                            + "AND (author='" + legacyTitle + "' OR config_json LIKE '%" + legacyLower + "%')"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM mate_agent WHERE id=1000000640 "
                            + "AND system_prompt LIKE '%NewsClaw%'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM mate_agent WHERE id=900000000001 "
                            + "AND system_prompt='" + legacyTitle + " custom prompt'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_name='mate_ai_news_event_evidence' "
                            + "AND column_name='semantic_relation'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_name='mate_ai_news_event_evidence' "
                            + "AND column_name='relation_origin'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_name='mate_ai_news_event_evidence' "
                            + "AND column_name='relation_origin' "
                            + "AND character_maximum_length >= 24"));
            statement.execute("UPDATE mate_ai_news_event_evidence "
                    + "SET relation_origin='DETERMINISTIC_EXTRACTIVE' WHERE id=900000000102");
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM mate_ai_news_event_evidence "
                            + "WHERE id=900000000102 "
                            + "AND relation_origin='DETERMINISTIC_EXTRACTIVE'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_name='mate_ai_news_event_evidence' "
                            + "AND column_name='source_capture_id'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_name='mate_ai_news_event_evidence' "
                            + "AND column_name='evidence_identity_hash'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.indexes "
                            + "WHERE table_name='mate_ai_news_event_evidence' "
                            + "AND index_name='uk_ai_news_evidence_identity'"));
            assertEquals(0L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.indexes "
                            + "WHERE table_name='mate_ai_news_event_evidence' "
                            + "AND index_name='uk_ai_news_evidence_source'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_name='mate_ai_news_event' "
                            + "AND column_name='ranking_score'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_name='mate_ai_news_event' "
                            + "AND column_name='source_published_at'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_name='mate_ai_news_candidate' "
                            + "AND column_name='event_id'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_name='mate_ai_news_candidate' "
                            + "AND column_name='promoted_at'"));
            assertEquals(3L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.columns "
                            + "WHERE table_name='mate_ai_news_candidate' "
                            + "AND column_name IN ('reviewed_by','reviewed_at','review_origin')"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM mate_ai_news_event "
                            + "WHERE id=900000000101 "
                            + "AND source_published_at=TIMESTAMP '2026-08-26 04:30:00'"));
        }
    }

    private static long scalar(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }
}
