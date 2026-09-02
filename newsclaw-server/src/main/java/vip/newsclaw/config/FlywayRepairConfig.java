package vip.newsclaw.config;

import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Optional Flyway repair configuration.
 * <p>
 * Replaces the default {@link FlywayMigrationInitializer} with one that can
 * call {@code flyway.repair()} before {@code flyway.migrate()} when explicitly
 * enabled. Repair is deliberately opt-in: it can rewrite checksums and remove
 * failed history rows, which would otherwise hide a changed or partially
 * applied migration in production. A failed validation should be investigated
 * (and, if appropriate, repaired explicitly) rather than silently normalised
 * during every application start.
 *
 * @author NewsClaw Team
 */
@Slf4j
@Configuration
public class FlywayRepairConfig {

    /**
     * Emergency/desktop compatibility switch. Keep this false in production;
     * operators can set {@code newsclaw.flyway.auto-repair=true} for a reviewed
     * one-off recovery and then turn it back off.
     */
    @Value("${newsclaw.flyway.auto-repair:false}")
    private boolean autoRepair;

    @Bean
    public FlywayMigrationInitializer flywayInitializer(Flyway flyway) {
        return new FlywayMigrationInitializer(flyway, f -> {
            if (autoRepair) {
                log.warn("[Flyway] Running explicitly enabled repair before migrate; "
                        + "verify migration history after startup");
                f.repair();
            }
            f.migrate();
        });
    }
}
