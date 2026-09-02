package vip.newsclaw.llm.chatmodel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredOutputFormatTest {

    @AfterEach
    void clearHolder() {
        StructuredOutputFormatHolder.clear();
    }

    @Test
    void defaultsToTextAndOnlyAcceptsDeclaredWireValues() {
        assertEquals(StructuredOutputFormat.TEXT, StructuredOutputFormat.fromWire(null));
        assertEquals(StructuredOutputFormat.TEXT, StructuredOutputFormat.fromWire("  "));
        assertEquals(StructuredOutputFormat.TEXT, StructuredOutputFormat.fromWire("TEXT"));
        assertEquals(StructuredOutputFormat.JSON_OBJECT,
                StructuredOutputFormat.fromWire(" json_object "));
        assertThrows(IllegalArgumentException.class,
                () -> StructuredOutputFormat.fromWire("json_schema"));
    }

    @Test
    void holderDoesNotLeakTextModeAcrossTurns() {
        assertEquals(StructuredOutputFormat.TEXT, StructuredOutputFormatHolder.get());
        StructuredOutputFormatHolder.set(StructuredOutputFormat.JSON_OBJECT);
        assertEquals(StructuredOutputFormat.JSON_OBJECT, StructuredOutputFormatHolder.get());
        StructuredOutputFormatHolder.set(StructuredOutputFormat.TEXT);
        assertEquals(StructuredOutputFormat.TEXT, StructuredOutputFormatHolder.get());
    }

    @Test
    void responseSchemaDefaultsToGenericAndParsesNamedAiNewsContract() {
        assertEquals(StructuredOutputSchema.GENERIC, StructuredOutputSchema.fromWire(null));
        assertEquals(StructuredOutputSchema.GENERIC, StructuredOutputSchema.fromWire(" "));
        assertEquals(StructuredOutputSchema.AI_NEWS_DECISION_V1,
                StructuredOutputSchema.fromWire(" ai_news_decision_v1 "));
        assertEquals(StructuredOutputSchema.AI_NEWS_EVIDENCE_RELATIONS_V2,
                StructuredOutputSchema.fromWire("ai_news_evidence_relations_v2"));
        assertTrue(StructuredOutputSchema.AI_NEWS_DECISION_V1.requiresAiNewsDecision());
        assertTrue(StructuredOutputSchema.AI_NEWS_EVIDENCE_RELATIONS_V2
                .requiresAiNewsEvidenceRelations());
        assertFalse(StructuredOutputSchema.GENERIC.requiresAiNewsDecision());
    }
}
