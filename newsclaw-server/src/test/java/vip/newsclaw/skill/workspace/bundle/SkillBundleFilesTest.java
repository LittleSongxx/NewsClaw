package vip.newsclaw.skill.workspace.bundle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillBundleFilesTest {

    @Test
    @DisplayName("only UTF-8 text assets are mirrored into the canonical skill-file store")
    void readDbEligibleSkipsBinaryAssets() throws Exception {
        SkillBundleSource source = new SkillBundleSource() {
            @Override
            public String origin() {
                return "test:binary-template";
            }

            @Override
            public List<BundleAsset> assets() {
                return List.of(
                        asset("scripts/run.py", "print('ok')".getBytes(StandardCharsets.UTF_8)),
                        asset("templates/readme.md", "# Text template".getBytes(StandardCharsets.UTF_8)),
                        asset("templates/paper.pdf", new byte[]{'%', 'P', 'D', 'F', 0, 1}),
                        asset("references/not-utf8.bin", new byte[]{(byte) 0xC3, 0x28}),
                        asset("assets/logo.png", new byte[]{0, 1, 2})
                );
            }
        };

        Map<String, String> stored = SkillBundleFiles.readDbEligible(source);

        assertEquals(Map.of(
                "scripts/run.py", "print('ok')",
                "templates/readme.md", "# Text template"
        ), stored);
        assertFalse(stored.containsKey("templates/paper.pdf"));
        assertFalse(stored.containsKey("references/not-utf8.bin"));
        assertFalse(stored.containsKey("assets/logo.png"));
    }

    @Test
    @DisplayName("strict decoder rejects NUL and malformed UTF-8 without changing valid text")
    void decodeUtf8TextIsStrict() {
        assertEquals("AI 动态", SkillBundleFiles.decodeUtf8Text("AI 动态".getBytes(StandardCharsets.UTF_8)).orElseThrow());
        assertTrue(SkillBundleFiles.decodeUtf8Text(new byte[0]).isPresent());
        assertTrue(SkillBundleFiles.decodeUtf8Text(new byte[]{'a', 0, 'b'}).isEmpty());
        assertTrue(SkillBundleFiles.decodeUtf8Text(new byte[]{(byte) 0xC3, 0x28}).isEmpty());
    }

    private static SkillBundleSource.BundleAsset asset(String path, byte[] bytes) {
        return new SkillBundleSource.BundleAsset(path, () -> new ByteArrayInputStream(bytes));
    }
}
