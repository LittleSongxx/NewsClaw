package vip.newsclaw.wiki.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import vip.newsclaw.agent.repository.AgentMapper;
import vip.newsclaw.wiki.model.WikiKnowledgeBaseEntity;
import vip.newsclaw.wiki.repository.*;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WikiKnowledgeBaseDeleteCompletenessTest {

    @Test
    void deletePurgesExtendedWikiTablesNotOnlyLegacyCoreRows() {
        WikiKnowledgeBaseMapper kb = mock(WikiKnowledgeBaseMapper.class);
        WikiRawMaterialMapper raw = mock(WikiRawMaterialMapper.class);
        WikiPageMapper page = mock(WikiPageMapper.class);
        WikiChunkMapper chunk = mock(WikiChunkMapper.class);
        WikiPageCitationMapper citation = mock(WikiPageCitationMapper.class);
        WikiProcessingJobMapper jobs = mock(WikiProcessingJobMapper.class);
        WikiKnowledgeBaseEntity entity = new WikiKnowledgeBaseEntity();
        entity.setId(9L);
        entity.setName("kb");
        when(kb.selectById(9L)).thenReturn(entity);
        when(raw.selectList(any())).thenReturn(List.of());
        when(page.selectList(any())).thenReturn(List.of());
        WikiKnowledgeBaseService service = new WikiKnowledgeBaseService(
                kb, raw, page, chunk, citation, jobs, mock(AgentMapper.class));
        WikiHotCacheMapper hotCache = mock(WikiHotCacheMapper.class);
        WikiTransformationRunMapper transformationRuns = mock(WikiTransformationRunMapper.class);
        WikiPipelineDefinitionMapper pipelineDefinitions = mock(WikiPipelineDefinitionMapper.class);
        WikiEntityMentionMapper mentions = mock(WikiEntityMentionMapper.class);
        ReflectionTestUtils.setField(service, "hotCacheMapper", hotCache);
        ReflectionTestUtils.setField(service, "transformationRunMapper", transformationRuns);
        ReflectionTestUtils.setField(service, "pipelineDefinitionMapper", pipelineDefinitions);
        ReflectionTestUtils.setField(service, "entityMentionMapper", mentions);

        service.delete(9L);

        verify(hotCache).delete(any());
        verify(transformationRuns).delete(any());
        verify(pipelineDefinitions).delete(any());
        verify(mentions).delete(any());
        verify(kb).deleteById(9L);
    }
}
