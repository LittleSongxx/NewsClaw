package vip.mate.skill.proposal;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.mate.audit.service.AuditEventService;
import vip.mate.exception.MateClawException;
import vip.mate.skill.lifecycle.SkillSnapshotService;
import vip.mate.skill.lifecycle.model.SkillSnapshotEntity;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.model.SkillOrigin;
import vip.mate.skill.runtime.SkillSecurityService;
import vip.mate.skill.runtime.SkillValidationResult;
import vip.mate.skill.routine.model.SkillRoutineCandidateEntity;
import vip.mate.skill.routine.repository.SkillRoutineCandidateMapper;
import vip.mate.skill.service.SkillService;
import vip.mate.tool.builtin.SkillManageTool;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillChangeProposalServiceTest {

    private SkillChangeProposalMapper proposalMapper;
    private SkillService skillService;
    private SkillSecurityService securityService;
    private SkillSnapshotService snapshotService;
    private SkillManageTool skillManageTool;
    private AuditEventService auditEventService;
    private SkillRoutineCandidateMapper routineCandidateMapper;
    private SkillChangeProposalService service;
    private AtomicReference<SkillChangeProposalEntity> stored;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "");
        TableInfoHelper.initTableInfo(assistant, SkillChangeProposalEntity.class);
        TableInfoHelper.initTableInfo(assistant, SkillRoutineCandidateEntity.class);
    }

    @BeforeEach
    void setUp() {
        proposalMapper = mock(SkillChangeProposalMapper.class);
        skillService = mock(SkillService.class);
        securityService = mock(SkillSecurityService.class);
        snapshotService = mock(SkillSnapshotService.class);
        skillManageTool = mock(SkillManageTool.class);
        auditEventService = mock(AuditEventService.class);
        routineCandidateMapper = mock(SkillRoutineCandidateMapper.class);
        stored = new AtomicReference<>();
        service = new SkillChangeProposalService(proposalMapper, skillService, securityService,
                snapshotService, skillManageTool, new ObjectMapper(), auditEventService,
                routineCandidateMapper);

        when(proposalMapper.selectOne(any())).thenAnswer(invocation -> stored.get());
        when(proposalMapper.insert(any(SkillChangeProposalEntity.class))).thenAnswer(invocation -> {
            SkillChangeProposalEntity proposal = invocation.getArgument(0);
            proposal.setId(101L);
            stored.set(proposal);
            return 1;
        });
        when(proposalMapper.update(any(), any())).thenReturn(1);
        when(routineCandidateMapper.update(any(), any())).thenReturn(1);
        when(skillService.findByName(anyString(), anyLong())).thenReturn(null);
        when(securityService.scanContent(anyString(), anyString())).thenAnswer(invocation ->
                SkillValidationResult.pass(invocation.getArgument(1)));
    }

    @Test
    @DisplayName("同一 workspace 的相同候选只入库一次")
    void proposalIsIdempotentByWorkspaceAndDiff() {
        SkillProposalDraft draft = draft("create", "ai-news-radar", content("radar"));

        SkillChangeProposalEntity first = service.propose(draft);
        SkillChangeProposalEntity duplicate = service.propose(draft);

        assertSame(first, duplicate);
        assertEquals(SkillChangeProposalService.STATUS_PENDING, first.getStatus());
        assertTrue(first.getDiffText().contains("+++ proposed"));
        verify(proposalMapper, times(1)).insert(any(SkillChangeProposalEntity.class));
    }

    @Test
    @DisplayName("安全扫描阻断的候选保留审计记录但不能获批或写入生产 Skill")
    void blockedProposalCannotBeApprovedOrApplied() {
        when(securityService.scanContent(anyString(), anyString())).thenReturn(
                SkillValidationResult.block("unsafe", new ArrayList<>(), new ArrayList<>()));

        SkillChangeProposalEntity proposal = service.propose(draft("create", "unsafe", content("unsafe")));

        assertEquals(SkillChangeProposalService.STATUS_BLOCKED, proposal.getStatus());
        assertThrows(MateClawException.class, () -> service.approve(7L, proposal.getId(), null));
        assertThrows(MateClawException.class, () -> service.apply(7L, proposal.getId()));
        verify(skillManageTool, never()).skillManageAs(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("patch 候选保留精确 before/after/diff，且拒绝模糊替换")
    void patchRequiresOneExactTargetAndPersistsDiff() {
        SkillEntity existing = skill("ai-news-radar", "alpha\nold section\nomega");
        when(skillService.findByName("ai-news-radar", 7L)).thenReturn(existing);

        SkillChangeProposalEntity proposal = service.propose(new SkillProposalDraft(7L, 3L,
                "REFLECTION", "conv-1", null, "patch", "ai-news-radar", null,
                "old section", "new section", "feedback"));

        assertEquals("alpha\nold section\nomega", proposal.getBeforeContent());
        assertEquals("alpha\nnew section\nomega", proposal.getAfterContent());
        assertTrue(proposal.getDiffText().contains("old section"));
        assertThrows(MateClawException.class, () -> service.propose(new SkillProposalDraft(7L, 3L,
                "REFLECTION", "conv-1", null, "patch", "ai-news-radar", null,
                "missing", "new section", "feedback")));
    }

    @Test
    @DisplayName("获批后先创建快照，再通过正式 Skill 管道应用；重试不重复写入")
    void applyCapturesSnapshotAndIsIdempotentAfterSuccess() {
        SkillChangeProposalEntity proposal = approvedProposal("create", "ai-news-radar", content("radar"));
        stored.set(proposal);
        SkillSnapshotEntity snapshot = new SkillSnapshotEntity();
        snapshot.setId(88L);
        SkillEntity applied = skill("ai-news-radar", content("radar"));
        applied.setId(55L);
        applied.setVersion("1.0");
        when(snapshotService.captureRequired(anyString(), eq(7L))).thenReturn(snapshot);
        when(skillManageTool.skillManageAs(eq(SkillOrigin.AGENT), eq("create"), eq("ai-news-radar"),
                eq(content("radar")), any(), any(), any(), any()))
                .thenReturn("Skill 'ai-news-radar' created successfully");
        when(skillService.findByName("ai-news-radar", 7L)).thenReturn(applied);

        SkillChangeProposalEntity result = service.apply(7L, proposal.getId());
        SkillChangeProposalEntity retried = service.apply(7L, proposal.getId());

        assertEquals(SkillChangeProposalService.STATUS_APPLIED, result.getStatus());
        assertEquals(88L, result.getSnapshotId());
        assertEquals(55L, result.getAppliedSkillId());
        assertSame(result, retried);
        verify(snapshotService, times(1)).captureRequired("pre-skill-proposal 101", 7L);
        verify(skillManageTool, times(1)).skillManageAs(eq(SkillOrigin.AGENT), eq("create"),
                eq("ai-news-radar"), eq(content("radar")), any(), any(), any(), any());
    }

    @Test
    @DisplayName("回滚已应用候选时仅恢复同一 workspace 的快照")
    void rollbackRestoresScopedSnapshot() {
        SkillChangeProposalEntity proposal = approvedProposal("edit", "ai-news-radar", content("v2"));
        proposal.setStatus(SkillChangeProposalService.STATUS_APPLIED);
        proposal.setSnapshotId(88L);
        stored.set(proposal);

        SkillChangeProposalEntity result = service.rollback(7L, proposal.getId(), "editor", "恢复旧模板");

        assertEquals("ROLLED_BACK", result.getRollbackStatus());
        verify(snapshotService).restore(88L, 7L);
    }

    @Test
    @DisplayName("Routine proposal 应用和回滚时同步候选投影，普通 proposal 不受影响")
    void routineCandidateTracksApplyAndRollback() {
        SkillChangeProposalEntity proposal = approvedProposal("create", "daily-ai-brief", content("brief"));
        proposal.setSourceType("ROUTINE");
        proposal.setSourceRunId(901L);
        stored.set(proposal);
        SkillSnapshotEntity snapshot = new SkillSnapshotEntity();
        snapshot.setId(88L);
        when(snapshotService.captureRequired(anyString(), eq(7L))).thenReturn(snapshot);
        when(skillManageTool.skillManageAs(eq(SkillOrigin.AGENT), eq("create"), eq("daily-ai-brief"),
                eq(content("brief")), any(), any(), any(), any()))
                .thenReturn("Skill 'daily-ai-brief' created successfully");

        service.apply(7L, proposal.getId());
        service.rollback(7L, proposal.getId(), "editor", "verify rollback");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<SkillRoutineCandidateEntity>> updates =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(routineCandidateMapper, times(2)).update(eq(null), updates.capture());
        assertTrue(updates.getAllValues().get(0).getParamNameValuePairs().containsValue(
                SkillRoutineCandidateEntity.STATUS_PROMOTED));
        assertTrue(updates.getAllValues().get(1).getParamNameValuePairs().containsValue(
                SkillRoutineCandidateEntity.STATUS_PROPOSED));
    }

    private SkillProposalDraft draft(String action, String name, String content) {
        return new SkillProposalDraft(7L, 3L, "REFLECTION", "conv-1", null,
                action, name, content, null, null, "test evidence");
    }

    private SkillChangeProposalEntity approvedProposal(String action, String name, String afterContent) {
        SkillChangeProposalEntity proposal = new SkillChangeProposalEntity();
        proposal.setId(101L);
        proposal.setWorkspaceId(7L);
        proposal.setAction(action);
        proposal.setSkillName(name);
        proposal.setAfterContent(afterContent);
        proposal.setStatus(SkillChangeProposalService.STATUS_APPROVED);
        proposal.setDeleted(0);
        return proposal;
    }

    private SkillEntity skill(String name, String content) {
        SkillEntity skill = new SkillEntity();
        skill.setName(name);
        skill.setWorkspaceId(7L);
        skill.setSkillContent(content);
        skill.setBuiltin(false);
        return skill;
    }

    private String content(String name) {
        return "---\nname: " + name + "\ndescription: test\n---\n# " + name;
    }
}
