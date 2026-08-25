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

class AiNewsMigrationSmokeTest {

    @Test
    void emptyH2DatabaseMigratesThroughCaptureAuditReviewCardRuntimeRebrandFeedbackAndSourceTool() throws Exception {
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
        }

        Flyway flyway = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration/h2")
                .placeholderReplacement(false)
                .load();
        flyway.migrate();

        assertEquals("201", flyway.info().current().getVersion().getVersion());
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_name='mate_ai_news_capture_attempt'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_name='mate_ai_news_feedback'"));
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
        }
    }

    private static long scalar(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }
}
