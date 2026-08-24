package vip.mate.news;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiNewsMigrationSmokeTest {

    @Test
    void emptyH2DatabaseMigratesThroughCaptureAuditAndReviewCardTool() throws Exception {
        String url = "jdbc:h2:mem:ai_news_migration_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";
        Flyway flyway = Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration/h2")
                .placeholderReplacement(false)
                .load();

        flyway.migrate();

        assertEquals("198", flyway.info().current().getVersion().getVersion());
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_name='mate_ai_news_capture_attempt'"));
            assertEquals(1L, scalar(statement,
                    "SELECT COUNT(*) FROM mate_tool WHERE id=1000000646 "
                            + "AND name='ai_news_review_card' AND enabled=TRUE"));
        }
    }

    private static long scalar(Statement statement, String sql) throws Exception {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }
}
