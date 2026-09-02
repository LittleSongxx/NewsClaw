package vip.newsclaw.tool.guard.engine;

import org.junit.jupiter.api.Test;
import vip.newsclaw.tool.guard.model.GuardDecision;
import vip.newsclaw.tool.guard.model.ToolInvocationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolPolicyResolverSkillScriptTest {

    @Test
    void cleanSkillScriptStillRequiresApproval() {
        ToolInvocationContext context = ToolInvocationContext.of(
                "runSkillScript", "{}", "conv", "agent");
        assertEquals(GuardDecision.NEEDS_APPROVAL,
                new ToolPolicyResolver().resolve(List.of(), context));
    }
}
