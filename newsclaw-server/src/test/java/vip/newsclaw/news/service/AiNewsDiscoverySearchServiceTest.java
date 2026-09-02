package vip.newsclaw.news.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vip.newsclaw.news.source.NewsSourceHealth;
import vip.newsclaw.news.source.NewsSourceChannel;
import vip.newsclaw.news.source.NewsSourceProvider;
import vip.newsclaw.news.source.NewsSourceProviderRegistry;
import vip.newsclaw.news.source.NewsSourceProvenance;
import vip.newsclaw.news.source.NewsSourceResult;
import vip.newsclaw.news.source.ScheduledNewsSourceProvider;
import vip.newsclaw.tool.builtin.WebSearchService;
import vip.newsclaw.tool.search.SearchQuery;
import vip.newsclaw.tool.search.SearchResult;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiNewsDiscoverySearchServiceTest {

    @Test
    void executesOfficialAndVerticalNewsLanesThenFusesAndDeduplicates() {
        WebSearchService search = mock(WebSearchService.class);
        when(search.searchCandidates(any(SearchQuery.class))).thenAnswer(invocation -> {
            SearchQuery query = invocation.getArgument(0);
            if ("general".equals(query.topic()) && !query.includeDomains().isEmpty()) {
                return batch(result("Qualcomm releases AI IMSDK 2.0",
                        "https://www.qualcomm.com/developer/blog/update?utm_source=test", 0.91D));
            }
            if (query.query().contains("funding")) {
                return batch(result("AI startup closes a $10 million funding round",
                        "https://techcrunch.com/startup-round?ref=home", 0.80D));
            }
            if (query.query().contains("product")) {
                return batch(result("Qualcomm releases AI IMSDK 2.0",
                        "https://syndication.example/qualcomm-imsdk", 0.70D));
            }
            return new WebSearchService.SearchBatch("tavily", false, List.of(), "");
        });
        AiNewsDiscoverySearchService service = new AiNewsDiscoverySearchService(
                search, new AiNewsSourceRegistry());

        var output = service.discover("artificial intelligence",
                Instant.parse("2026-08-26T03:15:40Z"),
                Instant.parse("2026-08-27T03:15:40Z"), 30);

        assertEquals(10, output.queryCount());
        assertFalse(output.evidenceEligible());
        assertEquals(2, output.candidates().size(),
                "same-title syndication should be conservatively collapsed");
        var official = output.candidates().stream().filter(item -> item.officialDomain())
                .findFirst().orElseThrow();
        assertFalse(official.url().contains("utm_source"));
        assertTrue(output.candidates().stream().allMatch(item -> item.rank() > 0));
        assertEquals(5, output.executions().stream()
                .filter(item -> item.family().startsWith("official_"))
                .filter(item -> !item.requestedIncludeDomains().isEmpty()).count());

        ArgumentCaptor<SearchQuery> requests = ArgumentCaptor.forClass(SearchQuery.class);
        verify(search, times(10)).searchCandidates(requests.capture());
        assertEquals(5, requests.getAllValues().stream().filter(item -> "general".equals(item.topic())
                && !item.includeDomains().isEmpty()).count());
        assertEquals(5, requests.getAllValues().stream()
                .filter(item -> "news".equals(item.topic())).count());
        assertEquals(3, requests.getAllValues().stream()
                .filter(item -> "news".equals(item.topic()) && !item.includeDomains().isEmpty()).count());
        assertTrue(requests.getAllValues().stream().anyMatch(item -> "zh-cn".equals(item.language())
                && item.includeDomains().contains("jiqizhixin.com")));
    }

    @Test
    void configuredProviderUnionKeepsPerProviderSnapshotsAndDistinctRrfVotes() {
        WebSearchService search = mock(WebSearchService.class);
        when(search.searchCandidates(any(List.class), any(SearchQuery.class))).thenAnswer(invocation -> {
            SearchResult primary = SearchResult.builder()
                    .title("Primary AI model launch")
                    .url("https://primary.example/news/model-launch")
                    .snippet("candidate only")
                    .date("2026-08-26T10:00:00Z")
                    .providerId("primary")
                    .relevanceScore(0.9D)
                    .build();
            SearchResult secondary = SearchResult.builder()
                    .title("Secondary AI startup funding event")
                    .url("https://secondary.example/news/funding-event")
                    .snippet("candidate only")
                    .date("2026-08-26T11:00:00Z")
                    .providerId("secondary")
                    .relevanceScore(0.8D)
                    .build();
            List<WebSearchService.SearchBatch> batches = List.of(
                    new WebSearchService.SearchBatch("primary", false, List.of(primary), ""),
                    new WebSearchService.SearchBatch("secondary", false, List.of(secondary), ""));
            return new WebSearchService.SearchUnion(List.of("primary", "secondary"),
                    batches, List.of(primary, secondary), 2, 0, List.of());
        });
        AiNewsDiscoveryProperties properties = new AiNewsDiscoveryProperties();
        properties.setProviderIds(List.of("primary", "secondary"));
        AiNewsDiscoverySearchService service = new AiNewsDiscoverySearchService(
                search, new AiNewsSourceRegistry(), properties);

        var output = service.discover("artificial intelligence",
                Instant.parse("2026-08-26T03:15:40Z"),
                Instant.parse("2026-08-27T03:15:40Z"), 30);

        assertEquals(20, output.queryCount(),
                "queryCount reflects the two provider attempts for each of ten lanes");
        assertEquals(20, output.executions().size());
        assertEquals(20, output.querySnapshots().size());
        assertTrue(output.executions().stream().anyMatch(item ->
                "official_models@primary".equals(item.family())
                        && "primary".equals(item.providerId())));
        assertTrue(output.executions().stream().anyMatch(item ->
                "official_models@secondary".equals(item.family())
                        && "secondary".equals(item.providerId())));
        assertTrue(output.candidates().stream().anyMatch(item ->
                item.queryFamilies().contains("official_models@primary")));
        assertTrue(output.candidates().stream().anyMatch(item ->
                item.queryFamilies().contains("official_models@secondary")));
        verify(search, times(10)).searchCandidates(any(List.class), any(SearchQuery.class));
        verify(search, never()).searchCandidates(any(SearchQuery.class));
    }

    @Test
    void urlCanonicalizationDropsTrackersButKeepsFunctionalParameters() {
        assertEquals("https://example.com/story?id=7",
                AiNewsDiscoverySearchService.canonicalDiscoveryUrl(
                        "https://EXAMPLE.com/story/?utm_campaign=x&id=7#fragment"));
    }

    @Test
    void fusesSchemeAndDeliveryHostAliasesWithoutDroppingRawObservations() {
        WebSearchService search = mock(WebSearchService.class);
        AtomicInteger calls = new AtomicInteger();
        when(search.searchCandidates(any(SearchQuery.class))).thenAnswer(invocation -> switch (calls.getAndIncrement()) {
            case 0 -> batch(
                    result("AI policy service launches",
                            "https://wap.example.com/data/story.html", 0.9D),
                    result("AI policy service enters production",
                            "http://www.example.com/data/story.html", 0.8D));
            case 1 -> batch(
                    result("AI policy service deployment expands",
                            "https://example.com/data/story.html", 0.85D),
                    result("Different AI chip funding announced",
                            "https://www.example.com/data/other.html", 0.7D));
            default -> new WebSearchService.SearchBatch("tavily", false, List.of(), "");
        });
        AiNewsDiscoverySearchService service = new AiNewsDiscoverySearchService(
                search, new AiNewsSourceRegistry());

        var output = service.discover("artificial intelligence",
                Instant.parse("2026-08-26T03:15:40Z"),
                Instant.parse("2026-08-27T03:15:40Z"), 30);

        assertEquals(2, output.uniqueUrlCount());
        assertEquals(2, output.candidates().size());
        var aliased = output.candidates().stream()
                .filter(item -> "https://wap.example.com/data/story.html".equals(item.url()))
                .findFirst().orElseThrow();
        assertEquals(2, aliased.queryFamilies().size());
        double expectedScore = Math.round((2.0D / (AiNewsDiscoverySearchService.RRF_K + 1)
                + 0.9D * 0.00001D) * 1_000_000D) / 1_000_000D;
        assertEquals(expectedScore, aliased.rrfScore(), 1.0e-12,
                "aliases in one lane must not receive duplicate RRF credit");
        assertEquals(4, output.querySnapshots().stream()
                .mapToInt(snapshot -> snapshot.results().size()).sum(),
                "raw provider observations must remain available for audit");
        String functionalQuery = AiNewsDiscoverySearchService.discoveryUrlAliasKey(
                "https://www.example.com/data/story.html?id=7");
        assertFalse(functionalQuery.equals(AiNewsDiscoverySearchService.discoveryUrlAliasKey(
                "http://m.example.com/data/story.html?id=8")));
        assertFalse(functionalQuery.equals(AiNewsDiscoverySearchService.discoveryUrlAliasKey(
                "https://m.example.com:8443/data/story.html?id=7")));
        assertTrue(output.rankingPolicyVersion().startsWith("discovery-temporal-story-v9@"));
    }

    @Test
    void explicitUrlYearFilterRejectsStaleArticlesButNotAmbiguousProductNumbers() {
        Instant start = Instant.parse("2026-08-26T03:15:40Z");
        Instant end = Instant.parse("2026-08-27T03:15:40Z");
        assertTrue(AiNewsDiscoverySearchService.hasExplicitUrlDateOutsideWindow(
                "https://about.fb.com/news/2024/07/story", start, end));
        assertFalse(AiNewsDiscoverySearchService.hasExplicitUrlDateOutsideWindow(
                "https://example.com/news/2026/08/story", start, end));
        assertFalse(AiNewsDiscoverySearchService.hasExplicitUrlDateOutsideWindow(
                "https://example.com/products/model-2024-update", start, end));
    }

    @Test
    void exactUrlDayFilterUsesHalfOpenWindowOverlap() {
        Instant start = Instant.parse("2026-08-26T03:15:40Z");
        Instant end = Instant.parse("2026-08-27T03:15:40Z");
        assertTrue(AiNewsDiscoverySearchService.hasExplicitUrlDateOutsideWindow(
                "https://example.com/2026/08/25/stale", start, end));
        assertFalse(AiNewsDiscoverySearchService.hasExplicitUrlDateOutsideWindow(
                "https://example.com/2026/08/26/current", start, end));
        assertFalse(AiNewsDiscoverySearchService.hasExplicitUrlDateOutsideWindow(
                "https://example.com/2026/08/27/current-before-cutoff", start, end));
    }

    @Test
    void arxivStyleIdsProvideAConservativePublicationMonthGate() {
        Instant start = Instant.parse("2026-08-27T02:10:00Z");
        Instant end = Instant.parse("2026-08-28T02:10:00Z");
        assertTrue(AiNewsDiscoverySearchService.hasExplicitUrlDateOutsideWindow(
                "https://huggingface.co/papers/2506.22183", start, end));
        assertTrue(AiNewsDiscoverySearchService.hasExplicitUrlDateOutsideWindow(
                "https://arxiv.org/abs/2607.12345v2", start, end));
        assertFalse(AiNewsDiscoverySearchService.hasExplicitUrlDateOutsideWindow(
                "https://arxiv.org/abs/2608.12345", start, end));
        assertFalse(AiNewsDiscoverySearchService.hasExplicitUrlDateOutsideWindow(
                "https://example.com/papers/2506.22183", start, end),
                "numeric ids on unrelated hosts are not publication dates");
    }

    @Test
    void obviousStaticPagesAreFilteredWithoutRejectingEditorialPaths() {
        assertTrue(AiNewsDiscoverySearchService.isObviousNonNewsUrl(
                "https://vendor.example/"));
        assertTrue(AiNewsDiscoverySearchService.isObviousNonNewsUrl(
                "https://vendor.example/docs/latest/release"));
        assertTrue(AiNewsDiscoverySearchService.isObviousNonNewsUrl(
                "https://vendor.example/reports/benchmark.pdf"));
        assertTrue(AiNewsDiscoverySearchService.isObviousNonNewsUrl(
                "https://cloud.example/document/product/terms"));
        assertTrue(AiNewsDiscoverySearchService.isObviousNonNewsUrl(
                "https://openai.com/research"));
        assertTrue(AiNewsDiscoverySearchService.isObviousNonNewsUrl(
                "https://www.instagram.com/reel/example"));
        assertTrue(AiNewsDiscoverySearchService.isObviousNonNewsUrl(
                "https://www.linkedin.com/posts/example"));
        assertTrue(AiNewsDiscoverySearchService.isObviousNonNewsUrl(
                "https://caifuhao.eastmoney.com/news/20260901062720301718000"));
        assertTrue(AiNewsDiscoverySearchService.isObviousNonNewsUrl(
                "https://www.laohu8.com/post/ai-market-commentary"));
        assertFalse(AiNewsDiscoverySearchService.isObviousNonNewsUrl(
                "https://vendor.example/newsroom/model-release"));
    }

    @Test
    void explicitCallsToRegisterForEventsAreNotNewsCandidates() {
        assertTrue(AiNewsDiscoverySearchService.isObviousPromotion(SearchResult.builder()
                .title("Join us at AI Summit — register now")
                .snippet("Buy tickets for the conference")
                .build()));
        assertFalse(AiNewsDiscoverySearchService.isObviousPromotion(SearchResult.builder()
                .title("Company launches new AI model after annual summit")
                .snippet("The release is available today")
                .build()));
        assertTrue(AiNewsDiscoverySearchService.isObviousPromotion(SearchResult.builder()
                .title("TechCrunch Disrupt 2026 Will Explore the Future of AI Startups")
                .snippet("The event returns this fall")
                .build()));
    }

    @Test
    void obviousRecapsAndGenericUndatedLandingPagesAreFilteredConservatively() {
        assertTrue(AiNewsDiscoverySearchService.isObviousNonEventContent(SearchResult.builder()
                .title("Here’s all the times AI has gone rogue and hacked companies").build()));
        assertTrue(AiNewsDiscoverySearchService.isObviousNonEventContent(SearchResult.builder()
                .title("Agentic AI in the Enterprise Part 2: Guidance by Persona").build()));
        assertTrue(AiNewsDiscoverySearchService.isObviousNonEventContent(SearchResult.builder()
                .title("Ox Alpha 身份揭晓；AI 已迈过商业化拐点；机器人公司完成融资 ...")
                .build()));
        assertTrue(AiNewsDiscoverySearchService.isObviousNonEventContent(SearchResult.builder()
                .title("小鹏机器人完成融资；北京君正上市丨全球投融资周报08.22-08.28")
                .build()));
        assertTrue(AiNewsDiscoverySearchService.isObviousNonEventContent(SearchResult.builder()
                .title("人工智能产业日报(08.28)：AI行业动态").build()));
        assertTrue(AiNewsDiscoverySearchService.isObviousNonEventContent(SearchResult.builder()
                .title("新浪芯片热点小时报丨2026年08月29日20时").build()));
        assertTrue(AiNewsDiscoverySearchService.isObviousNonEventContent(SearchResult.builder()
                .title("金融产业日报(08.27)：行业动态").build()));
        assertTrue(AiNewsDiscoverySearchService.isObviousNonEventContent(SearchResult.builder()
                .title("德邦证券-政策半月谈(第1期)：人工智能政策全梳理").build()));
        assertTrue(AiNewsDiscoverySearchService.isObviousNonEventContent(SearchResult.builder()
                .title("企业级 AI 智能体平台解析：选型指南、能力对比与未来趋势").build()));
        assertTrue(AiNewsDiscoverySearchService.isObviousNonEventContent(SearchResult.builder()
                .title("Garmin Cirqa Review: A Fine But Utterly Inessential Smart Band").build()));
        assertTrue(AiNewsDiscoverySearchService.isObviousNonEventContent(SearchResult.builder()
                .title("Not Nvidia, Not AMD. Micron Could Be September's Biggest AI Winner or Loser.")
                .build()));
        assertTrue(AiNewsDiscoverySearchService.isObviousNonEventContent(SearchResult.builder()
                .title("The Index-Fund Strategy Quietly Beating Big Tech This Year").build()));
        assertTrue(AiNewsDiscoverySearchService.isObviousNonEventContent(SearchResult.builder()
                .title("2026年人工智能技术特点及投资分析与全球金融核心数据").build()));
        assertTrue(AiNewsDiscoverySearchService.isObviousNonEventContent(SearchResult.builder()
                .title("2026年人工智能芯片行业现状深度调研及发展趋势预测").build()));
        assertTrue(AiNewsDiscoverySearchService.isObviousNonEventContent(SearchResult.builder()
                .title("Artificial Intelligence in Agriculture Market Forecasted to Reach USD 35 Billion with 23% CAGR by 2035")
                .build()));
        assertTrue(AiNewsDiscoverySearchService.isObviousNonEventContent(SearchResult.builder()
                .title("2026年人工智能芯片市场前景分析预测 - 市场现状调查与未来发展趋势报告")
                .build()));
        assertTrue(AiNewsDiscoverySearchService.isObviousNonEventContent(SearchResult.builder()
                .title("人工智能安全治理研究报告(2026年)_报告网").build()));
        assertTrue(AiNewsDiscoverySearchService.isObviousNonEventContent(SearchResult.builder()
                .title("算力产业链爆发,行情又要回来了吗?|AI|基金|存储芯片").build()));
        assertFalse(AiNewsDiscoverySearchService.isObviousNonEventContent(SearchResult.builder()
                .title("人民日报：公司发布新一代人工智能模型").build()));
        assertFalse(AiNewsDiscoverySearchService.isObviousNonEventContent(SearchResult.builder()
                .title("OpenAI launches a model; CEO explains the safety plan").build()),
                "one semicolon may still describe one atomic event");
        assertFalse(AiNewsDiscoverySearchService.isObviousNonEventContent(SearchResult.builder()
                .title("Revenue rises; margin improves; guidance raised").build()),
                "multiple facts about one event are not a roundup without a roundup marker");
        assertFalse(AiNewsDiscoverySearchService.isObviousNonEventContent(SearchResult.builder()
                .title("Company launches Agentic AI Enterprise 2").build()));
        assertTrue(AiNewsDiscoverySearchService.isObviousUndatedLandingUrl(
                "https://aws.amazon.com/vi/ai/infrastructure?pg=ln&sec=uc"));
        assertFalse(AiNewsDiscoverySearchService.isObviousUndatedLandingUrl(
                "https://aws.amazon.com/blogs/machine-learning/new-model-release"));
        assertFalse(AiNewsDiscoverySearchService.isObviousUndatedLandingUrl(
                "https://openai.com/index/introducing-a-specific-model"));
    }

    @Test
    void requiresHeadlineOrLeadEvidenceThatTheCardIsAboutAiNews() {
        assertTrue(AiNewsDiscoverySearchService.isTopicallyRelevantAiNews(SearchResult.builder()
                .title("Anthropic resumes external testing after security incidents").build()));
        assertTrue(AiNewsDiscoverySearchService.isTopicallyRelevantAiNews(SearchResult.builder()
                .title("Texas launches Project Watershed 250")
                .snippet("The administration is launching artificial intelligence tools to protect water systems.")
                .build()));
        assertTrue(AiNewsDiscoverySearchService.isTopicallyRelevantAiNews(SearchResult.builder()
                .title("3 new ways to plan and book travel in Search")
                .url("https://blog.google/products/search/book-travel-ai-mode")
                .snippet("Book hotels and track airfares with AI Mode in Google Search.")
                .build()));
        assertTrue(AiNewsDiscoverySearchService.isTopicallyRelevantAiNews(SearchResult.builder()
                .title("Metriport Raises $26M for Clinical Intelligence")
                .snippet("The company secured new funding. " + "healthcare data ".repeat(30)
                        + "It will expand its artificial intelligence capabilities.")
                .build()));
        assertTrue(AiNewsDiscoverySearchService.isTopicallyRelevantAiNews(SearchResult.builder()
                .title("Chipmaker beats forecasts")
                .snippet("The company forecast revenue growth after an AI‑fueled rally in custom silicon demand.")
                .build()));
        assertFalse(AiNewsDiscoverySearchService.isTopicallyRelevantAiNews(SearchResult.builder()
                .title("VLC crosses 7 billion downloads")
                .snippet("The media player reached a milestone. A related story farther down mentions AI.")
                .build()));
        assertFalse(AiNewsDiscoverySearchService.isTopicallyRelevantAiNews(SearchResult.builder()
                .title("California opposes Paramount bond request")
                .snippet("The court filing concerns a studio acquisition. Reuters and AI.")
                .build()));
    }

    @Test
    void extractsOnlyConservativeSnippetHeaderDates() {
        assertEquals("Jul 22, 2026",
                AiNewsDiscoverySearchService.conservativeSnippetPublicationHint(
                        "Jul 22, 2026 ... A proven enterprise product for putting AI agents to work"));
        assertEquals("August 8, 2024",
                AiNewsDiscoverySearchService.conservativeSnippetPublicationHint(
                        "# Figure robot August 8, 2024 by Scott Martin 0 Comments"));
        assertEquals("March 18, 2024",
                AiNewsDiscoverySearchService.conservativeSnippetPublicationHint(
                        "SAN JOSE, Calif., March 18, 2024 (GLOBE NEWSWIRE) -- NVIDIA announced"));
        assertEquals("2026年8月27日",
                AiNewsDiscoverySearchService.conservativeSnippetPublicationHint(
                        "发布时间：2026年8月27日 正文"));
        assertEquals("8月22日",
                AiNewsDiscoverySearchService.conservativeSnippetPublicationHint(
                        "8月22日，以人工智能为主题的大会正式举行"));
        assertEquals(AiNewsDiscoverySearchService.TemporalStatus.OUTSIDE_WINDOW,
                AiNewsDiscoverySearchService.publicationHintStatus("8月22日",
                        Instant.parse("2026-08-27T16:00:00Z"),
                        Instant.parse("2026-08-28T16:00:00Z"),
                        Instant.parse("2026-08-28T17:00:00Z")));
        assertEquals(null, AiNewsDiscoverySearchService.conservativeSnippetPublicationHint(
                "The company referenced an older August 8, 2024 product in the article body"));
        assertEquals(null, AiNewsDiscoverySearchService.conservativeSnippetPublicationHint(
                "August 8, 2024 product details remain available for customers"));
    }

    @Test
    void leadingSearchCardDateRejectsStaleCandidateButKeepsFrozenRawRows() {
        WebSearchService search = mock(WebSearchService.class);
        when(search.searchCandidates(any(SearchQuery.class))).thenReturn(batch(
                SearchResult.builder()
                        .title("Introducing OpenAI Presence")
                        .url("https://openai.com/index/introducing-openai-presence")
                        .snippet("Jul 22, 2026 ... A proven enterprise product for putting AI agents to work")
                        .relevanceScore(0.9D).build()));
        AiNewsDiscoverySearchService service = new AiNewsDiscoverySearchService(
                search, new AiNewsSourceRegistry());

        var output = service.discover("artificial intelligence",
                Instant.parse("2026-08-27T16:00:00Z"),
                Instant.parse("2026-08-28T16:00:00Z"), 30);

        assertTrue(output.candidates().isEmpty());
        assertEquals(10, output.querySnapshots().stream()
                .mapToInt(snapshot -> snapshot.results().size()).sum());
        assertTrue(output.diagnostics().get("rejectedPublicationOutsideWindow") > 0);
    }

    @Test
    void sourceHeaderDateOverridesMutableProviderIndexTimestamp() {
        WebSearchService search = mock(WebSearchService.class);
        when(search.searchCandidates(any(SearchQuery.class))).thenReturn(batch(
                SearchResult.builder()
                        .title("AI 数据中心安全白皮书正式发布")
                        .url("https://example.com/news/security-whitepaper")
                        .date("2026-08-28T00:00:00Z")
                        .snippet("8月22日，以算力安全为主题的白皮书正式发布")
                        .relevanceScore(0.9D).build()));
        AiNewsDiscoverySearchService service = new AiNewsDiscoverySearchService(
                search, new AiNewsSourceRegistry());

        var output = service.discover("artificial intelligence",
                Instant.parse("2026-08-27T16:00:00Z"),
                Instant.parse("2026-08-28T16:00:00Z"), 30);

        assertTrue(output.candidates().isEmpty());
        assertTrue(output.diagnostics().get("rejectedPublicationOutsideWindow") > 0);
    }

    @Test
    void trustOnlyBreaksTiesAndCannotPromoteADeepOfficialResultOverTopicalRankOne() {
        WebSearchService search = mock(WebSearchService.class);
        AtomicBoolean officialSupplied = new AtomicBoolean();
        AtomicBoolean newsSupplied = new AtomicBoolean();
        when(search.searchCandidates(any(SearchQuery.class))).thenAnswer(invocation -> {
            SearchQuery query = invocation.getArgument(0);
            if (!query.includeDomains().isEmpty() && officialSupplied.compareAndSet(false, true)) {
                List<SearchResult> deepOfficial = new ArrayList<>();
                for (int i = 0; i < 19; i++) {
                    deepOfficial.add(result("ignored", "", 0.9D));
                }
                deepOfficial.add(result("stale official AI background page",
                        "https://openai.com/background", 0.9D));
                return new WebSearchService.SearchBatch("tavily", false, deepOfficial, "");
            }
            if (query.includeDomains().isEmpty() && newsSupplied.compareAndSet(false, true)) {
                return batch(result("current vertical AI news event",
                        "https://unregistered.example/current-event", 0.5D));
            }
            return new WebSearchService.SearchBatch("tavily", false, List.of(), "");
        });
        AiNewsDiscoverySearchService service = new AiNewsDiscoverySearchService(
                search, new AiNewsSourceRegistry());

        var output = service.discover("artificial intelligence",
                Instant.parse("2026-08-26T03:15:40Z"),
                Instant.parse("2026-08-27T03:15:40Z"), 30);

        assertEquals("https://unregistered.example/current-event",
                output.candidates().getFirst().url());
        assertTrue(output.candidates().get(1).officialDomain());
    }

    @Test
    void parseableOutsideWindowHintsAreRejectedWithoutDroppingBoundaryDates() {
        Instant start = Instant.parse("2026-08-26T03:15:40Z");
        Instant end = Instant.parse("2026-08-27T03:15:40Z");

        assertTrue(AiNewsDiscoverySearchService.hasPublicationHintOutsideWindow(
                "Tue, 25 Aug 2026 13:00:00 GMT", start, end));
        assertTrue(AiNewsDiscoverySearchService.hasPublicationHintOutsideWindow(
                "2026-08-27T04:36:06Z", start, end));
        assertFalse(AiNewsDiscoverySearchService.hasPublicationHintOutsideWindow(
                "2026-08-26T15:00:00Z", start, end));
        assertFalse(AiNewsDiscoverySearchService.hasPublicationHintOutsideWindow(
                "2026-08-27", start, end),
                "date-only boundary hints overlap the window and must remain candidates");
        assertFalse(AiNewsDiscoverySearchService.hasPublicationHintOutsideWindow(
                "unknown", start, end));

        WebSearchService search = mock(WebSearchService.class);
        when(search.searchCandidates(any(SearchQuery.class))).thenReturn(new WebSearchService.SearchBatch(
                "tavily", false, List.of(
                result("stale AI event but highly fused", "https://example.com/stale", 0.99D,
                        "Tue, 25 Aug 2026 13:00:00 GMT"),
                result("current lower-ranked AI event", "https://example.com/current", 0.70D,
                        "2026-08-26T15:00:00Z")), ""));
        AiNewsDiscoverySearchService service = new AiNewsDiscoverySearchService(
                search, new AiNewsSourceRegistry());

        var output = service.discover("artificial intelligence", start, end, 30);

        assertEquals(1, output.candidates().size());
        assertEquals("https://example.com/current", output.candidates().getFirst().url());
        assertEquals(AiNewsDiscoverySearchService.TemporalStatus.IN_WINDOW,
                output.candidates().getFirst().temporalStatus());
        assertEquals(1, output.diagnostics().get("rejectedPublicationOutsideWindow"));
    }

    @Test
    void parsesObservedLiveDateFormatsAndRelativeHintsAgainstFrozenClock() {
        Instant start = Instant.parse("2026-08-27T02:00:00Z");
        Instant end = Instant.parse("2026-08-28T02:00:00Z");
        Instant observed = Instant.parse("2026-08-28T01:00:00Z");

        assertEquals(AiNewsDiscoverySearchService.TemporalStatus.IN_WINDOW,
                AiNewsDiscoverySearchService.publicationHintStatus(
                        "Aug 27, 2026", start, end, observed));
        assertEquals(AiNewsDiscoverySearchService.TemporalStatus.IN_WINDOW,
                AiNewsDiscoverySearchService.publicationHintStatus(
                        "发布于 2026年8月27日", start, end, observed));
        assertEquals(AiNewsDiscoverySearchService.TemporalStatus.IN_WINDOW,
                AiNewsDiscoverySearchService.publicationHintStatus(
                        "3 hours ago", start, end, observed));
        assertEquals(AiNewsDiscoverySearchService.TemporalStatus.OUTSIDE_WINDOW,
                AiNewsDiscoverySearchService.publicationHintStatus(
                        "3 days ago", start, end, observed));
        assertEquals(AiNewsDiscoverySearchService.TemporalStatus.OUTSIDE_WINDOW,
                AiNewsDiscoverySearchService.publicationHintStatus(
                        "3天前", start, end, observed));
        assertEquals(AiNewsDiscoverySearchService.TemporalStatus.UNKNOWN,
                AiNewsDiscoverySearchService.publicationHintStatus(
                        "recently updated", start, end, observed));
    }

    @Test
    void undatedOpenWebRowsUseExplorationQuotaInsteadOfPaddingTopThirty() {
        WebSearchService search = mock(WebSearchService.class);
        List<SearchResult> undated = java.util.stream.IntStream.range(0, 10)
                .mapToObj(index -> result("undated AI candidate " + index,
                        "https://publisher-" + index + ".example/story", 1.0D - index / 100.0D,
                        null))
                .toList();
        when(search.searchCandidates(any(SearchQuery.class))).thenReturn(
                new WebSearchService.SearchBatch("tavily", false, undated, ""));
        AiNewsDiscoveryProperties properties = new AiNewsDiscoveryProperties();
        properties.setMaxUnknownPercent(100);
        properties.setUnknownOpenWebPercent(10);
        AiNewsDiscoverySearchService service = new AiNewsDiscoverySearchService(
                search, new AiNewsSourceRegistry(), properties,
                Clock.fixed(Instant.parse("2026-08-28T01:00:00Z"), ZoneOffset.UTC));

        var output = service.discover("artificial intelligence",
                Instant.parse("2026-08-27T02:00:00Z"),
                Instant.parse("2026-08-28T02:00:00Z"), 30);

        assertEquals(3, output.candidates().size(), "10% unknown-open exploration quota");
        assertTrue(output.candidates().stream().allMatch(candidate ->
                candidate.temporalStatus() == AiNewsDiscoverySearchService.TemporalStatus.UNKNOWN));
        assertTrue(output.candidates().stream().allMatch(candidate ->
                "unknown_open_web".equals(candidate.selectionLane())));
        assertEquals(7, output.diagnostics().get("rejectedExplorationQuota"));
    }

    @Test
    void defaultUnknownQuotaKeepsUndatedRowsOutOfTheAutomaticQueue() {
        WebSearchService search = mock(WebSearchService.class);
        when(search.searchCandidates(any(SearchQuery.class))).thenAnswer(invocation -> {
            SearchQuery query = invocation.getArgument(0);
            String host = query.includeDomains().isEmpty() ? "unregistered.example" :
                    query.topic().equals("news") ? "techcrunch.com" : "openai.com";
            return batch(result("undated AI " + query.query(),
                    "https://" + host + "/story-" + Math.abs(query.query().hashCode()), 0.8D, null));
        });
        AiNewsDiscoverySearchService service = new AiNewsDiscoverySearchService(
                search, new AiNewsSourceRegistry());

        var output = service.discover("artificial intelligence",
                Instant.parse("2026-08-27T02:00:00Z"),
                Instant.parse("2026-08-28T02:00:00Z"), 30);

        assertTrue(output.candidates().isEmpty());
        assertEquals(0, output.diagnostics().get("selectedUnknownCandidates"));
        assertTrue(output.diagnostics().get("rejectedUnknownAggregateQuota") > 0);
    }

    @Test
    void explicitOldUrlOrSnippetDateSuppressesUndatedAliasesOfSameTitle() {
        WebSearchService search = mock(WebSearchService.class);
        when(search.searchCandidates(any(SearchQuery.class))).thenReturn(batch(
                SearchResult.builder()
                        .title("NVIDIA Corporation - Project GR00T Foundation Model")
                        .url("https://investor.nvidia.com/press/2024/project-groot")
                        .snippet("March 18, 2024 (GLOBE NEWSWIRE) -- NVIDIA announced")
                        .relevanceScore(0.9D).build(),
                SearchResult.builder()
                        .title("Project GR00T Foundation Model | NVIDIA Newsroom")
                        .url("https://nvidianews.nvidia.com/news/project-groot")
                        .snippet("An undated alias of the same release")
                        .relevanceScore(0.8D).build(),
                SearchResult.builder()
                        .title("Figure unveils robot")
                        .url("https://blogs.nvidia.com/blog/figure-robot")
                        .snippet("August 8, 2024 by Scott Martin")
                        .relevanceScore(0.7D).build()));
        AiNewsDiscoverySearchService service = new AiNewsDiscoverySearchService(
                search, new AiNewsSourceRegistry());

        var output = service.discover("artificial intelligence",
                Instant.parse("2026-08-27T02:00:00Z"),
                Instant.parse("2026-08-28T02:00:00Z"), 30);

        assertTrue(output.candidates().isEmpty());
        assertTrue(output.diagnostics().get("rejectedStaleAlias") > 0);
        assertTrue(output.diagnostics().get("rejectedPublicationOutsideWindow") > 0);
    }

    @Test
    void oneHostCannotMonopoliseUnknownOfficialCaptureQueue() {
        WebSearchService search = mock(WebSearchService.class);
        List<SearchResult> undated = java.util.stream.IntStream.range(0, 10)
                .mapToObj(index -> result("official AI candidate " + index,
                        "https://openai.com/news/story-" + index, 1.0D - index / 100.0D, null))
                .toList();
        when(search.searchCandidates(any(SearchQuery.class))).thenReturn(
                new WebSearchService.SearchBatch("tavily", false, undated, ""));
        AiNewsDiscoveryProperties properties = new AiNewsDiscoveryProperties();
        properties.setMaxUnknownPercent(100);
        AiNewsDiscoverySearchService service = new AiNewsDiscoverySearchService(
                search, new AiNewsSourceRegistry(), properties,
                Clock.fixed(Instant.parse("2026-08-28T01:00:00Z"), ZoneOffset.UTC));

        var output = service.discover("artificial intelligence",
                Instant.parse("2026-08-27T02:00:00Z"),
                Instant.parse("2026-08-28T02:00:00Z"), 30);

        assertEquals(4, output.candidates().size());
        assertEquals(6, output.diagnostics().get("rejectedHostLimit"));
        assertEquals(1, output.diagnostics().get("selectedDistinctHosts"));
    }

    @Test
    void emitsContentAddressedSnapshotAndRankingIdentity() {
        WebSearchService search = mock(WebSearchService.class);
        when(search.searchCandidates(any(SearchQuery.class))).thenReturn(
                new WebSearchService.SearchBatch("tavily", true, List.of(result(
                        "current AI model event", "https://example.com/2026/08/27/event",
                        0.9D, "2026-08-27T10:00:00Z")), ""));
        AiNewsDiscoveryProperties properties = new AiNewsDiscoveryProperties();
        AiNewsDiscoverySearchService service = new AiNewsDiscoverySearchService(
                search, new AiNewsSourceRegistry(), properties,
                Clock.fixed(Instant.parse("2026-08-28T01:00:00Z"), ZoneOffset.UTC));

        var first = service.discover("artificial intelligence",
                Instant.parse("2026-08-27T02:00:00Z"),
                Instant.parse("2026-08-28T02:00:00Z"), 30);
        var second = service.discover("artificial intelligence",
                Instant.parse("2026-08-27T02:00:00Z"),
                Instant.parse("2026-08-28T02:00:00Z"), 30);
        var replay = service.replay("artificial intelligence", first, 30);

        assertEquals(first.snapshotHash(), second.snapshotHash());
        assertEquals(first.rankingHash(), second.rankingHash());
        assertEquals(first.snapshotHash(), replay.snapshotHash());
        assertEquals(first.rankingHash(), replay.rankingHash());
        assertEquals(first.candidates(), replay.candidates());
        assertFalse(replay.snapshotPersisted());
        assertTrue(first.rankingPolicyVersion().startsWith(
                AiNewsDiscoverySearchService.RANKING_POLICY_BASE));
        assertTrue(first.executions().stream().allMatch(AiNewsDiscoverySearchService.QueryExecution::fromCache));
        assertTrue(first.executions().stream().allMatch(execution -> execution.resultHash().length() == 64));
        assertEquals(10, first.querySnapshots().size());
        assertTrue(first.querySnapshots().stream().allMatch(snapshot ->
                snapshot.results().size() == 1 && snapshot.resultHash().length() == 64));
        verify(search, times(20)).searchCandidates(any(SearchQuery.class));
    }

    @Test
    void replayRejectsSnapshotWhoseStoredContentHashDoesNotMatch() {
        WebSearchService search = mock(WebSearchService.class);
        when(search.searchCandidates(any(SearchQuery.class))).thenReturn(
                new WebSearchService.SearchBatch("tavily", false, List.of(result(
                        "current AI model event", "https://example.com/2026/08/27/event",
                        0.9D, "2026-08-27T10:00:00Z")), ""));
        AiNewsDiscoverySearchService service = new AiNewsDiscoverySearchService(
                search, new AiNewsSourceRegistry(), new AiNewsDiscoveryProperties(),
                Clock.fixed(Instant.parse("2026-08-28T01:00:00Z"), ZoneOffset.UTC));
        var original = service.discover("artificial intelligence",
                Instant.parse("2026-08-27T02:00:00Z"),
                Instant.parse("2026-08-28T02:00:00Z"), 30);
        var snapshots = new ArrayList<>(original.querySnapshots());
        var first = snapshots.getFirst();
        snapshots.set(0, new AiNewsDiscoverySearchService.QuerySnapshot(
                first.family(), first.providerId(), first.fromCache(), "0".repeat(64),
                first.requestedQuery(), first.requestedSearchTopic(), first.requestedStartDate(),
                first.requestedEndDate(), first.requestedIncludeDomains(), first.results()));
        var tampered = new AiNewsDiscoverySearchService.DiscoveryBatch(
                original.mode(), original.evidenceEligible(), original.windowStart(), original.windowEnd(),
                original.queryCount(), original.uniqueUrlCount(), original.candidates(),
                original.executions(), original.structuredSourceCount(), original.message(),
                original.observedAt(), original.rankingPolicyVersion(), original.snapshotHash(),
                original.rankingHash(), original.diagnostics(), snapshots,
                original.discoveryRunId(), original.snapshotPersisted());

        assertThrows(IllegalArgumentException.class,
                () -> service.replay("artificial intelligence", tampered, 30));
    }

    @Test
    void replayPreservesFrozenProviderFailureDiagnostics() {
        WebSearchService search = mock(WebSearchService.class);
        AtomicBoolean firstCall = new AtomicBoolean(true);
        when(search.searchCandidates(any(SearchQuery.class))).thenAnswer(invocation -> {
            if (firstCall.getAndSet(false)) {
                return WebSearchService.SearchBatch.unavailable("tavily", "simulated timeout",
                        List.of(new WebSearchService.ProviderFailure("tavily", "timeout")));
            }
            return new WebSearchService.SearchBatch("tavily", false, List.of(result(
                    "current AI model event", "https://example.com/2026/08/27/event",
                    0.9D, "2026-08-27T10:00:00Z")), "");
        });
        AiNewsDiscoverySearchService service = new AiNewsDiscoverySearchService(
                search, new AiNewsSourceRegistry(), new AiNewsDiscoveryProperties(),
                Clock.fixed(Instant.parse("2026-08-28T01:00:00Z"), ZoneOffset.UTC));

        var live = service.discover("artificial intelligence",
                Instant.parse("2026-08-27T02:00:00Z"),
                Instant.parse("2026-08-28T02:00:00Z"), 30);
        var replay = service.replay("artificial intelligence", live, 30);

        assertEquals(live.executions().getFirst().failureMessage(),
                replay.executions().getFirst().failureMessage());
        assertTrue(replay.executions().getFirst().failureMessage().contains("timeout"));
    }

    @Test
    void configuredRssIsFusedWithoutConsumingAWebSearchCredit() {
        WebSearchService search = mock(WebSearchService.class);
        when(search.searchCandidates(any(SearchQuery.class))).thenReturn(
                new WebSearchService.SearchBatch("tavily", false, List.of(
                        result("AI startup raises funding",
                                "https://techcrunch.com/2026/08/26/ai-funding", 0.8D, null)), ""));
        NewsSourceProvider rss = new NewsSourceProvider() {
            @Override public String providerId() { return "rss"; }
            @Override public List<NewsSourceResult> search(
                    vip.newsclaw.news.source.NewsSourceQuery query) {
                assertEquals(100, query.limit());
                assertEquals(Instant.parse("2026-08-25T03:15:40Z"), query.since());
                return List.of(new NewsSourceResult("AI startup raises funding", "AI funding event", "",
                        new NewsSourceProvenance("rss", "media",
                                "https://techcrunch.com/2026/08/26/ai-funding/?utm_source=rss",
                                "https://techcrunch.com/2026/08/26/ai-funding", Instant.now(),
                                200, "RSS_SEARCH",
                                Map.of("publishedAt", "2026-08-26T08:00:00Z"))));
            }
            @Override public Optional<NewsSourceResult> fetch(URI url) { return Optional.empty(); }
            @Override public NewsSourceHealth health() { return NewsSourceHealth.healthy("rss", 1); }
        };
        AiNewsDiscoverySearchService service = new AiNewsDiscoverySearchService(
                search, new AiNewsSourceRegistry());
        service.setNewsSourceProviderRegistry(new NewsSourceProviderRegistry(List.of(rss)));

        var output = service.discover("artificial intelligence",
                Instant.parse("2026-08-26T03:15:40Z"),
                Instant.parse("2026-08-27T03:15:40Z"), 30);

        assertEquals(10, output.queryCount(), "RSS must not be charged as a web-search lane");
        assertEquals(1, output.structuredSourceCount());
        assertEquals(1, output.candidates().size());
        assertTrue(output.candidates().getFirst().queryFamilies().contains("structured_rss"));
        assertEquals("2026-08-26T08:00:00Z",
                output.candidates().getFirst().publishedAtHint(),
                "structured timestamp must survive a merge with a scored web result");
        verify(search, times(10)).searchCandidates(any(SearchQuery.class));
    }

    @Test
    void allStructuredProviderChannelsAreFusedWithoutAddingWebQueries() {
        WebSearchService search = mock(WebSearchService.class);
        when(search.searchCandidates(any(SearchQuery.class))).thenReturn(
                new WebSearchService.SearchBatch("tavily", false, List.of(), ""));
        NewsSourceProvider sitemap = new NewsSourceProvider() {
            @Override public String providerId() { return "news-sitemap"; }
            @Override public NewsSourceChannel channel() { return NewsSourceChannel.SITEMAP; }
            @Override public List<NewsSourceResult> search(
                    vip.newsclaw.news.source.NewsSourceQuery query) {
                return List.of(new NewsSourceResult("AI infrastructure launch", "Official news", "",
                        new NewsSourceProvenance("news-sitemap", "official",
                                "https://aws.amazon.com/blogs/machine-learning/launch",
                                "https://aws.amazon.com/blogs/machine-learning/launch", Instant.now(),
                                200, "NEWS_SITEMAP_DISCOVERY",
                                Map.of("publishedAt", "2026-08-26T08:00:00Z"))));
            }
            @Override public Optional<NewsSourceResult> fetch(URI url) { return Optional.empty(); }
            @Override public NewsSourceHealth health() {
                return NewsSourceHealth.healthy("news-sitemap", 1);
            }
        };
        AiNewsDiscoverySearchService service = new AiNewsDiscoverySearchService(
                search, new AiNewsSourceRegistry());
        service.setNewsSourceProviderRegistry(new NewsSourceProviderRegistry(List.of(sitemap)));

        var output = service.discover("artificial intelligence",
                Instant.parse("2026-08-26T03:15:40Z"),
                Instant.parse("2026-08-27T03:15:40Z"), 30);

        assertEquals(10, output.queryCount());
        assertEquals(1, output.structuredSourceCount());
        assertTrue(output.candidates().getFirst().queryFamilies()
                .contains("structured_news_sitemap"));
        verify(search, times(10)).searchCandidates(any(SearchQuery.class));
    }

    @Test
    void scheduledProvidersAreReadFromDurableProjectionWithoutRequestTimePolling() {
        WebSearchService search = mock(WebSearchService.class);
        when(search.searchCandidates(any(SearchQuery.class))).thenReturn(
                new WebSearchService.SearchBatch("tavily", false, List.of(), ""));
        ScheduledNewsSourceProvider scheduled = mock(ScheduledNewsSourceProvider.class);
        when(scheduled.providerId()).thenReturn("rss");
        when(scheduled.channel()).thenReturn(NewsSourceChannel.FEED);
        AiNewsStructuredIngestionService ingestion = mock(AiNewsStructuredIngestionService.class);
        when(ingestion.persistentMainlineEnabled()).thenReturn(true);
        when(ingestion.recentCandidates(any(Instant.class), eq(500), eq(true))).thenReturn(List.of(
                new NewsSourceResult("AI model agent launch", "Official model update", "",
                        new NewsSourceProvenance("rss", "official",
                                "https://openai.com/news/model-agent-launch",
                                "https://openai.com/news/model-agent-launch", Instant.now(),
                                200, "RSS_SEARCH",
                                Map.of("publishedAt", "2026-08-26T08:00:00Z")))));
        AiNewsDiscoverySearchService service = new AiNewsDiscoverySearchService(
                search, new AiNewsSourceRegistry());
        service.setNewsSourceProviderRegistry(new NewsSourceProviderRegistry(List.of(scheduled)));
        service.setStructuredIngestionService(ingestion);

        var output = service.discover("artificial intelligence",
                Instant.parse("2026-08-26T03:15:40Z"),
                Instant.parse("2026-08-27T03:15:40Z"), 30);

        assertEquals(1, output.structuredSourceCount());
        assertEquals("https://openai.com/news/model-agent-launch",
                output.candidates().getFirst().url());
        assertTrue(output.candidates().getFirst().queryFamilies().contains("structured_rss"));
        verify(ingestion).recentCandidates(
                Instant.parse("2026-08-25T03:15:40Z"), 500, true);
        verify(scheduled, never()).search(any());
        verify(scheduled, never()).poll(any(), any());
    }

    @Test
    void publisherBoilerplateDoesNotLeakCrossHostDuplicateCards() {
        assertEquals(1.0D, AiNewsDiscoverySearchService.titleSimilarity(
                "NVIDIA Corporation - NVIDIA Releases New Physical AI Models as Global Partners Unveil Next-Generation Robots",
                "NVIDIA Releases New Physical AI Models as Global Partners Unveil Next-Generation Robots | NVIDIA Newsroom"));
        assertTrue(AiNewsDiscoverySearchService.titleSimilarity(
                "NVIDIA Releases New Physical AI Models as Global Partners Unveil Next-Generation Robots",
                "NVIDIA and Global Robotics Leaders Take Physical AI to the Real World") < 0.91D,
                "different NVIDIA announcements must remain separate events");
        assertEquals(1.0D, AiNewsDiscoverySearchService.titleSimilarity(
                "工业智能体走向生产环节,竞争转向工程化落地能力_腾讯新闻",
                "工业智能体走向生产环节,竞争转向工程化落地能力|界面新闻 · 科技"));
    }

    @Test
    void crossSiteDuplicatePrefersOfficialStructuredFeedUrlWithTimestamp() {
        WebSearchService search = mock(WebSearchService.class);
        AtomicBoolean supplied = new AtomicBoolean();
        when(search.searchCandidates(any(SearchQuery.class))).thenAnswer(invocation ->
                supplied.compareAndSet(false, true)
                        ? batch(result(
                        "NVIDIA Corporation - NVIDIA Releases New Physical AI Models as Global Partners Unveil Next-Generation Robots",
                        "https://investor.nvidia.com/news/press-release-details/2026/models/default.aspx",
                        0.99D, null))
                        : new WebSearchService.SearchBatch("tavily", false, List.of(), ""));
        NewsSourceProvider rss = new NewsSourceProvider() {
            @Override public String providerId() { return "rss"; }
            @Override public List<NewsSourceResult> search(
                    vip.newsclaw.news.source.NewsSourceQuery query) {
                return List.of(new NewsSourceResult(
                        "NVIDIA Releases New Physical AI Models as Global Partners Unveil Next-Generation Robots | NVIDIA Newsroom",
                        "Official feed item", "",
                        new NewsSourceProvenance("rss", "official",
                                "https://nvidianews.nvidia.com/news/nvidia-releases-new-physical-ai-models-as-global-partners-unveil-next-generation-robots",
                                "https://nvidianews.nvidia.com/news/nvidia-releases-new-physical-ai-models-as-global-partners-unveil-next-generation-robots",
                                Instant.now(), 200, "RSS_SEARCH",
                                Map.of("publishedAt", "2026-08-26T21:05:00Z"))));
            }
            @Override public Optional<NewsSourceResult> fetch(URI url) { return Optional.empty(); }
            @Override public NewsSourceHealth health() { return NewsSourceHealth.healthy("rss", 1); }
        };
        AiNewsDiscoverySearchService service = new AiNewsDiscoverySearchService(
                search, new AiNewsSourceRegistry());
        service.setNewsSourceProviderRegistry(new NewsSourceProviderRegistry(List.of(rss)));

        var output = service.discover("artificial intelligence",
                Instant.parse("2026-08-26T03:15:40Z"),
                Instant.parse("2026-08-27T03:15:40Z"), 30);

        assertEquals(1, output.candidates().size());
        assertEquals("https://nvidianews.nvidia.com/news/"
                        + "nvidia-releases-new-physical-ai-models-as-global-partners-unveil-next-generation-robots",
                output.candidates().getFirst().url());
        assertEquals("2026-08-26T21:05:00Z",
                output.candidates().getFirst().publishedAtHint());
        assertTrue(output.candidates().getFirst().queryFamilies().contains("structured_rss"));
    }

    private static WebSearchService.SearchBatch batch(SearchResult... results) {
        return new WebSearchService.SearchBatch("tavily", false, List.of(results), "");
    }

    private static SearchResult result(String title, String url, double score) {
        return result(title, url, score, "2026-08-26T10:00:00Z");
    }

    private static SearchResult result(String title, String url, double score, String date) {
        return SearchResult.builder().title(title).url(url).source("example.com")
                .date(date).snippet("candidate only")
                .providerId("tavily").relevanceScore(score).build();
    }
}
