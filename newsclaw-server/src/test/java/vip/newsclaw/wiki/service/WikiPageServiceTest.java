package vip.newsclaw.wiki.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.newsclaw.wiki.model.WikiPageEntity;
import vip.newsclaw.wiki.repository.WikiPageMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WikiPageServiceTest {

    static {
        // LambdaQueryWrapper resolves column metadata from MyBatis-Plus's TableInfo
        // cache, which Spring normally populates at startup. In a plain unit test we
        // seed it once so getBySlug / listSummaries can build their lambda queries.
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                WikiPageEntity.class);
    }

    @Test
    void manualUpdateRefreshesUpdateTimeBeforePersisting() {
        WikiPageMapper mapper = mock(WikiPageMapper.class);
        WikiPageEntity page = new WikiPageEntity();
        page.setId(99L);
        page.setKbId(7L);
        page.setSlug("page");
        page.setContent("old");
        page.setSummary("old summary");
        page.setVersion(1);
        page.setLastUpdatedBy("ai");
        LocalDateTime oldUpdateTime = LocalDateTime.now().minusDays(1);
        page.setUpdateTime(oldUpdateTime);
        when(mapper.selectOne(any())).thenReturn(page);
        when(mapper.updateContentIfVersion(any(WikiPageEntity.class), anyInt())).thenReturn(1);

        ObjectMapper om = new ObjectMapper();
        WikiLinkService link = new WikiLinkService(om);
        new WikiPageService(mapper, om, link)
                .updatePageManually(7L, "page", "new body", null);

        assertTrue(page.getUpdateTime().isAfter(oldUpdateTime));
        verify(mapper).updateContentIfVersion(page, 1);
    }

    @Test
    void aiUpdateReloadsAndRetriesAfterVersionConflict() {
        WikiPageMapper mapper = mock(WikiPageMapper.class);
        WikiPageEntity first = page(99L, 7L, "page", 1, "[1]");
        WikiPageEntity concurrent = page(99L, 7L, "page", 2, "[1,2]");
        WikiPageEntity persisted = page(99L, 7L, "page", 3, "[1,2,3]");
        when(mapper.selectOne(any())).thenReturn(first, concurrent, persisted);
        when(mapper.selectList(any())).thenReturn(List.of());
        when(mapper.updateContentIfVersion(any(WikiPageEntity.class), anyInt())).thenReturn(0, 1);
        ObjectMapper om = new ObjectMapper();

        WikiPageEntity result = new WikiPageService(mapper, om, new WikiLinkService(om))
                .updatePageByAi(7L, "page", "merged", "summary", 3L);

        assertEquals(persisted, result);
        ArgumentCaptor<WikiPageEntity> writes = ArgumentCaptor.forClass(WikiPageEntity.class);
        verify(mapper, times(2)).updateContentIfVersion(writes.capture(), anyInt());
        assertTrue(writes.getAllValues().get(1).getSourceRawIds().contains("2"));
        assertTrue(writes.getAllValues().get(1).getSourceRawIds().contains("3"));
    }

    private static WikiPageEntity page(long id, long kbId, String slug, int version, String rawIds) {
        WikiPageEntity page = new WikiPageEntity();
        page.setId(id);
        page.setKbId(kbId);
        page.setSlug(slug);
        page.setVersion(version);
        page.setSourceRawIds(rawIds);
        page.setLastUpdatedBy("ai");
        return page;
    }

    @Test
    void canonicalTitleFoldsCaseAndSeparators() {
        // Case + ASCII separators fold away so spelling variants collapse to one key.
        assertEquals("erweibadusan", WikiPageService.canonicalTitle("Erwei-Badu_San"));
        assertEquals("erweibadusan", WikiPageService.canonicalTitle("er wei badu san"));
        // Chinese title with surrounding/full-width whitespace and an inserted hyphen.
        assertEquals("二味拔毒散", WikiPageService.canonicalTitle("  二味-拔毒散　"));
        assertEquals(WikiPageService.canonicalTitle("二味拔毒散"),
                WikiPageService.canonicalTitle("二味拔毒散 "));
        // Null / blank degrade to empty so callers can short-circuit.
        assertEquals("", WikiPageService.canonicalTitle(null));
        assertEquals("", WikiPageService.canonicalTitle("   "));
    }

    @Test
    void findByCanonicalTitleMatchesAcrossDifferentSlugs() {
        // Same concept already stored under an LLM-chosen slug; a later run arrives
        // with the same title but would have minted a different slug. Title match
        // must find the existing row regardless of the slug spelling.
        WikiPageMapper mapper = mock(WikiPageMapper.class);
        WikiPageEntity summary = new WikiPageEntity();
        summary.setKbId(7L);
        summary.setSlug("erwei-badu-san");
        summary.setTitle("二味拔毒散");
        WikiPageEntity full = new WikiPageEntity();
        full.setId(42L);
        full.setKbId(7L);
        full.setSlug("erwei-badu-san");
        full.setTitle("二味拔毒散");
        // listSummaries() -> selectList ; getBySlug() -> selectOne
        when(mapper.selectList(any())).thenReturn(List.of(summary));
        when(mapper.selectOne(any())).thenReturn(full);

        ObjectMapper om = new ObjectMapper();
        WikiPageService service = new WikiPageService(mapper, om, new WikiLinkService(om));

        WikiPageEntity hit = service.findByCanonicalTitle(7L, "二味拔毒散");
        assertNotNull(hit);
        assertEquals(42L, hit.getId());
        assertEquals("erwei-badu-san", hit.getSlug());
    }

    @Test
    void findByCanonicalTitleReturnsNullWhenNoConceptMatches() {
        WikiPageMapper mapper = mock(WikiPageMapper.class);
        WikiPageEntity summary = new WikiPageEntity();
        summary.setKbId(7L);
        summary.setSlug("shennong-bencao");
        summary.setTitle("神农本草经");
        when(mapper.selectList(any())).thenReturn(List.of(summary));

        ObjectMapper om = new ObjectMapper();
        WikiPageService service = new WikiPageService(mapper, om, new WikiLinkService(om));

        assertNull(service.findByCanonicalTitle(7L, "二味拔毒散"));
    }
}
