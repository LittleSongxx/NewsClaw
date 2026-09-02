package vip.newsclaw.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.core.api.output.RepairResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FlywayRepairConfigTest {

    @Test
    void repairIsDisabledByDefault() throws Exception {
        Flyway flyway = mock(Flyway.class);
        when(flyway.migrate()).thenReturn(mock(MigrateResult.class));

        new FlywayRepairConfig().flywayInitializer(flyway).afterPropertiesSet();

        verify(flyway).migrate();
        verify(flyway, never()).repair();
    }

    @Test
    void repairRunsOnlyWhenExplicitlyEnabled() throws Exception {
        Flyway flyway = mock(Flyway.class);
        when(flyway.migrate()).thenReturn(mock(MigrateResult.class));
        when(flyway.repair()).thenReturn(mock(RepairResult.class));
        FlywayRepairConfig config = new FlywayRepairConfig();
        ReflectionTestUtils.setField(config, "autoRepair", true);

        config.flywayInitializer(flyway).afterPropertiesSet();

        verify(flyway).repair();
        verify(flyway).migrate();
    }
}
