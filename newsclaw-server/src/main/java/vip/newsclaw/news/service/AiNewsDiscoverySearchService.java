package vip.newsclaw.news.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.news.source.NewsSourceProviderRegistry;
import vip.newsclaw.news.source.NewsSourceHashing;
import vip.newsclaw.news.source.NewsSourceQuery;
import vip.newsclaw.news.source.NewsSourceResult;
import vip.newsclaw.news.source.NewsSourceTextMatcher;
import vip.newsclaw.news.source.ScheduledNewsSourceProvider;
import vip.newsclaw.tool.builtin.WebSearchService;
import vip.newsclaw.tool.search.SearchQuery;
import vip.newsclaw.tool.search.SearchResult;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded multi-query retrieval for the AI-news vertical.
 *
 * <p>Official self-reports and third-party news are deliberately searched in
 * separate lanes, then fused with reciprocal-rank fusion. Every output remains
 * an untrusted discovery candidate and must pass source capture before use.</p>
 */
@Service
@Slf4j
public class AiNewsDiscoverySearchService {

    static final int RRF_K = 60;
    static final int DEFAULT_MAX_CANDIDATES = 30;
    static final int MAX_CANDIDATES = 50;
    static final Duration MAX_WINDOW = Duration.ofDays(31);
    static final String RANKING_POLICY_BASE = "discovery-temporal-story-v9";
    private static final Pattern URL_DATE_SEGMENT = Pattern.compile(
            "(?:^|/)(20\\d{2})/(0?[1-9]|1[0-2])/(0?[1-9]|[12]\\d|3[01])(?:/|$)");
    private static final Pattern URL_YEAR_SEGMENT = Pattern.compile("(?:^|/)(20\\d{2})(?:/|$)");
    private static final Pattern ARXIV_ID_PATH = Pattern.compile(
            "^/(?:abs|pdf|papers)/(\\d{2})(0[1-9]|1[0-2])\\.\\d{4,5}(?:v\\d+)?(?:\\.pdf)?/?$");
    private static final Set<String> OBVIOUS_NON_NEWS_SEGMENTS = Set.of(
            "doc", "docs", "document", "documentation", "ai-doc", "terms", "pricing",
            "industries", "on-demand", "glossary", "career", "careers", "jobs",
            "register", "registration", "tickets", "agenda", "speakers", "press-kit");
    private static final Set<String> OBVIOUS_SECTION_LANDING_PATHS = Set.of(
            "/ai", "/blog", "/news", "/product", "/products", "/research");
    private static final Set<String> GENERIC_LANDING_SEGMENTS = Set.of(
            "ai", "ml", "artificial-intelligence", "machine-learning", "generative-ai",
            "infrastructure", "platform", "platforms", "solution", "solutions",
            "product", "products", "service", "services", "enterprise", "cloud",
            "compute", "overview", "capabilities", "innovation");
    private static final Pattern LOCALE_PATH_SEGMENT = Pattern.compile(
            "(?iu)^[a-z]{2}(?:[-_][a-z]{2})?$");
    private static final Set<String> OBVIOUS_NON_ARTICLE_HOSTS = Set.of(
            "instagram.com", "youtube.com", "youtu.be", "tiktok.com", "facebook.com",
            "linkedin.com", "reddit.com", "x.com", "twitter.com",
            "caifuhao.eastmoney.com", "laohu8.com");
    private static final Pattern OBVIOUS_PROMOTION = Pattern.compile(
            "(?iu)(?:\\bregister\\s+now\\b|\\bbuy\\s+tickets?\\b|\\bjoin(?:ing)?\\s+us\\s+(?:at|for)\\b|"
                    + "\\bmeet\\s+us\\s+at\\b|\\bjoin(?:ing)?\\b.{0,80}\\b(?:stage|conference|summit|webinar)\\b|"
                    + "\\b(?:conference|summit|disrupt|stage)\\b.{0,80}\\bwill\\s+(?:explore|feature|host|bring|cover)\\b|"
                    + "立即报名|购票|欢迎参加.{0,30}(?:大会|峰会|研讨会))");
    private static final Pattern OBVIOUS_NON_EVENT_CONTENT = Pattern.compile(
            "(?iu)(?:^\\s*here(?:'|’)?s\\s+all\\s+(?:the\\s+)?times\\b|"
                    + "^\\s*(?:what\\s+is|a\\s+beginner(?:'|’)?s\\s+guide\\s+to)\\b|"
                    + "^\\s*(?:create|build|deploy|configure|use|using|get(?:ting)?\\s+started)\\b|"
                    + "\\bpart\\s+\\d+\\s*:\\s*(?:guidance|guide|tutorial)\\b|"
                    + "\\bis\\s+a\\s+hedge\\s+against\\b|"
                    + "\\b(?:review|hands-on)\\s*[:：]|"
                    + "\\bcould\\s+be\\b.{0,80}\\b(?:winner|loser)\\b|"
                    + "\\bindex[-\\s]+fund\\s+strategy\\b|"
                    + "\\bmarket\\s+(?:forecast|outlook)\\w*\\b.{0,100}\\b(?:reach|cagr|20[3-9]\\d)\\b|"
                    + "\\band\\s+no\\s+[^:|—-]{0,40}\\bnews\\b|"
                    + "^\\s*(?:什么是|如何|一文读懂|新手指南)\\b|"
                    + "(?:平台解析.{0,20}选型指南|选型指南.{0,20}能力对比|能力对比.{0,20}未来趋势)|"
                    + "(?:研究报告|行业报告)\\s*[（(]?20\\d{2}年?[)）]?\\s*(?:_|-|$)|"
                    + "(?:行情|概念股|股票).{0,30}(?:回来|爆发|起飞|买入|抄底).{0,10}(?:吗|[?？])|"
                    + "(?:投资分析|市场前景分析预测|行业现状.{0,30}(?:发展趋势|深度调研)|"
                    + "市场现状调查.{0,30}未来发展趋势|深度调研.{0,30}(?:发展趋势|趋势预测)))");
    private static final Pattern AI_TITLE_SIGNAL = Pattern.compile(
            "(?iu)(?:\\b(?:ai|artificial\\s+intelligence|machine\\s+learning|deep\\s+learning|"
                    + "generative\\s+ai|llms?|large\\s+language\\s+models?|foundation\\s+models?|"
                    + "agentic|chatgpt|claude|gemini|copilot|grok|openai|anthropic|deepmind|xai|"
                    + "mistral|cohere|hugging\\s+face|perplexity|nvidia|robots?|robotics|humanoids?|gpus?)\\b|"
                    + "人工智能|生成式\\s*AI|机器学习|深度学习|神经网络|大模型|基础模型|多模态|"
                    + "智能体|具身智能|机器人|人形机器人|算力|DeepSeek|通义|智谱|科大讯飞)");
    private static final Pattern AI_LEAD_SIGNAL = Pattern.compile(
            "(?iu)(?:\\b(?:artificial\\s+intelligence|machine\\s+learning|deep\\s+learning|"
                    + "generative\\s+ai|llms?|large\\s+language\\s+models?|foundation\\s+models?|"
                    + "ai[-‐‑‒–—\\s]+(?:mode|fueled|powered|driven|enabled|focused|native|models?|hardware|"
                    + "software|chips?|agents?|startups?|companies|tools?|systems?|infrastructure|security)|"
                    + "agentic\\s+ai|openai|anthropic|deepmind|mistral|cohere|hugging\\s+face|"
                    + "perplexity|nvidia|chatgpt|claude|gemini|robots?|robotics|humanoids?)\\b|"
                    + "人工智能|生成式\\s*AI|机器学习|深度学习|神经网络|大模型|基础模型|多模态|"
                    + "智能体|具身智能|机器人|人形机器人|DeepSeek|通义|智谱|科大讯飞)");
    private static final Pattern AI_URL_SIGNAL = Pattern.compile(
            "(?iu)(?:^|[-_/])(?:ai|artificial-intelligence|machine-learning|generative-ai|"
                    + "agentic-ai|llms?)(?:[-_/]|$)");
    private static final Pattern AI_SNIPPET_EVENT = Pattern.compile(
            "(?iu)(?:\\b(?:launch(?:es|ed|ing)?|unveil(?:s|ed|ing)?|releas(?:e|es|ed|ing)|"
                    + "announc(?:e|es|ed|ing)|introduc(?:e|es|ed|ing)|debut(?:s|ed|ing)?|"
                    + "deploy(?:s|ed|ing)?|pilot(?:s|ed|ing)?|test(?:s|ed|ing)?|"
                    + "funding|raises?|raised|acquir(?:e|es|ed|ing)|acquisition|partner(?:s|ed|ing|ship)?|"
                    + "expand(?:s|ed|ing)?|approv(?:e|es|ed|ing)|ban(?:s|ned|ning)?|regulat(?:e|es|ed|ing|ion)|"
                    + "warn(?:s|ed|ing)?|report(?:s|ed|ing)?|forecast(?:s|ed|ing)?|contract|order(?:s|ed|ing)?)\\b|"
                    + "发布|推出|上线|亮相|开放|融资|收购|并购|合作|部署|扩展|获批|禁止|"
                    + "监管|政策|警告|测试|研发|签署|订单|中标|投资|漏洞|攻击|安全事件|升级)");
    private static final Pattern MULTI_STORY_ROUNDUP_TITLE = Pattern.compile(
            "(?iu)(?:^(?=.*(?:\\.\\.\\.|…))(?:[^;；]{4,}[;；]){2,}|"
                    + "\\b(?:daily|weekly)\\s+(?:ai\\s+)?(?:news\\s+)?roundup\\b|"
                    + "每日资讯|新闻早知道|AI日报|资讯速递|"
                    + "(?:全球)?投融资周报|(?:人工智能|AI|芯片)(?:产业)?日报|"
                    + "(?:新浪)?(?:芯片|AI|人工智能)热点小时报|政策半月谈|"
                    + "(?:产业)?日报\\s*[（(]?\\d{1,2}[./月-]\\d{1,2}[)）]?\\s*[:：].*行业动态)");
    private static final String ENGLISH_MONTH_TOKEN =
            "(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|"
                    + "Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|"
                    + "Dec(?:ember)?)";
    private static final String ENGLISH_DATE_TOKEN =
            ENGLISH_MONTH_TOKEN + "\\s+\\d{1,2},\\s+20\\d{2}";
    private static final String ISO_OR_CHINESE_DATE_TOKEN =
            "(?:20\\d{2}-\\d{1,2}-\\d{1,2}|20\\d{2}\\s*年\\s*\\d{1,2}\\s*月\\s*\\d{1,2}\\s*日?)";
    private static final String CHINESE_MONTH_DAY_TOKEN =
            "(?:\\d{1,2}\\s*月\\s*\\d{1,2}\\s*日?)";
    private static final Pattern LABELED_SNIPPET_DATE = Pattern.compile(
            "(?iu)(?:published(?:\\s+on)?|posted(?:\\s+on)?|last\\s+updated|发布日期|发布时间|发布于)"
                    + "\\s*[:：-]?\\s*(" + ENGLISH_DATE_TOKEN + "|"
                    + ISO_OR_CHINESE_DATE_TOKEN + ")");
    private static final Pattern LEADING_SNIPPET_DATE = Pattern.compile(
            "(?iu)^\\s*(" + ENGLISH_DATE_TOKEN + "|" + ISO_OR_CHINESE_DATE_TOKEN
                    + "|" + CHINESE_MONTH_DAY_TOKEN + ")\\s*(?:\\.{3}|…|[-–—|·,，。:：])");
    private static final Pattern BYLINE_SNIPPET_DATE = Pattern.compile(
            "(?iu)\\b(" + ENGLISH_DATE_TOKEN + ")\\s+(?:by|作者[:：])\\s+[\\p{L}]");
    private static final Pattern NEWSWIRE_SNIPPET_DATE = Pattern.compile(
            "(?iu)\\b(" + ENGLISH_DATE_TOKEN + ")\\s*(?:\\([^)]{0,80}"
                    + "(?:newswire|reuters|business\\s+wire)[^)]{0,80}\\)|[-–—]{2})");
    private static final Pattern ENGLISH_DATE = Pattern.compile(
            "(?iu)\\b(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|"
                    + "Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|"
                    + "Dec(?:ember)?)\\s+(\\d{1,2}),\\s+(20\\d{2})\\b");
    private static final Pattern CHINESE_DATE = Pattern.compile(
            "(?u)(20\\d{2})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日?");
    private static final Pattern CHINESE_MONTH_DAY = Pattern.compile(
            "(?u)(?<!\\d)(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日?");
    private static final Pattern ISO_DATE = Pattern.compile(
            "(?<!\\d)(20\\d{2})-(\\d{1,2})-(\\d{1,2})(?!\\d)");
    private static final Pattern ENGLISH_RELATIVE = Pattern.compile(
            "(?iu)\\b(\\d+)\\s*(minute|hour|day|week|month|year)s?\\s+ago\\b");
    private static final Pattern CHINESE_RELATIVE = Pattern.compile(
            "(?u)(\\d+)\\s*(分钟|小时|天|周|个月|月|年)前");
    private static final DateTimeFormatter ENGLISH_DATE_FORMATTER = new DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("MMM d, uuuu").toFormatter(Locale.ENGLISH);
    private static final DateTimeFormatter ENGLISH_LONG_DATE_FORMATTER = new DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("MMMM d, uuuu").toFormatter(Locale.ENGLISH);

    private final WebSearchService webSearchService;
    private final AiNewsSourceRegistry sourceRegistry;
    private final AiNewsDiscoveryProperties properties;
    private final Clock clock;
    private NewsSourceProviderRegistry newsSourceProviderRegistry;
    private AiNewsStructuredIngestionService structuredIngestionService;
    private AiNewsDiscoveryRunLedger discoveryRunLedger;

    public AiNewsDiscoverySearchService(WebSearchService webSearchService,
                                        AiNewsSourceRegistry sourceRegistry) {
        this(webSearchService, sourceRegistry, new AiNewsDiscoveryProperties(), Clock.systemUTC());
    }

    @Autowired
    public AiNewsDiscoverySearchService(WebSearchService webSearchService,
                                        AiNewsSourceRegistry sourceRegistry,
                                        AiNewsDiscoveryProperties properties) {
        this(webSearchService, sourceRegistry, properties, Clock.systemUTC());
    }

    AiNewsDiscoverySearchService(WebSearchService webSearchService,
                                 AiNewsSourceRegistry sourceRegistry,
                                 AiNewsDiscoveryProperties properties,
                                 Clock clock) {
        this.webSearchService = webSearchService;
        this.sourceRegistry = sourceRegistry;
        this.properties = properties == null ? new AiNewsDiscoveryProperties() : properties;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * Keep the structured-source extension optional for narrow unit tests and
     * deployments that have not enabled any feed adapter. Spring injects the
     * registry when present; a blank RSS configuration remains a no-op.
     */
    @Autowired(required = false)
    void setNewsSourceProviderRegistry(NewsSourceProviderRegistry registry) {
        this.newsSourceProviderRegistry = registry;
    }

    @Autowired(required = false)
    void setStructuredIngestionService(AiNewsStructuredIngestionService service) {
        this.structuredIngestionService = service;
    }

    @Autowired(required = false)
    void setDiscoveryRunLedger(AiNewsDiscoveryRunLedger ledger) {
        this.discoveryRunLedger = ledger;
    }

    public DiscoveryBatch discover(String topic, Instant windowStart, Instant windowEnd,
                                   Integer requestedMaxCandidates) {
        return discover(1L, topic, windowStart, windowEnd, requestedMaxCandidates);
    }

    public DiscoveryBatch discover(Long workspaceId,
                                   String topic,
                                   Instant windowStart,
                                   Instant windowEnd,
                                   Integer requestedMaxCandidates) {
        return discover(workspaceId, topic, windowStart, windowEnd,
                requestedMaxCandidates, true);
    }

    /** Candidate-pipeline hook: collect once, then persist the final multi-provider union. */
    DiscoveryBatch discoverUnpersisted(Long workspaceId,
                                       String topic,
                                       Instant windowStart,
                                       Instant windowEnd,
                                       Integer requestedMaxCandidates) {
        return discover(workspaceId, topic, windowStart, windowEnd,
                requestedMaxCandidates, false);
    }

    private DiscoveryBatch discover(Long workspaceId,
                                    String topic,
                                    Instant windowStart,
                                    Instant windowEnd,
                                    Integer requestedMaxCandidates,
                                    boolean persist) {
        validateWindow(windowStart, windowEnd);
        Instant observedAt = clock.instant();
        String scope = topic == null || topic.isBlank() ? "artificial intelligence"
                : bounded(topic, 512);
        int maxCandidates = requestedMaxCandidates == null ? DEFAULT_MAX_CANDIDATES
                : Math.min(Math.max(requestedMaxCandidates, 1), MAX_CANDIDATES);

        // Provider date filters are calendar-granular. Expand by one day on
        // each side for recall; capture_source remains the exact timestamp gate.
        LocalDate startDate = windowStart.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1);
        LocalDate endDate = windowEnd.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1);
        List<QueryLane> lanes = queryLanes(scope, startDate, endDate);
        Map<String, CandidateAccumulator> fused = new LinkedHashMap<>();
        List<QueryExecution> executions = new ArrayList<>();
        List<QuerySnapshot> querySnapshots = new ArrayList<>();
        DiscoveryCounters counters = new DiscoveryCounters();
        List<String> explicitProviderIds = properties.normalizedProviderIds();
        boolean multiProvider = !explicitProviderIds.isEmpty();

        for (QueryLane lane : lanes) {
            List<WebSearchService.SearchBatch> batches = multiProvider
                    ? safeUnionBatches(explicitProviderIds, lane.query())
                    : safeSingleBatch(lane.query());
            for (WebSearchService.SearchBatch batch : batches) {
                if (batch == null) {
                    counters.increment("webProviderFailures");
                    continue;
                }
                String providerId = firstNonBlank(batch.providerId(), "unknown");
                String family = multiProvider
                        ? providerLaneFamily(lane.family(), providerId) : lane.family();
                QueryLane observedLane = new QueryLane(family, lane.query());
                QuerySnapshot laneSnapshot = querySnapshot(observedLane, batch);
                String resultHash = laneSnapshot.resultHash();
                executions.add(new QueryExecution(family, providerId,
                        laneSnapshot.results().size(), failureMessage(batch), lane.query().topic(),
                        lane.query().startDate(), lane.query().endDate(), lane.query().includeDomains(),
                        batch.fromCache(), resultHash));
                querySnapshots.add(laneSnapshot);
                counters.increment("webProviderAttempts");
                if (isProviderFailure(batch)) counters.increment("webProviderFailures");
                else counters.increment("webProviderSuccesses");
                counters.increment("webSuppliedResults", batch.suppliedResultCount());
                counters.increment("webFilteredResults", batch.filteredResultCount());
                for (SnapshotResult row : laneSnapshot.results()) {
                    fuseSearchResult(fused, family, row.rank(), searchResult(row), windowStart, windowEnd,
                            counters, false);
                }
            }
        }

        // Feed, sitemap and first-party API adapters are structured,
        // zero-search-credit recall lanes. Their metadata is still only a
        // discovery hint: every selected URL must pass source capture.
        int structuredSourceCount = fuseStructuredSources(fused, scope, windowStart,
                windowEnd, counters, querySnapshots);

        return finalizeDiscovery(workspaceId, scope, windowStart, windowEnd, observedAt,
                maxCandidates, executions.size(), fused, executions, structuredSourceCount,
                counters, querySnapshots, persist);
    }

    /**
     * Re-run admission, fusion and ranking against a frozen query snapshot.
     * No network call or durable write occurs, so policy changes can be
     * compared without confusing them with search-index drift.
     */
    public DiscoveryBatch replay(String topic,
                                 DiscoveryBatch frozen,
                                 Integer requestedMaxCandidates) {
        if (frozen == null || frozen.querySnapshots().isEmpty()) {
            throw new IllegalArgumentException("replay requires frozen query snapshots");
        }
        Instant windowStart = Instant.parse(frozen.windowStart());
        Instant windowEnd = Instant.parse(frozen.windowEnd());
        validateWindow(windowStart, windowEnd);
        Instant observedAt = Instant.parse(frozen.observedAt());
        String scope = topic == null || topic.isBlank() ? "artificial intelligence"
                : bounded(topic, 512);
        validateReplaySnapshot(scope, windowStart, windowEnd, frozen);
        int maxCandidates = requestedMaxCandidates == null ? DEFAULT_MAX_CANDIDATES
                : Math.min(Math.max(requestedMaxCandidates, 1), MAX_CANDIDATES);
        Map<String, CandidateAccumulator> fused = new LinkedHashMap<>();
        List<QueryExecution> executions = new ArrayList<>();
        DiscoveryCounters counters = new DiscoveryCounters();
        int structuredSourceCount = 0;
        int webQueryCount = 0;
        /*
         * Query snapshots intentionally contain only the bounded result rows.
         * Keep the execution diagnostics from the original live run alongside
         * them so replay does not silently turn a failed provider into a
         * successful empty query.  A family/provider pair can occur more than
         * once in older ledgers, hence the small occurrence cursor.
         */
        Map<String, List<QueryExecution>> frozenExecutions = frozen.executions().stream()
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.groupingBy(
                        execution -> executionKey(execution.family(), execution.providerId()),
                        LinkedHashMap::new, java.util.stream.Collectors.toList()));
        Map<String, Integer> consumedExecutions = new LinkedHashMap<>();
        for (QuerySnapshot snapshot : frozen.querySnapshots()) {
            boolean structured = snapshot.family().startsWith("structured_");
            if (!structured) {
                webQueryCount++;
                String executionIdentity = executionKey(snapshot.family(), snapshot.providerId());
                List<QueryExecution> matching = frozenExecutions.getOrDefault(
                        executionIdentity, List.of());
                int occurrence = consumedExecutions.merge(executionIdentity, 1, Integer::sum) - 1;
                QueryExecution original = occurrence < matching.size() ? matching.get(occurrence) : null;
                executions.add(new QueryExecution(snapshot.family(), snapshot.providerId(),
                        snapshot.results().size(), original == null ? "" : original.failureMessage(),
                        snapshot.requestedSearchTopic(),
                        snapshot.requestedStartDate(), snapshot.requestedEndDate(),
                        snapshot.requestedIncludeDomains(), snapshot.fromCache(),
                        hashSnapshotResults(snapshot.results())));
            }
            for (SnapshotResult row : snapshot.results()) {
                boolean accepted = fuseSearchResult(fused, snapshot.family(), row.rank(),
                        searchResult(row),
                        windowStart, windowEnd, counters, structured);
                if (structured && accepted) structuredSourceCount++;
            }
        }
        return finalizeDiscovery(null, scope, windowStart, windowEnd, observedAt,
                maxCandidates, webQueryCount, fused, executions, structuredSourceCount,
                counters, frozen.querySnapshots(), false);
    }

    private static void validateReplaySnapshot(String scope,
                                               Instant windowStart,
                                               Instant windowEnd,
                                               DiscoveryBatch frozen) {
        for (QuerySnapshot snapshot : frozen.querySnapshots()) {
            if (isSha256(snapshot.resultHash())
                    && !snapshot.resultHash().equals(hashSnapshotResults(snapshot.results()))) {
                throw new IllegalArgumentException("replay query snapshot hash mismatch");
            }
        }
        if (isSha256(frozen.snapshotHash())) {
            String actual = hashDiscoverySnapshot(scope, windowStart, windowEnd,
                    frozen.querySnapshots());
            if (!frozen.snapshotHash().equals(actual)) {
                throw new IllegalArgumentException("replay discovery snapshot hash mismatch");
            }
        }
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }

    private DiscoveryBatch finalizeDiscovery(Long workspaceId,
                                              String scope,
                                              Instant windowStart,
                                              Instant windowEnd,
                                              Instant observedAt,
                                              int maxCandidates,
                                              int queryCount,
                                              Map<String, CandidateAccumulator> fused,
                                              List<QueryExecution> executions,
                                              int structuredSourceCount,
                                              DiscoveryCounters counters,
                                              List<QuerySnapshot> querySnapshots,
                                              boolean persist) {
        Comparator<DiscoveryCandidate> rankingComparator = Comparator
                .comparingInt((DiscoveryCandidate candidate) -> candidate.temporalStatus().rankOrder())
                .thenComparing(Comparator.comparingDouble(
                        DiscoveryCandidate::rrfScore).reversed())
                .thenComparing(DiscoveryCandidate::publishedAtHint,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(DiscoveryCandidate::url);
        List<DiscoveryCandidate> temporallyResolved = fused.values().stream()
                .map(value -> toCandidate(value, windowStart, windowEnd, observedAt))
                .sorted(rankingComparator)
                .toList();
        long outsideCount = temporallyResolved.stream()
                .filter(candidate -> candidate.temporalStatus() == TemporalStatus.OUTSIDE_WINDOW)
                .count();
        counters.set("rejectedPublicationOutsideWindow", Math.toIntExact(outsideCount));
        List<String> outsideTitles = new ArrayList<>(counters.explicitOutsideTitles());
        temporallyResolved.stream()
                .filter(candidate -> candidate.temporalStatus() == TemporalStatus.OUTSIDE_WINDOW)
                .map(DiscoveryCandidate::title).filter(title -> title != null && !title.isBlank())
                .forEach(outsideTitles::add);
        List<DiscoveryCandidate> temporalAdmissible = temporallyResolved.stream()
                .filter(candidate -> candidate.temporalStatus() != TemporalStatus.OUTSIDE_WINDOW)
                .toList();
        List<DiscoveryCandidate> aliasFiltered = temporalAdmissible.stream()
                .filter(candidate -> {
                    boolean staleAlias = candidate.temporalStatus() == TemporalStatus.UNKNOWN
                            && outsideTitles.stream().anyMatch(title ->
                            titleSimilarity(title, candidate.title()) >= 0.91D);
                    if (staleAlias) counters.increment("rejectedStaleAlias");
                    return !staleAlias;
                }).toList();
        List<DiscoveryCandidate> admissible = aliasFiltered.stream()
                .filter(candidate -> {
                    boolean landing = candidate.temporalStatus() == TemporalStatus.UNKNOWN
                            && isObviousUndatedLandingUrl(candidate.url());
                    if (landing) counters.increment("rejectedUndatedLandingPage");
                    return !landing;
                }).toList();
        counters.set("currentCandidates", Math.toIntExact(admissible.stream()
                .filter(candidate -> candidate.temporalStatus() == TemporalStatus.IN_WINDOW).count()));
        counters.set("unknownTimeCandidates", Math.toIntExact(admissible.stream()
                .filter(candidate -> candidate.temporalStatus() == TemporalStatus.UNKNOWN).count()));
        List<DiscoveryCandidate> suppressed = suppressNearDuplicateTitles(admissible);
        counters.set("rejectedNearDuplicate", admissible.size() - suppressed.size());
        List<DiscoveryCandidate> deduplicated = suppressed.stream()
                // A lower-ranked official feed URL may replace a duplicate Web
                // syndication/IR URL because it has stronger structured-time
                // provenance. Re-sort after replacement so the displayed rank
                // remains consistent with the selected card's own RRF score.
                .sorted(rankingComparator)
                .toList();
        AiNewsDiscoveryStoryDeduplicator.DeduplicationResult storyDeduplication =
                AiNewsDiscoveryStoryDeduplicator.deduplicate(deduplicated, sourceRegistry,
                        properties.getMaxCandidatesPerStory());
        counters.set("provisionalStoryClusters", storyDeduplication.provisionalStoryCount());
        counters.set("rejectedStorySamePublisherDuplicate",
                storyDeduplication.samePublisherDuplicates());
        counters.set("rejectedStorySourceQuota", storyDeduplication.sourceQuotaDuplicates());
        List<DiscoveryCandidate> selected = selectCandidates(
                storyDeduplication.candidates(), maxCandidates, counters);
        List<DiscoveryCandidate> withRanks = new ArrayList<>(deduplicated.size());
        for (int i = 0; i < selected.size(); i++) {
            withRanks.add(selected.get(i).withRank(i + 1));
        }
        counters.set("fusedUniqueUrls", fused.size());
        counters.set("selectedCandidates", withRanks.size());
        String rankingPolicyVersion = rankingPolicyVersion();
        String snapshotHash = hashDiscoverySnapshot(scope, windowStart, windowEnd, querySnapshots);
        String rankingHash = hashRanking(withRanks, rankingPolicyVersion, windowStart, windowEnd);
        DiscoveryBatch output = new DiscoveryBatch("untrusted_fused_news_candidates", false,
                windowStart.toString(), windowEnd.toString(), queryCount, fused.size(),
                List.copyOf(withRanks), List.copyOf(executions), structuredSourceCount,
                "可解析的窗口外发布时间已硬拒绝；未知时间候选仅通过分层探索配额进入。"
                        + "RRF、snippet 和 publishedAtHint 仍只是候选筛选信号，最终发布时间和正文"
                + "必须逐条通过 capture_source/read_capture 证明",
                observedAt.toString(), rankingPolicyVersion, snapshotHash, rankingHash,
                counters.snapshot(), List.copyOf(querySnapshots), null, false);
        if (!persist || discoveryRunLedger == null) return output;
        try {
            Long runId = discoveryRunLedger.persist(workspaceId == null ? 1L : workspaceId,
                    scope, maxCandidates, output);
            return output.withPersistence(runId, true);
        } catch (Exception e) {
            // Discovery must remain usable when the audit ledger is temporarily
            // unavailable, but the response explicitly reports the gap.
            log.warn("Failed to persist AI-news discovery snapshot: {}", e.getMessage());
            return output.withPersistence(null, false);
        }
    }

    private int fuseStructuredSources(Map<String, CandidateAccumulator> fused, String scope,
                                      Instant windowStart, Instant windowEnd,
                                      DiscoveryCounters counters,
                                      List<QuerySnapshot> querySnapshots) {
        NewsSourceQuery query = new NewsSourceQuery(
                scope + " AI model agent open source funding security chip cloud robotics",
                100, "", windowStart.minus(Duration.ofDays(1)));
        int accepted = 0;
        Map<String, List<SnapshotResult>> structuredSnapshots = new LinkedHashMap<>();
        boolean ledgerMainline = structuredIngestionService != null
                && structuredIngestionService.persistentMainlineEnabled();
        if (ledgerMainline) {
            List<NewsSourceResult> persisted = structuredIngestionService.recentCandidates(
                    query.since(), 500, true);
            Map<String, Integer> providerRanks = new LinkedHashMap<>();
            for (NewsSourceResult result : persisted) {
                if (!NewsSourceTextMatcher.matches(result, query)) continue;
                String providerId = result == null || result.provenance() == null
                        ? "ledger" : firstNonBlank(result.provenance().providerId(), "ledger");
                int rank = providerRanks.merge(providerId, 1, Integer::sum);
                if (fuseStructuredResult(fused, result, providerId, rank,
                        windowStart, windowEnd, counters, structuredSnapshots)) accepted++;
            }
        }
        if (newsSourceProviderRegistry != null) {
            for (var provider : newsSourceProviderRegistry.all()) {
                // Keep the original rss provider-id contract compatible with
                // third-party/test adapters compiled before channel() existed.
                if (!provider.channel().structured() && !"rss".equals(provider.providerId())) continue;
                // Scheduled adapters are read from the durable latest-version
                // projection. Calling them again here would make request latency
                // depend on publisher availability and double-write poll runs.
                if (ledgerMainline && provider instanceof ScheduledNewsSourceProvider) continue;
                List<NewsSourceResult> results = newsSourceProviderRegistry.search(
                        query, List.of(provider.providerId()));
                int rank = 0;
                for (NewsSourceResult result : results) {
                    rank++;
                    if (fuseStructuredResult(fused, result, provider.providerId(), rank,
                            windowStart, windowEnd, counters, structuredSnapshots)) accepted++;
                }
            }
        }
        structuredSnapshots.forEach((providerId, rows) -> {
            String family = structuredFamily(providerId);
            String resultHash = hashSnapshotResults(rows);
            querySnapshots.add(new QuerySnapshot(family, providerId, false, resultHash,
                    "structured persistent/source-provider lane", "structured",
                    null, null, List.of(), rows));
        });
        return accepted;
    }

    private boolean fuseSearchResult(Map<String, CandidateAccumulator> fused,
                                     String family,
                                     int rank,
                                     SearchResult result,
                                     Instant windowStart,
                                     Instant windowEnd,
                                     DiscoveryCounters counters,
                                     boolean structured) {
        counters.increment(structured ? "structuredResultsSeen" : "webResultsSeen");
        if (result == null || result.getUrl() == null || result.getUrl().isBlank()) {
            counters.increment("rejectedInvalidUrl");
            return false;
        }
        String url = canonicalDiscoveryUrl(result.getUrl());
        if (url.isBlank()) {
            counters.increment("rejectedInvalidUrl");
            return false;
        }
        if (hasExplicitUrlDateOutsideWindow(url, windowStart, windowEnd)) {
            counters.increment("rejectedExplicitUrlOutsideWindow");
            counters.rememberExplicitOutsideTitle(result.getTitle());
            return false;
        }
        if (isObviousNonNewsUrl(url)) {
            counters.increment("rejectedNonArticleUrl");
            return false;
        }
        if (isObviousPromotion(result)) {
            counters.increment("rejectedPromotion");
            return false;
        }
        if (isObviousNonEventContent(result)) {
            counters.increment("rejectedNonEventContent");
            return false;
        }
        if (!isTopicallyRelevantAiNews(result)) {
            counters.increment("rejectedOffTopic");
            return false;
        }
        CandidateAccumulator candidate = fused.computeIfAbsent(discoveryUrlAliasKey(url),
                ignored -> new CandidateAccumulator(url, result));
        candidate.add(family, rank, result);
        return true;
    }

    private boolean fuseStructuredResult(Map<String, CandidateAccumulator> fused,
                                         NewsSourceResult result,
                                         String providerId,
                                         int rank,
                                         Instant windowStart,
                                         Instant windowEnd,
                                         DiscoveryCounters counters,
                                         Map<String, List<SnapshotResult>> structuredSnapshots) {
        counters.increment("structuredResultsSeen");
        structuredSnapshots.computeIfAbsent(firstNonBlank(providerId, "ledger"),
                ignored -> new ArrayList<>()).add(snapshotResult(rank, result, providerId));
        if (result == null || result.provenance() == null) {
            counters.increment("rejectedInvalidStructuredResult");
            return false;
        }
        String rawUrl = firstNonBlank(result.canonicalUrl(), result.sourceUrl());
        String url = canonicalDiscoveryUrl(rawUrl);
        if (url.isBlank()) {
            counters.increment("rejectedInvalidUrl");
            return false;
        }
        if (hasExplicitUrlDateOutsideWindow(url, windowStart, windowEnd)) {
            counters.increment("rejectedExplicitUrlOutsideWindow");
            counters.rememberExplicitOutsideTitle(result.title());
            return false;
        }
        if (isObviousNonNewsUrl(url)) {
            counters.increment("rejectedNonArticleUrl");
            return false;
        }
        Object published = result.provenance().metadata().get("publishedAt");
        SearchResult normalized = SearchResult.builder()
                .title(bounded(result.title(), 512))
                .url(url)
                .snippet(bounded(result.snippet(), 1_500))
                .source(bounded(sourceHost(url), 256))
                .date(bounded(published == null ? null : String.valueOf(published), 256))
                .providerId(bounded(result.provenance().providerId(), 128))
                .build();
        if (isObviousPromotion(normalized)) {
            counters.increment("rejectedPromotion");
            return false;
        }
        if (isObviousNonEventContent(normalized)) {
            counters.increment("rejectedNonEventContent");
            return false;
        }
        if (!isTopicallyRelevantAiNews(normalized)) {
            counters.increment("rejectedOffTopic");
            return false;
        }
        CandidateAccumulator candidate = fused.computeIfAbsent(discoveryUrlAliasKey(url),
                ignored -> new CandidateAccumulator(url, normalized));
        String family = structuredFamily(providerId);
        candidate.add(family, rank, normalized);
        return true;
    }

    public SourcePlan sourcePlan(String category) {
        List<AiNewsSourceRegistry.OfficialSource> sources = sourceRegistry.officialSearchPlan(category);
        return new SourcePlan(category == null || category.isBlank() ? "all" : category,
                sources, sources.stream().flatMap(item -> item.domains().stream())
                        .collect(java.util.stream.Collectors.collectingAndThen(
                                java.util.stream.Collectors.toCollection(LinkedHashSet::new), List::copyOf)));
    }

    private List<QueryLane> queryLanes(String scope, LocalDate startDate, LocalDate endDate) {
        List<QueryLane> lanes = new ArrayList<>();
        // A single broad official-domain query lets high-volume vendors crowd
        // every other publisher out of the provider's top results. Split the
        // registry into bounded editorial lanes while retaining the same
        // ten-credit/run budget used by the original ensemble. Infrastructure
        // gets its own lane because a combined product/infra query lets cloud
        // release-note volume crowd out chip and capacity announcements.
        lanes.add(officialLane("official_models",
                scope + " AI model research open source safety announcement release",
                officialDomains("model", "open_source", "security"), startDate, endDate));
        lanes.add(officialLane("official_global_products",
                scope + " AI product agent enterprise launch update",
                officialDomainsByGroups("global_products"), startDate, endDate));
        lanes.add(new QueryLane("official_china_products", new SearchQuery(
                "人工智能 产品 智能体 云服务 最新发布 更新", null, "zh-cn", 20,
                "general", startDate, endDate, officialDomainsByGroups("china_products"), List.of())));
        lanes.add(officialLane("official_infrastructure",
                scope + " AI cloud chip GPU compute infrastructure launch expansion",
                officialDomains("infrastructure"), startDate, endDate));
        lanes.add(officialLane("official_robotics",
                scope + " AI robotics humanoid autonomous system launch update",
                officialDomains("robotics"), startDate, endDate));
        // Broad web search is useful for discovery but is not an exhaustive
        // news feed. Reserve three of the five vertical lanes for the
        // editorially curated media registry so high-volume SEO/community
        // pages cannot crowd every trusted outlet out of top-20 results;
        // split global and Chinese publishers so query language stays aligned.
        List<String> globalMedia = sourceRegistry.trustedMediaSearchDomains("global");
        lanes.add(newsLane("trusted_media_global_core",
                scope + " AI model product open source security chip cloud partnership launch release",
                "en", startDate, endDate, globalMedia));
        lanes.add(newsLane("trusted_media_global_funding",
                scope + " AI startup funding seed Series valuation acquisition robotics",
                "en", startDate, endDate, globalMedia));
        lanes.add(new QueryLane("trusted_media_china", new SearchQuery(
                "人工智能 模型 产品 开源 融资 合作 芯片 机器人 最新发布",
                null, "zh-cn", 20, "news", startDate, endDate,
                sourceRegistry.trustedMediaSearchDomains("china"), List.of())));
        lanes.add(newsLane("open_web_model_product_security",
                scope + " AI model product agent open source security incident chip infrastructure launch",
                "en", startDate, endDate));
        lanes.add(newsLane("open_web_funding_partnership_robotics",
                scope + " AI startup funding partnership acquisition regulation robotics humanoid launch",
                "en", startDate, endDate));
        return List.copyOf(lanes);
    }

    private QueryLane officialLane(String family, String query, List<String> domains,
                                   LocalDate startDate, LocalDate endDate) {
        return new QueryLane(family, new SearchQuery(query, null, "en", 20,
                "general", startDate, endDate, domains, List.of()));
    }

    private List<String> officialDomains(String... categories) {
        LinkedHashSet<String> domains = new LinkedHashSet<>();
        for (String category : categories) {
            sourceRegistry.officialSearchPlan(category).stream()
                    .flatMap(source -> source.domains().stream()).forEach(domains::add);
        }
        return List.copyOf(domains);
    }

    private List<String> officialDomainsByGroups(String... groups) {
        Set<String> requested = Set.of(groups);
        return sourceRegistry.officialSearchPlan("all").stream()
                .filter(source -> requested.contains(source.group()))
                .flatMap(source -> source.domains().stream())
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new), List::copyOf));
    }

    private static QueryLane newsLane(String family, String query, String language,
                                      LocalDate startDate, LocalDate endDate) {
        return newsLane(family, query, language, startDate, endDate, List.of());
    }

    private static QueryLane newsLane(String family, String query, String language,
                                      LocalDate startDate, LocalDate endDate, List<String> domains) {
        return new QueryLane(family, new SearchQuery(query, null, language, 20,
                "news", startDate, endDate, domains, List.of()));
    }

    /**
     * Invoke the explicit provider union while keeping a malformed extension
     * or mock from aborting the whole discovery run. The normal single-provider
     * path intentionally remains untouched when no provider list is configured.
     */
    private List<WebSearchService.SearchBatch> safeUnionBatches(List<String> providerIds,
                                                                 SearchQuery query) {
        try {
            WebSearchService.SearchUnion union = webSearchService
                    .searchCandidates(providerIds, query);
            if (union == null) {
                return List.of(WebSearchService.SearchBatch.unavailable(
                        "union", "provider union returned null response",
                        List.of(new WebSearchService.ProviderFailure(
                                "union", "provider union returned null response"))));
            }
            if (!union.batches().isEmpty()) {
                // A gateway may report union-level diagnostics (for example,
                // an explicit provider-list truncation) without attaching
                // them to a particular batch. Materialise those diagnostics
                // as synthetic executions so they cannot disappear from the
                // scan ledger.
                List<WebSearchService.SearchBatch> batches = new ArrayList<>(union.batches());
                for (WebSearchService.ProviderFailure failure : union.failures()) {
                    boolean represented = batches.stream().anyMatch(batch -> batch != null
                            && batch.failures().stream().anyMatch(existing -> sameFailure(existing, failure)));
                    if (!represented && failure != null) {
                        batches.add(WebSearchService.SearchBatch.unavailable(
                                firstNonBlank(failure.providerId(), "union"),
                                "provider union diagnostic: " + failure.message(),
                                List.of(failure)));
                    }
                }
                return List.copyOf(batches);
            }
            // Preserve an aggregate union failure as an auditable execution
            // even if a custom gateway did not materialise one batch per id.
            if (!union.failures().isEmpty()) {
                List<WebSearchService.ProviderFailure> failures = union.failures();
                WebSearchService.ProviderFailure first = failures.getFirst();
                return List.of(WebSearchService.SearchBatch.unavailable(
                        first.providerId(), "provider union failed: " + failureMessage(failures),
                        failures));
            }
            return List.of();
        } catch (Exception error) {
            String message = bounded(error.getMessage(), 500);
            if (message.isBlank()) message = error.getClass().getSimpleName();
            return List.of(WebSearchService.SearchBatch.unavailable(
                    "union", "provider union failed: " + message,
                    List.of(new WebSearchService.ProviderFailure("union", message))));
        }
    }

    private List<WebSearchService.SearchBatch> safeSingleBatch(SearchQuery query) {
        try {
            WebSearchService.SearchBatch batch = webSearchService.searchCandidates(query);
            if (batch != null) return List.of(batch);
            return List.of(WebSearchService.SearchBatch.unavailable(
                    "unknown", "provider returned null response",
                    List.of(new WebSearchService.ProviderFailure(
                            "unknown", "provider returned null response"))));
        } catch (Exception error) {
            String message = bounded(error.getMessage(), 500);
            if (message.isBlank()) message = error.getClass().getSimpleName();
            return List.of(WebSearchService.SearchBatch.unavailable(
                    "unknown", "provider search failed: " + message,
                    List.of(new WebSearchService.ProviderFailure("unknown", message))));
        }
    }

    private static String providerLaneFamily(String laneFamily, String providerId) {
        String normalized = firstNonBlank(providerId, "unknown").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "_");
        return laneFamily + "@" + (normalized.isBlank() ? "unknown" : normalized);
    }

    private static boolean sameFailure(WebSearchService.ProviderFailure left,
                                       WebSearchService.ProviderFailure right) {
        return left != null && right != null
                && firstNonBlank(left.providerId(), "").equals(firstNonBlank(right.providerId(), ""))
                && firstNonBlank(left.message(), "").equals(firstNonBlank(right.message(), ""));
    }

    private static boolean isProviderFailure(WebSearchService.SearchBatch batch) {
        return batch.results().isEmpty() && batch.suppliedResultCount() == 0
                && !batch.failures().isEmpty();
    }

    private static String failureMessage(WebSearchService.SearchBatch batch) {
        if (batch == null) return "provider returned null response";
        List<String> details = new ArrayList<>();
        if (batch.message() != null && !batch.message().isBlank()) details.add(batch.message());
        for (WebSearchService.ProviderFailure failure : batch.failures()) {
            if (failure == null) continue;
            String detail = firstNonBlank(failure.providerId(), "provider") + ": "
                    + firstNonBlank(failure.message(), "provider search failed");
            if (!details.contains(detail)) details.add(detail);
        }
        return bounded(String.join("; ", details), 1_000);
    }

    private static String failureMessage(List<WebSearchService.ProviderFailure> failures) {
        if (failures == null || failures.isEmpty()) return "provider search failed";
        List<String> details = new ArrayList<>();
        for (WebSearchService.ProviderFailure failure : failures) {
            if (failure == null) continue;
            String detail = firstNonBlank(failure.providerId(), "provider") + ": "
                    + firstNonBlank(failure.message(), "provider search failed");
            if (!details.contains(detail)) details.add(detail);
        }
        return bounded(String.join("; ", details), 1_000);
    }

    private DiscoveryCandidate toCandidate(CandidateAccumulator value,
                                           Instant windowStart,
                                           Instant windowEnd,
                                           Instant observedAt) {
        boolean official = sourceRegistry.isOfficialUrl(value.url);
        boolean trustedMedia = sourceRegistry.isTrustedMediaUrl(value.url);
        TemporalResolution temporal = resolveTemporalStatus(value, windowStart, windowEnd, observedAt);
        // Trust is a tie-break, not a substitute for relevance. The former
        // 0.004 boost was roughly 25% of a one-lane RRF score and let the 20th
        // stale official result outrank a first-ranked vertical-news result.
        double trustTieBreak = official ? 0.00004D : trustedMedia ? 0.00002D : 0.0D;
        double providerTieBreak = value.bestProviderScore == null ? 0.0D
                : Math.max(0.0D, Math.min(1.0D, value.bestProviderScore)) * 0.00001D;
        return new DiscoveryCandidate(0, value.bestTitle, value.url, value.source,
                temporal.displayHint(), round(value.rrfScore + trustTieBreak + providerTieBreak),
                value.bestProviderScore, official, trustedMedia, List.copyOf(value.queryFamilies),
                bounded(value.bestSnippet, 360), temporal.status(), temporal.source(), null);
    }

    private List<DiscoveryCandidate> selectCandidates(List<DiscoveryCandidate> ranked,
                                                      int maxCandidates,
                                                      DiscoveryCounters counters) {
        int hostLimit = Math.max(1, properties.getMaxCandidatesPerHost());
        Map<String, Integer> laneLimits = Map.of(
                "current_official", maxCandidates,
                "current_media", maxCandidates,
                "current_open_web", quota(maxCandidates, properties.getCurrentOpenWebPercent()),
                "unknown_official", quota(maxCandidates, properties.getUnknownOfficialPercent()),
                "unknown_media", quota(maxCandidates, properties.getUnknownMediaPercent()),
                "unknown_open_web", quota(maxCandidates, properties.getUnknownOpenWebPercent()));
        Map<String, Integer> laneCounts = new LinkedHashMap<>();
        Map<String, Integer> hostCounts = new LinkedHashMap<>();
        List<DiscoveryCandidate> selected = new ArrayList<>();
        int maxUnknown = quota(maxCandidates, properties.getMaxUnknownPercent());
        int selectedUnknown = 0;
        for (DiscoveryCandidate candidate : ranked) {
            if (selected.size() >= maxCandidates) break;
            String lane = selectionLane(candidate);
            boolean unknown = candidate.temporalStatus() == TemporalStatus.UNKNOWN;
            int laneLimit = laneLimits.getOrDefault(lane, 0);
            if (laneCounts.getOrDefault(lane, 0) >= laneLimit) {
                counters.increment("rejectedExplorationQuota");
                continue;
            }
            if (unknown && selectedUnknown >= maxUnknown) {
                counters.increment("rejectedUnknownAggregateQuota");
                continue;
            }
            String host = normalizedHost(candidate.url());
            if (hostCounts.getOrDefault(host, 0) >= hostLimit) {
                counters.increment("rejectedHostLimit");
                continue;
            }
            selected.add(candidate.withSelectionLane(lane));
            if (unknown) selectedUnknown++;
            laneCounts.merge(lane, 1, Integer::sum);
            hostCounts.merge(host, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : laneCounts.entrySet()) {
            counters.set("selected_" + entry.getKey(), entry.getValue());
        }
        counters.set("selectedDistinctHosts", hostCounts.size());
        counters.set("selectedUnknownCandidates", selectedUnknown);
        return List.copyOf(selected);
    }

    private static int quota(int maxCandidates, int percent) {
        int boundedPercent = Math.min(100, Math.max(0, percent));
        if (boundedPercent == 0) return 0;
        return Math.max(1, (int) Math.ceil(maxCandidates * boundedPercent / 100.0D));
    }

    private static String selectionLane(DiscoveryCandidate candidate) {
        String time = candidate.temporalStatus() == TemporalStatus.IN_WINDOW
                ? "current" : "unknown";
        String source = candidate.officialDomain() ? "official"
                : candidate.trustedMediaDomain() ? "media" : "open_web";
        return time + "_" + source;
    }

    private static List<DiscoveryCandidate> suppressNearDuplicateTitles(List<DiscoveryCandidate> ranked) {
        List<DiscoveryCandidate> kept = new ArrayList<>();
        for (DiscoveryCandidate candidate : ranked) {
            int duplicateIndex = -1;
            for (int i = 0; i < kept.size(); i++) {
                if (titleSimilarity(kept.get(i).title(), candidate.title()) >= 0.91D) {
                    duplicateIndex = i;
                    break;
                }
            }
            if (duplicateIndex < 0) {
                kept.add(candidate);
            } else if (preferDuplicateCandidate(candidate, kept.get(duplicateIndex))) {
                kept.set(duplicateIndex, candidate);
            }
        }
        return kept;
    }

    /**
     * Cross-site duplicates are still discovery hints, but an official RSS
     * item with a structured timestamp is a safer capture target than an
     * otherwise equivalent undated Web/IR card. This does not promote the feed
     * timestamp to evidence; capture remains mandatory and authoritative.
     */
    private static boolean preferDuplicateCandidate(DiscoveryCandidate challenger,
                                                     DiscoveryCandidate incumbent) {
        if (challenger.temporalStatus() != incumbent.temporalStatus()) {
            return challenger.temporalStatus().rankOrder() < incumbent.temporalStatus().rankOrder();
        }
        boolean challengerStructuredTime = hasStructuredTimestamp(challenger);
        boolean incumbentStructuredTime = hasStructuredTimestamp(incumbent);
        if (challengerStructuredTime != incumbentStructuredTime) return challengerStructuredTime;
        boolean challengerTime = challenger.publishedAtHint() != null
                && !challenger.publishedAtHint().isBlank();
        boolean incumbentTime = incumbent.publishedAtHint() != null
                && !incumbent.publishedAtHint().isBlank();
        return challengerTime && !incumbentTime;
    }

    private static boolean hasStructuredTimestamp(DiscoveryCandidate candidate) {
        return candidate.queryFamilies().stream().anyMatch(family -> family.startsWith("structured_"))
                && candidate.publishedAtHint() != null
                && !candidate.publishedAtHint().isBlank();
    }

    static double titleSimilarity(String left, String right) {
        Set<String> a = characterNgrams(editorialTitleCore(left), 3);
        Set<String> b = characterNgrams(editorialTitleCore(right), 3);
        if (a.isEmpty() || b.isEmpty()) return 0.0D;
        Set<String> intersection = new LinkedHashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0.0D : (double) intersection.size() / union.size();
    }

    /** Remove only reviewed publisher boilerplate before near-duplicate comparison. */
    private static String editorialTitleCore(String value) {
        if (value == null) return "";
        return value.replaceFirst("(?iu)^\\s*nvidia corporation\\s*[-|:]\\s*", "")
                .replaceFirst("(?iu)\\s*[|]\\s*nvidia newsroom\\s*$", "")
                .replaceFirst("(?iu)\\s*[|]\\s*nvidia blog\\s*$", "")
                .replaceFirst("(?iu)\\s*-\\s*techcrunch\\s*$", "")
                .replaceFirst("(?iu)\\s*(?:[_|｜])\\s*(?:腾讯新闻|界面新闻(?:\\s*·\\s*科技)?|新浪新闻)\\s*$", "")
                .trim();
    }

    /**
     * Provider dates are discovery hints, not evidence. A parseable hint that
     * is unambiguously outside the frozen half-open window is nevertheless a
     * safe rejection signal. Date-only hints represent a whole UTC day so a
     * partially overlapping boundary day is not incorrectly rejected.
     */
    static boolean hasPublicationHintOutsideWindow(String value, Instant windowStart,
                                                   Instant windowEnd) {
        return publicationHintStatus(value, windowStart, windowEnd, windowEnd)
                == TemporalStatus.OUTSIDE_WINDOW;
    }

    static TemporalStatus publicationHintStatus(String value,
                                                Instant windowStart,
                                                Instant windowEnd,
                                                Instant observedAt) {
        if (windowStart == null || windowEnd == null) return TemporalStatus.UNKNOWN;
        ParsedPublicationTime parsed = parsePublicationTime(value,
                observedAt == null ? windowEnd : observedAt);
        return parsed == null ? TemporalStatus.UNKNOWN : parsed.status(windowStart, windowEnd);
    }

    private static TemporalResolution resolveTemporalStatus(CandidateAccumulator candidate,
                                                            Instant windowStart,
                                                            Instant windowEnd,
                                                            Instant observedAt) {
        List<ResolvedPublicationHint> parsed = new ArrayList<>();
        for (PublicationHint hint : candidate.publicationHints) {
            ParsedPublicationTime time = parsePublicationTime(hint.value(), observedAt);
            if (time != null) {
                parsed.add(new ResolvedPublicationHint(hint,
                        time.status(windowStart, windowEnd), time.normalized()));
            }
        }
        List<ResolvedPublicationHint> structured = parsed.stream()
                .filter(item -> item.hint().family().startsWith("structured_"))
                .toList();
        List<ResolvedPublicationHint> snippetHeaders = parsed.stream()
                .filter(item -> item.hint().family().endsWith("_snippet_header"))
                .toList();
        // Publisher feed time remains strongest. For ordinary search results,
        // a conservative date at the start of the source snippet is safer than
        // a mutable provider index timestamp.
        List<ResolvedPublicationHint> authoritativeHints = !structured.isEmpty() ? structured
                : !snippetHeaders.isEmpty() ? snippetHeaders : parsed;
        if (!authoritativeHints.isEmpty()) {
            boolean inWindow = authoritativeHints.stream()
                    .anyMatch(item -> item.status() == TemporalStatus.IN_WINDOW);
            boolean outside = authoritativeHints.stream()
                    .anyMatch(item -> item.status() == TemporalStatus.OUTSIDE_WINDOW);
            // Conflicting parseable timestamps must not silently become a
            // current event. Source capture can later resolve the conflict.
            TemporalStatus status = inWindow && outside ? TemporalStatus.UNKNOWN
                    : outside ? TemporalStatus.OUTSIDE_WINDOW
                    : inWindow ? TemporalStatus.IN_WINDOW : TemporalStatus.UNKNOWN;
            ResolvedPublicationHint selected = authoritativeHints.stream()
                    .filter(item -> item.status() == status).findFirst()
                    .orElse(authoritativeHints.getFirst());
            String source = inWindow && outside
                    ? "conflicting_" + selected.hint().family() : selected.hint().family();
            return new TemporalResolution(status, selected.normalized(), source);
        }

        LocalDate urlDate = explicitUrlPublicationDate(candidate.url);
        if (urlDate != null) {
            ParsedPublicationTime parsedUrl = ParsedPublicationTime.day(urlDate);
            return new TemporalResolution(parsedUrl.status(windowStart, windowEnd),
                    urlDate.toString(), "url_path");
        }
        String unparsed = candidate.publicationHints.stream().map(PublicationHint::value)
                .filter(value -> value != null && !value.isBlank()).findFirst().orElse(null);
        return new TemporalResolution(TemporalStatus.UNKNOWN, unparsed, "none");
    }

    private static ParsedPublicationTime parsePublicationTime(String value, Instant observedAt) {
        if (value == null || value.isBlank()) return null;
        String hint = value.trim();
        try {
            return ParsedPublicationTime.instant(Instant.parse(hint));
        } catch (DateTimeParseException ignored) {
            // Continue through observed provider formats.
        }
        try {
            return ParsedPublicationTime.instant(ZonedDateTime.parse(
                    hint, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        } catch (DateTimeParseException ignored) {
            // Continue with generic offset timestamps and human dates.
        }
        try {
            return ParsedPublicationTime.instant(OffsetDateTime.parse(hint).toInstant());
        } catch (DateTimeParseException ignored) {
            // Continue with calendar and relative formats.
        }

        Matcher english = ENGLISH_DATE.matcher(hint);
        if (english.find()) {
            String matched = english.group();
            for (DateTimeFormatter formatter : List.of(
                    ENGLISH_DATE_FORMATTER, ENGLISH_LONG_DATE_FORMATTER)) {
                try {
                    return ParsedPublicationTime.day(LocalDate.parse(matched, formatter));
                } catch (DateTimeParseException ignored) {
                    // Try abbreviated/long month alternative.
                }
            }
        }
        Matcher chinese = CHINESE_DATE.matcher(hint);
        if (chinese.find()) {
            try {
                return ParsedPublicationTime.day(LocalDate.of(
                        Integer.parseInt(chinese.group(1)), Integer.parseInt(chinese.group(2)),
                        Integer.parseInt(chinese.group(3))));
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        Matcher isoDate = ISO_DATE.matcher(hint);
        if (isoDate.find()) {
            try {
                return ParsedPublicationTime.day(LocalDate.of(
                        Integer.parseInt(isoDate.group(1)), Integer.parseInt(isoDate.group(2)),
                        Integer.parseInt(isoDate.group(3))));
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        Instant anchor = observedAt == null ? Instant.now() : observedAt;
        Matcher monthDay = CHINESE_MONTH_DAY.matcher(hint);
        if (monthDay.find()) {
            try {
                LocalDate anchorDay = anchor.atZone(ZoneOffset.UTC).toLocalDate();
                LocalDate day = LocalDate.of(anchorDay.getYear(),
                        Integer.parseInt(monthDay.group(1)), Integer.parseInt(monthDay.group(2)));
                if (day.isAfter(anchorDay.plusDays(1))) day = day.minusYears(1);
                return ParsedPublicationTime.day(day);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        String lower = hint.toLowerCase(Locale.ROOT);
        if (lower.matches(".*\\b(today)\\b.*") || hint.contains("今天")) {
            return ParsedPublicationTime.day(anchor.atZone(ZoneOffset.UTC).toLocalDate());
        }
        if (lower.matches(".*\\b(yesterday)\\b.*") || hint.contains("昨天")) {
            return ParsedPublicationTime.day(anchor.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1));
        }
        Matcher relative = ENGLISH_RELATIVE.matcher(hint);
        if (relative.find()) {
            return ParsedPublicationTime.instant(relativeInstant(anchor,
                    Long.parseLong(relative.group(1)), relative.group(2).toLowerCase(Locale.ROOT)));
        }
        Matcher chineseRelative = CHINESE_RELATIVE.matcher(hint);
        if (chineseRelative.find()) {
            String unit = switch (chineseRelative.group(2)) {
                case "分钟" -> "minute";
                case "小时" -> "hour";
                case "天" -> "day";
                case "周" -> "week";
                case "个月", "月" -> "month";
                default -> "year";
            };
            return ParsedPublicationTime.instant(relativeInstant(anchor,
                    Long.parseLong(chineseRelative.group(1)), unit));
        }
        return null;
    }

    private static Instant relativeInstant(Instant anchor, long amount, String unit) {
        long safeAmount = Math.min(Math.max(amount, 0), 100_000);
        return switch (unit) {
            case "minute" -> anchor.minus(Duration.ofMinutes(safeAmount));
            case "hour" -> anchor.minus(Duration.ofHours(safeAmount));
            case "day" -> anchor.minus(Duration.ofDays(safeAmount));
            case "week" -> anchor.minus(Duration.ofDays(Math.multiplyExact(safeAmount, 7)));
            case "month" -> anchor.atZone(ZoneOffset.UTC).minusMonths(safeAmount).toInstant();
            case "year" -> anchor.atZone(ZoneOffset.UTC).minusYears(safeAmount).toInstant();
            default -> anchor;
        };
    }

    private static LocalDate explicitUrlPublicationDate(String url) {
        try {
            Matcher matcher = URL_DATE_SEGMENT.matcher(URI.create(url).getPath());
            if (!matcher.find()) return null;
            return LocalDate.of(Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Set<String> characterNgrams(String value, int n) {
        String normalized = AiNewsAtomicFactGuard.fingerprintText(value);
        if (normalized.isBlank()) return Set.of();
        if (normalized.length() <= n) return Set.of(normalized);
        Set<String> grams = new LinkedHashSet<>();
        for (int i = 0; i <= normalized.length() - n; i++) grams.add(normalized.substring(i, i + n));
        return grams;
    }

    static String canonicalDiscoveryUrl(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            URI uri = URI.create(raw.trim());
            if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))) return "";
            String path = uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath();
            path = path.replaceAll("/{2,}", "/");
            if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
            Map<String, String> query = new TreeMap<>();
            if (uri.getRawQuery() != null) {
                for (String item : uri.getRawQuery().split("&")) {
                    String[] pair = item.split("=", 2);
                    String name = pair[0].trim().toLowerCase(Locale.ROOT);
                    if (name.startsWith("utm_") || Set.of("ref", "source", "fbclid", "gclid", "mc_cid")
                            .contains(name)) continue;
                    query.putIfAbsent(name, pair.length > 1 ? pair[1] : "");
                }
            }
            String rawQuery = query.isEmpty() ? null : query.entrySet().stream()
                    .map(item -> item.getKey() + (item.getValue().isBlank() ? "" : "=" + item.getValue()))
                    .reduce((left, right) -> left + "&" + right).orElse(null);
            return new URI(uri.getScheme().toLowerCase(Locale.ROOT), null,
                    uri.getHost().toLowerCase(Locale.ROOT), uri.getPort(), path, rawQuery, null).toString();
        } catch (IllegalArgumentException | URISyntaxException e) {
            return "";
        }
    }

    /** Fuse common delivery aliases without rewriting the URL later used for capture. */
    static String discoveryUrlAliasKey(String canonicalUrl) {
        if (canonicalUrl == null || canonicalUrl.isBlank()) return "";
        try {
            URI uri = URI.create(canonicalUrl);
            String host = uri.getHost().toLowerCase(Locale.ROOT)
                    .replaceFirst("^(?:www|m|mobile|wap)\\.(?=.+\\.)", "");
            int port = uri.getPort();
            if ((port == 80 && "http".equalsIgnoreCase(uri.getScheme()))
                    || (port == 443 && "https".equalsIgnoreCase(uri.getScheme()))) port = -1;
            return host + "|" + port + "|" + firstNonBlank(uri.getRawPath(), "/")
                    + "|" + firstNonBlank(uri.getRawQuery(), "");
        } catch (Exception ignored) {
            return canonicalUrl;
        }
    }

    /**
     * High-precision stale-page filter. Provider date filters are often based
     * on last-update time, so an article under /2024/ can reappear in a 2026
     * window after a template refresh. Only an explicit path year segment is
     * used; ambiguous numbers and pages without a year remain candidates for
     * the authoritative capture timestamp gate.
     */
    static boolean hasExplicitUrlDateOutsideWindow(String url, Instant windowStart, Instant windowEnd) {
        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            if (path == null || path.isBlank()) return false;
            Matcher dated = URL_DATE_SEGMENT.matcher(path);
            boolean foundDate = false;
            while (dated.find()) {
                foundDate = true;
                LocalDate day = LocalDate.of(Integer.parseInt(dated.group(1)),
                        Integer.parseInt(dated.group(2)), Integer.parseInt(dated.group(3)));
                Instant dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant();
                Instant dayEnd = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
                if (dayStart.isBefore(windowEnd) && dayEnd.isAfter(windowStart)) return false;
            }
            if (foundDate) return true;

            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            if ("arxiv.org".equals(host) || "huggingface.co".equals(host)) {
                Matcher arxivId = ARXIV_ID_PATH.matcher(path);
                if (arxivId.matches()) {
                    int year = 2000 + Integer.parseInt(arxivId.group(1));
                    int month = Integer.parseInt(arxivId.group(2));
                    Instant monthStart = LocalDate.of(year, month, 1)
                            .atStartOfDay(ZoneOffset.UTC).toInstant();
                    Instant monthEnd = LocalDate.of(year, month, 1).plusMonths(1)
                            .atStartOfDay(ZoneOffset.UTC).toInstant();
                    return !monthStart.isBefore(windowEnd) || !monthEnd.isAfter(windowStart);
                }
            }

            int startYear = windowStart.atZone(ZoneOffset.UTC).getYear();
            int endYear = windowEnd.minusNanos(1).atZone(ZoneOffset.UTC).getYear();
            Matcher matcher = URL_YEAR_SEGMENT.matcher(path);
            boolean found = false;
            while (matcher.find()) {
                found = true;
                int year = Integer.parseInt(matcher.group(1));
                if (year >= startYear && year <= endYear) return false;
            }
            return found;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** High-precision static-page filter; capture remains the final authority. */
    static boolean isObviousNonNewsUrl(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            for (String blocked : OBVIOUS_NON_ARTICLE_HOSTS) {
                if (host.equals(blocked) || host.endsWith("." + blocked)) return true;
            }
            String path = uri.getPath();
            if (path == null || path.isBlank() || "/".equals(path)) return true;
            String lower = path.toLowerCase(Locale.ROOT);
            if (lower.length() > 1 && lower.endsWith("/")) lower = lower.substring(0, lower.length() - 1);
            if (OBVIOUS_SECTION_LANDING_PATHS.contains(lower)) return true;
            if (lower.endsWith(".pdf")) return true;
            for (String segment : lower.split("/")) {
                if (OBVIOUS_NON_NEWS_SEGMENTS.contains(segment)) return true;
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Conservative promotional-event filter applied before URL capture. */
    static boolean isObviousPromotion(SearchResult result) {
        if (result == null) return false;
        String text = firstNonBlank(result.getTitle(), "") + "\n"
                + firstNonBlank(result.getSnippet(), "");
        return OBVIOUS_PROMOTION.matcher(text).find();
    }

    /** High-precision exclusion of recaps/tutorials that do not describe a discrete event. */
    static boolean isObviousNonEventContent(SearchResult result) {
        if (result == null) return false;
        String title = firstNonBlank(result.getTitle(), "");
        return OBVIOUS_NON_EVENT_CONTENT.matcher(title).find()
                || MULTI_STORY_ROUNDUP_TITLE.matcher(title).find();
    }

    /**
     * A provider/domain match is not a topic label. Require an AI signal in the
     * headline, or an AI signal plus a discrete-event verb near the beginning
     * of the search card. The bounded fallback retains headlines such as a
     * named public-sector project whose lead explicitly says it launches AI.
     */
    static boolean isTopicallyRelevantAiNews(SearchResult result) {
        if (result == null) return false;
        String title = firstNonBlank(result.getTitle(), "");
        if (AI_TITLE_SIGNAL.matcher(title).find()) return true;
        if (AI_URL_SIGNAL.matcher(firstNonBlank(result.getUrl(), "")).find()) return true;
        String snippet = firstNonBlank(result.getSnippet(), "");
        if (snippet.length() > 800) snippet = snippet.substring(0, 800);
        return AI_LEAD_SIGNAL.matcher(snippet).find()
                && AI_SNIPPET_EVENT.matcher(snippet).find();
    }

    /**
     * Reject only short, entirely generic routes when no publication time is
     * available. Specific article slugs (including short official slugs) remain
     * eligible for the bounded capture exploration lane.
     */
    static boolean isObviousUndatedLandingUrl(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path == null || path.isBlank() || "/".equals(path)) return true;
            List<String> meaningful = new ArrayList<>();
            for (String raw : path.toLowerCase(Locale.ROOT).split("/")) {
                String segment = raw.trim();
                if (segment.isBlank() || LOCALE_PATH_SEGMENT.matcher(segment).matches()) continue;
                meaningful.add(segment);
            }
            return !meaningful.isEmpty() && meaningful.size() <= 4
                    && meaningful.stream().allMatch(GENERIC_LANDING_SEGMENTS::contains);
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Extract only search-card headers/bylines/newswire dates, never dates in article prose. */
    static String conservativeSnippetPublicationHint(String snippet) {
        if (snippet == null || snippet.isBlank()) return null;
        for (Pattern pattern : List.of(LEADING_SNIPPET_DATE, LABELED_SNIPPET_DATE,
                BYLINE_SNIPPET_DATE, NEWSWIRE_SNIPPET_DATE)) {
            Matcher matcher = pattern.matcher(snippet);
            if (matcher.find()) return matcher.group(1).trim();
        }
        return null;
    }

    private static void validateWindow(Instant start, Instant end) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw new NewsClawException(400, "discover requires windowStart < windowEnd");
        }
        if (end.isAfter(Instant.now().plus(Duration.ofMinutes(5)))) {
            throw new NewsClawException(400, "discover windowEnd cannot be materially in the future");
        }
        if (Duration.between(start, end).compareTo(MAX_WINDOW) > 0) {
            throw new NewsClawException(400, "discover window cannot exceed 31 days");
        }
    }

    private static String bounded(String value, int max) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max).trim();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        return second == null ? "" : second;
    }

    private static String sourceHost(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "rss" : host.toLowerCase(Locale.ROOT);
        } catch (Exception ignored) {
            return "rss";
        }
    }

    private static String normalizedHost(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null || host.isBlank()) return "invalid-host";
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception ignored) {
            return "invalid-host";
        }
    }

    private static double round(double value) {
        return Math.round(value * 1_000_000D) / 1_000_000D;
    }

    private String rankingPolicyVersion() {
        String effective = properties.getMaxCandidatesPerHost() + "|"
                + properties.getMaxCandidatesPerStory() + "|"
                + properties.getCurrentOpenWebPercent() + "|"
                + properties.getMaxUnknownPercent() + "|"
                + properties.getUnknownOfficialPercent() + "|"
                + properties.getUnknownMediaPercent() + "|"
                + properties.getUnknownOpenWebPercent();
        return RANKING_POLICY_BASE + "@" + NewsSourceHashing.shortHash(effective);
    }

    private static QuerySnapshot querySnapshot(QueryLane lane,
                                               WebSearchService.SearchBatch batch) {
        List<SnapshotResult> rows = new ArrayList<>();
        int rank = 0;
        for (SearchResult result : batch.results()) {
            rows.add(snapshotResult(++rank, result, batch.providerId()));
        }
        return new QuerySnapshot(lane.family(), firstNonBlank(batch.providerId(), "unknown"),
                batch.fromCache(),
                hashSnapshotResults(rows),
                bounded(lane.query().query(), 1_000), lane.query().topic(),
                lane.query().startDate(), lane.query().endDate(),
                lane.query().includeDomains(), rows);
    }

    private static SnapshotResult snapshotResult(int rank, SearchResult result) {
        return snapshotResult(rank, result, null);
    }

    private static SnapshotResult snapshotResult(int rank, SearchResult result,
                                                 String fallbackProviderId) {
        if (result == null) return new SnapshotResult(rank, "", "", "", "", "", "", null);
        return new SnapshotResult(rank, bounded(result.getTitle(), 512),
                bounded(result.getUrl(), 4_096), bounded(result.getSnippet(), 1_500),
                bounded(result.getDate(), 256), bounded(result.getSource(), 256),
                bounded(firstNonBlank(result.getProviderId(), fallbackProviderId), 128),
                result.getRelevanceScore());
    }

    private static SearchResult searchResult(SnapshotResult row) {
        return SearchResult.builder().title(row.title()).url(row.url()).snippet(row.snippet())
                .date(row.publishedAtHint()).source(row.source()).providerId(row.providerId())
                .relevanceScore(row.relevanceScore()).build();
    }

    private static SnapshotResult snapshotResult(int rank,
                                                 NewsSourceResult result,
                                                 String providerId) {
        if (result == null) return new SnapshotResult(rank, "", "", "", "", "",
                bounded(providerId, 128), null);
        Object published = result.provenance() == null ? null
                : result.provenance().metadata().get("publishedAt");
        String rawUrl = firstNonBlank(result.canonicalUrl(), result.sourceUrl());
        return new SnapshotResult(rank, bounded(result.title(), 512), bounded(rawUrl, 4_096),
                bounded(result.snippet(), 1_500),
                bounded(published == null ? null : String.valueOf(published), 256),
                bounded(sourceHost(rawUrl), 256), bounded(providerId, 128), null);
    }

    private static String hashSnapshotResults(List<SnapshotResult> results) {
        List<String> rows = new ArrayList<>();
        for (SnapshotResult result : results) {
            rows.add(result.rank() + "|" + hashField(result.title()) + "|"
                    + hashField(result.url()) + "|" + hashField(result.snippet()) + "|"
                    + hashField(result.publishedAtHint()) + "|" + hashField(result.source()) + "|"
                    + hashField(result.providerId()) + "|" + result.relevanceScore());
        }
        return NewsSourceHashing.sha256(String.join("\n", rows));
    }

    private static String hashDiscoverySnapshot(String scope,
                                                Instant windowStart,
                                                Instant windowEnd,
                                                List<QuerySnapshot> snapshots) {
        List<String> rows = new ArrayList<>();
        rows.add("scope=" + hashField(scope));
        rows.add("windowStart=" + windowStart);
        rows.add("windowEnd=" + windowEnd);
        for (QuerySnapshot snapshot : snapshots) {
            rows.add(hashField(snapshot.family()) + "|" + hashField(snapshot.providerId()) + "|"
                    + hashField(snapshot.requestedQuery()) + "|"
                    + hashField(snapshot.requestedSearchTopic()) + "|"
                    + snapshot.requestedStartDate() + "|" + snapshot.requestedEndDate() + "|"
                    + snapshot.requestedIncludeDomains().stream().map(AiNewsDiscoverySearchService::hashField)
                    .reduce((left, right) -> left + "," + right).orElse("") + "|"
                    + hashSnapshotResults(snapshot.results()));
        }
        return NewsSourceHashing.sha256(String.join("\n", rows));
    }

    private static String hashRanking(List<DiscoveryCandidate> candidates,
                                      String policyVersion,
                                      Instant windowStart,
                                      Instant windowEnd) {
        List<String> rows = new ArrayList<>();
        rows.add(hashField(policyVersion) + "|" + windowStart + "|" + windowEnd);
        for (DiscoveryCandidate candidate : candidates) {
            rows.add(candidate.rank() + "|" + hashField(candidate.url()) + "|"
                    + candidate.temporalStatus() + "|" + hashField(candidate.selectionLane()) + "|"
                    + candidate.rrfScore());
        }
        return NewsSourceHashing.sha256(String.join("\n", rows));
    }

    private static String hashField(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("|", "\\|")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static String executionKey(String family, String providerId) {
        return hashField(family) + "\u0000" + hashField(providerId);
    }

    private static String structuredFamily(String providerId) {
        return "structured_" + firstNonBlank(providerId, "ledger")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    }

    private record QueryLane(String family, SearchQuery query) {
    }

    private record PublicationHint(String family, String value) {
    }

    private record ResolvedPublicationHint(PublicationHint hint,
                                           TemporalStatus status,
                                           String normalized) {
    }

    private record TemporalResolution(TemporalStatus status,
                                      String displayHint,
                                      String source) {
    }

    private record ParsedPublicationTime(Instant point,
                                         Instant intervalStart,
                                         Instant intervalEnd,
                                         String normalized) {

        static ParsedPublicationTime instant(Instant instant) {
            return new ParsedPublicationTime(instant, null, null, instant.toString());
        }

        static ParsedPublicationTime day(LocalDate date) {
            Instant start = date.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant end = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            return new ParsedPublicationTime(null, start, end, date.toString());
        }

        TemporalStatus status(Instant windowStart, Instant windowEnd) {
            if (point != null) {
                return !point.isBefore(windowStart) && point.isBefore(windowEnd)
                        ? TemporalStatus.IN_WINDOW : TemporalStatus.OUTSIDE_WINDOW;
            }
            boolean overlaps = intervalStart.isBefore(windowEnd) && intervalEnd.isAfter(windowStart);
            return overlaps ? TemporalStatus.IN_WINDOW : TemporalStatus.OUTSIDE_WINDOW;
        }
    }

    private static final class DiscoveryCounters {
        private final LinkedHashMap<String, Integer> values = new LinkedHashMap<>();
        private final List<String> explicitOutsideTitles = new ArrayList<>();

        void increment(String name) {
            values.merge(name, 1, Integer::sum);
        }

        void increment(String name, int amount) {
            if (amount <= 0) return;
            values.merge(name, amount, Integer::sum);
        }

        void set(String name, int value) {
            values.put(name, value);
        }

        void rememberExplicitOutsideTitle(String title) {
            if (title != null && !title.isBlank()) explicitOutsideTitles.add(bounded(title, 512));
        }

        List<String> explicitOutsideTitles() {
            return List.copyOf(explicitOutsideTitles);
        }

        Map<String, Integer> snapshot() {
            return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }

    private static final class CandidateAccumulator {
        private final String url;
        private String bestTitle;
        private String source;
        private String bestSnippet;
        private Double bestProviderScore;
        private double rrfScore;
        private final LinkedHashSet<String> queryFamilies = new LinkedHashSet<>();
        private final List<PublicationHint> publicationHints = new ArrayList<>();

        private CandidateAccumulator(String url, SearchResult first) {
            this.url = url;
            this.bestTitle = first.getTitle();
            this.source = first.getSource();
            this.bestSnippet = first.getSnippet();
            this.bestProviderScore = first.getRelevanceScore();
        }

        private void add(String family, int rank, SearchResult result) {
            // RRF grants one vote per ranked lane. Delivery aliases observed
            // again in that same lane must not amplify the candidate's score.
            if (queryFamilies.add(family)) rrfScore += 1.0D / (RRF_K + rank);
            // Keep every observed date rather than allowing a higher provider
            // score to overwrite source metadata. Resolution later gives
            // publisher feeds, then conservative source-snippet headers,
            // precedence over mutable provider index timestamps.
            if (result.getDate() != null && !result.getDate().isBlank()) {
                publicationHints.add(new PublicationHint(family, result.getDate().trim()));
            }
            String snippetHint = conservativeSnippetPublicationHint(result.getSnippet());
            if (snippetHint != null) {
                publicationHints.add(new PublicationHint(family + "_snippet_header", snippetHint));
            }
            Double score = result.getRelevanceScore();
            if (score != null && (bestProviderScore == null || score > bestProviderScore)) {
                bestProviderScore = score;
                bestTitle = result.getTitle();
                source = result.getSource();
                bestSnippet = result.getSnippet();
            }
            if ((bestTitle == null || bestTitle.isBlank()) && result.getTitle() != null) {
                bestTitle = result.getTitle();
            }
            if ((bestSnippet == null || bestSnippet.isBlank()) && result.getSnippet() != null) {
                bestSnippet = result.getSnippet();
            }
        }
    }

    public record DiscoveryCandidate(int rank,
                                     String title,
                                     String url,
                                     String source,
                                     String publishedAtHint,
                                     double rrfScore,
                                     Double providerScore,
                                     boolean officialDomain,
                                     boolean trustedMediaDomain,
                                     List<String> queryFamilies,
                                     String snippet,
                                     TemporalStatus temporalStatus,
                                     String timeHintSource,
                                     String selectionLane) {
        public DiscoveryCandidate {
            queryFamilies = queryFamilies == null ? List.of() : List.copyOf(queryFamilies);
            temporalStatus = temporalStatus == null ? TemporalStatus.UNKNOWN : temporalStatus;
        }

        public DiscoveryCandidate(int rank,
                                  String title,
                                  String url,
                                  String source,
                                  String publishedAtHint,
                                  double rrfScore,
                                  Double providerScore,
                                  boolean officialDomain,
                                  boolean trustedMediaDomain,
                                  List<String> queryFamilies,
                                  String snippet) {
            this(rank, title, url, source, publishedAtHint, rrfScore, providerScore,
                    officialDomain, trustedMediaDomain, queryFamilies, snippet,
                    TemporalStatus.UNKNOWN, "legacy", null);
        }

        DiscoveryCandidate withRank(int newRank) {
            return new DiscoveryCandidate(newRank, title, url, source, publishedAtHint, rrfScore,
                    providerScore, officialDomain, trustedMediaDomain, queryFamilies, snippet,
                    temporalStatus, timeHintSource, selectionLane);
        }

        DiscoveryCandidate withSelectionLane(String newSelectionLane) {
            return new DiscoveryCandidate(rank, title, url, source, publishedAtHint, rrfScore,
                    providerScore, officialDomain, trustedMediaDomain, queryFamilies, snippet,
                    temporalStatus, timeHintSource, newSelectionLane);
        }
    }

    public enum TemporalStatus {
        IN_WINDOW(0),
        UNKNOWN(1),
        OUTSIDE_WINDOW(2);

        private final int rankOrder;

        TemporalStatus(int rankOrder) {
            this.rankOrder = rankOrder;
        }

        int rankOrder() {
            return rankOrder;
        }
    }

    /** Sanitised, bounded query-lane payload retained for deterministic replay. */
    public record QuerySnapshot(String family,
                                String providerId,
                                boolean fromCache,
                                String resultHash,
                                String requestedQuery,
                                String requestedSearchTopic,
                                LocalDate requestedStartDate,
                                LocalDate requestedEndDate,
                                List<String> requestedIncludeDomains,
                                List<SnapshotResult> results) {
        public QuerySnapshot {
            requestedIncludeDomains = requestedIncludeDomains == null
                    ? List.of() : List.copyOf(requestedIncludeDomains);
            results = results == null ? List.of() : List.copyOf(results);
        }
    }

    public record SnapshotResult(int rank,
                                 String title,
                                 String url,
                                 String snippet,
                                 String publishedAtHint,
                                 String source,
                                 String providerId,
                                 Double relevanceScore) {
    }

    public record QueryExecution(String family,
                                 String providerId,
                                 int resultCount,
                                 String failureMessage,
                                 String requestedTopic,
                                 LocalDate requestedStartDate,
                                 LocalDate requestedEndDate,
                                 List<String> requestedIncludeDomains,
                                 boolean fromCache,
                                 String resultHash) {
        public QueryExecution {
            requestedIncludeDomains = requestedIncludeDomains == null
                    ? List.of() : List.copyOf(requestedIncludeDomains);
            resultHash = resultHash == null ? "" : resultHash;
        }

        public QueryExecution(String family,
                              String providerId,
                              int resultCount,
                              String failureMessage,
                              String requestedTopic,
                              LocalDate requestedStartDate,
                              LocalDate requestedEndDate,
                              List<String> requestedIncludeDomains) {
            this(family, providerId, resultCount, failureMessage, requestedTopic,
                    requestedStartDate, requestedEndDate, requestedIncludeDomains, false, "");
        }
    }

    public record DiscoveryBatch(String mode,
                                 boolean evidenceEligible,
                                 String windowStart,
                                 String windowEnd,
                                 int queryCount,
                                 int uniqueUrlCount,
                                 List<DiscoveryCandidate> candidates,
                                 List<QueryExecution> executions,
                                 int structuredSourceCount,
                                 String message,
                                 String observedAt,
                                 String rankingPolicyVersion,
                                 String snapshotHash,
                                 String rankingHash,
                                 Map<String, Integer> diagnostics,
                                 List<QuerySnapshot> querySnapshots,
                                 Long discoveryRunId,
                                 boolean snapshotPersisted) {
        public DiscoveryBatch {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
            executions = executions == null ? List.of() : List.copyOf(executions);
            diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
            querySnapshots = querySnapshots == null ? List.of() : List.copyOf(querySnapshots);
        }

        public DiscoveryBatch(String mode,
                              boolean evidenceEligible,
                              String windowStart,
                              String windowEnd,
                              int queryCount,
                              int uniqueUrlCount,
                              List<DiscoveryCandidate> candidates,
                              List<QueryExecution> executions,
                              int structuredSourceCount,
                              String message) {
            this(mode, evidenceEligible, windowStart, windowEnd, queryCount, uniqueUrlCount,
                    candidates, executions, structuredSourceCount, message, null, null,
                    null, null, Map.of(), List.of(), null, false);
        }

        DiscoveryBatch withPersistence(Long runId, boolean persisted) {
            return new DiscoveryBatch(mode, evidenceEligible, windowStart, windowEnd, queryCount,
                    uniqueUrlCount, candidates, executions, structuredSourceCount, message,
                    observedAt, rankingPolicyVersion, snapshotHash, rankingHash, diagnostics,
                    querySnapshots, runId, persisted);
        }
    }

    public record SourcePlan(String category,
                             List<AiNewsSourceRegistry.OfficialSource> officialSources,
                             List<String> includeDomains) {
    }
}
