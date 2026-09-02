package vip.newsclaw.memory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.newsclaw.memory.MemoryProperties;
import vip.newsclaw.memory.identity.MemoryScope;
import vip.newsclaw.memory.model.MemoryRecallEntity;
import vip.newsclaw.memory.repository.MemoryRecallMapper;
import vip.newsclaw.workspace.document.model.WorkspaceFileEntity;
import vip.newsclaw.workspace.document.repository.WorkspaceFileMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemoryRecallOwnerIsolationTest {

    @Test
    void trackerCarriesPersonalOwnerAndScopesTheSourceQuery() {
        MemoryRecallService recallService = mock(MemoryRecallService.class);
        WorkspaceFileMapper fileMapper = mock(WorkspaceFileMapper.class);
        WorkspaceFileEntity personal = new WorkspaceFileEntity();
        personal.setAgentId(1L);
        personal.setFilename("MEMORY.md");
        personal.setContent("private fact");
        personal.setEnabled(true);
        personal.setScope(MemoryScope.PERSONAL);
        personal.setOwnerKey("user:alice");
        WorkspaceFileEntity foreign = new WorkspaceFileEntity();
        foreign.setAgentId(1L);
        foreign.setFilename("PROFILE.md");
        foreign.setContent("foreign private fact");
        foreign.setEnabled(true);
        foreign.setScope(MemoryScope.PERSONAL);
        foreign.setOwnerKey("user:bob");
        when(fileMapper.selectList(any())).thenReturn(List.of(personal, foreign));

        new MemoryRecallTracker(recallService, fileMapper)
                .trackRecalls(1L, "question", "user:alice");

        verify(recallService).recordRecall(eq(1L), eq("MEMORY.md"), eq("private fact"),
                anyString(), eq("user:alice"), eq(MemoryScope.PERSONAL));
        verify(recallService, never()).recordRecall(eq(1L), eq("PROFILE.md"), anyString(),
                any(), any(), any());
    }

    @Test
    void recallWriterPersistsTheExactOwnerBucket() {
        MemoryRecallMapper mapper = mock(MemoryRecallMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        MemoryRecallService service = new MemoryRecallService(
                mapper, new MemoryProperties(), new ObjectMapper());

        service.recordRecall(1L, "MEMORY.md", "private fact", "query-hash",
                "user:alice", MemoryScope.PERSONAL);

        ArgumentCaptor<MemoryRecallEntity> inserted = ArgumentCaptor.forClass(MemoryRecallEntity.class);
        verify(mapper).insert(inserted.capture());
        assertThat(inserted.getValue().getOwnerKey()).isEqualTo("user:alice");
        assertThat(inserted.getValue().getScope()).isEqualTo(MemoryScope.PERSONAL);
    }

    @Test
    void personalRecallWithoutOwnerFailsClosed() {
        MemoryRecallMapper mapper = mock(MemoryRecallMapper.class);
        MemoryRecallService service = new MemoryRecallService(
                mapper, new MemoryProperties(), new ObjectMapper());

        service.recordRecall(1L, "MEMORY.md", "private fact", "query-hash",
                null, MemoryScope.PERSONAL);

        verifyNoInteractions(mapper);
    }
}
