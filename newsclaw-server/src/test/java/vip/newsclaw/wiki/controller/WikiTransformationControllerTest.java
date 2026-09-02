package vip.newsclaw.wiki.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.newsclaw.common.result.R;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.wiki.model.WikiTransformationEntity;
import vip.newsclaw.wiki.model.WikiTransformationRunEntity;
import vip.newsclaw.wiki.model.WikiKnowledgeBaseEntity;
import vip.newsclaw.wiki.model.WikiRawMaterialEntity;
import vip.newsclaw.wiki.service.WikiKnowledgeBaseService;
import vip.newsclaw.wiki.service.WikiPageService;
import vip.newsclaw.wiki.service.WikiRawMaterialService;
import vip.newsclaw.wiki.service.WikiTransformationAggregator;
import vip.newsclaw.wiki.service.WikiTransformationExecutor;
import vip.newsclaw.wiki.service.WikiTransformationService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WikiTransformationControllerTest {

    private WikiTransformationService transformationService;
    private WikiTransformationExecutor executor;
    private WikiKnowledgeBaseService kbService;
    private WikiRawMaterialService rawService;
    private WikiTransformationController controller;

    @BeforeEach
    void setUp() {
        transformationService = mock(WikiTransformationService.class);
        executor = mock(WikiTransformationExecutor.class);
        kbService = mock(WikiKnowledgeBaseService.class);
        rawService = mock(WikiRawMaterialService.class);
        controller = new WikiTransformationController(
                transformationService,
                executor,
                mock(WikiTransformationAggregator.class),
                kbService,
                rawService,
                mock(WikiPageService.class));
    }

    @Test
    void applyMissingTemplateReturns404Envelope() {
        when(transformationService.getById(99L)).thenReturn(null);

        R<WikiTransformationRunEntity> response = controller.apply(
                99L, Map.of("rawId", 1L), false, 1L);

        assertEquals(404, response.getCode());
    }

    @Test
    void applyWithRawIdAndPageIdReturns400Envelope() {
        WikiTransformationEntity transformation = new WikiTransformationEntity();
        transformation.setId(99L);
        transformation.setWorkspaceId(1L);
        when(transformationService.getById(99L)).thenReturn(transformation);

        R<WikiTransformationRunEntity> response = controller.apply(
                99L, Map.of("rawId", 1L, "pageId", 2L), false, 1L);

        assertEquals(400, response.getCode());
    }

    @Test
    void updateGlobalTemplateThrows403() {
        WikiTransformationEntity global = new WikiTransformationEntity();
        global.setId(1000004001L);
        global.setWorkspaceId(null); // global starter pack
        when(transformationService.getById(1000004001L)).thenReturn(global);

        NewsClawException ex = assertThrows(NewsClawException.class, () ->
                controller.update(1000004001L, new WikiTransformationEntity(), 999L));

        assertEquals(403, ex.getCode());
        assertEquals("err.wiki.global_template_readonly", ex.getMsgKey());
        // The mutating service call must never be reached — no partial write.
        verify(transformationService, never()).update(anyLong(), any());
    }

    @Test
    void deleteGlobalTemplateThrows403() {
        WikiTransformationEntity global = new WikiTransformationEntity();
        global.setId(1000004001L);
        global.setWorkspaceId(null);
        when(transformationService.getById(1000004001L)).thenReturn(global);

        NewsClawException ex = assertThrows(NewsClawException.class, () ->
                controller.delete(1000004001L, 999L));

        assertEquals(403, ex.getCode());
        assertEquals("err.wiki.global_template_readonly", ex.getMsgKey());
        verify(transformationService, never()).delete(anyLong());
    }

    @Test
    void applyRejectsRawFromAnotherWorkspaceBeforeExecutorRuns() {
        WikiTransformationEntity transformation = new WikiTransformationEntity();
        transformation.setId(99L);
        transformation.setWorkspaceId(1L);
        when(transformationService.getById(99L)).thenReturn(transformation);
        WikiRawMaterialEntity raw = new WikiRawMaterialEntity();
        raw.setId(50L);
        raw.setKbId(20L);
        when(rawService.getById(50L)).thenReturn(raw);
        WikiKnowledgeBaseEntity foreignKb = new WikiKnowledgeBaseEntity();
        foreignKb.setId(20L);
        foreignKb.setWorkspaceId(2L);
        when(kbService.getById(20L)).thenReturn(foreignKb);

        assertThrows(NewsClawException.class,
                () -> controller.apply(99L, Map.of("rawId", 50L), false, 1L));
        verify(executor, never()).runOnRawAsync(any(), anyLong(), any());
    }
}
