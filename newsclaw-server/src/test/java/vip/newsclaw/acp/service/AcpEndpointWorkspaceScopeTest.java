package vip.newsclaw.acp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import vip.newsclaw.acp.model.AcpEndpointEntity;
import vip.newsclaw.acp.repository.AcpEndpointMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AcpEndpointWorkspaceScopeTest {

    private AcpEndpointMapper mapper;
    private AcpEndpointService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AcpEndpointMapper.class);
        service = new AcpEndpointService(
                mapper, new ObjectMapper(), mock(ApplicationEventPublisher.class));
    }

    @Test
    void createOverwritesBodyWorkspaceWithAuthenticatedWorkspace() {
        AcpEndpointEntity input = new AcpEndpointEntity();
        input.setName("custom");
        input.setCommand("safe-command");
        input.setWorkspaceId(999L);

        service.create(input, 7L);

        assertThat(input.getWorkspaceId()).isEqualTo(7L);
    }

    @Test
    void scopedGetHidesAnotherWorkspaceEndpoint() {
        AcpEndpointEntity endpoint = new AcpEndpointEntity();
        endpoint.setId(42L);
        endpoint.setWorkspaceId(1L);
        when(mapper.selectById(42L)).thenReturn(endpoint);

        assertThatThrownBy(() -> service.get(42L, 2L))
                .hasMessageContaining("ACP endpoint not found");
    }
}
