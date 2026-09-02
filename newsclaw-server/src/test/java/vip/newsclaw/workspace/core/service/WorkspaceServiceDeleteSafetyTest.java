package vip.newsclaw.workspace.core.service;

import org.junit.jupiter.api.Test;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.i18n.I18nService;
import vip.newsclaw.wiki.service.WikiKnowledgeBaseService;
import vip.newsclaw.workspace.conversation.model.ConversationEntity;
import vip.newsclaw.workspace.conversation.repository.ConversationMapper;
import vip.newsclaw.workspace.core.model.WorkspaceEntity;
import vip.newsclaw.workspace.core.model.WorkspaceMemberEntity;
import vip.newsclaw.workspace.core.repository.WorkspaceMapper;
import vip.newsclaw.workspace.core.repository.WorkspaceMemberMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkspaceServiceDeleteSafetyTest {

    @Test
    void activeConversationBlocksWorkspaceDeletion() {
        Harness h = harness();
        when(h.conversations.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> h.service.delete(9L)).isInstanceOf(NewsClawException.class);
        verify(h.workspaces, never()).deleteById(9L);
    }

    @Test
    void successfulDeleteRemovesMembersAndEvictsTheirCachedAuthorization() {
        Harness h = harness();
        WorkspaceMemberEntity member = new WorkspaceMemberEntity();
        member.setWorkspaceId(9L);
        member.setUserId(7L);
        member.setRole("owner");
        when(h.members.selectOne(any())).thenReturn(member, null);
        when(h.members.selectList(any())).thenReturn(List.of(member));
        assertThat(h.service.hasPermissionCached(9L, 7L, "viewer")).isTrue();

        h.service.delete(9L);

        verify(h.members).delete(any());
        verify(h.workspaces).deleteById(9L);
        assertThat(h.service.hasPermissionCached(9L, 7L, "viewer")).isFalse();
    }

    private static Harness harness() {
        WorkspaceMapper workspaces = mock(WorkspaceMapper.class);
        WorkspaceMemberMapper members = mock(WorkspaceMemberMapper.class);
        ConversationMapper conversations = mock(ConversationMapper.class);
        WikiKnowledgeBaseService wiki = mock(WikiKnowledgeBaseService.class);
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(9L);
        workspace.setSlug("delete-me");
        when(workspaces.selectById(9L)).thenReturn(workspace);
        when(wiki.listByWorkspace(9L)).thenReturn(List.of());
        when(conversations.selectCount(any())).thenReturn(0L);
        WorkspaceService service = new WorkspaceService(
                workspaces, members, conversations, wiki, mock(I18nService.class));
        return new Harness(service, workspaces, members, conversations);
    }

    private record Harness(WorkspaceService service, WorkspaceMapper workspaces,
                           WorkspaceMemberMapper members, ConversationMapper conversations) {}
}
