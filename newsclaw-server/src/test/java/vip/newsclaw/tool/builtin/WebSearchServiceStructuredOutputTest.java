package vip.newsclaw.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.newsclaw.tool.search.SearchResult;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSearchServiceStructuredOutputTest {

    @Test
    void returnsBoundedStructuredDiscoveryCandidatesInsteadOfSpillingHugeSnippets() throws Exception {
        List<SearchResult> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            results.add(SearchResult.builder()
                    .title("Result " + i)
                    .url("https://example.com/news/" + i)
                    .source("example.com")
                    .date("2026-08-26T12:00:00Z")
                    .providerId("tavily")
                    .snippet("large external body ".repeat(2_000))
                    .build());
        }

        String output = WebSearchService.formatResults(results, "tavily", false);
        JsonNode json = new ObjectMapper().readTree(output);

        assertTrue(output.length() <= WebSearchService.MAX_FORMATTED_CHARS);
        assertEquals("untrusted_search_candidates", json.path("mode").asText());
        assertFalse(json.path("evidenceEligible").asBoolean(true));
        assertTrue(json.path("returnedResultCount").asInt() > 0);
        assertTrue(json.path("results").get(0).path("snippetTruncated").asBoolean());
        assertTrue(json.path("message").asText().contains("Capture"));
    }
}
