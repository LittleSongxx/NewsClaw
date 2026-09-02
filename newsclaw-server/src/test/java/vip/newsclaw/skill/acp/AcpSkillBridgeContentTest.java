package vip.newsclaw.skill.acp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.tool.ToolCallback;
import vip.newsclaw.agent.context.ChatOrigin;
import vip.newsclaw.acp.model.AcpEndpointEntity;
import vip.newsclaw.acp.service.AcpDelegationService;
import vip.newsclaw.acp.service.AcpEndpointService;
import vip.newsclaw.skill.model.SkillEntity;
import vip.newsclaw.skill.runtime.model.ResolvedSkill;
import vip.newsclaw.tool.ToolRegistry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Asserts the bridge synthesizes a non-empty SKILL.md body for every
 * ACP-derived virtual skill and wires it onto both the {@link ResolvedSkill}
 * {@code content} field and the virtual {@link SkillEntity} {@code skillContent}.
 *
 * <p>Without a body, an agent calling
 * {@code readSkillFile(filePath="SKILL.md")} on an ACP endpoint gets
 * "Error: SKILL.md content not available" and has nothing beyond the
 * one-line description to work from.
 */
class AcpSkillBridgeContentTest {

    private AcpEndpointService endpointService;
    private AcpDelegationService delegationService;
    private ToolRegistry toolRegistry;
    private AcpSkillBridge bridge;

    @BeforeEach
    void setUp() {
        endpointService = mock(AcpEndpointService.class);
        delegationService = mock(AcpDelegationService.class);
        toolRegistry = mock(ToolRegistry.class);
        bridge = new AcpSkillBridge(endpointService, delegationService, new ObjectMapper(), toolRegistry);
    }

    @Test
    @DisplayName("ResolvedSkill carries a synthesized SKILL.md body, not an empty string")
    void resolvedSkillHasNonEmptyContent() {
        AcpEndpointEntity ep = newEndpoint(7L, "codex");
        ep.setDescription("OpenAI-compatible coding agent");
        when(endpointService.listEnabled()).thenReturn(List.of(ep));

        ResolvedSkill resolved = bridge.listAcpDerivedResolvedSkills().get(0);

        assertNotNull(resolved.getContent());
        assertFalse(resolved.getContent().isBlank(), "SKILL.md body must not be blank");
        String body = resolved.getContent();
        assertTrue(body.contains("# codex"), "expected a markdown title, got: " + body);
        assertTrue(body.contains("OpenAI-compatible coding agent"),
                "expected the endpoint description in the body, got: " + body);
        assertTrue(body.contains("acp_codex_prompt"),
                "expected the wrapper tool name in the body, got: " + body);
        assertTrue(body.contains("`prompt`") && body.contains("`cwd`"),
                "expected both wrapper parameters documented, got: " + body);
        assertTrue(body.contains("## Usage notes"),
                "expected a usage notes section, got: " + body);
    }

    @Test
    @DisplayName("virtual SkillEntity skillContent matches the ResolvedSkill content")
    void skillEntityContentMatchesResolved() {
        AcpEndpointEntity ep = newEndpoint(7L, "codex");
        ep.setWorkspaceId(23L);
        when(endpointService.listEnabled()).thenReturn(List.of(ep));

        SkillEntity entity = bridge.listAcpDerivedSkillEntities().get(0);
        ResolvedSkill resolved = bridge.listAcpDerivedResolvedSkills().get(0);

        assertNotNull(entity.getSkillContent());
        assertFalse(entity.getSkillContent().isBlank(), "skillContent must not be blank");
        assertEquals(resolved.getContent(), entity.getSkillContent(),
                "entity skillContent and resolved content must be the same synthesized body");
        assertEquals(23L, entity.getWorkspaceId());
        assertEquals(23L, resolved.getWorkspaceId());
    }

    @Test
    @DisplayName("trusted endpoints document the no-approval behavior")
    void trustedEndpointPhrasing() {
        AcpEndpointEntity ep = newEndpoint(7L, "codex");
        ep.setTrusted(true);
        when(endpointService.listEnabled()).thenReturn(List.of(ep));

        String body = bridge.listAcpDerivedResolvedSkills().get(0).getContent();

        assertTrue(body.contains("trusted: the agent's own tool calls are accepted"),
                "expected trusted phrasing, got: " + body);
    }

    @Test
    @DisplayName("untrusted endpoints document that tool calls may need approval")
    void untrustedEndpointPhrasing() {
        AcpEndpointEntity ep = newEndpoint(7L, "codex");
        ep.setTrusted(false);
        when(endpointService.listEnabled()).thenReturn(List.of(ep));

        String body = bridge.listAcpDerivedResolvedSkills().get(0).getContent();

        assertTrue(body.contains("not trusted: the agent's tool calls may require"),
                "expected untrusted phrasing, got: " + body);
    }

    @Test
    @DisplayName("status hint reflects a failed connection test")
    void erroredEndpointStatusHint() {
        AcpEndpointEntity ep = newEndpoint(7L, "codex");
        ep.setLastStatus("ERROR");
        ep.setLastError("command not found: codex");
        when(endpointService.listEnabled()).thenReturn(List.of(ep));

        String body = bridge.listAcpDerivedResolvedSkills().get(0).getContent();

        assertTrue(body.contains("Last connection test failed: command not found: codex"),
                "expected the error surfaced in the body, got: " + body);
    }

    @Test
    @DisplayName("status hint reflects an OK connection test")
    void okEndpointStatusHint() {
        AcpEndpointEntity ep = newEndpoint(7L, "codex");
        ep.setLastStatus("OK");
        when(endpointService.listEnabled()).thenReturn(List.of(ep));

        String body = bridge.listAcpDerivedResolvedSkills().get(0).getContent();

        assertTrue(body.contains("Last connection test: OK."),
                "expected OK status in the body, got: " + body);
    }

    @Test
    @DisplayName("untested endpoints note the CLI is spawned on first call")
    void untestedEndpointStatusHint() {
        AcpEndpointEntity ep = newEndpoint(7L, "codex");
        ep.setLastStatus("UNKNOWN");
        when(endpointService.listEnabled()).thenReturn(List.of(ep));

        String body = bridge.listAcpDerivedResolvedSkills().get(0).getContent();

        assertTrue(body.contains("Not yet tested"),
                "expected an untested hint, got: " + body);
    }

    @Test
    @DisplayName("CJK-only endpoint names slug to a stable id-based wrapper tool name")
    void cjkOnlyNameUsesIdSlugInContent() {
        AcpEndpointEntity ep = newEndpoint(42L, "代码助手");
        when(endpointService.listEnabled()).thenReturn(List.of(ep));

        String body = bridge.listAcpDerivedResolvedSkills().get(0).getContent();

        assertTrue(body.contains("acp_acp-42_prompt"),
                "all-CJK name should fall back to an id-based wrapper name, got: " + body);
    }

    @Test
    @DisplayName("findResolvedById and findEntityById return entries with the synthesized body")
    void lookupByVirtualIdCarriesContent() {
        AcpEndpointEntity ep = newEndpoint(7L, "codex");
        when(endpointService.get(7L)).thenReturn(ep);

        long virtualId = AcpSkillBridge.virtualIdFor(ep);
        ResolvedSkill resolved = bridge.findResolvedById(virtualId);
        SkillEntity entity = bridge.findEntityById(virtualId);

        assertNotNull(resolved);
        assertNotNull(entity);
        assertFalse(resolved.getContent().isBlank(), "resolved content must not be blank");
        assertFalse(entity.getSkillContent().isBlank(), "entity skillContent must not be blank");
    }

    @Test
    void wrapperRejectsCallsFromAnotherWorkspace() {
        AcpEndpointEntity ep = newEndpoint(7L, "codex");
        ep.setWorkspaceId(23L);
        when(endpointService.listEnabled()).thenReturn(List.of(ep));

        bridge.onReady();

        ArgumentCaptor<ToolCallback> callback = ArgumentCaptor.forClass(ToolCallback.class);
        verify(toolRegistry).registerPluginTool(callback.capture(), org.mockito.ArgumentMatchers.any());
        String result = callback.getValue().call(
                "{\"prompt\":\"inspect\"}",
                ChatOrigin.web("conv", "alice", 99L, null).toToolContext());

        assertTrue(result.contains("outside the current workspace"));
        verifyNoInteractions(delegationService);
    }

    private static AcpEndpointEntity newEndpoint(long id, String name) {
        AcpEndpointEntity ep = new AcpEndpointEntity();
        ep.setId(id);
        ep.setName(name);
        ep.setEnabled(true);
        ep.setCommand("codex");
        return ep;
    }
}
