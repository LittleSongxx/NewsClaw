package vip.newsclaw.news.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiNewsCategoryTest {

    @Test
    void preservesVerticalCategoriesAndNormalizesDocumentedAliases() {
        assertEquals("funding", AiNewsCategory.normalize("funding"));
        assertEquals("funding", AiNewsCategory.normalize("financing"));
        assertEquals("security", AiNewsCategory.normalize("safety"));
        assertEquals("partnership", AiNewsCategory.normalize("collaboration"));
        assertEquals("open_source", AiNewsCategory.normalize("open-source"));
    }

    @Test
    void rejectsUnknownInsteadOfSilentlyPollutingModelSlice() {
        assertThrows(IllegalArgumentException.class, () -> AiNewsCategory.normalize("marketing"));
    }
}
