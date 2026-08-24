package vip.mate.agent.graph.plan.node;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the step_deps parsing contract: valid annotations become 0-based
 * prerequisite indices enabling parallel board dispatch; ANY irregularity
 * falls back to the sequential chain that matches today's serial semantics.
 */
class PlanGenerationStepDepsTest {

    @Test
    @DisplayName("valid deps parse to 0-based indices; empty entries mean no prerequisite")
    void validDepsParse() {
        List<List<Integer>> deps = PlanGenerationNode.parseStepDeps(
                List.of("", "", "1,2"), 3);
        assertEquals(List.of(), deps.get(0));
        assertEquals(List.of(), deps.get(1));
        assertEquals(List.of(0, 1), deps.get(2));
    }

    @Test
    @DisplayName("missing field or length mismatch falls back to the sequential chain")
    void missingFallsBackToChain() {
        List<List<Integer>> absent = PlanGenerationNode.parseStepDeps(null, 3);
        assertEquals(List.of(), absent.get(0));
        assertEquals(List.of(0), absent.get(1));
        assertEquals(List.of(1), absent.get(2));

        List<List<Integer>> mismatch = PlanGenerationNode.parseStepDeps(List.of(""), 3);
        assertEquals(List.of(1), mismatch.get(2));
    }

    @Test
    @DisplayName("self references, forward references and junk all fall back to the chain")
    void invalidFallsBackToChain() {
        // Self reference (step 2 depends on step 2).
        assertEquals(List.of(0),
                PlanGenerationNode.parseStepDeps(List.of("", "2"), 2).get(1));
        // Forward reference (step 1 depends on step 2).
        assertEquals(List.of(),
                PlanGenerationNode.parseStepDeps(List.of("2", ""), 2).get(0));
        // Unparseable entry.
        assertEquals(List.of(0),
                PlanGenerationNode.parseStepDeps(List.of("", "abc"), 2).get(1));
    }

    @Test
    @DisplayName("full-width comma separators are accepted")
    void fullWidthCommaAccepted() {
        List<List<Integer>> deps = PlanGenerationNode.parseStepDeps(
                List.of("", "", "1，2"), 3);
        assertEquals(List.of(0, 1), deps.get(2));
    }
}
