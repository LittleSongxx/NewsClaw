package vip.newsclaw.llm.chatmodel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import vip.newsclaw.exception.NewsClawException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolCandidatePolicyTest {

    @AfterEach
    void clearHolder() {
        ToolCandidateHolder.clear();
    }

    @Test
    void nullIsBackwardCompatibleAndExplicitEmptyMeansNoTools() {
        ToolCandidatePolicy unrestricted = ToolCandidatePolicy.fromWire(null);
        ToolCandidatePolicy empty = ToolCandidatePolicy.fromWire(List.of());

        assertFalse(unrestricted.restricted());
        assertEquals(2, unrestricted.restrict(List.of(callback("one"), callback("two"))).size());
        assertTrue(empty.restricted());
        assertTrue(empty.restrict(List.of(callback("one"))).isEmpty());
    }

    @Test
    void intersectsWithoutReorderingOrWidening() {
        ToolCallback one = callback("one");
        ToolCallback two = callback("two");
        ToolCallback three = callback("three");

        List<ToolCallback> selected = ToolCandidatePolicy.fromWire(List.of("three", "one"))
                .restrict(List.of(one, two, three));

        assertEquals(List.of("one", "three"), selected.stream()
                .map(item -> item.getToolDefinition().name()).toList());
    }

    @Test
    void unavailableCandidateFailsClosed() {
        NewsClawException error = assertThrows(NewsClawException.class,
                () -> ToolCandidatePolicy.fromWire(List.of("not_active"))
                        .restrict(List.of(callback("active"))));

        assertEquals(422, error.getCode());
    }

    @Test
    void rejectsMalformedDuplicateAndOversizedWireValues() {
        assertThrows(IllegalArgumentException.class,
                () -> ToolCandidatePolicy.fromWire(List.of(" bad")));
        assertThrows(IllegalArgumentException.class,
                () -> ToolCandidatePolicy.fromWire(List.of("same", "same")));
        assertThrows(IllegalArgumentException.class,
                () -> ToolCandidatePolicy.fromWire(java.util.Collections.nCopies(33, "same")));
    }

    @Test
    void holderPreservesExplicitEmptyPolicy() {
        ToolCandidateHolder.set(ToolCandidatePolicy.fromWire(List.of()));
        assertTrue(ToolCandidateHolder.get().restricted());
        assertTrue(ToolCandidateHolder.get().names().isEmpty());

        ToolCandidateHolder.set(ToolCandidatePolicy.UNRESTRICTED);
        assertFalse(ToolCandidateHolder.get().restricted());
    }

    private static ToolCallback callback(String name) {
        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name(name).description("test").inputSchema("{}").build());
        return callback;
    }
}
