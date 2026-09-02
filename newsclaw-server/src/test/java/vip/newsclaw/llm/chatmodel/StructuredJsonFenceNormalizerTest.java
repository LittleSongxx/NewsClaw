package vip.newsclaw.llm.chatmodel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredJsonFenceNormalizerTest {

    @Test
    void acceptsOnlyOneUnadornedStrictJsonObjectFence() {
        assertEquals("{\"ok\":true}", StructuredJsonFenceNormalizer
                .normalizeSingleJsonObjectFence("```json\n{\"ok\":true}\n```").orElseThrow());
        assertEquals("{\"ok\":true}", StructuredJsonFenceNormalizer
                .normalizeSingleJsonObjectFence("```\n{\"ok\":true}\n```").orElseThrow());
    }

    @Test
    void rejectsProseArraysDuplicatesTrailingTokensAndMultipleFences() {
        assertTrue(StructuredJsonFenceNormalizer
                .normalizeSingleJsonObjectFence("answer:\n```json\n{\"ok\":true}\n```").isEmpty());
        assertTrue(StructuredJsonFenceNormalizer
                .normalizeSingleJsonObjectFence("```json\n[1]\n```").isEmpty());
        assertTrue(StructuredJsonFenceNormalizer
                .normalizeSingleJsonObjectFence("```json\n{\"a\":1,\"a\":2}\n```").isEmpty());
        assertTrue(StructuredJsonFenceNormalizer
                .normalizeSingleJsonObjectFence("```json\n{\"a\":1} {}\n```").isEmpty());
        assertTrue(StructuredJsonFenceNormalizer
                .normalizeSingleJsonObjectFence("```json\n{\"a\":1}\n```\n```json\n{\"b\":2}\n```").isEmpty());
    }
}
