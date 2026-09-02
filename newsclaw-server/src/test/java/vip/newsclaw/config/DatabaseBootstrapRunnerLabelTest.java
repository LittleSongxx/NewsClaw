package vip.newsclaw.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the small, pure helpers on {@link DatabaseBootstrapRunner}.
 * Verifies canonical database labels and the bootstrap password boundary.
 */
class DatabaseBootstrapRunnerLabelTest {

    @Test
    @DisplayName("Bootstrap password stays within BCrypt's UTF-8 byte limit")
    void bootstrapPasswordHonorsBcryptLimit() {
        String base = "0123456789abcdef".repeat(4);
        assertTrue(DatabaseBootstrapRunner.isUsableBootstrapPassword(base));
        assertTrue(DatabaseBootstrapRunner.isUsableBootstrapPassword(base + "ABCDEFGH"));
        assertFalse(DatabaseBootstrapRunner.isUsableBootstrapPassword(base + "ABCDEFGHI"));
    }

    @Test
    @DisplayName("KingbaseES product name (with version noise) → '人大金仓'")
    void kingbaseNormalizes() {
        assertEquals("人大金仓", DatabaseBootstrapRunner.normalizeDatabaseLabel("KingbaseES"));
        assertEquals("人大金仓", DatabaseBootstrapRunner.normalizeDatabaseLabel("KingbaseES V008R006"));
        assertEquals("人大金仓", DatabaseBootstrapRunner.normalizeDatabaseLabel("kingbasees"));
    }

    @Test
    @DisplayName("MySQL / MariaDB → canonical labels")
    void mysqlFamilyNormalizes() {
        assertEquals("MySQL", DatabaseBootstrapRunner.normalizeDatabaseLabel("MySQL"));
        assertEquals("MariaDB", DatabaseBootstrapRunner.normalizeDatabaseLabel("MariaDB"));
    }

    @Test
    @DisplayName("PostgreSQL and H2 → canonical labels")
    void postgresAndH2Normalize() {
        assertEquals("PostgreSQL", DatabaseBootstrapRunner.normalizeDatabaseLabel("PostgreSQL"));
        assertEquals("H2", DatabaseBootstrapRunner.normalizeDatabaseLabel("H2"));
    }

    @Test
    @DisplayName("Unknown / blank product name → 'Unknown'; unrecognized name passes through trimmed")
    void fallbacks() {
        assertEquals("Unknown", DatabaseBootstrapRunner.normalizeDatabaseLabel(null));
        assertEquals("Unknown", DatabaseBootstrapRunner.normalizeDatabaseLabel("   "));
        assertEquals("Oracle", DatabaseBootstrapRunner.normalizeDatabaseLabel("  Oracle  "));
    }
}
