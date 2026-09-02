package vip.newsclaw.tool.search;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SerperSearchProviderTest {

    @Test
    void forwardsDomainAndHalfOpenDateConstraintsUpstream() {
        SearchQuery query = new SearchQuery("AI release", null, "en", 20, "news",
                LocalDate.of(2026, 8, 27), LocalDate.of(2026, 8, 29),
                List.of("openai.com", "anthropic.com"), List.of("example.com"));

        assertEquals("AI release (site:openai.com OR site:anthropic.com) -site:example.com",
                SerperSearchProvider.scopedQuery(query));
        assertEquals("cdr:1,cd_min:08/27/2026,cd_max:08/28/2026",
                SerperSearchProvider.customDateRange(query));
    }
}
