package vip.mate.skill.routine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.skill.proposal.SkillChangeProposalEntity;
import vip.mate.skill.proposal.SkillChangeProposalService;
import vip.mate.skill.proposal.SkillProposalDraft;
import vip.mate.skill.routine.model.SkillRoutineCandidateEntity;
import vip.mate.skill.routine.repository.SkillRoutineCandidateMapper;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Proposal-first guardrails for cross-session routine mining. */
class SkillRoutinePromoterProposalTest {

    private SkillRoutineCandidateMapper candidateMapper;
    private ConversationService conversations;
    private ModelConfigService models;
    private AgentGraphBuilder graphBuilder;
    private SkillRoutinePromoter promoter;

    @BeforeEach
    void setUp() {
        candidateMapper = mock(SkillRoutineCandidateMapper.class);
        conversations = mock(ConversationService.class);
        models = mock(ModelConfigService.class);
        graphBuilder = mock(AgentGraphBuilder.class);
        promoter = new SkillRoutinePromoter(candidateMapper, conversations, models, graphBuilder,
                new SkillRoutineProperties(), new ObjectMapper());

        ConversationEntity conversation = new ConversationEntity();
        conversation.setWorkspaceId(7L);
        when(conversations.findByConversationId("conv-1")).thenReturn(conversation);
        when(conversations.listMessages("conv-1")).thenReturn(List.of(
                message("user", "请整理今天的 AI 圈动态"),
                message("assistant", "已完成模型、机器人与大厂产品动态整理。")));

        ModelConfigEntity model = new ModelConfigEntity();
        model.setId(3L);
        when(models.getDefaultModel()).thenReturn(model);
        ChatModel chatModel = responseModel("{\"name\":\"ai_news_weekly_digest\","
                + "\"content\":\"# AI 动态周报\\n先核验官方来源，再输出公众号与小红书素材。\"}");
        when(graphBuilder.buildRuntimeChatModel(eq(model))).thenReturn(chatModel);
    }

    @Test
    @DisplayName("Routine Mining 只产生待审批 proposal，并回写 candidate 的 proposalId")
    void qualifiedRoutineCreatesProposalInsteadOfLiveSkill() {
        SkillChangeProposalService proposals = mock(SkillChangeProposalService.class);
        SkillChangeProposalEntity proposal = new SkillChangeProposalEntity();
        proposal.setId(301L);
        when(proposals.propose(any(SkillProposalDraft.class))).thenReturn(proposal);
        promoter.setProposalService(proposals);
        SkillRoutineCandidateEntity candidate = candidate();

        boolean proposed = promoter.promoteCandidate(candidate);

        assertTrue(proposed);
        assertEquals(SkillRoutineCandidateEntity.STATUS_PROPOSED, candidate.getStatus());
        assertEquals(301L, candidate.getProposalId());
        assertEquals("ai_news_weekly_digest", candidate.getPromotedSkillName());
        verify(proposals).propose(any(SkillProposalDraft.class));
        verify(candidateMapper).updateById(candidate);
    }

    @Test
    @DisplayName("proposal 服务不可用时 fail-closed，Routine Mining 不会直写生产 Skill")
    void missingProposalServiceFailsClosed() {
        SkillRoutineCandidateEntity candidate = candidate();

        boolean proposed = promoter.promoteCandidate(candidate);

        assertFalse(proposed);
        assertEquals(SkillRoutineCandidateEntity.STATUS_OBSERVING, candidate.getStatus());
        verify(candidateMapper, never()).updateById(any(SkillRoutineCandidateEntity.class));
    }

    private static SkillRoutineCandidateEntity candidate() {
        SkillRoutineCandidateEntity candidate = new SkillRoutineCandidateEntity();
        candidate.setId(71L);
        candidate.setWorkspaceId(7L);
        candidate.setAgentId(3L);
        candidate.setSignature("生成 AI 动态周报");
        candidate.setRepresentativeText("请整理今天的 AI 圈动态");
        candidate.setOccurrenceCount(4);
        candidate.setDistinctDayCount(3);
        candidate.setSampleConversations("[\"conv-1\"]");
        candidate.setStatus(SkillRoutineCandidateEntity.STATUS_OBSERVING);
        return candidate;
    }

    private static MessageEntity message(String role, String content) {
        MessageEntity message = new MessageEntity();
        message.setRole(role);
        message.setContent(content);
        return message;
    }

    private static ChatModel responseModel(String content) {
        ChatModel model = mock(ChatModel.class);
        Generation generation = new Generation(new AssistantMessage(content), ChatGenerationMetadata.NULL);
        ChatResponse response = mock(ChatResponse.class);
        when(response.getResult()).thenReturn(generation);
        when(model.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(response);
        return model;
    }
}
