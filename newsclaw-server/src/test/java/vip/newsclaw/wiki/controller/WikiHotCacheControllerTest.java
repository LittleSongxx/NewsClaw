package vip.newsclaw.wiki.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.newsclaw.common.result.R;
import vip.newsclaw.wiki.hotcache.HotCacheUpdateReason;
import vip.newsclaw.wiki.hotcache.HotCacheUpdateScheduler;
import vip.newsclaw.wiki.hotcache.WikiHotCacheService;
import vip.newsclaw.wiki.model.WikiHotCacheEntity;
import vip.newsclaw.wiki.model.WikiKnowledgeBaseEntity;
import vip.newsclaw.wiki.service.WikiKnowledgeBaseService;
import vip.newsclaw.exception.NewsClawException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain controller tests — pure behavioral verification, no MockMvc.
 * Spring wiring is exercised by WikiHotCacheProviderE2ETest; here we
 * focus on the controller's logic and call shape.
 */
class WikiHotCacheControllerTest {

    private WikiHotCacheService service;
    private HotCacheUpdateScheduler scheduler;
    private WikiKnowledgeBaseService kbService;
    private WikiHotCacheController controller;

    @BeforeEach
    void setUp() {
        service = mock(WikiHotCacheService.class);
        scheduler = mock(HotCacheUpdateScheduler.class);
        kbService = mock(WikiKnowledgeBaseService.class);
        WikiKnowledgeBaseEntity kb = new WikiKnowledgeBaseEntity();
        kb.setId(7L);
        kb.setWorkspaceId(1L);
        when(kbService.getById(7L)).thenReturn(kb);
        controller = new WikiHotCacheController(service, scheduler, kbService);
    }

    @Test
    @DisplayName("GET returns the row when one exists")
    void get_present() {
        WikiHotCacheEntity row = new WikiHotCacheEntity();
        row.setKbId(7L);
        row.setContent("body");
        when(service.findByKb(7L)).thenReturn(Optional.of(row));

        R<WikiHotCacheEntity> resp = controller.get(7L, 1L);

        assertThat(resp.getData()).isNotNull();
        assertThat(resp.getData().getKbId()).isEqualTo(7L);
        assertThat(resp.getData().getContent()).isEqualTo("body");
    }

    @Test
    @DisplayName("GET returns ok with null data when no row")
    void get_missing() {
        when(service.findByKb(7L)).thenReturn(Optional.empty());

        R<WikiHotCacheEntity> resp = controller.get(7L, 1L);

        // ok envelope, null payload — operators distinguish "never built" vs "error"
        assertThat(resp.getData()).isNull();
    }

    @Test
    @DisplayName("regenerate schedules a MANUAL rebuild and returns ok")
    void regenerate_schedules() {
        controller.regenerate(7L, 1L);

        verify(scheduler).scheduleRebuild(7L, HotCacheUpdateReason.MANUAL);
    }

    @Test
    @DisplayName("regenerate response carries no payload (ack only)")
    void regenerate_responseShape() {
        R<Void> resp = controller.regenerate(7L, 1L);
        assertThat(resp.getData()).isNull();
    }

    @Test
    @DisplayName("reset soft-deletes the row when one exists")
    void reset_existing() {
        WikiHotCacheEntity row = new WikiHotCacheEntity();
        row.setId(99L);
        row.setKbId(7L);
        when(service.findByKb(7L)).thenReturn(Optional.of(row));

        controller.reset(7L, 1L);

        verify(service).softDelete(99L);
    }

    @Test
    @DisplayName("reset is a no-op when no row to delete")
    void reset_missing() {
        when(service.findByKb(7L)).thenReturn(Optional.empty());

        controller.reset(7L, 1L);

        verify(service, never()).softDelete(anyLong());
    }

    @Test
    @DisplayName("all hot-cache operations reject a KB from another workspace")
    void crossWorkspaceRejectedBeforeCacheAccess() {
        WikiKnowledgeBaseEntity foreign = new WikiKnowledgeBaseEntity();
        foreign.setId(8L);
        foreign.setWorkspaceId(2L);
        when(kbService.getById(8L)).thenReturn(foreign);

        assertThatThrownBy(() -> controller.get(8L, 1L)).isInstanceOf(NewsClawException.class);
        assertThatThrownBy(() -> controller.regenerate(8L, 1L)).isInstanceOf(NewsClawException.class);
        assertThatThrownBy(() -> controller.reset(8L, 1L)).isInstanceOf(NewsClawException.class);
        verify(service, never()).findByKb(8L);
        verify(scheduler, never()).scheduleRebuild(anyLong(), any());
    }
}
