package vip.newsclaw.news.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.newsclaw.news.service.AiNewsEventService;
import vip.newsclaw.news.repository.AiNewsFeedbackMapper;
import vip.newsclaw.skill.proposal.SkillChangeProposalEntity;
import vip.newsclaw.skill.proposal.SkillProposalDraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiNewsFeedbackServiceTest {

    private AiNewsFeedbackMapper feedbackMapper;
    private AiNewsEventService eventService;
    private AiNewsFeedbackProposalBridge proposalBridge;
    private AiNewsFeedbackService service;

    @BeforeEach
    void setUp() {
        feedbackMapper = mock(AiNewsFeedbackMapper.class);
        eventService = mock(AiNewsEventService.class);
        proposalBridge = mock(AiNewsFeedbackProposalBridge.class);
        service = new AiNewsFeedbackService(feedbackMapper, eventService, proposalBridge,
                new ObjectMapper());
        when(feedbackMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            AiNewsFeedbackEntity row = invocation.getArgument(0);
            row.setId(100L);
            return 1;
        }).when(feedbackMapper).insert(any(AiNewsFeedbackEntity.class));
    }

    @Test
    void feedbackWithoutSkillTargetIsRecordedOnly() {
        var result = service.submit(7L, new AiNewsFeedbackRequest(
                7L, null, null, null, "badcase", "source was stale", "{}",
                null, null, null, null, null));

        assertEquals("RECORDED", result.feedback().getStatus());
        verify(proposalBridge, never()).propose(any(SkillProposalDraft.class));
    }

    @Test
    void explicitSkillTargetCreatesProposalThroughBridge() {
        SkillChangeProposalEntity proposal = new SkillChangeProposalEntity();
        proposal.setId(200L);
        when(proposalBridge.propose(any(SkillProposalDraft.class))).thenReturn(proposal);

        var result = service.submit(7L, new AiNewsFeedbackRequest(
                7L, null, 8L, 9L, "editorial", "avoid unsupported claim", "evidence",
                "news-editor", "edit", "replace claim wording", null, null));

        assertEquals(200L, result.feedback().getProposalId());
        assertEquals("PROPOSAL_CREATED", result.feedback().getStatus());
        verify(proposalBridge).propose(any(SkillProposalDraft.class));
        verify(feedbackMapper).updateById(any(AiNewsFeedbackEntity.class));
    }

    @Test
    void mismatchedBodyWorkspaceCannotOverrideHeaderWorkspace() {
        assertThrows(RuntimeException.class, () -> service.submit(7L, new AiNewsFeedbackRequest(
                8L, null, null, null, "badcase", "cross tenant", null,
                null, null, null, null, null)));
        verify(feedbackMapper, never()).insert(any(AiNewsFeedbackEntity.class));
    }
}
