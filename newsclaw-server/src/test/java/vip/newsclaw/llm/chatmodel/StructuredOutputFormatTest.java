package vip.newsclaw.llm.chatmodel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
