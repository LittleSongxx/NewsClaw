package vip.mate.skill.runtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SkillFrontmatterParser 单元测试
 * 验证依赖声明解析
 */
class SkillFrontmatterParserTest {

    private SkillFrontmatterParser parser;

    @BeforeEach
    void setUp() {
        parser = new SkillFrontmatterParser();
    }

    @Test
    @DisplayName("解析完整依赖声明")
    void shouldParseFullDependencies() {
        String content = """
            ---
            name: test_skill
            description: Test skill
            dependencies:
              commands: ["python3", "tesseract"]
              env: ["OPENAI_API_KEY", "SERPER_API_KEY"]
              tools: ["skillScriptTool"]
            platforms: ["macos", "linux"]
            ---
            # Test Skill Body
            """;

        SkillFrontmatterParser.ParsedSkillMd parsed = parser.parse(content);

        assertEquals("test_skill", parsed.getName());
        assertEquals("Test skill", parsed.getDescription());
        assertNotNull(parsed.getDependencies());

        SkillFrontmatterParser.SkillDependencies deps = parsed.getDependencies();
        assertEquals(List.of("python3", "tesseract"), deps.getCommands());
        assertEquals(List.of("OPENAI_API_KEY", "SERPER_API_KEY"), deps.getEnv());
        assertEquals(List.of("skillScriptTool"), deps.getTools());

        assertEquals(List.of("macos", "linux"), parsed.getPlatforms());
    }

    @Test
    @DisplayName("无依赖声明 → 空依赖")
    void shouldReturnEmptyDepsWhenNotDeclared() {
        String content = """
            ---
            name: simple_skill
            description: No deps
            ---
            # Body
            """;

        SkillFrontmatterParser.ParsedSkillMd parsed = parser.parse(content);

        assertNotNull(parsed.getDependencies());
        assertTrue(parsed.getDependencies().isEmpty());
        assertTrue(parsed.getPlatforms().isEmpty());
    }

    @Test
    @DisplayName("部分依赖声明")
    void shouldParsePartialDependencies() {
        String content = """
            ---
            name: partial_skill
            description: Partial deps
            dependencies:
              commands: ["bash"]
            ---
            # Body
            """;

        SkillFrontmatterParser.ParsedSkillMd parsed = parser.parse(content);

        assertEquals(List.of("bash"), parsed.getDependencies().getCommands());
        assertTrue(parsed.getDependencies().getEnv().isEmpty());
        assertTrue(parsed.getDependencies().getTools().isEmpty());
    }

    @Test
    @DisplayName("无 frontmatter → 空解析")
    void shouldHandleNoFrontmatter() {
        String content = "# Just a body\n\nNo frontmatter here.";

        SkillFrontmatterParser.ParsedSkillMd parsed = parser.parse(content);

        assertEquals("", parsed.getName());
        assertTrue(parsed.getDependencies().isEmpty());
    }

    @Test
    @DisplayName("空内容 → 安全返回")
    void shouldHandleEmptyContent() {
        SkillFrontmatterParser.ParsedSkillMd parsed = parser.parse("");
        assertNotNull(parsed);
        assertTrue(parsed.getDependencies().isEmpty());

        parsed = parser.parse(null);
        assertNotNull(parsed);
        assertTrue(parsed.getDependencies().isEmpty());
    }
}
