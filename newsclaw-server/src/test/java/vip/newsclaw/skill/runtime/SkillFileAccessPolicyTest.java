package vip.newsclaw.skill.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SkillFileAccessPolicyTest {

    @TempDir
    Path tempDir;

    private final SkillFileAccessPolicy policy = new SkillFileAccessPolicy();
    private final Path skillDir = Path.of("/workspace/skills/architecture-diagram");

    @Test
    @DisplayName("allows architecture skill templates")
    void allowsTemplatesDirectory() throws IOException {
        Path actualSkillDir = Files.createDirectories(tempDir.resolve("architecture-diagram"));
        Files.createDirectories(actualSkillDir.resolve("templates"));
        Path resolved = policy.validateAndResolve(actualSkillDir, "templates/template.html");

        assertEquals(actualSkillDir.resolve("templates/template.html"), resolved);
    }

    @Test
    @DisplayName("still rejects unsupported top-level paths")
    void rejectsUnsupportedTopLevelPaths() {
        assertNull(policy.validateAndResolve(skillDir, "assets/logo.svg"));
    }

    @Test
    @DisplayName("rejects traversal from allowed directories")
    void rejectsTraversal() {
        assertNull(policy.validateAndResolve(skillDir, "templates/../SKILL.md"));
    }

    @Test
    @DisplayName("rejects a symlink from an allowed directory to outside the skill")
    void rejectsSymlinkEscape() throws IOException {
        Path skill = Files.createDirectories(tempDir.resolve("skill"));
        Path references = Files.createDirectories(skill.resolve("references"));
        Path outside = Files.writeString(tempDir.resolve("secret.txt"), "secret");
        try {
            Files.createSymbolicLink(references.resolve("secret.txt"), outside);
        } catch (UnsupportedOperationException | SecurityException e) {
            return; // Symlinks are unavailable on some Windows CI workers.
        }

        assertNull(policy.validateAndResolve(skill, "references/secret.txt"));
    }
}
