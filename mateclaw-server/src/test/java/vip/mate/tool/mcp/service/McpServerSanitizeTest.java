package vip.mate.tool.mcp.service;

import org.junit.jupiter.api.Test;
import vip.mate.tool.mcp.service.McpServerService;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP Server 脱敏逻辑单元测试
 */
class McpServerSanitizeTest {

    @Test
    void maskValue_shortValue_fullyMasked() {
        assertEquals("***", McpServerService.maskValue("abc"));
        assertEquals("********", McpServerService.maskValue("12345678"));
    }

    @Test
    void maskValue_longValue_showsPrefixAndSuffix() {
        // sk-proj-1234567890abcdefghij1234 -> sk-****...1234
        String result = McpServerService.maskValue("sk-proj-1234567890abcdefghij1234");
        assertTrue(result.startsWith("sk-"));
        assertTrue(result.endsWith("1234"));
        assertTrue(result.contains("*"));
    }

    @Test
    void maskValue_noDash_showsTwoCharPrefix() {
        String result = McpServerService.maskValue("abc123456789xyz");
        assertTrue(result.startsWith("ab"));
        assertTrue(result.endsWith("9xyz"));
    }

    @Test
    void maskValue_null_returnsNull() {
        assertNull(McpServerService.maskValue(null));
    }

    @Test
    void maskValue_empty_returnsEmpty() {
        assertEquals("", McpServerService.maskValue(""));
    }

    @Test
    void maskJsonValues_validJson_masksAllValues() {
        String json = "{\"Authorization\":\"Bearer sk-12345678901234\",\"X-Custom\":\"secret-value\"}";
        String result = McpServerService.maskJsonValues(json);
        assertNotNull(result);
        assertFalse(result.contains("sk-12345678901234"));
        assertFalse(result.contains("secret-value"));
        assertTrue(result.contains("Authorization"));
        assertTrue(result.contains("X-Custom"));
    }

    @Test
    void maskJsonValues_nullOrBlank_returnsAsIs() {
        assertNull(McpServerService.maskJsonValues(null));
        assertEquals("", McpServerService.maskJsonValues(""));
        assertEquals("   ", McpServerService.maskJsonValues("   "));
    }

    @Test
    void maskJsonValues_invalidJson_returnsAsIs() {
        assertEquals("not json", McpServerService.maskJsonValues("not json"));
    }
}
