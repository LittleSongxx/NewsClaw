package vip.newsclaw.wiki.service;

import org.junit.jupiter.api.Test;
import vip.newsclaw.agent.repository.AgentMapper;
import vip.newsclaw.wiki.model.WikiKnowledgeBaseEntity;
import vip.newsclaw.wiki.repository.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WikiEmbeddingModelSwitchTest {

    @Test
    void switchingModelInvalidatesBothChunkAndPageVectors() {
        WikiKnowledgeBaseMapper kbMapper = mock(WikiKnowledgeBaseMapper.class);
        WikiChunkMapper chunks = mock(WikiChunkMapper.class);
        WikiPageMapper pages = mock(WikiPageMapper.class);
        WikiKnowledgeBaseEntity kb = new WikiKnowledgeBaseEntity();
        kb.setId(7L);
        kb.setEmbeddingModelId(1L);
        when(kbMapper.selectById(7L)).thenReturn(kb);
        WikiKnowledgeBaseService service = new WikiKnowledgeBaseService(
                kbMapper, mock(WikiRawMaterialMapper.class), pages, chunks,
                mock(WikiPageCitationMapper.class), mock(WikiProcessingJobMapper.class),
                mock(AgentMapper.class));

        service.updateEmbeddingModelId(7L, 2L);

        verify(chunks).update(any(), any());
        verify(pages).update(any(), any());
    }
}
