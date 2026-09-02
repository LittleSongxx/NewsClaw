package vip.newsclaw.content.service;

import org.junit.jupiter.api.Test;
import vip.newsclaw.content.model.ContentItemEntity;
import vip.newsclaw.content.repository.ContentItemMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContentItemDeliveryGateTest {

    @Test
    void platformPublicationNeedsOperatorAckHashAndExternalRef() {
        ContentItemMapper mapper = mock(ContentItemMapper.class);
        ContentItemEntity item = new ContentItemEntity();
        item.setId(9L);
        item.setWorkspaceId(7L);
        item.setStatus("packaged");
        when(mapper.selectOne(any())).thenReturn(item);
        ContentItemService service = new ContentItemService(mapper);

        assertFalse(service.markPublished(7L, 9L, "platform-ack"));
        assertTrue(service.acknowledge(7L, 9L, "a".repeat(64)));
        assertEquals("operator_acknowledged", item.getStatus());
        assertFalse(service.markPublished(7L, 9L, ""));
        assertTrue(service.markPublished(7L, 9L, "platform-ack"));
        assertEquals("published", item.getStatus());
        assertNotNull(item.getPlatformPublishedAt());
    }
}
