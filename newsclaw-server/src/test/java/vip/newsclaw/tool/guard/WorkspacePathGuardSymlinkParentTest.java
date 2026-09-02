package vip.newsclaw.tool.guard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import vip.newsclaw.tool.builtin.ToolExecutionContext;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

@DisabledOnOs(OS.WINDOWS)
class WorkspacePathGuardSymlinkParentTest {

    @TempDir Path temp;

    @AfterEach
    void clear() {
        ToolExecutionContext.clear();
    }

    @Test
    void newFileCannotEscapeThroughSymlinkedParent() throws Exception {
        Path root = Files.createDirectory(temp.resolve("workspace"));
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Files.createSymbolicLink(root.resolve("link"), outside);
        ToolExecutionContext.set("conv", "alice", root.toString());

        assertThrows(IllegalArgumentException.class,
                () -> WorkspacePathGuard.validatePath("link/new-file.txt"));
    }
}
