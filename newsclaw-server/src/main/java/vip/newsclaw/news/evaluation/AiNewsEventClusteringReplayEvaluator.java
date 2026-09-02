package vip.newsclaw.news.evaluation;

import vip.newsclaw.news.service.AiNewsEventClusterScorer;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Replays the production link scorer as a bounded, first-story online clusterer.
 *
 * <p>This evaluator deliberately keeps borderline proposals as singleton
 * clusters, exactly like production. It reports automatic quality separately
 * from review-assisted coverage so a human review proposal can never be
 * misrepresented as an automatic merge.</p>
 */
public final class AiNewsEventClusteringReplayEvaluator {

    private final AiNewsEventClusterScorer scorer;

    public AiNewsEventClusteringReplayEvaluator(AiNewsEventClusterScorer scorer) {
        this.scorer = Objects.requireNonNull(scorer, "scorer");
    }

    public Report evaluate(Dataset dataset, String gitCommit, String command) {
        Validated validated = validate(dataset);
        List<Replay> replays = validated.orders().stream()
                .map(order -> replay(order, validated.byId())).toList();
        Replay primary = replays.getFirst();
        List<Badcase> badcases = new ArrayList<>();
        Map<String, Metric> metrics = new LinkedHashMap<>();

        scoreClusters(validated.observations(), primary, metrics, badcases);
        scoreDecisions(validated.byId(), primary, metrics, badcases);
        scoreStability(replays, metrics, badcases);

        if (dataset.expectedConfigHash() != null && !dataset.expectedConfigHash().isBlank()
                && !dataset.expectedConfigHash().equals(scorer.configHash())) {
            badcases.add(new Badcase("config-hash-drift", "dataset",
                    dataset.expectedConfigHash(), scorer.configHash()));
        }
        for (Observation observation : validated.observations()) {
            String expected = normalizeDecision(observation.expectedDecision());
            if (expected.isBlank()) continue;
            Decision actual = primary.decisions().get(observation.caseId());
            if (actual == null || !expected.equals(actual.type())) {
                badcases.add(new Badcase("decision-mismatch", observation.caseId(), expected,
                        actual == null ? "missing" : actual.type()));
            }
        }

        Map<String, GateResult> gateResults = new LinkedHashMap<>();
        dataset.gates().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            Metric metric = metrics.get(entry.getKey());
            boolean passed = metric != null && metric.value() + 1.0E-12 >= entry.getValue();
            gateResults.put(entry.getKey(), new GateResult(entry.getValue(),
                    metric == null ? null : metric.value(), passed));
            if (!passed) {
                badcases.add(new Badcase("quality-gate", entry.getKey(),
                        String.valueOf(entry.getValue()), metric == null ? "missing" : String.valueOf(metric.value())));
            }
        });

        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("observations", (long) validated.observations().size());
        counts.put("goldClusters", distinctGold(validated.observations()));
        counts.put("predictedClusters", (long) new HashSet<>(primary.assignments().values()).size());
        counts.put("automaticLinks", primary.decisions().values().stream()
                .filter(item -> "AUTO_LINK".equals(item.type())).count());
        counts.put("reviewProposals", primary.decisions().values().stream()
                .filter(item -> "REVIEW".equals(item.type())).count());
        counts.put("replayOrders", (long) replays.size());

        List<ReplaySummary> summaries = replays.stream().map(item -> new ReplaySummary(
                item.name(), item.caseIds(), item.assignments(), item.decisions())).toList();
        boolean passed = gateResults.values().stream().allMatch(GateResult::passed)
                && badcases.stream().noneMatch(item -> Set.of("config-hash-drift",
                "decision-mismatch", "invalid-dataset").contains(item.kind()));
        return new Report("1.0", dataset.datasetId(), dataset.datasetVersion(),
                dataset.evaluationScope(), nullToUnknown(gitCommit), nullToUnknown(command),
                AiNewsEventClusterScorer.ALGORITHM_NAME,
                AiNewsEventClusterScorer.ALGORITHM_VERSION,
                AiNewsEventClusterScorer.FEATURE_VERSION, scorer.configHash(), passed,
                counts, metrics, gateResults,
                List.copyOf(summaries), List.copyOf(badcases), dataset.limitations());
    }

    public static String toMarkdown(Report report) {
        StringBuilder out = new StringBuilder();
        out.append("# AI News Event Clustering Replay\n\n")
                .append("- Dataset: `").append(report.datasetId()).append('@')
                .append(report.datasetVersion()).append("`\n")
                .append("- Scope: `").append(report.evaluationScope()).append("`\n")
                .append("- Production scorer: `").append(report.algorithmName()).append('@')
                .append(report.algorithmVersion()).append("` / `")
                .append(report.featureVersion()).append("`\n")
                .append("- Config SHA-256: `").append(report.configHash()).append("`\n")
                .append("- Gate result: **").append(report.passed() ? "PASS" : "FAIL")
                .append("**\n\n## Metrics\n\n")
                .append("| Metric | Value | Numerator | Denominator |\n")
                .append("|---|---:|---:|---:|\n");
        report.metrics().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> out
                .append('|').append(entry.getKey()).append('|')
                .append(String.format(Locale.ROOT, "%.6f", entry.getValue().value())).append('|')
                .append(entry.getValue().numerator()).append('|')
                .append(entry.getValue().denominator()).append("|\n"));
        out.append("\n## Gate thresholds\n\n");
        report.gates().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            GateResult gate = entry.getValue();
            out.append("- `").append(entry.getKey()).append("` ≥ ")
                    .append(gate.minimum()).append(": ").append(gate.passed() ? "PASS" : "FAIL")
                    .append("\n");
        });
        out.append("\n## Limitations\n\n");
        report.limitations().forEach(item -> out.append("- ").append(item).append("\n"));
        if (!report.badcases().isEmpty()) {
            out.append("\n## Bad cases\n\n");
            report.badcases().forEach(item -> out.append("- `").append(item.kind()).append("` ")
                    .append(item.caseId()).append(": expected=").append(item.expected())
                    .append(", actual=").append(item.actual()).append("\n"));
        }
        return out.toString();
    }

    private Replay replay(ReplayOrder order, Map<String, Observation> byId) {
        List<PredictedCluster> clusters = new ArrayList<>();
        Map<String, String> assignments = new LinkedHashMap<>();
        Map<String, Decision> decisions = new LinkedHashMap<>();
        int ordinal = 0;
        for (String caseId : order.caseIds()) {
            Observation incoming = byId.get(caseId);
            ordinal++;
            List<PredictedCluster> eligible = clusters.stream()
                    .sorted(Comparator.comparingInt(PredictedCluster::updatedOrdinal).reversed()
                            .thenComparingInt(PredictedCluster::number))
                    .limit(scorer.effectiveMaxCandidates()).toList();
            List<ScoredCluster> scored = eligible.stream()
                    .map(cluster -> new ScoredCluster(cluster,
                            scorer.score(document(incoming), document(cluster.representative()))))
                    .filter(item -> item.score().value() > 0.0D)
                    .sorted(Comparator.comparingDouble(
                                    (ScoredCluster item) -> item.score().value()).reversed()
                            .thenComparingInt(item -> item.cluster().number()))
                    .toList();
            ScoredCluster automatic = scored.stream()
                    .filter(item -> item.score().automaticLink()).findFirst().orElse(null);
            if (automatic != null) {
                boolean correct = sameGold(automatic.cluster().members(), incoming.goldClusterId());
                automatic.cluster().add(incoming, ordinal);
                assignments.put(caseId, automatic.cluster().id());
                decisions.put(caseId, new Decision("AUTO_LINK", automatic.score().value(),
                        automatic.cluster().id(), correct, automatic.score().assignmentOrigin()));
                continue;
            }
            PredictedCluster singleton = new PredictedCluster(clusters.size() + 1, incoming, ordinal);
            clusters.add(singleton);
            assignments.put(caseId, singleton.id());
            ScoredCluster review = scored.stream()
                    .filter(item -> item.score().reviewSuggested()).findFirst().orElse(null);
            if (review == null) {
                decisions.put(caseId, new Decision("SINGLETON", 1.0D, null, true, "SINGLETON"));
            } else {
                boolean correct = sameGold(review.cluster().members(), incoming.goldClusterId());
                decisions.put(caseId, new Decision("REVIEW", review.score().value(),
                        review.cluster().id(), correct, review.score().assignmentOrigin()));
            }
        }
        return new Replay(order.name(), List.copyOf(order.caseIds()), assignments, decisions);
    }

    private static void scoreClusters(List<Observation> observations, Replay replay,
                                      Map<String, Metric> metrics, List<Badcase> badcases) {
        Map<String, Long> goldSizes = frequency(observations,
                observation -> observation.goldClusterId());
        Map<String, Long> predictedSizes = frequency(observations,
                observation -> replay.assignments().get(observation.caseId()));
        Map<Pair, Long> intersections = new HashMap<>();
        for (Observation observation : observations) {
            intersections.merge(new Pair(observation.goldClusterId(),
                    replay.assignments().get(observation.caseId())), 1L, Long::sum);
        }
        double precision = 0.0D;
        double recall = 0.0D;
        for (Observation observation : observations) {
            long intersection = intersections.get(new Pair(observation.goldClusterId(),
                    replay.assignments().get(observation.caseId())));
            precision += (double) intersection / predictedSizes.get(
                    replay.assignments().get(observation.caseId()));
            recall += (double) intersection / goldSizes.get(observation.goldClusterId());
        }
        precision /= observations.size();
        recall /= observations.size();
        metrics.put("clustering.bcubedPrecision", scalar(precision,
                "mean per-item B-Cubed precision"));
        metrics.put("clustering.bcubedRecall", scalar(recall,
                "mean per-item B-Cubed recall"));
        metrics.put("clustering.bcubedF1", scalar(harmonic(precision, recall),
                "harmonic mean of B-Cubed precision and recall"));

        long truePairs = 0;
        long predictedPairs = 0;
        long goldPairs = 0;
        for (int left = 0; left < observations.size(); left++) {
            for (int right = left + 1; right < observations.size(); right++) {
                Observation a = observations.get(left);
                Observation b = observations.get(right);
                boolean goldSame = a.goldClusterId().equals(b.goldClusterId());
                boolean predictedSame = replay.assignments().get(a.caseId())
                        .equals(replay.assignments().get(b.caseId()));
                if (goldSame) goldPairs++;
                if (predictedSame) predictedPairs++;
                if (goldSame && predictedSame) truePairs++;
                if (!goldSame && predictedSame) {
                    badcases.add(new Badcase("over-merge-pair", a.caseId() + "+" + b.caseId(),
                            "separate", "same cluster"));
                } else if (goldSame && !predictedSame) {
                    badcases.add(new Badcase("over-split-pair", a.caseId() + "+" + b.caseId(),
                            "same cluster", "separate"));
                }
            }
        }
        metrics.put("clustering.pairwisePrecision", ratioMetric(truePairs, predictedPairs,
                "correct predicted same-cluster pairs / predicted same-cluster pairs"));
        metrics.put("clustering.pairwiseRecall", ratioMetric(truePairs, goldPairs,
                "correct predicted same-cluster pairs / gold same-cluster pairs"));
        metrics.put("clustering.pairwiseF1", scalar(harmonic(ratio(truePairs, predictedPairs),
                ratio(truePairs, goldPairs)), "harmonic mean of pairwise precision and recall"));
        metrics.put("clustering.overMergePairRate", ratioMetric(predictedPairs - truePairs,
                predictedPairs, "incorrectly merged pairs / predicted same-cluster pairs"));
        metrics.put("clustering.overSplitPairRate", ratioMetric(goldPairs - truePairs,
                goldPairs, "incorrectly split pairs / gold same-cluster pairs"));
    }

    private static void scoreDecisions(Map<String, Observation> byId, Replay replay,
                                       Map<String, Metric> metrics, List<Badcase> badcases) {
        Set<String> seenGold = new HashSet<>();
        long duplicateOpportunities = 0;
        long firstStories = 0;
        long automatic = 0;
        long correctAutomatic = 0;
        long reviews = 0;
        long correctReviews = 0;
        long safeFirstStories = 0;
        for (String caseId : replay.caseIds()) {
            Observation observation = byId.get(caseId);
            boolean duplicate = !seenGold.add(observation.goldClusterId());
            Decision decision = replay.decisions().get(observation.caseId());
            if (duplicate) duplicateOpportunities++;
            else firstStories++;
            if ("AUTO_LINK".equals(decision.type())) {
                automatic++;
                if (decision.candidateGoldMatch()) correctAutomatic++;
                else badcases.add(new Badcase("incorrect-auto-link", observation.caseId(),
                        "gold-compatible target", decision.candidateClusterId()));
            }
            if ("REVIEW".equals(decision.type())) {
                reviews++;
                if (decision.candidateGoldMatch()) correctReviews++;
                else badcases.add(new Badcase("incorrect-review-proposal", observation.caseId(),
                        "gold-compatible target", decision.candidateClusterId()));
            }
            if (!duplicate && !"AUTO_LINK".equals(decision.type())) safeFirstStories++;
        }
        metrics.put("decision.autoLinkPrecision", ratioMetric(correctAutomatic, automatic,
                "correct automatic links / automatic links"));
        metrics.put("decision.autoLinkDuplicateRecall", ratioMetric(correctAutomatic,
                duplicateOpportunities, "correct automatic links / duplicate opportunities"));
        metrics.put("decision.reviewProposalPrecision", ratioMetric(correctReviews, reviews,
                "correct borderline proposals / review proposals"));
        metrics.put("decision.assistedDuplicateRecall", ratioMetric(
                correctAutomatic + correctReviews, duplicateOpportunities,
                "correct automatic links plus correct review proposals / duplicate opportunities"));
        metrics.put("decision.firstStorySafety", ratioMetric(safeFirstStories, firstStories,
                "gold first stories not automatically merged / gold first stories"));
        metrics.put("decision.reviewQueueRate", ratioMetric(reviews, byId.size(),
                "review proposals / observations"));
    }

    private static void scoreStability(List<Replay> replays, Map<String, Metric> metrics,
                                       List<Badcase> badcases) {
        Replay primary = replays.getFirst();
        Set<String> primaryPairs = sameClusterPairs(primary.assignments());
        double total = 0.0D;
        int comparisons = 0;
        for (int index = 1; index < replays.size(); index++) {
            Replay alternative = replays.get(index);
            Set<String> alternativePairs = sameClusterPairs(alternative.assignments());
            Set<String> union = new HashSet<>(primaryPairs);
            union.addAll(alternativePairs);
            Set<String> intersection = new HashSet<>(primaryPairs);
            intersection.retainAll(alternativePairs);
            double jaccard = union.isEmpty() ? 1.0D : (double) intersection.size() / union.size();
            total += jaccard;
            comparisons++;
            if (jaccard < 1.0D) {
                badcases.add(new Badcase("order-instability", alternative.name(),
                        primaryPairs.toString(), alternativePairs.toString()));
            }
        }
        double value = comparisons == 0 ? 1.0D : total / comparisons;
        metrics.put("stability.coClusterPairJaccard", new Metric(value,
                Math.round(total * 1_000_000D), comparisons * 1_000_000L,
                "mean Jaccard similarity of same-cluster pair sets across declared arrival orders"));
    }

    private static Validated validate(Dataset dataset) {
        if (dataset == null || dataset.observations().isEmpty()) {
            throw new IllegalArgumentException("clustering replay dataset requires observations");
        }
        Map<String, Observation> byId = new LinkedHashMap<>();
        for (Observation observation : dataset.observations()) {
            if (blank(observation.caseId()) || blank(observation.goldClusterId())
                    || blank(observation.title())) {
                throw new IllegalArgumentException("caseId, goldClusterId and title are required");
            }
            if (byId.putIfAbsent(observation.caseId(), observation) != null) {
                throw new IllegalArgumentException("duplicate clustering caseId: " + observation.caseId());
            }
        }
        List<ReplayOrder> orders = dataset.replayOrders();
        if (orders.isEmpty()) {
            orders = List.of(new ReplayOrder("manifest-order", new ArrayList<>(byId.keySet())));
        }
        for (ReplayOrder order : orders) {
            if (blank(order.name()) || order.caseIds().size() != byId.size()
                    || !new LinkedHashSet<>(order.caseIds()).equals(byId.keySet())) {
                throw new IllegalArgumentException("every replay order must contain each case exactly once: "
                        + order.name());
            }
        }
        return new Validated(List.copyOf(dataset.observations()), byId, List.copyOf(orders));
    }

    private static AiNewsEventClusterScorer.EventDocument document(Observation value) {
        return new AiNewsEventClusterScorer.EventDocument(null, value.eventKey(), value.title(),
                value.summary(), value.category(), value.entities(), value.urls(),
                value.sourcePublishedAt(), value.discoveredAt());
    }

    private static boolean sameGold(Collection<Observation> values, String goldClusterId) {
        return !values.isEmpty() && values.stream()
                .allMatch(item -> goldClusterId.equals(item.goldClusterId()));
    }

    private static long distinctGold(List<Observation> observations) {
        return observations.stream().map(Observation::goldClusterId).distinct().count();
    }

    private static Map<String, Long> frequency(List<Observation> observations,
                                                Function<Observation, String> key) {
        return observations.stream().collect(Collectors.groupingBy(key, LinkedHashMap::new,
                Collectors.counting()));
    }

    private static Set<String> sameClusterPairs(Map<String, String> assignments) {
        List<String> ids = assignments.keySet().stream().sorted().toList();
        Set<String> pairs = new HashSet<>();
        for (int left = 0; left < ids.size(); left++) {
            for (int right = left + 1; right < ids.size(); right++) {
                if (assignments.get(ids.get(left)).equals(assignments.get(ids.get(right)))) {
                    pairs.add(ids.get(left) + "\u0000" + ids.get(right));
                }
            }
        }
        return pairs;
    }

    private static Metric scalar(double value, String description) {
        return new Metric(rounded(value), 0L, 0L, description);
    }

    private static Metric ratioMetric(long numerator, long denominator, String description) {
        return new Metric(rounded(ratio(numerator, denominator)), numerator, denominator, description);
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0.0D : (double) numerator / denominator;
    }

    private static double harmonic(double precision, double recall) {
        return precision + recall == 0.0D ? 0.0D
                : 2.0D * precision * recall / (precision + recall);
    }

    private static double rounded(double value) {
        return Math.round(value * 1_000_000D) / 1_000_000D;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String normalizeDecision(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String nullToUnknown(String value) {
        return blank(value) ? "unknown" : value;
    }

    /**
     * {@link Map#copyOf(Map)} deliberately makes no iteration-order guarantee.
     * Evaluation artifacts are content-addressed, so preserve the already
     * deterministic insertion order before exposing an immutable view.
     */
    private static <K, V> Map<K, V> immutableOrderedMap(Map<K, V> source) {
        if (source == null || source.isEmpty()) return Map.of();
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private record Pair(String gold, String predicted) {
    }

    private record ScoredCluster(PredictedCluster cluster,
                                 AiNewsEventClusterScorer.Score score) {
    }

    private record Replay(String name, List<String> caseIds, Map<String, String> assignments,
                          Map<String, Decision> decisions) {
        private Replay {
            caseIds = caseIds == null ? List.of() : List.copyOf(caseIds);
            assignments = immutableOrderedMap(assignments);
            decisions = immutableOrderedMap(decisions);
        }
    }

    private record Validated(List<Observation> observations, Map<String, Observation> byId,
                             List<ReplayOrder> orders) {
        private Validated {
            observations = observations == null ? List.of() : List.copyOf(observations);
            byId = immutableOrderedMap(byId);
            orders = orders == null ? List.of() : List.copyOf(orders);
        }
    }

    private static final class PredictedCluster {
        private final int number;
        private final List<Observation> members = new ArrayList<>();
        private Observation representative;
        private int updatedOrdinal;

        private PredictedCluster(int number, Observation first, int updatedOrdinal) {
            this.number = number;
            this.members.add(first);
            this.representative = first;
            this.updatedOrdinal = updatedOrdinal;
        }

        private void add(Observation observation, int ordinal) {
            members.add(observation);
            representative = members.stream().min(Comparator
                    .comparingDouble((Observation item) -> -item.effectiveRankingScore())
                    .thenComparing(Observation::effectiveTime,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(Observation::caseId)).orElseThrow();
            updatedOrdinal = ordinal;
        }

        private int number() {
            return number;
        }

        private String id() {
            return "cluster-" + number;
        }

        private List<Observation> members() {
            return members;
        }

        private Observation representative() {
            return representative;
        }

        private int updatedOrdinal() {
            return updatedOrdinal;
        }
    }

    public record Dataset(String schemaVersion,
                          String datasetId,
                          String datasetVersion,
                          String evaluationScope,
                          String expectedConfigHash,
                          Map<String, String> provenance,
                          List<Observation> observations,
                          List<ReplayOrder> replayOrders,
                          Map<String, Double> gates,
                          List<String> limitations) {
        public Dataset {
            provenance = immutableOrderedMap(provenance);
            observations = observations == null ? List.of() : List.copyOf(observations);
            replayOrders = replayOrders == null ? List.of() : List.copyOf(replayOrders);
            gates = immutableOrderedMap(gates);
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
        }
    }

    public record Observation(String caseId,
                              String goldClusterId,
                              String eventKey,
                              String title,
                              String summary,
                              String category,
                              Set<String> entities,
                              Set<String> urls,
                              LocalDateTime sourcePublishedAt,
                              LocalDateTime discoveredAt,
                              Double rankingScore,
                              String expectedDecision) {
        public Observation {
            entities = entities == null ? Set.of() : Set.copyOf(entities);
            urls = urls == null ? Set.of() : Set.copyOf(urls);
        }

        private double effectiveRankingScore() {
            return rankingScore == null || !Double.isFinite(rankingScore) ? 0.0D : rankingScore;
        }

        private LocalDateTime effectiveTime() {
            return sourcePublishedAt == null ? discoveredAt : sourcePublishedAt;
        }
    }

    public record ReplayOrder(String name, List<String> caseIds) {
        public ReplayOrder {
            caseIds = caseIds == null ? List.of() : List.copyOf(caseIds);
        }
    }

    public record Decision(String type, double score, String candidateClusterId,
                           boolean candidateGoldMatch, String origin) {
    }

    public record ReplaySummary(String name, List<String> caseIds,
                                Map<String, String> assignments,
                                Map<String, Decision> decisions) {
        public ReplaySummary {
            caseIds = caseIds == null ? List.of() : List.copyOf(caseIds);
            assignments = immutableOrderedMap(assignments);
            decisions = immutableOrderedMap(decisions);
        }
    }

    public record Metric(double value, long numerator, long denominator, String description) {
    }

    public record GateResult(double minimum, Double actual, boolean passed) {
    }

    public record Badcase(String kind, String caseId, String expected, String actual) {
    }

    public record Report(String schemaVersion,
                         String datasetId,
                         String datasetVersion,
                         String evaluationScope,
                         String gitCommit,
                         String reproductionCommand,
                         String algorithmName,
                         String algorithmVersion,
                         String featureVersion,
                         String configHash,
                         boolean passed,
                         Map<String, Long> counts,
                         Map<String, Metric> metrics,
                         Map<String, GateResult> gates,
                         List<ReplaySummary> replays,
                         List<Badcase> badcases,
                         List<String> limitations) {
        public Report {
            counts = immutableOrderedMap(counts);
            metrics = immutableOrderedMap(metrics);
            gates = immutableOrderedMap(gates);
            replays = replays == null ? List.of() : List.copyOf(replays);
            badcases = badcases == null ? List.of() : List.copyOf(badcases);
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
        }
    }
}
