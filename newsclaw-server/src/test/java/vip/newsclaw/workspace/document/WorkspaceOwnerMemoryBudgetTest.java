package vip.newsclaw.workspace.document;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import vip.newsclaw.memory.MemoryProperties;
import vip.newsclaw.memory.identity.MemoryScope;
import vip.newsclaw.workspace.document.model.WorkspaceFileEntity;
import vip.newsclaw.workspace.document.repository.WorkspaceFileMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkspaceOwnerMemoryBudgetTest {

    @Test
    void ownerBlockIsBoundedAndKeepsOnlyNewestDailyFiles() {
        WorkspaceFileMapper mapper = mock(WorkspaceFileMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(
                file("MEMORY.md", "stable"),
                file("memory/2026-08-29.md", "old"),
                file("memory/2026-08-30.md", "newer"),
                file("memory/2026-08-31.md", "newest")));
        WorkspaceFileService service = new WorkspaceFileService(
                mapper, mock(ApplicationEventPublisher.class));
        MemoryProperties properties = new MemoryProperties();
        properties.setOwnerDailyMaxFiles(2);
        properties.setOwnerBlockMaxChars(120);
        ReflectionTestUtils.setField(service, "memoryProperties", properties);

        String block = service.buildOwnerMemoryBlock(1L, "user:alice");

        assertThat(block).hasSizeLessThanOrEqualTo(120)
                .contains("MEMORY.md", "2026-08-31", "2026-08-30")
                .doesNotContain("2026-08-29");
    }

    private static WorkspaceFileEntity file(String name, String content) {
        WorkspaceFileEntity file = new WorkspaceFileEntity();
        file.setFilename(name);
        file.setContent(content);
        file.setEnabled(true);
        file.setScope(MemoryScope.PERSONAL);
        file.setOwnerKey("user:alice");
        return file;
    }
}
