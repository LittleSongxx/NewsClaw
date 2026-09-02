package vip.newsclaw.wiki.service;

import org.junit.jupiter.api.Test;
import vip.newsclaw.wiki.WikiProperties;
import vip.newsclaw.wiki.metrics.WikiMetrics;
import vip.newsclaw.wiki.model.WikiChunkEntity;
import vip.newsclaw.wiki.repository.WikiPageMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class HybridRetrieverEmbeddingIdentityTest {

    @Test
    void semanticSearchDropsVectorsFromOldModelOrInputVersion() {
        WikiEmbeddingService embeddings = mock(WikiEmbeddingService.class);
        when(embeddings.isAvailable()).thenReturn(true);
        when(embeddings.embedQueryWithIdentity(1L, "q")).thenReturn(
                new WikiEmbeddingService.QueryEmbedding(new float[]{1, 0}, "new-model", "v2"));
        WikiChunkService chunks = mock(WikiChunkService.class);
        WikiChunkEntity stale = chunk(1L, "old-model", "v1", new float[]{1, 0});
        WikiChunkEntity current = chunk(2L, "new-model", "v2", new float[]{0, 1});
        when(chunks.listByKbId(1L)).thenReturn(List.of(stale, current));
        HybridRetriever retriever = new HybridRetriever(
                mock(WikiPageService.class), chunks, embeddings, new WikiProperties(),
                mock(WikiPageMapper.class), mock(WikiMetrics.class),
                mock(WikiContentNormalizer.class));

        List<HybridRetriever.ChunkHit> hits = retriever.searchChunks(1L, "q", 10);

        assertThat(hits).extracting(HybridRetriever.ChunkHit::chunkId).containsExactly(2L);
    }

    private static WikiChunkEntity chunk(Long id, String model, String version, float[] vector) {
        WikiChunkEntity chunk = new WikiChunkEntity();
        chunk.setId(id);
        chunk.setRawId(id);
        chunk.setContent("text");
        chunk.setEmbedding(WikiEmbeddingService.floatsToBytes(vector));
        chunk.setEmbeddingModel(model);
        chunk.setEmbeddingTextVersion(version);
        return chunk;
    }
}
