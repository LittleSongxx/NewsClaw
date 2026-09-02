package vip.newsclaw.news.evaluation;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Deterministic, event-level evaluation for the AI-news discovery product line.
 *
 * <p>This scorer consumes an independently adjudicated event ledger and a
 * frozen system run. It never calls a model or the network while scoring. The
 * metric families follow temporal information retrieval (TREC), B-Cubed text
 * clustering, nDCG ranking, and claim-level citation quality. Product SLAs such
 * as freshness cutoffs remain dataset configuration rather than hard-coded
 * claims about an industry standard.</p>
 */
public final class AiNewsDiscoveryQualityEvaluator {

    public static final List<Integer> DEFAULT_FRESHNESS_CUTOFFS_MINUTES = List.of(30, 120, 1_440);
    public static final List<Integer> DEFAULT_RANKING_CUTOFFS = List.of(5, 10, 20);
    public static final int DEFAULT_LATENCY_STEP_MINUTES = 360;
    public static final int DEFAULT_RANKING_LOOKBACK_MINUTES = 1_440;
    public static final int SMALL_SAMPLE_THRESHOLD = 20;

    private static final double WILSON_95_Z = 1.959963984540054D;
    private static final Set<String> SOURCE_TIERS = Set.of("official", "media", "community");
    private static final Set<String> EVIDENCE_RELATIONS = Set.of(
            "entails", "partial", "contradicts", "unrelated", "hedged", "unknown");

    public EvaluationReport evaluate(DiscoveryDataset dataset, String gitCommit, String testCommand) {
        DiscoveryDataset input = Objects.requireNonNull(dataset, "discovery dataset is required");
        ResolvedConfig config = resolveConfig(input.config());
        ParsedWindow window = parseAndValidateWindow(input.window(), config);
        ValidationResult validated = validateAndParse(input, config, window);

        List<Badcase> badcases = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, MetricSummary> metrics = new LinkedHashMap<>();

        scoreDiscoveryCandidates(validated, config, badcases, metrics);
        RetrievalScore retrieval = scoreRetrieval(validated, config, badcases, metrics);
        Map<String, SliceSummary> slices = scoreSlices(validated, retrieval, config);
        Integer declaredClusterUniverse = positiveMetadataInt(input.executionMetadata(),
                "clusterUniverseItemCount");
        if (declaredClusterUniverse != null
                && input.clusterAssignments().size() > declaredClusterUniverse) {
            throw new IllegalArgumentException("clusterAssignments exceed declared cluster universe: assigned="
                    + input.clusterAssignments().size() + ", declared=" + declaredClusterUniverse);
        }
        scoreClustering(input.clusterAssignments(), declaredClusterUniverse,
                badcases, metrics, warnings);
        EvidenceCounts evidenceCounts = scoreEvidence(validated.outputWindowEvents(), badcases, metrics, warnings);
        scoreReadiness(validated.outputWindowEvents(), metrics, warnings);
        List<RankingSnapshotSummary> ranking = scoreRanking(validated, config, metrics, warnings);

        // A gold file by itself does not mean that retrieval/freshness was
        // observed.  Candidate-only shadow runs must expose those families as
        // N/A instead of presenting a zero/empty final-output score as a
        // completed evaluation.
        boolean hasRetrieval = !validated.goldById().isEmpty()
                && !validated.systemEvents().isEmpty();
        boolean hasFreshness = hasRetrieval && !validated.outputWindowEvents().isEmpty();
        boolean hasClustering = !input.clusterAssignments().isEmpty();
        boolean hasEvidence = !validated.outputWindowEvents().isEmpty()
                && evidenceCounts.claims() > 0 && evidenceCounts.evidence() > 0;
        // An empty returned list is an observed zero-quality ranking, not an
        // unavailable ranking measurement.  Only an empty qrel (no eligible
        // gold at the snapshot time) makes the family unscorable.
        boolean hasRanking = ranking.stream().anyMatch(item -> item.eligibleGoldEvents() > 0);
        int availableFamilies = (hasRetrieval ? 1 : 0) + (hasFreshness ? 1 : 0)
                + (hasClustering ? 1 : 0) + (hasEvidence ? 1 : 0) + (hasRanking ? 1 : 0);
        boolean p0Complete = availableFamilies == 5;
        if (!p0Complete) {
            warnings.add("P0 report is incomplete: available metric families=" + availableFamilies
                    + "/5 (retrieval, freshness, clustering, evidence, ranking)");
        }
        boolean evaluationEligible = formalEvaluationEligible(validated, ranking,
                declaredClusterUniverse, warnings);

        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("goldEvents", (long) validated.goldById().size());
        counts.put("discoveryCandidates", (long) input.discoveryCandidates().size());
        counts.put("systemEvents", (long) validated.systemEvents().size());
        counts.put("outputWindowEvents", (long) validated.outputWindowEvents().size());
        counts.put("adjudicatedOutputEvents", validated.outputWindowEvents().stream()
                .filter(item -> isAdjudicated(item.value())).count());
        counts.put("unknownOutputEvents", validated.outputWindowEvents().stream()
                .filter(item -> !isAdjudicated(item.value())).count());
        counts.put("relevantSystemEventsWithCompleteAnnotations", validated.systemEvents().stream()
                .filter(item -> isRelevant(item.value()))
                .filter(item -> hasCompleteClaimEvidenceAnnotations(item.value()))
                .count());
        counts.put("relevantSystemEventsWithIncompleteAnnotations", validated.systemEvents().stream()
                .filter(item -> isRelevant(item.value()))
                .filter(item -> !hasCompleteClaimEvidenceAnnotations(item.value()))
                .count());
        counts.put("tailObservationEvents",
                (long) validated.systemEvents().size() - validated.outputWindowEvents().size());
        counts.put("detectedGoldEvents", (long) retrieval.earliestByGold().size());
        counts.put("evidenceReadyDetectedGoldEvents", (long) retrieval.earliestEvidenceReadyByGold().size());
        counts.put("matchedOutputEvents", retrieval.matchedOutputEvents());
        counts.put("uniqueMatchedOutputEvents", retrieval.uniqueMatchedOutputEvents());
        counts.put("relevantOutputEvents", retrieval.relevantOutputEvents());
        counts.put("uniqueRelevantOutputEvents", retrieval.uniqueRelevantOutputEvents());
        counts.put("evidenceReadyOutputEvents", retrieval.evidenceReadyOutputEvents());
        counts.put("uniqueEvidenceReadyOutputEvents", retrieval.uniqueEvidenceReadyOutputEvents());
        counts.put("falsePositiveOutputEvents", retrieval.falsePositiveOutputEvents());
        counts.put("redundantOutputEvents", retrieval.redundantOutputEvents());
        counts.put("clusterItems", (long) input.clusterAssignments().size());
        if (declaredClusterUniverse != null) {
            counts.put("clusterUniverseItems", (long) declaredClusterUniverse);
        }
        counts.put("claims", evidenceCounts.claims());
        counts.put("verifiableClaims", evidenceCounts.verifiableClaims());
        counts.put("evidence", evidenceCounts.evidence());
        counts.put("citationRelations", evidenceCounts.citationRelations());
        counts.put("technicalReadyOutputEvents", validated.outputWindowEvents().stream()
                .filter(item -> isRelevant(item.value()))
                .filter(item -> item.value().evidence().stream()
                        .anyMatch(AiNewsDiscoveryQualityEvaluator::isTechnicallyEligibleEvidence))
                .count());
        counts.put("semanticReadyOutputEvents", validated.outputWindowEvents().stream()
                .filter(item -> isSemanticallyReady(item.value())).count());
        counts.put("rankingSnapshots", (long) input.rankingSnapshots().size());
        counts.put("eligibleRankingSnapshots", ranking.stream()
                .filter(item -> item.eligibleGoldEvents() > 0).count());
        counts.put("emptyQrelRankingSnapshots", ranking.stream()
                .filter(item -> item.eligibleGoldEvents() == 0).count());
        counts.put("p0MetricFamiliesAvailable", (long) availableFamilies);
        counts.put("formalEvaluationEligible", evaluationEligible ? 1L : 0L);
        counts.put("badcases", (long) badcases.size());

        Map<String, String> methods = new LinkedHashMap<>();
        methods.put("temporalRetrieval",
                "https://trec.nist.gov/pubs/trec24/papers/Overview-TS.pdf");
        methods.put("clustering",
                "https://aclanthology.org/C98-1012/");
        methods.put("ranking",
                "https://github.com/usnistgov/trec_eval/blob/main/m_ndcg_cut.c");
        methods.put("citationQuality",
                "https://aclanthology.org/2023.emnlp-main.398/");

        DiscoveryQualityManifest manifest = new DiscoveryQualityManifest(
                "1.0",
                defaultText(input.evaluationScope(), "unspecified"),
                defaultText(input.datasetId(), "unnamed-dataset"),
                defaultText(input.datasetVersion(), "unknown"),
                Instant.now().toString(),
                defaultText(gitCommit, "unknown"),
                input.window(),
                config,
                input.executionMetadata(),
                Map.copyOf(counts),
                Collections.unmodifiableMap(new LinkedHashMap<>(metrics)),
                Collections.unmodifiableMap(new LinkedHashMap<>(slices)),
                List.copyOf(ranking),
                List.copyOf(badcases),
                List.copyOf(warnings),
                input.limitations(),
                Map.copyOf(methods),
                p0Complete,
                defaultText(testCommand, "unspecified"),
                evaluationEligible);
        return new EvaluationReport(manifest, List.copyOf(badcases));
    }

    /**
     * Scores the retrieval boundary independently from capture, Agent
     * selection and evidence admission. Unmatched candidates are not called
     * false positives because a frozen gold ledger can be incomplete; the
     * precision-like metrics are therefore named explicitly as gold matches.
     */
    private static void scoreDiscoveryCandidates(ValidationResult validated,
                                                 ResolvedConfig config,
                                                 List<Badcase> badcases,
                                                 Map<String, MetricSummary> metrics) {
        List<DiscoveryCandidateAssessment> candidates = validated.dataset().discoveryCandidates();
        if (candidates.isEmpty()) {
            List<String> missing = List.of("discoveryCandidates is empty; candidate retrieval was not observed");
            for (String name : List.of("candidate.goldEventRecall",
                    "candidate.goldMatchCardPrecision", "candidate.uniqueGoldMatchPrecision",
                    "candidate.duplicateGoldMatchRate")) {
                metrics.put(name, unavailableMetric("ratio", "not applicable: no discovery candidates", missing));
            }
            for (int cutoff : config.rankingCutoffs()) {
                metrics.put("candidate.goldEventRecallAt" + cutoff,
                        unavailableMetric("ratio", "not applicable: no discovery candidates", missing));
            }
            return;
        }
        long goldCount = validated.goldById().size();
        long matchedCards = candidates.stream()
                .filter(item -> !defaultText(item.matchedGoldEventId(), "").isBlank()).count();
        long uniqueMatched = candidates.stream().map(DiscoveryCandidateAssessment::matchedGoldEventId)
                .filter(Objects::nonNull).filter(value -> !value.isBlank()).distinct().count();
        long duplicates = matchedCards - uniqueMatched;
        metrics.put("candidate.goldEventRecall", ratioMetric(uniqueMatched, goldCount,
                "ratio", "distinct frozen-gold events present in returned discovery candidates", true));
        metrics.put("candidate.goldMatchCardPrecision", ratioMetric(matchedCards, candidates.size(),
                "ratio", "candidate cards matched to frozen gold / returned candidates", true));
        metrics.put("candidate.uniqueGoldMatchPrecision", ratioMetric(uniqueMatched, candidates.size(),
                "ratio", "distinct frozen-gold matches / returned candidates", true));
        metrics.put("candidate.duplicateGoldMatchRate", ratioMetric(duplicates, matchedCards,
                "ratio", "duplicate cards among candidates matched to frozen gold", true));

        for (int cutoff : config.rankingCutoffs()) {
            long distinctAtCutoff = candidates.stream().filter(item -> item.rank() <= cutoff)
                    .map(DiscoveryCandidateAssessment::matchedGoldEventId)
                    .filter(Objects::nonNull).filter(value -> !value.isBlank()).distinct().count();
            metrics.put("candidate.goldEventRecallAt" + cutoff, ratioMetric(distinctAtCutoff, goldCount,
                    "ratio", "distinct frozen-gold events in discovery top " + cutoff, true));
        }

        Set<String> matchedGoldIds = candidates.stream()
                .map(DiscoveryCandidateAssessment::matchedGoldEventId)
                .filter(Objects::nonNull).filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        validated.goldById().values().stream()
                .filter(gold -> !matchedGoldIds.contains(gold.value().eventId()))
                .forEach(gold -> badcases.add(new Badcase("candidate-missed-event",
                        gold.value().eventId(), gold.value().title(),
                        "not present in returned discovery candidates")));
    }

    private static RetrievalScore scoreRetrieval(ValidationResult validated,
                                                  ResolvedConfig config,
                                                  List<Badcase> badcases,
                                                  Map<String, MetricSummary> metrics) {
        Map<String, ParsedSystemEvent> earliestByGold = earliestMatches(validated.systemEvents());
        Map<String, ParsedSystemEvent> earliestOutputByGold = earliestMatches(validated.outputWindowEvents());
        List<ParsedSystemEvent> evidenceReadyEvents = validated.systemEvents().stream()
                .filter(AiNewsDiscoveryQualityEvaluator::isEvidenceReady)
                .toList();
        List<ParsedSystemEvent> evidenceReadyOutput = validated.outputWindowEvents().stream()
                .filter(AiNewsDiscoveryQualityEvaluator::isEvidenceReady)
                .toList();
        Map<String, ParsedSystemEvent> earliestEvidenceReadyByGold = earliestMatches(evidenceReadyEvents);
        Map<String, ParsedSystemEvent> earliestEvidenceReadyOutputByGold = earliestMatches(evidenceReadyOutput);
        long goldCount = validated.goldById().size();
        if (validated.systemEvents().isEmpty()) {
            markFinalMetricsUnavailable(config, metrics,
                    "final systemEvents is empty; candidate-only run has no final retrieval/freshness observations");
            return new RetrievalScore(Map.copyOf(earliestByGold), Map.copyOf(earliestEvidenceReadyByGold),
                    0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        }
        long detectedCount = earliestByGold.size();
        long outputCount = validated.outputWindowEvents().size();
        long adjudicatedOutput = validated.outputWindowEvents().stream()
                .filter(item -> isAdjudicated(item.value())).count();
        long matchedOutput = validated.outputWindowEvents().stream()
                .filter(item -> item.value().matchedGoldEventId() != null
                        && !item.value().matchedGoldEventId().isBlank())
                .count();
        long uniqueMatchedOutput = earliestOutputByGold.size();
        long relevantOutput = validated.outputWindowEvents().stream()
                .filter(item -> isRelevant(item.value())).count();
        long uniqueRelevantOutput = distinctRelevantIdentities(validated.outputWindowEvents());
        long falsePositive = validated.outputWindowEvents().stream()
                .filter(item -> isExplicitFalsePositive(item.value())).count();
        long redundant = relevantOutput - uniqueRelevantOutput;
        long evidenceReadyOutputCount = evidenceReadyOutput.size();
        long uniqueEvidenceReadyOutputCount = distinctRelevantIdentities(evidenceReadyOutput);

        metrics.put("retrieval.eventRecall", ratioMetric(detectedCount, goldCount,
                "ratio", "distinct detected gold events / gold events", true));
        metrics.put("retrieval.goldMatchPrecision", ratioMetric(matchedOutput, outputCount,
                "ratio", "outputs matched to the frozen gold ledger / output events", true));
        metrics.put("retrieval.uniqueGoldMatchPrecision", ratioMetric(uniqueMatchedOutput, outputCount,
                "ratio", "distinct frozen-gold matches / output events", true));
        // An unmatched row without an explicit human relevance label is an
        // open-world unknown, not a false positive.  Keep it visible in the
        // counts, but do not let an unreviewed tail lower precision.
        metrics.put("retrieval.relevancePrecision", ratioMetric(relevantOutput, adjudicatedOutput,
                "ratio", "independently adjudicated relevant output events / adjudicated output events", true));
        metrics.put("retrieval.novelEventPrecision", ratioMetric(uniqueRelevantOutput, adjudicatedOutput,
                "ratio", "distinct adjudicated relevant event identities / adjudicated output events", true));
        metrics.put("retrieval.novelEventF1", scalarMetric(
                harmonic(ratio(uniqueRelevantOutput, adjudicatedOutput), ratio(detectedCount, goldCount)),
                "ratio", "harmonic mean of novel-event precision and event recall"));
        metrics.put("retrieval.evidenceReadyEventRecall", ratioMetric(
                earliestEvidenceReadyByGold.size(), goldCount, "ratio",
                "gold events with a matched, fully supported and fetchable system card / gold events", true));
        metrics.put("retrieval.evidenceReadyOutputPrecision", ratioMetric(
                evidenceReadyOutputCount, adjudicatedOutput, "ratio",
                "adjudicated output cards whose verifiable claims are fully supported and have fetchable evidence", true));
        metrics.put("retrieval.evidenceReadyNovelPrecision", ratioMetric(
                uniqueEvidenceReadyOutputCount, adjudicatedOutput, "ratio",
                "distinct evidence-ready matched events / adjudicated output events", true));
        metrics.put("retrieval.evidenceReadyF1", scalarMetric(harmonic(
                ratio(uniqueEvidenceReadyOutputCount, adjudicatedOutput),
                ratio(earliestEvidenceReadyByGold.size(), goldCount)),
                "ratio", "harmonic mean of evidence-ready novel precision and evidence-ready recall"));

        double totalImportance = validated.goldById().values().stream()
                .mapToDouble(item -> importanceWeight(item.value().importance())).sum();
        double detectedImportance = earliestByGold.keySet().stream()
                .map(validated.goldById()::get)
                .mapToDouble(item -> importanceWeight(item.value().importance())).sum();
        metrics.put("retrieval.importanceWeightedRecall", boundedAverageMetric(
                detectedImportance, totalImportance, "ratio",
                "TREC-style exponential importance-weighted event recall"));

        metrics.put("dedup.redundantOutputRate", ratioMetric(redundant, outputCount,
                "ratio", "duplicate matched cards / output events", true));
        metrics.put("dedup.duplicateLeakageAmongRelevant", ratioMetric(redundant, relevantOutput,
                "ratio", "duplicate relevant cards / relevant output events", true));

        boolean candidateOnly = validated.systemEvents().isEmpty()
                && !validated.dataset().discoveryCandidates().isEmpty();
        for (ParsedGoldEvent gold : validated.goldById().values()) {
            ParsedSystemEvent earliest = earliestByGold.get(gold.value().eventId());
            if (earliest == null) {
                if (!candidateOnly) {
                    badcases.add(new Badcase("missed-event", gold.value().eventId(),
                            gold.value().title(), "no matched system event before observationEndAt"));
                }
                continue;
            }
            long lagMinutes = effectiveLagMinutes(gold, earliest);
            int largestCutoff = config.freshnessCutoffsMinutes()
                    .get(config.freshnessCutoffsMinutes().size() - 1);
            if (lagMinutes > largestCutoff) {
                badcases.add(new Badcase("late-event", gold.value().eventId(),
                        gold.value().title(), "earliest detection lag=" + lagMinutes
                        + "m exceeds largest configured cutoff=" + largestCutoff + "m"));
            }
        }
        validated.outputWindowEvents().stream()
                .filter(item -> isExplicitFalsePositive(item.value()))
                .forEach(item -> badcases.add(new Badcase("false-positive-event",
                        item.value().systemEventId(), item.value().title(),
                        defaultText(item.value().adjudicationReason(), "unmatched by adjudicator"))));
        validated.outputWindowEvents().stream()
                .filter(item -> !isAdjudicated(item.value()))
                .forEach(item -> badcases.add(new Badcase("unadjudicated-output-event",
                        item.value().systemEventId(), item.value().title(),
                        "open-world row has no explicit adjudicatedRelevant label")));
        Map<String, List<ParsedSystemEvent>> outputByGold = groupMatches(validated.outputWindowEvents());
        outputByGold.forEach((goldId, items) -> items.stream()
                .sorted(SYSTEM_EVENT_ORDER)
                .skip(1)
                .forEach(item -> badcases.add(new Badcase("duplicate-output-event",
                        item.value().systemEventId(), item.value().title(),
                        "duplicates gold event " + goldId))));
        validated.outputWindowEvents().stream()
                .filter(item -> isRelevant(item.value()))
                .filter(item -> !isEvidenceReady(item))
                .forEach(item -> badcases.add(new Badcase("not-evidence-ready-event",
                        item.value().systemEventId(), item.value().title(),
                        "requires at least one verifiable claim, full joint support, and a fetched valid evidence URL")));

        scoreFreshness(validated, config, earliestByGold, earliestOutputByGold,
                earliestEvidenceReadyByGold, metrics);
        return new RetrievalScore(Map.copyOf(earliestByGold), Map.copyOf(earliestEvidenceReadyByGold),
                matchedOutput, uniqueMatchedOutput, relevantOutput, uniqueRelevantOutput,
                evidenceReadyOutputCount, uniqueEvidenceReadyOutputCount, falsePositive, redundant);
    }

    private static void markFinalMetricsUnavailable(ResolvedConfig config,
                                                    Map<String, MetricSummary> metrics,
                                                    String reason) {
        List<String> missing = List.of(reason);
        for (String name : List.of("retrieval.eventRecall", "retrieval.goldMatchPrecision",
                "retrieval.uniqueGoldMatchPrecision", "retrieval.relevancePrecision",
                "retrieval.novelEventPrecision", "retrieval.novelEventF1",
                "retrieval.evidenceReadyEventRecall", "retrieval.evidenceReadyOutputPrecision",
                "retrieval.evidenceReadyNovelPrecision", "retrieval.evidenceReadyF1",
                "retrieval.importanceWeightedRecall", "dedup.redundantOutputRate",
                "dedup.duplicateLeakageAmongRelevant")) {
            metrics.put(name, unavailableMetric("ratio", "not applicable to candidate-only run", missing));
        }
        for (int cutoff : config.freshnessCutoffsMinutes()) {
            metrics.put("freshness.recallAt" + cutoff + "Minutes",
                    unavailableMetric("ratio", "not applicable to candidate-only run", missing));
            metrics.put("freshness.evidenceReadyRecallAt" + cutoff + "Minutes",
                    unavailableMetric("ratio", "not applicable to candidate-only run", missing));
        }
        for (String name : List.of("freshness.latencyAdjustedRecall",
                "freshness.latencyAdjustedNovelPrecision", "freshness.detectedLagP50Minutes",
                "freshness.detectedLagP90Minutes")) {
            metrics.put(name, unavailableMetric(name.endsWith("Minutes") ? "minutes" : "ratio",
                    "not applicable to candidate-only run", missing));
        }
    }

    private static void scoreFreshness(ValidationResult validated,
                                       ResolvedConfig config,
                                       Map<String, ParsedSystemEvent> earliestByGold,
                                       Map<String, ParsedSystemEvent> earliestOutputByGold,
                                       Map<String, ParsedSystemEvent> earliestEvidenceReadyByGold,
                                       Map<String, MetricSummary> metrics) {
        List<Double> detectedLags = new ArrayList<>();
        double weightedLatencyGain = 0.0D;
        double totalImportance = 0.0D;
        for (ParsedGoldEvent gold : validated.goldById().values()) {
            double weight = importanceWeight(gold.value().importance());
            totalImportance += weight;
            ParsedSystemEvent earliest = earliestByGold.get(gold.value().eventId());
            if (earliest != null) {
                long lagMinutes = effectiveLagMinutes(gold, earliest);
                detectedLags.add((double) lagMinutes);
                weightedLatencyGain += weight * latencyDiscount(lagMinutes,
                        config.latencyStepMinutes());
            }
        }
        for (int cutoff : config.freshnessCutoffsMinutes()) {
            long detectedWithin = validated.goldById().values().stream()
                    .filter(gold -> {
                        ParsedSystemEvent earliest = earliestByGold.get(gold.value().eventId());
                        return earliest != null && effectiveLagMinutes(gold, earliest) <= cutoff;
                    }).count();
            metrics.put("freshness.recallAt" + cutoff + "Minutes", ratioMetric(
                    detectedWithin, validated.goldById().size(), "ratio",
                    "gold events detected within " + cutoff + " minutes", true));
            long evidenceReadyWithin = validated.goldById().values().stream()
                    .filter(gold -> {
                        ParsedSystemEvent earliest = earliestEvidenceReadyByGold.get(gold.value().eventId());
                        return earliest != null && effectiveLagMinutes(gold, earliest) <= cutoff;
                    }).count();
            metrics.put("freshness.evidenceReadyRecallAt" + cutoff + "Minutes", ratioMetric(
                    evidenceReadyWithin, validated.goldById().size(), "ratio",
                    "gold events with an evidence-ready card within " + cutoff + " minutes", true));
        }
        metrics.put("freshness.latencyAdjustedRecall", boundedAverageMetric(weightedLatencyGain,
                totalImportance, "ratio",
                "importance-weighted recall with TREC arctangent latency discount"));

        double outputLatencyGain = 0.0D;
        for (Map.Entry<String, ParsedSystemEvent> entry : earliestOutputByGold.entrySet()) {
            ParsedGoldEvent gold = validated.goldById().get(entry.getKey());
            outputLatencyGain += latencyDiscount(effectiveLagMinutes(gold, entry.getValue()),
                    config.latencyStepMinutes());
        }
        metrics.put("freshness.latencyAdjustedNovelPrecision", boundedAverageMetric(outputLatencyGain,
                validated.outputWindowEvents().stream().filter(item -> isAdjudicated(item.value())).count(),
                "ratio", "TREC-style first-match latency gain / adjudicated output events"));

        List<String> percentileWarnings = new ArrayList<>();
        percentileWarnings.add("detected-only diagnostic; pair with Recall@T to expose missed events");
        if (detectedLags.size() < SMALL_SAMPLE_THRESHOLD) {
            percentileWarnings.add("small sample: N=" + detectedLags.size()
                    + " < " + SMALL_SAMPLE_THRESHOLD);
        }
        metrics.put("freshness.detectedLagP50Minutes", scalarMetric(
                percentile(detectedLags, 0.50D), "minutes",
                "linear-interpolated P50 over detected gold events", percentileWarnings));
        metrics.put("freshness.detectedLagP90Minutes", scalarMetric(
                percentile(detectedLags, 0.90D), "minutes",
                "linear-interpolated P90 over detected gold events", percentileWarnings));
    }

    private static boolean formalEvaluationEligible(ValidationResult validated,
                                                     List<RankingSnapshotSummary> ranking,
                                                     Integer declaredClusterUniverse,
                                                     List<String> warnings) {
        Map<String, String> metadata = validated.dataset().executionMetadata();
        List<String> blockers = new ArrayList<>();
        if (!metadataValue(metadata, "evaluationEligible", "true")) {
            blockers.add("evaluationEligible=true");
        }
        if (!metadataValue(metadata, "labelReviewStatus",
                "two-independent-reviewers-complete")) {
            blockers.add("labelReviewStatus=two-independent-reviewers-complete");
        }
        if (!metadataValue(metadata, "sourceUniverse", "FROZEN_APPROVED", "approved")) {
            blockers.add("sourceUniverse=FROZEN_APPROVED|approved");
        }
        if (!metadataValue(metadata, "independentCollector", "true")) {
            blockers.add("independentCollector=true");
        }
        if (!metadataValue(metadata, "futureLeakage", "PASS")) {
            blockers.add("futureLeakage=PASS");
        }
        if (!metadataValue(metadata, "splits", "FROZEN")) {
            blockers.add("splits=FROZEN");
        }

        String scope = normalize(validated.dataset().evaluationScope());
        String labelProvenance = normalize(metadata.get("labelProvenance"));
        if (scope.contains("synthetic") || scope.contains("fixture")
                || labelProvenance.contains("synthetic")
                || labelProvenance.contains("ai-authored")
                || labelProvenance.contains("mechanically")) {
            blockers.add("synthetic or mechanically authored data is not a formal benchmark");
        }
        if (validated.goldById().isEmpty()) blockers.add("goldEvents is empty");
        if (validated.dataset().discoveryCandidates().isEmpty()) blockers.add("discoveryCandidates is empty");
        if (validated.systemEvents().isEmpty()) blockers.add("systemEvents is empty");
        if (validated.outputWindowEvents().isEmpty()) blockers.add("outputWindowEvents is empty");
        if (validated.dataset().clusterAssignments().isEmpty()) blockers.add("clusterAssignments is empty");
        if (validated.dataset().rankingSnapshots().isEmpty()) blockers.add("rankingSnapshots is empty");
        if (declaredClusterUniverse == null) {
            blockers.add("executionMetadata.clusterUniverseItemCount is required");
        } else if (declaredClusterUniverse != validated.dataset().clusterAssignments().size()) {
            blockers.add("clusterAssignments do not cover declared cluster universe (expected "
                        + declaredClusterUniverse + ", got "
                        + validated.dataset().clusterAssignments().size() + ")");
        }
        Set<String> declaredClusterUniverseIds = parseClusterUniverseIds(metadata.get("clusterUniverseItemIds"));
        if (declaredClusterUniverseIds == null) {
            blockers.add("executionMetadata.clusterUniverseItemIds is required for exact cluster coverage");
        } else {
            Set<String> assignedIds = validated.dataset().clusterAssignments().stream()
                    .map(ClusterAssignment::itemId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            if (!declaredClusterUniverseIds.equals(assignedIds)) {
                blockers.add("clusterAssignments do not cover the declared cluster item identity set");
            }
        }
        if (!validated.dataset().discoveryCandidates().isEmpty()
                && !metadataValue(metadata, "candidateAdjudicationStatus", "complete")) {
            blockers.add("candidateAdjudicationStatus=complete");
        }
        if (!ranking.isEmpty() && ranking.stream().anyMatch(item -> item.eligibleGoldEvents() == 0)) {
            blockers.add("rankingSnapshots contains an empty qrel; declare an explicit empty-qrel policy and rebuild the split");
        }
        if (!ranking.isEmpty() && ranking.stream().noneMatch(item -> item.eligibleGoldEvents() > 0)) {
            blockers.add("rankingSnapshots has no eligible gold events");
        }
        boolean hasEvidence = validated.outputWindowEvents().stream()
                .anyMatch(event -> !event.value().claims().isEmpty() && !event.value().evidence().isEmpty());
        if (!hasEvidence) blockers.add("output events have no claim/evidence annotations");
        List<String> incompleteRelevant = validated.systemEvents().stream()
                .filter(event -> isRelevant(event.value()))
                .filter(event -> !hasCompleteClaimEvidenceAnnotations(event.value()))
                .map(event -> event.value().systemEventId())
                .toList();
        if (!incompleteRelevant.isEmpty()) {
            blockers.add("relevant system events lack complete claim/evidence annotations: "
                    + String.join(",", incompleteRelevant));
        }
        boolean adjudicationComplete = validated.systemEvents().stream().allMatch(event -> {
            SystemEvent value = event.value();
            if (emptyToNull(value.matchedGoldEventId()) != null) return true;
            if (value.adjudicatedRelevant() == null) return false;
            return !Boolean.TRUE.equals(value.adjudicatedRelevant())
                    || emptyToNull(value.adjudicatedEventId()) != null;
        });
        if (!adjudicationComplete) blockers.add("system event adjudication is incomplete");

        for (String blocker : blockers) {
            warnings.add("formal evaluation blocker: " + blocker);
        }
        return blockers.isEmpty();
    }

    private static boolean metadataValue(Map<String, String> metadata,
                                         String key,
                                         String... acceptedValues) {
        String actual = normalize(metadata.get(key));
        for (String accepted : acceptedValues) {
            if (actual.equals(normalize(accepted))) return true;
        }
        return false;
    }

    /**
     * Parses the coordinator-supplied exact item universe used for clustering.
     * IDs are deliberately represented as a comma-separated value in the
     * string-only execution metadata so the JSON contract remains backward
     * compatible.  Candidate IDs are URL hashes in production and therefore
     * cannot contain commas.
     */
    private static Set<String> parseClusterUniverseIds(String raw) {
        if (raw == null || raw.isBlank()) return null;
        Set<String> result = new LinkedHashSet<>();
        for (String token : raw.split(",", -1)) {
            String id = emptyToNull(token);
            if (id == null || !result.add(id)) return null;
        }
        return result;
    }

    private static boolean hasCompleteClaimEvidenceAnnotations(SystemEvent event) {
        if (event == null || event.evidence().isEmpty()
                || event.claims().stream().noneMatch(ClaimAssessment::verifiable)) return false;
        // A structurally complete packet with only unknown/negative relations
        // is not evidence readiness. Require at least one independently
        // adjudicated, jointly-supported claim; otherwise a fixture could pass
        // the formal gate vacuously while every claim remains unsupported.
        boolean hasJointlySupportedClaim = event.claims().stream()
                .anyMatch(claim -> claim.verifiable() && Boolean.TRUE.equals(claim.jointlySupported()));
        if (!hasJointlySupportedClaim) return false;
        Set<String> verifiableIds = event.claims().stream()
                .filter(ClaimAssessment::verifiable)
                .map(ClaimAssessment::claimId)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> relatedIds = event.evidence().stream()
                .flatMap(evidence -> evidence.relations().stream())
                .map(ClaimEvidenceRelation::claimId)
                .collect(java.util.stream.Collectors.toSet());
        boolean hasUsefulRelation = event.evidence().stream()
                .flatMap(evidence -> evidence.relations().stream())
                .map(relation -> normalize(relation.adjudicatedRelation()))
                .anyMatch(relation -> "entails".equals(relation) || "partial".equals(relation));
        return hasUsefulRelation && relatedIds.containsAll(verifiableIds);
    }

    private static Integer positiveMetadataInt(Map<String, String> metadata, String key) {
        if (metadata == null) return null;
        String raw = metadata.get(key);
        if (raw == null || raw.isBlank()) return null;
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Map<String, SliceSummary> scoreSlices(ValidationResult validated,
                                                          RetrievalScore retrieval,
                                                          ResolvedConfig config) {
        Map<String, List<ParsedGoldEvent>> grouped = new TreeMap<>();
        for (ParsedGoldEvent gold : validated.goldById().values()) {
            gold.value().slices().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> grouped.computeIfAbsent(entry.getKey() + "=" + entry.getValue(),
                            ignored -> new ArrayList<>()).add(gold));
        }
        Map<String, SliceSummary> result = new LinkedHashMap<>();
        grouped.forEach((slice, goldEvents) -> {
            Map<String, MetricSummary> sliceMetrics = new LinkedHashMap<>();
            if (validated.systemEvents().isEmpty()) {
                List<String> missing = List.of(
                        "final systemEvents is empty; candidate-only run has no slice retrieval/freshness observations");
                sliceMetrics.put("eventRecall", unavailableMetric("ratio",
                        "not applicable to candidate-only run", missing));
                sliceMetrics.put("evidenceReadyEventRecall", unavailableMetric("ratio",
                        "not applicable to candidate-only run", missing));
                sliceMetrics.put("importanceWeightedRecall", unavailableMetric("ratio",
                        "not applicable to candidate-only run", missing));
                for (int cutoff : config.freshnessCutoffsMinutes()) {
                    sliceMetrics.put("recallAt" + cutoff + "Minutes", unavailableMetric("ratio",
                            "not applicable to candidate-only run", missing));
                    sliceMetrics.put("evidenceReadyRecallAt" + cutoff + "Minutes",
                            unavailableMetric("ratio", "not applicable to candidate-only run", missing));
                }
                result.put(slice, new SliceSummary(goldEvents.size(), Map.copyOf(sliceMetrics), missing));
                return;
            }
            long detected = goldEvents.stream()
                    .filter(gold -> retrieval.earliestByGold().containsKey(gold.value().eventId()))
                    .count();
            sliceMetrics.put("eventRecall", ratioMetric(detected, goldEvents.size(),
                    "ratio", "detected gold events / gold events in slice", true));
            long evidenceReady = goldEvents.stream()
                    .filter(gold -> retrieval.earliestEvidenceReadyByGold()
                            .containsKey(gold.value().eventId()))
                    .count();
            sliceMetrics.put("evidenceReadyEventRecall", ratioMetric(evidenceReady,
                    goldEvents.size(), "ratio",
                    "gold events with an evidence-ready system card in slice", true));
            double totalWeight = goldEvents.stream()
                    .mapToDouble(gold -> importanceWeight(gold.value().importance())).sum();
            double detectedWeight = goldEvents.stream()
                    .filter(gold -> retrieval.earliestByGold().containsKey(gold.value().eventId()))
                    .mapToDouble(gold -> importanceWeight(gold.value().importance())).sum();
            sliceMetrics.put("importanceWeightedRecall", boundedAverageMetric(detectedWeight, totalWeight,
                    "ratio", "importance-weighted recall in slice"));
            for (int cutoff : config.freshnessCutoffsMinutes()) {
                long within = goldEvents.stream().filter(gold -> {
                    ParsedSystemEvent event = retrieval.earliestByGold().get(gold.value().eventId());
                    return event != null && effectiveLagMinutes(gold, event) <= cutoff;
                }).count();
                sliceMetrics.put("recallAt" + cutoff + "Minutes", ratioMetric(within,
                        goldEvents.size(), "ratio", "slice freshness recall", true));
                long evidenceReadyWithin = goldEvents.stream().filter(gold -> {
                    ParsedSystemEvent event = retrieval.earliestEvidenceReadyByGold()
                            .get(gold.value().eventId());
                    return event != null && effectiveLagMinutes(gold, event) <= cutoff;
                }).count();
                sliceMetrics.put("evidenceReadyRecallAt" + cutoff + "Minutes", ratioMetric(
                        evidenceReadyWithin, goldEvents.size(), "ratio",
                        "slice evidence-ready freshness recall", true));
            }
            List<String> sliceWarnings = goldEvents.size() < SMALL_SAMPLE_THRESHOLD
                    ? List.of("small slice: N=" + goldEvents.size() + " < " + SMALL_SAMPLE_THRESHOLD)
                    : List.of();
            result.put(slice, new SliceSummary(goldEvents.size(), Map.copyOf(sliceMetrics), sliceWarnings));
        });
        return result;
    }

    private static void scoreClustering(List<ClusterAssignment> assignments,
                                        Integer declaredUniverseSize,
                                        List<Badcase> badcases,
                                        Map<String, MetricSummary> metrics,
                                        List<String> warnings) {
        if (assignments.isEmpty()) {
            List<String> missing = List.of("no independently adjudicated cluster assignments");
            metrics.put("clustering.assignmentCoverage", unavailableMetric("ratio",
                    "assigned cluster items / declared cluster universe", missing));
            metrics.put("clustering.bcubedPrecision", unavailableMetric("ratio",
                    "B-Cubed precision", missing));
            metrics.put("clustering.bcubedRecall", unavailableMetric("ratio",
                    "B-Cubed recall", missing));
            metrics.put("clustering.bcubedF1", unavailableMetric("ratio",
                    "B-Cubed F1", missing));
            warnings.add("clustering metrics unavailable: clusterAssignments is empty");
            return;
        }

        if (declaredUniverseSize == null) {
            metrics.put("clustering.assignmentCoverage", unavailableMetric("ratio",
                    "assigned cluster items / declared cluster universe",
                    List.of("executionMetadata.clusterUniverseItemCount is missing")));
            warnings.add("clustering coverage unavailable: declare clusterUniverseItemCount");
        } else {
            metrics.put("clustering.assignmentCoverage", ratioMetric(assignments.size(),
                    declaredUniverseSize, "ratio",
                    "assigned cluster items / declared cluster universe", false));
            if (assignments.size() != declaredUniverseSize) {
                warnings.add("clustering assignment coverage is incomplete: expected "
                        + declaredUniverseSize + ", got " + assignments.size());
                badcases.add(new Badcase("cluster-coverage", "clusterAssignments", "",
                        "declared universe=" + declaredUniverseSize
                                + ", assigned=" + assignments.size()));
            }
        }

        Map<String, Long> goldSizes = frequency(assignments, true);
        Map<String, Long> predictedSizes = frequency(assignments, false);
        Map<ClusterPair, Long> intersections = new LinkedHashMap<>();
        for (ClusterAssignment item : assignments) {
            intersections.merge(new ClusterPair(item.goldClusterId(), item.predictedClusterId()),
                    1L, Long::sum);
        }
        double precisionSum = 0.0D;
        double recallSum = 0.0D;
        for (ClusterAssignment item : assignments) {
            long intersection = intersections.get(new ClusterPair(item.goldClusterId(),
                    item.predictedClusterId()));
            precisionSum += (double) intersection / predictedSizes.get(item.predictedClusterId());
            recallSum += (double) intersection / goldSizes.get(item.goldClusterId());
        }
        double bcubedPrecision = precisionSum / assignments.size();
        double bcubedRecall = recallSum / assignments.size();
        List<String> correlatedWarning = List.of(
                "item scores share clusters; no binomial confidence interval is reported");
        metrics.put("clustering.bcubedPrecision", scalarMetric(bcubedPrecision, "ratio",
                "mean per-item B-Cubed precision (over-merge sensitivity)", correlatedWarning));
        metrics.put("clustering.bcubedRecall", scalarMetric(bcubedRecall, "ratio",
                "mean per-item B-Cubed recall (over-split sensitivity)", correlatedWarning));
        metrics.put("clustering.bcubedF1", scalarMetric(harmonic(bcubedPrecision, bcubedRecall),
                "ratio", "harmonic mean of B-Cubed precision and recall", correlatedWarning));

        long truePositivePairs = intersections.values().stream().mapToLong(AiNewsDiscoveryQualityEvaluator::pairs).sum();
        long predictedPairs = predictedSizes.values().stream().mapToLong(AiNewsDiscoveryQualityEvaluator::pairs).sum();
        long goldPairs = goldSizes.values().stream().mapToLong(AiNewsDiscoveryQualityEvaluator::pairs).sum();
        long falsePositivePairs = predictedPairs - truePositivePairs;
        long falseNegativePairs = goldPairs - truePositivePairs;
        metrics.put("clustering.pairwisePrecision", ratioMetric(truePositivePairs, predictedPairs,
                "ratio", "same predicted and gold pairs / predicted same-cluster pairs", false));
        metrics.put("clustering.pairwiseRecall", ratioMetric(truePositivePairs, goldPairs,
                "ratio", "same predicted and gold pairs / gold same-cluster pairs", false));
        metrics.put("clustering.pairwiseF1", scalarMetric(harmonic(
                ratio(truePositivePairs, predictedPairs), ratio(truePositivePairs, goldPairs)),
                "ratio", "pairwise clustering F1"));
        metrics.put("clustering.overMergePairRate", ratioMetric(falsePositivePairs,
                predictedPairs, "ratio", "incorrectly merged pairs / predicted same-cluster pairs", false));
        metrics.put("clustering.overSplitPairRate", ratioMetric(falseNegativePairs,
                goldPairs, "ratio", "incorrectly split pairs / gold same-cluster pairs", false));
        metrics.put("clustering.predictedToGoldClusterRatio", averageMetric(
                predictedSizes.size(), goldSizes.size(), "ratio", "predicted cluster count / gold cluster count"));

        if (falsePositivePairs > 0) {
            badcases.add(new Badcase("cluster-over-merge", "clusterAssignments", "",
                    falsePositivePairs + " gold-distinct pair(s) were merged"));
        }
        if (falseNegativePairs > 0) {
            badcases.add(new Badcase("cluster-over-split", "clusterAssignments", "",
                    falseNegativePairs + " gold-identical pair(s) were split"));
        }
    }

    private static EvidenceCounts scoreEvidence(List<ParsedSystemEvent> events,
                                                List<Badcase> badcases,
                                                Map<String, MetricSummary> metrics,
                                                List<String> warnings) {
        long eventCount = events.size();
        long eventsWithEvidence = 0;
        long eventsWithOfficial = 0;
        long eventsWithFetchable = 0;
        long claims = 0;
        long verifiableClaims = 0;
        long supportedClaims = 0;
        long evidenceCount = 0;
        long fetchSuccess = 0;
        long parseableTimestamps = 0;
        long correctTimestamps = 0;
        long validUrls = 0;
        long completeProvenance = 0;
        long sourceTierCorrect = 0;
        long citationRelations = 0;
        long preciseCitations = 0;

        for (ParsedSystemEvent parsed : events) {
            SystemEvent event = parsed.value();
            Map<String, ClaimAssessment> claimById = new LinkedHashMap<>();
            Set<String> jointlySupportedClaims = new LinkedHashSet<>();
            Set<String> eligibleSupportedClaims = new LinkedHashSet<>();
            Set<String> contradictedClaims = new LinkedHashSet<>();
            for (ClaimAssessment claim : event.claims()) {
                claims++;
                claimById.put(claim.claimId(), claim);
                if (claim.verifiable()) {
                    verifiableClaims++;
                    if (Boolean.TRUE.equals(claim.jointlySupported())) {
                        jointlySupportedClaims.add(claim.claimId());
                    } else {
                        badcases.add(new Badcase("unsupported-claim", event.systemEventId(),
                                claim.claimId(), defaultText(claim.text(), "verifiable claim lacks full joint support")));
                    }
                }
            }
            if (!event.evidence().isEmpty()) eventsWithEvidence++;
            boolean official = false;
            boolean fetchable = false;
            for (EvidenceAssessment evidence : event.evidence()) {
                evidenceCount++;
                String adjudicatedTier = normalize(evidence.adjudicatedSourceTier());
                String predictedTier = normalize(evidence.predictedSourceTier());
                if ("official".equals(adjudicatedTier)) official = true;
                if (adjudicatedTier.equals(predictedTier)) {
                    sourceTierCorrect++;
                } else {
                    badcases.add(new Badcase("source-tier-mismatch", event.systemEventId(),
                            evidence.evidenceId(), "expected=" + adjudicatedTier + ", actual=" + predictedTier));
                }
                if (Boolean.TRUE.equals(evidence.fetchSucceeded())) {
                    fetchSuccess++;
                    fetchable = true;
                } else {
                    badcases.add(new Badcase("evidence-fetch-failure", event.systemEventId(),
                            evidence.evidenceId(), defaultText(evidence.sourceUrl(), "missing source URL")));
                }
                boolean validUrl = isAbsoluteHttpUrl(evidence.sourceUrl());
                if (validUrl) {
                    validUrls++;
                } else {
                    badcases.add(new Badcase("invalid-evidence-url", event.systemEventId(),
                            evidence.evidenceId(), defaultText(evidence.sourceUrl(), "missing source URL")));
                }
                boolean timestampPresent = parseOptionalInstant(evidence.sourcePublishedAt()) != null;
                if (timestampPresent) parseableTimestamps++;
                if (timestampPresent && Boolean.TRUE.equals(evidence.publishedAtCorrect())) {
                    correctTimestamps++;
                } else if (!timestampPresent || Boolean.FALSE.equals(evidence.publishedAtCorrect())) {
                    badcases.add(new Badcase("evidence-timestamp", event.systemEventId(),
                            evidence.evidenceId(), timestampPresent
                            ? "source publication timestamp was adjudicated incorrect"
                            : "source publication timestamp is missing or invalid"));
                }
                if (validUrl && !defaultText(evidence.sourceTitle(), "").isBlank()
                        && timestampPresent && SOURCE_TIERS.contains(adjudicatedTier)) {
                    completeProvenance++;
                }
                for (ClaimEvidenceRelation relation : evidence.relations()) {
                    ClaimAssessment claim = claimById.get(relation.claimId());
                    if (claim == null || !claim.verifiable()) continue;
                    citationRelations++;
                    String relationValue = normalize(relation.adjudicatedRelation());
                    if ("contradicts".equals(relationValue)
                            && jointlySupportedClaims.contains(claim.claimId())) {
                        contradictedClaims.add(claim.claimId());
                    }
                    boolean precise = Boolean.TRUE.equals(claim.jointlySupported())
                            && ("entails".equals(relationValue) || "partial".equals(relationValue));
                    if (precise) {
                        preciseCitations++;
                    } else {
                        badcases.add(new Badcase("irrelevant-or-insufficient-citation",
                                event.systemEventId(), evidence.evidenceId() + "->" + relation.claimId(),
                                "relation=" + relationValue + ", jointlySupported=" + claim.jointlySupported()));
                    }
                    if (isTechnicallyEligibleEvidence(evidence)
                            && precise) {
                        eligibleSupportedClaims.add(claim.claimId());
                    }
                }
            }
            // Citation recall is counted only when the adjudicated support is
            // backed by a technically usable, time-correct source.  This
            // prevents a hand-written `jointlySupported=true` label on an
            // unfetchable/invalid URL from inflating the headline metric.
            supportedClaims += eligibleSupportedClaims.stream()
                    .filter(claimId -> !contradictedClaims.contains(claimId))
                    .count();
            if (official) eventsWithOfficial++;
            if (fetchable) eventsWithFetchable++;
            if (event.evidence().isEmpty()) {
                badcases.add(new Badcase("missing-event-evidence", event.systemEventId(),
                        event.title(), "output event has no evidence packet"));
            }
        }

        metrics.put("evidence.claimCitationRecall", ratioMetric(supportedClaims, verifiableClaims,
                "ratio", "verifiable atomic claims fully supported by their joint citations", true));
        metrics.put("evidence.citationPrecision", ratioMetric(preciseCitations, citationRelations,
                "ratio", "useful claim-citation relations / adjudicated citation relations", true));
        metrics.put("evidence.eventEvidenceCoverage", ratioMetric(eventsWithEvidence, eventCount,
                "ratio", "output events with at least one evidence packet", true));
        metrics.put("evidence.officialSourceCoverage", ratioMetric(eventsWithOfficial, eventCount,
                "ratio", "output events with at least one adjudicated official source", true));
        metrics.put("evidence.fetchableEventCoverage", ratioMetric(eventsWithFetchable, eventCount,
                "ratio", "output events with at least one successfully fetched source", true));
        metrics.put("evidence.fetchSuccessRate", ratioMetric(fetchSuccess, evidenceCount,
                "ratio", "successfully fetched evidence / evidence packets", true));
        metrics.put("evidence.validUrlRate", ratioMetric(validUrls, evidenceCount,
                "ratio", "absolute HTTP(S) evidence URLs / evidence packets", true));
        metrics.put("evidence.sourceTimestampCoverage", ratioMetric(parseableTimestamps, evidenceCount,
                "ratio", "parseable source publication timestamps / evidence packets", true));
        metrics.put("evidence.sourceTimestampAccuracy", ratioMetric(correctTimestamps,
                parseableTimestamps, "ratio", "adjudicated-correct / parseable source timestamps", true));
        metrics.put("evidence.provenanceCompleteness", ratioMetric(completeProvenance, evidenceCount,
                "ratio", "URL, title, timestamp and adjudicated tier all present", true));
        metrics.put("evidence.sourceTierAccuracy", ratioMetric(sourceTierCorrect, evidenceCount,
                "ratio", "predicted source tier equals adjudicated source tier", true));

        if (claims == 0 || evidenceCount == 0) {
            warnings.add("evidence metrics incomplete: claims or evidence packets are empty");
        }
        return new EvidenceCounts(claims, verifiableClaims, evidenceCount, citationRelations);
    }

    /**
     * Keep the operational gates visible as separate measurements. A source
     * can be technically fetchable while its claim is semantically unsupported;
     * neither implies that a human approved the item for external publication.
     */
    private static void scoreReadiness(List<ParsedSystemEvent> events,
                                       Map<String, MetricSummary> metrics,
                                       List<String> warnings) {
        long relevant = events.stream()
                .filter(item -> isRelevant(item.value()))
                .count();
        if (relevant == 0) {
            List<String> missing = List.of("no adjudicated relevant output events");
            metrics.put("readiness.technicalReadyRate", unavailableMetric("ratio",
                    "relevant outputs with at least one technically eligible evidence packet / relevant outputs", missing));
            metrics.put("readiness.semanticReadyRate", unavailableMetric("ratio",
                    "relevant outputs with complete claim-level semantic support / relevant outputs", missing));
        } else {
            long technical = events.stream()
                    .filter(item -> isRelevant(item.value()))
                    .filter(item -> item.value().evidence().stream()
                            .anyMatch(AiNewsDiscoveryQualityEvaluator::isTechnicallyEligibleEvidence))
                    .count();
            long semantic = events.stream()
                    .filter(item -> isRelevant(item.value()))
                    .filter(item -> isSemanticallyReady(item.value()))
                    .count();
            metrics.put("readiness.technicalReadyRate", ratioMetric(technical, relevant,
                    "ratio", "relevant outputs with at least one technically eligible evidence packet / relevant outputs", true));
            metrics.put("readiness.semanticReadyRate", ratioMetric(semantic, relevant,
                    "ratio", "relevant outputs with complete claim-level semantic support / relevant outputs", true));
        }
        // SystemEvent intentionally has no authoritative workflow approval or
        // platform acknowledgement field. Do not infer publishability from a
        // model status string; make the missing contract explicit instead.
        metrics.put("readiness.publishReadyRate", unavailableMetric("ratio",
                "outputs with server approval and platform publication acknowledgement / relevant outputs",
                List.of("dataset does not contain an authoritative human-approval/platform-ack annotation")));
        warnings.add("publish readiness is reported as N/A until the evaluation dataset records server approval and platform acknowledgement");
    }

    private static boolean isSemanticallyReady(SystemEvent event) {
        List<ClaimAssessment> verifiable = event.claims().stream()
                .filter(ClaimAssessment::verifiable)
                .toList();
        if (verifiable.isEmpty() || verifiable.stream()
                .anyMatch(claim -> !Boolean.TRUE.equals(claim.jointlySupported()))) {
            return false;
        }
        Set<String> ids = verifiable.stream().map(ClaimAssessment::claimId)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> useful = new LinkedHashSet<>();
        for (EvidenceAssessment evidence : event.evidence()) {
            for (ClaimEvidenceRelation relation : evidence.relations()) {
                if (!ids.contains(relation.claimId())) continue;
                String value = normalize(relation.adjudicatedRelation());
                if ("contradicts".equals(value)) return false;
                if ("entails".equals(value) || "partial".equals(value)) {
                    useful.add(relation.claimId());
                }
            }
        }
        return useful.containsAll(ids);
    }

    private static List<RankingSnapshotSummary> scoreRanking(ValidationResult validated,
                                                              ResolvedConfig config,
                                                              Map<String, MetricSummary> metrics,
                                                              List<String> warnings) {
        if (validated.dataset().rankingSnapshots().isEmpty()) {
            for (int cutoff : config.rankingCutoffs()) {
                List<String> missing = List.of("no ranking snapshots");
                metrics.put("ranking.ndcgAt" + cutoff, unavailableMetric("ratio",
                        "macro nDCG@" + cutoff, missing));
                metrics.put("ranking.novelPrecisionAt" + cutoff, unavailableMetric("ratio",
                        "macro novelty-aware Precision@" + cutoff, missing));
                metrics.put("ranking.eventRecallAt" + cutoff, unavailableMetric("ratio",
                        "macro event Recall@" + cutoff, missing));
            }
            metrics.put("ranking.eligibleSnapshotRate", unavailableMetric("ratio",
                    "ranking snapshots with a non-empty qrel / ranking snapshots",
                    List.of("no ranking snapshots")));
            warnings.add("ranking metrics unavailable: rankingSnapshots is empty");
            return List.of();
        }

        Map<Integer, List<Double>> ndcgByCutoff = new LinkedHashMap<>();
        Map<Integer, List<Double>> precisionByCutoff = new LinkedHashMap<>();
        Map<Integer, List<Double>> recallByCutoff = new LinkedHashMap<>();
        config.rankingCutoffs().forEach(cutoff -> {
            ndcgByCutoff.put(cutoff, new ArrayList<>());
            precisionByCutoff.put(cutoff, new ArrayList<>());
            recallByCutoff.put(cutoff, new ArrayList<>());
        });

        List<RankingSnapshotSummary> summaries = new ArrayList<>();
        for (RankingSnapshot snapshot : validated.dataset().rankingSnapshots()) {
            Instant snapshotAt = parseInstant(snapshot.at(), "ranking snapshot " + snapshot.snapshotId() + " at");
            Instant lower = snapshotAt.minus(Duration.ofMinutes(config.rankingLookbackMinutes()));
            Map<String, ParsedGoldEvent> eligible = new LinkedHashMap<>();
            validated.goldById().forEach((id, gold) -> {
                if (!gold.publishedAt().isBefore(lower) && !gold.publishedAt().isAfter(snapshotAt)) {
                    eligible.put(id, gold);
                }
            });
            List<ParsedSystemEvent> ranked = snapshot.rankedSystemEventIds().stream()
                    .map(validated.systemById()::get)
                    .toList();
            Map<String, MetricSummary> snapshotMetrics = new LinkedHashMap<>();
            if (eligible.isEmpty()) {
                List<String> missing = List.of(
                        "empty qrel: no gold event is eligible at this ranking snapshot");
                for (int cutoff : config.rankingCutoffs()) {
                    snapshotMetrics.put("ndcgAt" + cutoff,
                            unavailableMetric("ratio", "empty-qrel snapshot", missing));
                    snapshotMetrics.put("novelPrecisionAt" + cutoff,
                            unavailableMetric("ratio", "empty-qrel snapshot", missing));
                    snapshotMetrics.put("eventRecallAt" + cutoff,
                            unavailableMetric("ratio", "empty-qrel snapshot", missing));
                }
                warnings.add("ranking snapshot " + snapshot.snapshotId()
                        + " has an empty qrel; excluded from macro ranking metrics");
                summaries.add(new RankingSnapshotSummary(snapshot.snapshotId(), snapshot.at(),
                        0L, ranked.size(), Map.copyOf(snapshotMetrics)));
                continue;
            }
            for (int cutoff : config.rankingCutoffs()) {
                Set<String> seenGold = new LinkedHashSet<>();
                Set<String> seenRelevant = new LinkedHashSet<>();
                List<Integer> gains = new ArrayList<>();
                int limit = Math.min(cutoff, ranked.size());
                for (int index = 0; index < limit; index++) {
                    SystemEvent event = ranked.get(index).value();
                    String matched = event.matchedGoldEventId();
                    if (matched != null && eligible.containsKey(matched) && seenGold.add(matched)) {
                        // Ranking qrels are defined by the snapshot's eligible gold
                        // window. Unmatched/novel adjudications (and gold events
                        // outside this lookback) must not inflate novelty precision.
                        seenRelevant.add(matched);
                        gains.add(eligible.get(matched).value().importance());
                    } else {
                        gains.add(0);
                    }
                }
                while (gains.size() < cutoff) gains.add(0);
                List<Integer> ideal = eligible.values().stream()
                        .map(item -> item.value().importance())
                        .sorted(Comparator.reverseOrder())
                        .toList();
                double dcg = dcg(gains, cutoff);
                double idcg = dcg(ideal, cutoff);
                Double ndcg = idcg == 0.0D ? null : dcg / idcg;
                long matchedRelevant = gains.stream().limit(cutoff).filter(value -> value > 0).count();
                long novelRelevant = seenRelevant.size();
                Double precision = ratio(novelRelevant, cutoff);
                Double recall = ratio(matchedRelevant, eligible.size());
                List<String> macroWarning = List.of(
                        "snapshot-level score; report macro mean across snapshots");
                snapshotMetrics.put("ndcgAt" + cutoff, scalarMetric(ndcg, "ratio",
                        "linear-gain nDCG@" + cutoff + " with duplicate matches scored zero", macroWarning));
                snapshotMetrics.put("novelPrecisionAt" + cutoff, ratioMetric(novelRelevant, cutoff,
                        "ratio", "distinct adjudicated relevant events in top " + cutoff + " / " + cutoff, true));
                snapshotMetrics.put("eventRecallAt" + cutoff, ratioMetric(matchedRelevant, eligible.size(),
                        "ratio", "eligible gold events found in top " + cutoff, true));
                if (ndcg != null) ndcgByCutoff.get(cutoff).add(ndcg);
                if (precision != null) precisionByCutoff.get(cutoff).add(precision);
                if (recall != null) recallByCutoff.get(cutoff).add(recall);
            }
            summaries.add(new RankingSnapshotSummary(snapshot.snapshotId(), snapshot.at(),
                    eligible.size(), ranked.size(), Map.copyOf(snapshotMetrics)));
        }

        for (int cutoff : config.rankingCutoffs()) {
            metrics.put("ranking.ndcgAt" + cutoff, meanMetric(ndcgByCutoff.get(cutoff),
                    "ratio", "macro mean of snapshot nDCG@" + cutoff));
            metrics.put("ranking.novelPrecisionAt" + cutoff, meanMetric(precisionByCutoff.get(cutoff),
                    "ratio", "macro mean of novelty-aware Precision@" + cutoff));
            metrics.put("ranking.eventRecallAt" + cutoff, meanMetric(recallByCutoff.get(cutoff),
                    "ratio", "macro mean of event Recall@" + cutoff));
        }
        long eligibleSnapshots = summaries.stream()
                .filter(item -> item.eligibleGoldEvents() > 0).count();
        metrics.put("ranking.eligibleSnapshotRate", ratioMetric(eligibleSnapshots,
                summaries.size(), "ratio",
                "ranking snapshots with a non-empty qrel / ranking snapshots", true));
        return List.copyOf(summaries);
    }

    private static ValidationResult validateAndParse(DiscoveryDataset dataset,
                                                      ResolvedConfig config,
                                                      ParsedWindow window) {
        if (!"1.0".equals(dataset.schemaVersion())) {
            throw new IllegalArgumentException("schemaVersion must be 1.0");
        }
        requireText(dataset.datasetId(), "datasetId");
        requireText(dataset.datasetVersion(), "datasetVersion");
        requireText(dataset.evaluationScope(), "evaluationScope");
        if (dataset.goldEvents().isEmpty()) {
            throw new IllegalArgumentException("goldEvents must contain the independently adjudicated event universe");
        }
        Set<String> goldIds = new LinkedHashSet<>();
        Map<String, ParsedGoldEvent> goldById = new LinkedHashMap<>();
        List<GoldEvent> normalizedGoldEvents = new ArrayList<>();
        for (GoldEvent event : dataset.goldEvents()) {
            String eventId = requireText(event.eventId(), "gold event id");
            if (!goldIds.add(eventId)) {
                throw new IllegalArgumentException("duplicate gold event id: " + eventId);
            }
            if (event.importance() < 1 || event.importance() > 3) {
                throw new IllegalArgumentException("gold event " + eventId
                        + " importance must be 1, 2 or 3");
            }
            Instant publishedAt = parseInstant(event.firstPublishedAt(),
                    "gold event " + eventId + " firstPublishedAt");
            if (publishedAt.isBefore(window.startAt()) || !publishedAt.isBefore(window.endAt())) {
                throw new IllegalArgumentException("gold event " + eventId
                        + " firstPublishedAt must be within [startAt, endAt)");
            }
            event.slices().forEach((key, value) -> {
                requireText(key, "slice key for gold event " + eventId);
                requireText(value, "slice value for gold event " + eventId);
            });
            GoldEvent normalized = new GoldEvent(eventId, event.title(), event.firstPublishedAt(),
                    event.importance(), event.slices());
            normalizedGoldEvents.add(normalized);
            goldById.put(eventId, new ParsedGoldEvent(normalized, publishedAt));
        }

        Set<String> candidateIds = new LinkedHashSet<>();
        Set<Integer> candidateRanks = new LinkedHashSet<>();
        List<DiscoveryCandidateAssessment> normalizedCandidates = new ArrayList<>();
        for (DiscoveryCandidateAssessment candidate : dataset.discoveryCandidates()) {
            String candidateId = requireText(candidate.candidateId(), "discovery candidate id");
            if (!candidateIds.add(candidateId)) {
                throw new IllegalArgumentException("duplicate discovery candidate id: "
                        + candidateId);
            }
            if (candidate.rank() <= 0 || !candidateRanks.add(candidate.rank())) {
                throw new IllegalArgumentException("discovery candidate ranks must be unique positive integers");
            }
            String title = requireText(candidate.title(), "discovery candidate title " + candidateId);
            String url = requireText(candidate.url(), "discovery candidate URL " + candidateId);
            String matched = emptyToNull(candidate.matchedGoldEventId());
            if (matched != null && !goldById.containsKey(matched)) {
                throw new IllegalArgumentException("discovery candidate " + candidate.candidateId()
                        + " references unknown matchedGoldEventId: " + matched);
            }
            if (matched == null && defaultText(candidate.adjudicationReason(), "").isBlank()) {
                throw new IllegalArgumentException("unmatched discovery candidate "
                        + candidate.candidateId() + " requires adjudicationReason");
            }
            normalizedCandidates.add(new DiscoveryCandidateAssessment(candidateId, candidate.rank(), title, url,
                    candidate.sourceClass(), candidate.publishedAtHint(), matched,
                    emptyToNull(candidate.adjudicationReason())));
        }
        for (int expectedRank = 1; expectedRank <= candidateRanks.size(); expectedRank++) {
            if (!candidateRanks.contains(expectedRank)) {
                throw new IllegalArgumentException("discovery candidate ranks must be contiguous from 1");
            }
        }

        Set<String> systemIds = new LinkedHashSet<>();
        Map<String, ParsedSystemEvent> systemById = new LinkedHashMap<>();
        List<ParsedSystemEvent> systemEvents = new ArrayList<>();
        for (SystemEvent event : dataset.systemEvents()) {
            String systemEventId = requireText(event.systemEventId(), "system event id");
            if (!systemIds.add(systemEventId)) {
                throw new IllegalArgumentException("duplicate system event id: " + systemEventId);
            }
            Instant detectedAt = parseInstant(event.detectedAt(),
                    "system event " + systemEventId + " detectedAt");
            if (detectedAt.isBefore(window.startAt()) || detectedAt.isAfter(window.observationEndAt())) {
                throw new IllegalArgumentException("system event " + systemEventId
                        + " detectedAt must be within [startAt, observationEndAt]");
            }
            String matched = emptyToNull(event.matchedGoldEventId());
            if (matched != null && !goldById.containsKey(matched)) {
                throw new IllegalArgumentException("system event " + systemEventId
                        + " references unknown matchedGoldEventId: " + matched);
            }
            if (matched == null && defaultText(event.adjudicationReason(), "").isBlank()) {
                throw new IllegalArgumentException("unmatched system event " + systemEventId
                        + " requires adjudicationReason");
            }
            if (matched != null && Boolean.FALSE.equals(event.adjudicatedRelevant())) {
                throw new IllegalArgumentException("matched system event " + systemEventId
                        + " cannot be adjudicatedRelevant=false");
            }
            String adjudicatedEventId = emptyToNull(event.adjudicatedEventId());
            if (matched != null && adjudicatedEventId != null && !matched.equals(adjudicatedEventId)) {
                throw new IllegalArgumentException("system event " + systemEventId
                        + " has conflicting matchedGoldEventId and adjudicatedEventId");
            }
            if (matched == null && Boolean.TRUE.equals(event.adjudicatedRelevant())
                    && adjudicatedEventId == null) {
                throw new IllegalArgumentException("relevant unmatched system event "
                        + systemEventId + " requires adjudicatedEventId");
            }
            if (matched == null && Boolean.FALSE.equals(event.adjudicatedRelevant())
                    && adjudicatedEventId != null) {
                throw new IllegalArgumentException("false-positive system event " + systemEventId
                        + " must not carry adjudicatedEventId");
            }
            if (matched == null && adjudicatedEventId != null
                    && goldById.containsKey(adjudicatedEventId)) {
                throw new IllegalArgumentException("unmatched system event " + systemEventId
                        + " must use matchedGoldEventId for a gold event");
            }
            if (matched != null) {
                Instant earliestAllowed = goldById.get(matched).publishedAt()
                        .minus(Duration.ofMinutes(config.earlyDetectionToleranceMinutes()));
                if (detectedAt.isBefore(earliestAllowed)) {
                    throw new IllegalArgumentException("system event " + systemEventId
                            + " precedes gold firstPublishedAt beyond earlyDetectionToleranceMinutes;"
                            + " audit timestamp leakage or the gold ledger");
                }
            }
            validateEvidenceAnnotations(event, detectedAt);
            SystemEvent normalized = new SystemEvent(systemEventId, event.title(), event.detectedAt(),
                    matched, event.adjudicatedRelevant(), matched == null ? adjudicatedEventId : matched,
                    event.adjudicationReason(), event.claims(), event.evidence());
            ParsedSystemEvent parsed = new ParsedSystemEvent(normalized, detectedAt);
            systemEvents.add(parsed);
            systemById.put(normalized.systemEventId(), parsed);
        }

        validateClusterAssignments(dataset.clusterAssignments(), systemById.keySet(),
                normalizedCandidates, systemEvents);
        validateRankingSnapshots(dataset.rankingSnapshots(), config, window, systemById);
        List<ParsedSystemEvent> outputWindowEvents = systemEvents.stream()
                .filter(event -> !event.detectedAt().isBefore(window.startAt())
                        && event.detectedAt().isBefore(window.endAt()))
                .toList();
        DiscoveryDataset normalizedDataset = new DiscoveryDataset(dataset.schemaVersion(), dataset.datasetId(),
                dataset.datasetVersion(), dataset.evaluationScope(), dataset.window(), dataset.config(),
                dataset.executionMetadata(), normalizedGoldEvents, normalizedCandidates, systemEvents.stream()
                        .map(ParsedSystemEvent::value).toList(), dataset.clusterAssignments(),
                dataset.rankingSnapshots(), dataset.limitations());
        return new ValidationResult(normalizedDataset, window, Map.copyOf(goldById), Map.copyOf(systemById),
                List.copyOf(systemEvents), outputWindowEvents);
    }

    private static void validateEvidenceAnnotations(SystemEvent event, Instant detectedAt) {
        Set<String> claimIds = new LinkedHashSet<>();
        for (ClaimAssessment claim : event.claims()) {
            requireText(claim.claimId(), "claim id for system event " + event.systemEventId());
            if (!claimIds.add(claim.claimId())) {
                throw new IllegalArgumentException("duplicate claim id " + claim.claimId()
                        + " in system event " + event.systemEventId());
            }
            if (claim.verifiable() && claim.jointlySupported() == null) {
                throw new IllegalArgumentException("verifiable claim " + claim.claimId()
                        + " requires independent jointlySupported adjudication");
            }
        }
        Set<String> evidenceIds = new LinkedHashSet<>();
        Set<String> claimsWithRelations = new LinkedHashSet<>();
        Set<String> claimsWithUsefulRelations = new LinkedHashSet<>();
        Set<String> relationKeys = new LinkedHashSet<>();
        for (EvidenceAssessment evidence : event.evidence()) {
            requireText(evidence.evidenceId(), "evidence id for system event " + event.systemEventId());
            if (!evidenceIds.add(evidence.evidenceId())) {
                throw new IllegalArgumentException("duplicate evidence id " + evidence.evidenceId()
                        + " in system event " + event.systemEventId());
            }
            String tier = normalize(evidence.adjudicatedSourceTier());
            if (!SOURCE_TIERS.contains(tier)) {
                throw new IllegalArgumentException("evidence " + evidence.evidenceId()
                        + " requires adjudicatedSourceTier=official|media|community");
            }
            if (evidence.publishedAtCorrect() == null) {
                throw new IllegalArgumentException("evidence " + evidence.evidenceId()
                        + " requires publishedAtCorrect adjudication");
            }
            Instant sourcePublishedAt = parseOptionalInstant(evidence.sourcePublishedAt());
            if (sourcePublishedAt != null && sourcePublishedAt.isAfter(detectedAt)) {
                throw new IllegalArgumentException("evidence " + evidence.evidenceId()
                        + " has sourcePublishedAt after system detectedAt; future evidence indicates timestamp leakage");
            }
            for (ClaimEvidenceRelation relation : evidence.relations()) {
                if (!claimIds.contains(relation.claimId())) {
                    throw new IllegalArgumentException("evidence " + evidence.evidenceId()
                            + " references unknown claimId " + relation.claimId());
                }
                String relationValue = normalize(relation.adjudicatedRelation());
                if (!EVIDENCE_RELATIONS.contains(relationValue)) {
                    throw new IllegalArgumentException("evidence relation for " + relation.claimId()
                            + " must be one of " + EVIDENCE_RELATIONS);
                }
                if (!relationKeys.add(relation.claimId() + "\u0000" + relationValue + "\u0000" + evidence.evidenceId())) {
                    throw new IllegalArgumentException("duplicate claim-evidence relation for "
                            + relation.claimId() + " in evidence " + evidence.evidenceId());
                }
                claimsWithRelations.add(relation.claimId());
                if ("entails".equals(relationValue) || "partial".equals(relationValue)) {
                    claimsWithUsefulRelations.add(relation.claimId());
                }
            }
        }
        for (ClaimAssessment claim : event.claims()) {
            if (claim.verifiable() && Boolean.TRUE.equals(claim.jointlySupported())
                    && !claimsWithUsefulRelations.contains(claim.claimId())) {
                throw new IllegalArgumentException("jointly supported claim " + claim.claimId()
                        + " has no entails/partial claim-evidence relation");
            }
        }
    }

    private static void validateClusterAssignments(List<ClusterAssignment> assignments,
                                                   Set<String> systemEventIds,
                                                   List<DiscoveryCandidateAssessment> candidates,
                                                   List<ParsedSystemEvent> systemEvents) {
        Set<String> itemIds = new LinkedHashSet<>();
        Set<String> linkedIds = new LinkedHashSet<>(systemEventIds);
        Set<String> candidateIds = new LinkedHashSet<>();
        for (DiscoveryCandidateAssessment candidate : candidates) {
            candidateIds.add(candidate.candidateId());
            linkedIds.add(candidate.candidateId());
        }
        Set<String> evidenceIds = new LinkedHashSet<>();
        for (ParsedSystemEvent event : systemEvents) {
            for (EvidenceAssessment evidence : event.value().evidence()) {
                if (!evidenceIds.add(evidence.evidenceId())) {
                    throw new IllegalArgumentException("duplicate evidence id across system events: "
                            + evidence.evidenceId());
                }
                linkedIds.add(evidence.evidenceId());
            }
        }
        Set<String> overlappingIds = new LinkedHashSet<>(systemEventIds);
        overlappingIds.retainAll(candidateIds);
        Set<String> systemAndEvidence = new LinkedHashSet<>(systemEventIds);
        systemAndEvidence.retainAll(evidenceIds);
        overlappingIds.addAll(systemAndEvidence);
        Set<String> candidateAndEvidence = new LinkedHashSet<>(candidateIds);
        candidateAndEvidence.retainAll(evidenceIds);
        overlappingIds.addAll(candidateAndEvidence);
        if (!overlappingIds.isEmpty()) {
            throw new IllegalArgumentException("cluster item identifiers must be unique across system, candidate, "
                    + "and evidence namespaces: " + overlappingIds);
        }
        for (ClusterAssignment item : assignments) {
            requireText(item.itemId(), "cluster item id");
            requireText(item.goldClusterId(), "gold cluster id for " + item.itemId());
            requireText(item.predictedClusterId(), "predicted cluster id for " + item.itemId());
            if (!itemIds.add(item.itemId())) {
                throw new IllegalArgumentException("duplicate cluster item id: " + item.itemId());
            }
            if (!linkedIds.contains(item.itemId())) {
                throw new IllegalArgumentException("cluster item " + item.itemId()
                        + " is not linked to a system event, discovery candidate, or evidence packet");
            }
        }
    }

    private static void validateRankingSnapshots(List<RankingSnapshot> snapshots,
                                                 ResolvedConfig config,
                                                 ParsedWindow window,
                                                 Map<String, ParsedSystemEvent> systemById) {
        Set<String> snapshotIds = new LinkedHashSet<>();
        Instant earliestCompleteSnapshot = window.startAt()
                .plus(Duration.ofMinutes(config.rankingLookbackMinutes()));
        for (RankingSnapshot snapshot : snapshots) {
            requireText(snapshot.snapshotId(), "ranking snapshot id");
            if (!snapshotIds.add(snapshot.snapshotId())) {
                throw new IllegalArgumentException("duplicate ranking snapshot id: " + snapshot.snapshotId());
            }
            Instant at = parseInstant(snapshot.at(), "ranking snapshot " + snapshot.snapshotId() + " at");
            if (at.isBefore(earliestCompleteSnapshot) || at.isAfter(window.endAt())) {
                throw new IllegalArgumentException("ranking snapshot " + snapshot.snapshotId()
                        + " must be within [startAt + rankingLookbackMinutes, endAt]");
            }
            Set<String> eventIds = new LinkedHashSet<>();
            for (String eventId : snapshot.rankedSystemEventIds()) {
                if (!eventIds.add(eventId)) {
                    throw new IllegalArgumentException("ranking snapshot " + snapshot.snapshotId()
                            + " contains duplicate system event id: " + eventId);
                }
                ParsedSystemEvent event = systemById.get(eventId);
                if (event == null) {
                    throw new IllegalArgumentException("ranking snapshot " + snapshot.snapshotId()
                            + " references unknown system event id: " + eventId);
                }
                if (event.detectedAt().isAfter(at)) {
                    throw new IllegalArgumentException("ranking snapshot " + snapshot.snapshotId()
                            + " contains future system event " + eventId);
                }
            }
        }
    }

    private static ParsedWindow parseAndValidateWindow(EvaluationWindow window, ResolvedConfig config) {
        Objects.requireNonNull(window, "evaluation window is required");
        Instant start = parseInstant(window.startAt(), "window.startAt");
        Instant end = parseInstant(window.endAt(), "window.endAt");
        Instant observationEnd = parseInstant(window.observationEndAt(), "window.observationEndAt");
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("window.startAt must be before window.endAt");
        }
        int largestCutoff = config.freshnessCutoffsMinutes()
                .get(config.freshnessCutoffsMinutes().size() - 1);
        Instant requiredObservationEnd = end.plus(Duration.ofMinutes(largestCutoff));
        if (observationEnd.isBefore(requiredObservationEnd)) {
            throw new IllegalArgumentException("window.observationEndAt must be at least endAt + "
                    + largestCutoff + " minutes to avoid right-censored Recall@T");
        }
        return new ParsedWindow(start, end, observationEnd);
    }

    private static ResolvedConfig resolveConfig(EvaluationConfig config) {
        EvaluationConfig raw = config == null ? new EvaluationConfig(null, null, null, null, null) : config;
        List<Integer> freshness = normalizedPositiveIntegers(raw.freshnessCutoffsMinutes(),
                DEFAULT_FRESHNESS_CUTOFFS_MINUTES, "freshnessCutoffsMinutes");
        List<Integer> ranking = normalizedPositiveIntegers(raw.rankingCutoffs(),
                DEFAULT_RANKING_CUTOFFS, "rankingCutoffs");
        int latencyStep = raw.latencyStepMinutes() == null
                ? DEFAULT_LATENCY_STEP_MINUTES : raw.latencyStepMinutes();
        int lookback = raw.rankingLookbackMinutes() == null
                ? DEFAULT_RANKING_LOOKBACK_MINUTES : raw.rankingLookbackMinutes();
        int earlyTolerance = raw.earlyDetectionToleranceMinutes() == null
                ? 0 : raw.earlyDetectionToleranceMinutes();
        if (latencyStep <= 0) throw new IllegalArgumentException("latencyStepMinutes must be positive");
        if (lookback <= 0) throw new IllegalArgumentException("rankingLookbackMinutes must be positive");
        if (earlyTolerance < 0) {
            throw new IllegalArgumentException("earlyDetectionToleranceMinutes must be non-negative");
        }
        return new ResolvedConfig(freshness, ranking, latencyStep, lookback, earlyTolerance);
    }

    private static List<Integer> normalizedPositiveIntegers(List<Integer> values,
                                                            List<Integer> defaults,
                                                            String field) {
        List<Integer> source = values == null || values.isEmpty() ? defaults : values;
        Set<Integer> result = new LinkedHashSet<>();
        source.stream().sorted().forEach(value -> {
            if (value == null || value <= 0) {
                throw new IllegalArgumentException(field + " values must be positive integers");
            }
            result.add(value);
        });
        return List.copyOf(result);
    }

    private static final Comparator<ParsedSystemEvent> SYSTEM_EVENT_ORDER = Comparator
            .comparing(ParsedSystemEvent::detectedAt)
            .thenComparing(item -> item.value().systemEventId());

    private static Map<String, ParsedSystemEvent> earliestMatches(List<ParsedSystemEvent> events) {
        Map<String, ParsedSystemEvent> result = new LinkedHashMap<>();
        for (ParsedSystemEvent event : events) {
            String goldId = event.value().matchedGoldEventId();
            if (goldId == null || goldId.isBlank()) continue;
            result.merge(goldId, event,
                    (left, right) -> SYSTEM_EVENT_ORDER.compare(left, right) <= 0 ? left : right);
        }
        return result;
    }

    private static Map<String, List<ParsedSystemEvent>> groupMatches(List<ParsedSystemEvent> events) {
        Map<String, List<ParsedSystemEvent>> result = new LinkedHashMap<>();
        for (ParsedSystemEvent event : events) {
            String goldId = event.value().matchedGoldEventId();
            if (goldId == null || goldId.isBlank()) continue;
            result.computeIfAbsent(goldId, ignored -> new ArrayList<>()).add(event);
        }
        return result;
    }

    private static long distinctRelevantIdentities(List<ParsedSystemEvent> events) {
        return events.stream().map(item -> eventIdentity(item.value()))
                .filter(Objects::nonNull).distinct().count();
    }

    /**
     * Frozen-gold matches are relevant by construction. Independently reviewed
     * discoveries outside an intentionally frozen but non-exhaustive ledger
     * remain relevant when the annotation says so. An unmatched row without an
     * explicit relevance label is kept as open-world unknown and is excluded
     * from precision denominators until adjudicated.
     */
    private static boolean isRelevant(SystemEvent event) {
        return event.matchedGoldEventId() != null && !event.matchedGoldEventId().isBlank()
                || Boolean.TRUE.equals(event.adjudicatedRelevant());
    }

    /** A row is precision-eligible only after an explicit human relevance label. */
    private static boolean isAdjudicated(SystemEvent event) {
        return event != null
                && (emptyToNull(event.matchedGoldEventId()) != null
                || event.adjudicatedRelevant() != null);
    }

    private static boolean isExplicitFalsePositive(SystemEvent event) {
        return event != null
                && emptyToNull(event.matchedGoldEventId()) == null
                && Boolean.FALSE.equals(event.adjudicatedRelevant());
    }

    private static String eventIdentity(SystemEvent event) {
        if (!isRelevant(event)) return null;
        String matched = emptyToNull(event.matchedGoldEventId());
        return matched != null ? matched : emptyToNull(event.adjudicatedEventId());
    }

    /**
     * Transparent operational intersection used for the product north-star:
     * the card is relevant, contains at least one atomic verifiable claim, all
     * such claims are jointly supported, and at least one independently
     * annotated evidence relation has a valid, fetched URL, trusted source tier,
     * and correct publication timestamp. It intentionally does not infer source
     * trust from the system-predicted source tier.
     */
    private static boolean isEvidenceReady(ParsedSystemEvent parsed) {
        SystemEvent event = parsed.value();
        if (!isRelevant(event)) return false;
        List<ClaimAssessment> verifiable = event.claims().stream()
                .filter(ClaimAssessment::verifiable)
                .toList();
        if (verifiable.isEmpty() || verifiable.stream()
                .anyMatch(claim -> !Boolean.TRUE.equals(claim.jointlySupported()))) {
            return false;
        }
        Set<String> supportedClaimIds = verifiable.stream()
                .map(ClaimAssessment::claimId)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> eligibleClaimIds = new LinkedHashSet<>();
        Set<String> contradictedClaimIds = new LinkedHashSet<>();
        for (EvidenceAssessment evidence : event.evidence()) {
            for (ClaimEvidenceRelation relation : evidence.relations()) {
                if (!supportedClaimIds.contains(relation.claimId())) continue;
                String relationValue = normalize(relation.adjudicatedRelation());
                if ("contradicts".equals(relationValue)) {
                    // A contradictory adjudication is unresolved even when a
                    // second source happens to entail the same claim.  Fail
                    // closed instead of letting an arbitrary useful citation
                    // make the card publishable.
                    contradictedClaimIds.add(relation.claimId());
                }
                if (isTechnicallyEligibleEvidence(evidence)
                        && ("entails".equals(relationValue) || "partial".equals(relationValue))) {
                    eligibleClaimIds.add(relation.claimId());
                }
            }
        }
        // Every verifiable claim must have a useful relation on at least one
        // independently fetchable, time-correct evidence packet. One good
        // citation must not make an otherwise unsupported multi-claim card
        // publishable.
        return contradictedClaimIds.isEmpty() && eligibleClaimIds.containsAll(supportedClaimIds);
    }

    private static boolean isTechnicallyEligibleEvidence(EvidenceAssessment evidence) {
        return Boolean.TRUE.equals(evidence.fetchSucceeded())
                && isAbsoluteHttpUrl(evidence.sourceUrl())
                && parseOptionalInstant(evidence.sourcePublishedAt()) != null
                && Boolean.TRUE.equals(evidence.publishedAtCorrect())
                && SOURCE_TIERS.contains(normalize(evidence.adjudicatedSourceTier()));
    }

    private static long effectiveLagMinutes(ParsedGoldEvent gold, ParsedSystemEvent system) {
        return effectiveLagMinutes(gold.publishedAt(), system.detectedAt());
    }

    static long effectiveLagMinutesForTest(String publishedAt, String detectedAt) {
        return effectiveLagMinutes(parseInstant(publishedAt, "publishedAt"),
                parseInstant(detectedAt, "detectedAt"));
    }

    private static long effectiveLagMinutes(Instant publishedAt, Instant detectedAt) {
        Duration elapsed = Duration.between(publishedAt, detectedAt);
        if (elapsed.isNegative() || elapsed.isZero()) return 0L;
        long seconds = elapsed.getSeconds();
        long additionalSecond = elapsed.getNano() == 0 ? 0L : 1L;
        return (seconds + additionalSecond + 59L) / 60L;
    }

    static double latencyDiscount(long lagMinutes, int latencyStepMinutes) {
        if (latencyStepMinutes <= 0) throw new IllegalArgumentException("latency step must be positive");
        long nonNegativeLag = Math.max(0L, lagMinutes);
        return 1.0D - (2.0D / Math.PI)
                * Math.atan((double) nonNegativeLag / latencyStepMinutes);
    }

    private static double importanceWeight(int importance) {
        return Math.exp(importance);
    }

    private static double dcg(List<Integer> relevance, int cutoff) {
        double result = 0.0D;
        int limit = Math.min(cutoff, relevance.size());
        for (int index = 0; index < limit; index++) {
            result += relevance.get(index) / log2(index + 2.0D);
        }
        return result;
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2.0D);
    }

    private static Map<String, Long> frequency(List<ClusterAssignment> assignments, boolean gold) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (ClusterAssignment item : assignments) {
            result.merge(gold ? item.goldClusterId() : item.predictedClusterId(), 1L, Long::sum);
        }
        return result;
    }

    private static long pairs(long size) {
        return size < 2 ? 0L : size * (size - 1L) / 2L;
    }

    static Double percentile(Collection<Double> values, double quantile) {
        if (values == null || values.isEmpty()) return null;
        if (quantile < 0.0D || quantile > 1.0D) {
            throw new IllegalArgumentException("quantile must be within [0, 1]");
        }
        List<Double> sorted = values.stream().sorted().toList();
        if (sorted.size() == 1) return sorted.get(0);
        double position = (sorted.size() - 1) * quantile;
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return sorted.get(lower);
        double fraction = position - lower;
        return sorted.get(lower) + fraction * (sorted.get(upper) - sorted.get(lower));
    }

    private static MetricSummary ratioMetric(long numerator,
                                             long denominator,
                                             String unit,
                                             String method,
                                             boolean binomialInterval) {
        List<String> warnings = new ArrayList<>();
        if (denominator == 0) warnings.add("no eligible observations");
        if (denominator > 0 && denominator < SMALL_SAMPLE_THRESHOLD) {
            warnings.add("small sample: N=" + denominator + " < " + SMALL_SAMPLE_THRESHOLD);
        }
        Double value = ratio(numerator, denominator);
        ConfidenceInterval interval = binomialInterval && denominator > 0
                ? wilson95(numerator, denominator) : new ConfidenceInterval(null, null);
        if (!binomialInterval && denominator > 0) {
            warnings.add("no Wilson interval: observations are pair/cluster correlated or non-binomial");
        }
        return new MetricSummary(value, unit, numerator, denominator,
                interval.lower(), interval.upper(), method, List.copyOf(warnings));
    }

    private static MetricSummary averageMetric(double numerator,
                                               double denominator,
                                               String unit,
                                               String method) {
        List<String> warnings = denominator == 0.0D
                ? List.of("no eligible observations")
                : List.of("non-binomial aggregate; no Wilson interval");
        return new MetricSummary(denominator == 0.0D ? null : numerator / denominator,
                unit, null, null, null, null, method, warnings);
    }

    private static MetricSummary boundedAverageMetric(double numerator,
                                                      double denominator,
                                                      String unit,
                                                      String method) {
        MetricSummary metric = averageMetric(numerator, denominator, unit, method);
        if (metric.value() == null) return metric;
        return new MetricSummary(Math.max(0.0D, Math.min(1.0D, metric.value())),
                metric.unit(), metric.numerator(), metric.denominator(),
                metric.confidenceLower(), metric.confidenceUpper(),
                metric.method(), metric.warnings());
    }

    private static MetricSummary meanMetric(List<Double> values, String unit, String method) {
        if (values == null || values.isEmpty()) {
            return unavailableMetric(unit, method, List.of("no eligible ranking snapshots"));
        }
        List<String> warnings = new ArrayList<>();
        warnings.add("macro mean over " + values.size() + " ranking snapshot(s)");
        if (values.size() < SMALL_SAMPLE_THRESHOLD) {
            warnings.add("small sample: snapshots=" + values.size() + " < " + SMALL_SAMPLE_THRESHOLD);
        }
        return new MetricSummary(values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0D),
                unit, null, (long) values.size(), null, null, method, List.copyOf(warnings));
    }

    private static MetricSummary scalarMetric(Double value, String unit, String method) {
        return scalarMetric(value, unit, method, List.of());
    }

    private static MetricSummary scalarMetric(Double value,
                                              String unit,
                                              String method,
                                              List<String> warnings) {
        return new MetricSummary(value, unit, null, null, null, null, method, List.copyOf(warnings));
    }

    private static MetricSummary unavailableMetric(String unit, String method, List<String> warnings) {
        return new MetricSummary(null, unit, null, 0L, null, null, method, List.copyOf(warnings));
    }

    static ConfidenceInterval wilson95(long successes, long trials) {
        if (trials <= 0) return new ConfidenceInterval(null, null);
        double n = trials;
        double p = Math.max(0.0D, Math.min(1.0D, (double) successes / n));
        double z2 = WILSON_95_Z * WILSON_95_Z;
        double denominator = 1.0D + z2 / n;
        double center = (p + z2 / (2.0D * n)) / denominator;
        double margin = WILSON_95_Z * Math.sqrt((p * (1.0D - p) / n)
                + (z2 / (4.0D * n * n))) / denominator;
        return new ConfidenceInterval(Math.max(0.0D, center - margin),
                Math.min(1.0D, center + margin));
    }

    private static Double harmonic(Double left, Double right) {
        if (left == null || right == null) return null;
        if (left + right == 0.0D) return 0.0D;
        return 2.0D * left * right / (left + right);
    }

    private static Double ratio(long numerator, long denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    private static Instant parseInstant(String value, String field) {
        try {
            return Instant.parse(requireText(value, field));
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(field + " must be an ISO-8601 UTC instant, e.g. 2026-08-01T00:00:00Z",
                    exception);
        }
    }

    private static Instant parseOptionalInstant(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static boolean isAbsoluteHttpUrl(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            return uri.isAbsolute() && uri.getHost() != null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static String defaultText(String value, String fallback) {
        return value == null ? fallback : value.trim();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record DiscoveryDataset(String schemaVersion,
                                   String datasetId,
                                   String datasetVersion,
                                   String evaluationScope,
                                   EvaluationWindow window,
                                   EvaluationConfig config,
                                   Map<String, String> executionMetadata,
                                   List<GoldEvent> goldEvents,
                                   List<DiscoveryCandidateAssessment> discoveryCandidates,
                                   List<SystemEvent> systemEvents,
                                   List<ClusterAssignment> clusterAssignments,
                                   List<RankingSnapshot> rankingSnapshots,
                                   List<String> limitations) {
        public DiscoveryDataset {
            Objects.requireNonNull(window, "window is required");
            goldEvents = requiredImmutableList(goldEvents, "goldEvents");
            systemEvents = requiredImmutableList(systemEvents, "systemEvents");
            clusterAssignments = requiredImmutableList(clusterAssignments, "clusterAssignments");
            rankingSnapshots = requiredImmutableList(rankingSnapshots, "rankingSnapshots");
            executionMetadata = immutableMap(executionMetadata);
            discoveryCandidates = immutableList(discoveryCandidates);
            limitations = immutableList(limitations);
        }
    }

    public record EvaluationWindow(String startAt, String endAt, String observationEndAt) {
    }

    public record EvaluationConfig(List<Integer> freshnessCutoffsMinutes,
                                   List<Integer> rankingCutoffs,
                                   Integer latencyStepMinutes,
                                   Integer rankingLookbackMinutes,
                                   Integer earlyDetectionToleranceMinutes) {
        public EvaluationConfig {
            freshnessCutoffsMinutes = immutableList(freshnessCutoffsMinutes);
            rankingCutoffs = immutableList(rankingCutoffs);
        }
    }

    public record ResolvedConfig(List<Integer> freshnessCutoffsMinutes,
                                 List<Integer> rankingCutoffs,
                                 int latencyStepMinutes,
                                 int rankingLookbackMinutes,
                                 int earlyDetectionToleranceMinutes) {
    }

    public record GoldEvent(String eventId,
                            String title,
                            String firstPublishedAt,
                            int importance,
                            Map<String, String> slices) {
        public GoldEvent {
            slices = immutableMap(slices);
        }
    }

    public record DiscoveryCandidateAssessment(String candidateId,
                                               int rank,
                                               String title,
                                               String url,
                                               String sourceClass,
                                               String publishedAtHint,
                                               String matchedGoldEventId,
                                               String adjudicationReason) {
    }

    public record SystemEvent(String systemEventId,
                              String title,
                              String detectedAt,
                              String matchedGoldEventId,
                              Boolean adjudicatedRelevant,
                              String adjudicatedEventId,
                              String adjudicationReason,
                              List<ClaimAssessment> claims,
                              List<EvidenceAssessment> evidence) {
        public SystemEvent {
            claims = requiredImmutableList(claims, "claims");
            evidence = requiredImmutableList(evidence, "evidence");
        }
    }

    public record ClaimAssessment(String claimId,
                                  String text,
                                  boolean verifiable,
                                  Boolean jointlySupported) {
    }

    public record EvidenceAssessment(String evidenceId,
                                     String sourceUrl,
                                     String sourceTitle,
                                     String sourcePublishedAt,
                                     Boolean publishedAtCorrect,
                                     String predictedSourceTier,
                                     String adjudicatedSourceTier,
                                     Boolean fetchSucceeded,
                                     List<ClaimEvidenceRelation> relations) {
        public EvidenceAssessment {
            relations = requiredImmutableList(relations, "relations");
        }
    }

    public record ClaimEvidenceRelation(String claimId, String adjudicatedRelation) {
    }

    public record ClusterAssignment(String itemId,
                                    String goldClusterId,
                                    String predictedClusterId) {
    }

    public record RankingSnapshot(String snapshotId,
                                  String at,
                                  List<String> rankedSystemEventIds) {
        public RankingSnapshot {
            rankedSystemEventIds = requiredImmutableList(rankedSystemEventIds, "rankedSystemEventIds");
        }
    }

    public record DiscoveryQualityManifest(String schemaVersion,
                                           String evaluationScope,
                                           String datasetId,
                                           String datasetVersion,
                                           String generatedAt,
                                           String gitCommit,
                                           EvaluationWindow window,
                                           ResolvedConfig config,
                                           Map<String, String> executionMetadata,
                                           Map<String, Long> counts,
                                           Map<String, MetricSummary> metrics,
                                           Map<String, SliceSummary> slices,
                                           List<RankingSnapshotSummary> rankingSnapshots,
                                           List<Badcase> badcases,
                                           List<String> warnings,
                                           List<String> limitations,
                                           Map<String, String> methodReferences,
                                           boolean p0Complete,
                                           String testCommand,
                                           boolean evaluationEligible) {
    }

    public record MetricSummary(Double value,
                                String unit,
                                Long numerator,
                                Long denominator,
                                Double confidenceLower,
                                Double confidenceUpper,
                                String method,
                                List<String> warnings) {
    }

    public record SliceSummary(long goldEvents,
                               Map<String, MetricSummary> metrics,
                               List<String> warnings) {
    }

    public record RankingSnapshotSummary(String snapshotId,
                                         String at,
                                         long eligibleGoldEvents,
                                         long returnedItems,
                                         Map<String, MetricSummary> metrics) {
    }

    public record Badcase(String kind, String id, String label, String detail) {
    }

    public record EvaluationReport(DiscoveryQualityManifest manifest, List<Badcase> badcases) {
    }

    record ConfidenceInterval(Double lower, Double upper) {
    }

    private record ParsedWindow(Instant startAt, Instant endAt, Instant observationEndAt) {
    }

    private record ParsedGoldEvent(GoldEvent value, Instant publishedAt) {
    }

    private record ParsedSystemEvent(SystemEvent value, Instant detectedAt) {
    }

    private record ValidationResult(DiscoveryDataset dataset,
                                    ParsedWindow window,
                                    Map<String, ParsedGoldEvent> goldById,
                                    Map<String, ParsedSystemEvent> systemById,
                                    List<ParsedSystemEvent> systemEvents,
                                    List<ParsedSystemEvent> outputWindowEvents) {
    }

    private record RetrievalScore(Map<String, ParsedSystemEvent> earliestByGold,
                                  Map<String, ParsedSystemEvent> earliestEvidenceReadyByGold,
                                  long matchedOutputEvents,
                                  long uniqueMatchedOutputEvents,
                                  long relevantOutputEvents,
                                  long uniqueRelevantOutputEvents,
                                  long evidenceReadyOutputEvents,
                                  long uniqueEvidenceReadyOutputEvents,
                                  long falsePositiveOutputEvents,
                                  long redundantOutputEvents) {
    }

    private record EvidenceCounts(long claims,
                                  long verifiableClaims,
                                  long evidence,
                                  long citationRelations) {
    }

    private record ClusterPair(String gold, String predicted) {
    }

    private static <T> List<T> immutableList(List<T> input) {
        return input == null ? List.of() : List.copyOf(input);
    }

    private static <T> List<T> requiredImmutableList(List<T> input, String field) {
        if (input == null) throw new IllegalArgumentException(field + " is required; use [] for an explicit empty set");
        return List.copyOf(input);
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> input) {
        if (input == null || input.isEmpty()) return Map.of();
        return Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }
}
