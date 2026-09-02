package vip.newsclaw.memory.fact;

import org.junit.jupiter.api.Test;
import vip.newsclaw.memory.MemoryProperties;
import vip.newsclaw.memory.fact.extraction.CompositeEntityExtractor;
import vip.newsclaw.memory.fact.projection.FactProjectionBuilder;
import vip.newsclaw.memory.fact.repository.FactMapper;
import vip.newsclaw.workspace.document.WorkspaceFileService;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FactProjectionForgetTest {

    @Test
    void rebuildOneDeletesProjectionWhenForgottenSectionExtractsNoFact() {
        FactMapper mapper = mock(FactMapper.class);
        CompositeEntityExtractor extractor = mock(CompositeEntityExtractor.class);
        when(extractor.extract(1L, "structured/user.md", "forgotten"))
                .thenReturn(List.of());
        MemoryProperties properties = new MemoryProperties();
        properties.getFact().setProjectionEnabled(true);
        FactProjectionBuilder builder = new FactProjectionBuilder(
                mapper, mock(WorkspaceFileService.class), extractor, properties);

        builder.rebuildOne(1L, "structured/user.md", "forgotten");

        verify(mapper).deleteStaleForSource(
                eq(1L), eq("structured/user.md"), eq(List.of()), any());
    }
}
