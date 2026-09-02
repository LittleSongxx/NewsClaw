package vip.newsclaw.memory.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import vip.newsclaw.memory.MemoryProperties;
import vip.newsclaw.memory.scheduler.DreamingScheduler;
import vip.newsclaw.memory.service.*;
import vip.newsclaw.workspace.document.WorkspaceFileService;

import static org.mockito.Mockito.*;

class MemoryControllerOwnerIsolationTest {

    @Test
    void manualSummarizeWritesToTheAuthenticatedOwner() {
        MemorySummarizationService summarization = mock(MemorySummarizationService.class);
        MemoryController controller = new MemoryController(
                mock(MemoryEmergenceService.class),
                summarization,
                mock(MemoryRecallService.class),
                new MemoryProperties(),
                mock(DreamingScheduler.class),
                mock(WorkspaceFileService.class),
                mock(StructuredMemoryConsolidationService.class));
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("alice");

        controller.triggerSummarize(1L, "conversation-1", auth);

        verify(summarization).analyzeAndUpdateMemory(
                1L, "conversation-1", "user:alice");
        verify(summarization, never()).analyzeAndUpdateMemory(1L, "conversation-1");
    }
}
