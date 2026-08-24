package vip.newsclaw.news.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Locks the layout regression that previously forced long model identifiers into the number column. */
class XhsContentCardTemplateTest {

    @Test
    @DisplayName("fact-card text flows beside an absolute counter instead of through CSS grid columns")
    void longIdentifierHasFullTextFlow() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
                "skills/xhs_note/references/xhs_card_content.html")) {
            assertNotNull(input);
            String html = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(html.contains("display: block; position: relative; min-width: 0"), html);
            assertTrue(html.contains("padding: 27px 28px 27px 116px"), html);
            assertTrue(html.contains("position: absolute; top: 27px; left: 28px"), html);
            assertTrue(html.contains("li strong { color: #075ca9; font-weight: 900; overflow-wrap: anywhere"), html);
            assertFalse(html.contains("grid-template-columns: 64px minmax(0, 1fr)"), html);
        }
    }
}
