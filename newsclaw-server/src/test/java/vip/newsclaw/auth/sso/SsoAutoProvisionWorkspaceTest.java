package vip.newsclaw.auth.sso;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import vip.newsclaw.auth.model.UserEntity;
import vip.newsclaw.auth.repository.UserMapper;
import vip.newsclaw.auth.service.AuthService;
import vip.newsclaw.auth.sso.provider.SsoProviderRegistry;
import vip.newsclaw.auth.sso.provider.SsoUserInfo;
import vip.newsclaw.auth.sso.repository.ExternalIdentityMapper;
import vip.newsclaw.workspace.core.service.WorkspaceService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SsoAutoProvisionWorkspaceTest {

    @Test
    void autoCreatedUserBecomesDefaultWorkspaceMember() {
        UserMapper users = mock(UserMapper.class);
        ExternalIdentityMapper identities = mock(ExternalIdentityMapper.class);
        WorkspaceService workspaces = mock(WorkspaceService.class);
        doAnswer(invocation -> {
            ((UserEntity) invocation.getArgument(0)).setId(7L);
            return 1;
        }).when(users).insert(any(UserEntity.class));
        SsoProperties properties = new SsoProperties();
        SsoService service = new SsoService(
                mock(SsoProviderRegistry.class), mock(SsoStateService.class), identities, users,
                mock(AuthService.class), properties, new BCryptPasswordEncoder(),
                new ObjectMapper(), workspaces);

        UserEntity created = ReflectionTestUtils.invokeMethod(service, "createSsoUser", "feishu",
                new SsoUserInfo("ou_1", null, "Alice", null, null, null), false);

        assertEquals(7L, created.getId());
        verify(workspaces).addMember(1L, 7L, "member");
    }
}
