package vip.newsclaw.notification;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import vip.newsclaw.agent.runtime.AgentRuntimeAggregator;
import vip.newsclaw.approval.ApprovalWorkflowService;
import vip.newsclaw.common.result.R;
import vip.newsclaw.wiki.service.WikiRawMaterialService;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationControllerTest {

    @Test
    void pendingApprovalCountIsRestrictedToRequestedWorkspace() {
        ApprovalWorkflowService approvals = mock(ApprovalWorkflowService.class);
        when(approvals.countPendingFromDb(42L)).thenReturn(3L);
        NotificationController controller = new NotificationController(
                approvals, mock(AgentRuntimeAggregator.class), mock(WikiRawMaterialService.class));

        R<Map<String, Object>> result = controller.summary(
                new UsernamePasswordAuthenticationToken(
                        "member", "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER"))),
                42L);

        assertThat(result.getData().get("pendingApprovals")).isEqualTo(3);
        verify(approvals).countPendingFromDb(42L);
    }
}
