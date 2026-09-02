package vip.newsclaw.news.evaluation;

import org.springframework.stereotype.Component;
import vip.newsclaw.news.service.AiNewsDiscoverySearchService;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Deterministic same-window stability diagnostics for live discovery sentinels. */
@Component
public class AiNewsDiscoveryStabilityEvaluator {

    public static final double DEFAULT_RBO_PERSISTENCE = 0.90D;

    public StabilityReport evaluate(List<AiNewsDiscoverySearchService.DiscoveryBatch> batches) {
        if (batches == null || batches.size() < 2) {
            throw new IllegalArgumentException("stability evaluation requires at least two runs");
        }
        String windowStart = required(batches.getFirst().windowStart(), "windowStart");
        String windowEnd = required(batches.getFirst().windowEnd(), "windowEnd");
        String policy = required(batches.getFirst().rankingPolicyVersion(), "rankingPolicyVersion");
        for (AiNewsDiscoverySearchService.DiscoveryBatch batch : batches) {
            if (!windowStart.equals(batch.windowStart()) || !windowEnd.equals(batch.windowEnd())) {
                throw new IllegalArgumentException("all stability runs must use the same frozen window");
            }
            if (!policy.equals(batch.rankingPolicyVersion())) {
                throw new IllegalArgumentException("all stability runs must use the same ranking policy");
            }
            required(batch.snapshotHash(), "snapshotHash");
            required(batch.rankingHash(), "rankingHash");
        }

        List<RunSummary> runs = batches.stream().map(AiNewsDiscoveryStabilityEvaluator::summarize).toList();
        List<PairwiseSummary> pairs = new ArrayList<>();
        for (int left = 0; left < batches.size(); left++) {
            for (int right = left + 1; right < batches.size(); right++) {
                List<String> a = urls(batches.get(left));
                List<String> b = urls(batches.get(right));
                pairs.add(new PairwiseSummary(left + 1, right + 1,
                        jaccardAt(a, b, 10), jaccardAt(a, b, 30),
                        rboAt(a, b, 10, DEFAULT_RBO_PERSISTENCE),
                        rboAt(a, b, 30, DEFAULT_RBO_PERSISTENCE),
                        batches.get(left).snapshotHash().equals(batches.get(right).snapshotHash()),
                        batches.get(left).rankingHash().equals(batches.get(right).rankingHash())));
            }
        }

        Map<String, Integer> laneContribution = new LinkedHashMap<>();
        batches.stream().flatMap(batch -> batch.candidates().stream())
                .map(candidate -> candidate.selectionLane() == null
                        ? "unclassified" : candidate.selectionLane())
                .forEach(lane -> laneContribution.merge(lane, 1, Integer::sum));
        boolean allUncached = runs.stream().allMatch(run -> run.cachedQueryCount() == 0);
        int staleAdmitted = runs.stream().mapToInt(RunSummary::outsideWindowCount).sum();
        return new StabilityReport("1.0", windowStart, windowEnd, policy,
                batches.size(), pairs.size(), allUncached,
                batches.size() >= 3 && allUncached,
                aggregate(pairs.stream().map(PairwiseSummary::jaccardAt10).toList()),
                aggregate(pairs.stream().map(PairwiseSummary::jaccardAt30).toList()),
                aggregate(pairs.stream().map(PairwiseSummary::rboAt10).toList()),
                aggregate(pairs.stream().map(PairwiseSummary::rboAt30).toList()),
                pairs.stream().filter(PairwiseSummary::identicalSnapshot).count()
                        / (double) pairs.size(),
                pairs.stream().filter(PairwiseSummary::identicalRanking).count()
                        / (double) pairs.size(),
                aggregate(runs.stream().map(run -> (double) run.selectedCount()).toList()),
                ratio(runs.stream().mapToInt(RunSummary::inWindowCount).sum(),
                        runs.stream().mapToInt(RunSummary::selectedCount).sum()),
                ratio(runs.stream().mapToInt(RunSummary::unknownTimeCount).sum(),
                        runs.stream().mapToInt(RunSummary::selectedCount).sum()),
                staleAdmitted, Map.copyOf(laneContribution), runs, List.copyOf(pairs),
                allUncached
                        ? "uncached live runs; index drift is measured separately from frozen replay"
                        : "at least one query was served from cache; do not use this report as a live sentinel SLA");
    }

    static double jaccardAt(List<String> left, List<String> right, int cutoff) {
        Set<String> a = prefixSet(left, cutoff);
        Set<String> b = prefixSet(right, cutoff);
        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        if (union.isEmpty()) return 1.0D;
        Set<String> intersection = new LinkedHashSet<>(a);
        intersection.retainAll(b);
        return intersection.size() / (double) union.size();
    }

    /** Extrapolated rank-biased overlap at a bounded depth. */
    static double rboAt(List<String> left, List<String> right, int cutoff, double persistence) {
        if (!(persistence > 0.0D && persistence < 1.0D)) {
            throw new IllegalArgumentException("RBO persistence must be in (0,1)");
        }
        Set<String> a = new LinkedHashSet<>();
        Set<String> b = new LinkedHashSet<>();
        int evaluationDepth = Math.min(Math.max(0, cutoff), Math.max(left.size(), right.size()));
        if (evaluationDepth == 0) return left.isEmpty() && right.isEmpty() ? 1.0D : 0.0D;
        double weightedAgreement = 0.0D;
        double agreementAtDepth = 0.0D;
        for (int depth = 1; depth <= evaluationDepth; depth++) {
            if (depth <= left.size()) a.add(left.get(depth - 1));
            if (depth <= right.size()) b.add(right.get(depth - 1));
            Set<String> intersection = new LinkedHashSet<>(a);
            intersection.retainAll(b);
            agreementAtDepth = intersection.size() / (double) depth;
            weightedAgreement += (1.0D - persistence) * agreementAtDepth
                    * Math.pow(persistence, depth - 1);
        }
        return weightedAgreement + agreementAtDepth * Math.pow(persistence, evaluationDepth);
    }

    private static RunSummary summarize(AiNewsDiscoverySearchService.DiscoveryBatch batch) {
        int current = 0;
        int unknown = 0;
        int outside = 0;
        Set<String> hosts = new LinkedHashSet<>();
        int official = 0;
        int media = 0;
        int open = 0;
        for (AiNewsDiscoverySearchService.DiscoveryCandidate candidate : batch.candidates()) {
            switch (candidate.temporalStatus()) {
                case IN_WINDOW -> current++;
                case UNKNOWN -> unknown++;
                case OUTSIDE_WINDOW -> outside++;
            }
            hosts.add(host(candidate.url()));
            if (candidate.officialDomain()) official++;
            else if (candidate.trustedMediaDomain()) media++;
            else open++;
        }
        int cached = (int) batch.executions().stream()
                .filter(AiNewsDiscoverySearchService.QueryExecution::fromCache).count();
        return new RunSummary(batch.discoveryRunId(), batch.observedAt(), batch.snapshotHash(),
                batch.rankingHash(), batch.candidates().size(), current, unknown, outside,
                hosts.size(), official, media, open, cached);
    }

    private static List<String> urls(AiNewsDiscoverySearchService.DiscoveryBatch batch) {
        return batch.candidates().stream().map(AiNewsDiscoverySearchService.DiscoveryCandidate::url)
                .filter(value -> value != null && !value.isBlank()).toList();
    }

    private static Set<String> prefixSet(List<String> values, int cutoff) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        values.stream().limit(Math.max(0, cutoff)).forEach(result::add);
        return result;
    }

    private static String host(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) return "invalid-host";
            String normalized = host.toLowerCase(Locale.ROOT);
            return normalized.startsWith("www.") ? normalized.substring(4) : normalized;
        } catch (Exception ignored) {
            return "invalid-host";
        }
    }

    private static MetricAggregate aggregate(List<Double> values) {
        if (values.isEmpty()) return new MetricAggregate(0.0D, 0.0D, 0.0D);
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0D);
        double min = values.stream().mapToDouble(Double::doubleValue).min().orElse(0.0D);
        double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0D);
        return new MetricAggregate(mean, min, max);
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0.0D : numerator / (double) denominator;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    public record MetricAggregate(double mean, double min, double max) {
    }

    public record RunSummary(Long discoveryRunId,
                             String observedAt,
                             String snapshotHash,
                             String rankingHash,
                             int selectedCount,
                             int inWindowCount,
                             int unknownTimeCount,
                             int outsideWindowCount,
                             int distinctHostCount,
                             int officialCount,
                             int mediaCount,
                             int openWebCount,
                             int cachedQueryCount) {
    }

    public record PairwiseSummary(int leftRun,
                                  int rightRun,
                                  double jaccardAt10,
                                  double jaccardAt30,
                                  double rboAt10,
                                  double rboAt30,
                                  boolean identicalSnapshot,
                                  boolean identicalRanking) {
    }

    public record StabilityReport(String schemaVersion,
                                  String windowStart,
                                  String windowEnd,
                                  String rankingPolicyVersion,
                                  int runCount,
                                  int pairCount,
                                  boolean allRunsUncached,
                                  boolean liveSentinelEligible,
                                  MetricAggregate jaccardAt10,
                                  MetricAggregate jaccardAt30,
                                  MetricAggregate rboAt10,
                                  MetricAggregate rboAt30,
                                  double identicalSnapshotPairRate,
                                  double identicalRankingPairRate,
                                  MetricAggregate selectedCount,
                                  double inWindowAdmissionRate,
                                  double unknownTimeAdmissionRate,
                                  int outsideWindowAdmittedCount,
                                  Map<String, Integer> laneContribution,
                                  List<RunSummary> runs,
                                  List<PairwiseSummary> pairs,
                                  String interpretation) {
    }
}
