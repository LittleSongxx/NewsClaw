package vip.newsclaw.news.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Executes a frozen, controlled AI-news benchmark against a running NewsClaw
 * instance. The runner deliberately treats the live model as the system under
 * test: the benchmark supplies expected labels and the runner only parses the
 * returned SSE trace. It never asks an LLM to judge another LLM.
 *
 * <p>Raw SSE traces are written only to caller-supplied output directories,
 * normally under {@code target/}; repository fixtures contain synthetic
 * evidence packets only. This is not a substitute for human-reviewed sampled
 * production traces.</p>
 */
final class AiNewsLiveAgentBenchmarkRunner {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectReader STRICT_JSON = JSON.readerFor(JsonNode.class)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);

    private AiNewsLiveAgentBenchmarkRunner() {
    }

    static LiveEvaluationResult run(Path benchmarkPath, LiveConfig config) throws Exception {
        Objects.requireNonNull(benchmarkPath, "benchmarkPath");
        Objects.requireNonNull(config, "config");
        LiveBenchmark benchmark = JSON.readValue(Files.readString(benchmarkPath), LiveBenchmark.class);
        validateBenchmark(benchmark);

        int maxCases = config.maxCases() <= 0 ? benchmark.cases().size()
                : Math.min(config.maxCases(), benchmark.cases().size());
        List<LiveBenchmarkCase> selectedCases = benchmark.cases().subList(0, maxCases);
        LiveApiClient client = new LiveApiClient(config.baseUrl(), config.timeout());
        String token = client.login(config.username(), config.password());
        String runId = "live-" + DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(java.time.ZoneOffset.UTC).format(Instant.now()) + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        Path rawDirectory = config.rawDirectory() == null ? null : config.rawDirectory().resolve(runId);
        if (rawDirectory != null) Files.createDirectories(rawDirectory);

        List<CaseRun> runs = new ArrayList<>();
        List<AiNewsQualityEvaluator.QualityCase> scoredCases = new ArrayList<>();
        for (int index = 0; index < selectedCases.size(); index++) {
            LiveBenchmarkCase benchmarkCase = selectedCases.get(index);
            String conversationId = runId + "-" + benchmarkCase.id();
            CaseRun run;
            try {
                run = client.execute(token, config.workspaceId(), config.agentId(), conversationId,
                        benchmarkCase, config.responseFormat());
            } catch (Exception e) {
                run = CaseRun.transportFailure(benchmarkCase.id(), conversationId, config.responseFormat(), e);
            }
            run = annotateFailures(benchmarkCase, run);
            if (rawDirectory != null && run.rawSse() != null && !run.rawSse().isBlank()) {
                Path rawFile = rawDirectory.resolve(safeFileName(benchmarkCase.id()) + ".sse");
                Files.writeString(rawFile, run.rawSse(), StandardCharsets.UTF_8);
                run = run.withRawTrace(relative(rawDirectory, rawFile), sha256(run.rawSse()));
            }
            runs.add(run);
            scoredCases.add(toQualityCase(benchmarkCase, run));
        }

        Map<String, String> metadata = new LinkedHashMap<>();
        if (benchmark.executionMetadata() != null) metadata.putAll(benchmark.executionMetadata());
        metadata.put("runner", "AiNewsLiveAgentBenchmarkRunner");
        metadata.put("runId", runId);
        metadata.put("agentId", String.valueOf(config.agentId()));
        metadata.put("workspaceId", String.valueOf(config.workspaceId()));
        metadata.put("requestedCases", String.valueOf(selectedCases.size()));
        metadata.put("requestTimeoutSeconds", String.valueOf(config.timeout().toSeconds()));
        metadata.put("requestedResponseFormat", config.responseFormat());
        metadata.put("evaluationTree", defaultText(config.evaluationTree(), "unknown"));
        metadata.put("observedModelRoutes", observedRoutes(runs));
        metadata.put("labelProvenance",
                "frozen synthetic evidence-policy labels; not human ratings of production user traffic");
        metadata.put("rawTraceRetention", rawDirectory == null
                ? "disabled" : "caller-supplied-output-directory; not repository input");

        AiNewsQualityEvaluator.QualityDataset qualityDataset = new AiNewsQualityEvaluator.QualityDataset(
                benchmark.datasetId(), benchmark.datasetVersion(), benchmark.evaluationScope(), metadata,
                scoredCases, benchmark.limitations());
        AiNewsQualityEvaluator.EvaluationReport qualityReport = new AiNewsQualityEvaluator().evaluate(
                qualityDataset, config.gitCommit(), "./scripts/run-ai-news-live-agent-eval.sh");
        LiveRuntimeManifest runtime = runtimeManifest(benchmark, config, runId, runs);
        return new LiveEvaluationResult(qualityDataset, qualityReport, runtime, runs);
    }

    static void writeArtifacts(LiveEvaluationResult result, Path traceDatasetPath, Path qualityManifestPath,
                               Path qualityMarkdownPath, Path runtimeManifestPath, Path runtimeMarkdownPath)
            throws IOException {
        writeJson(traceDatasetPath, result.qualityDataset());
        writeJson(qualityManifestPath, result.qualityReport().manifest());
        writeText(qualityMarkdownPath, AiNewsQualityReportRenderer.toMarkdown(result.qualityReport().manifest()));
        writeJson(runtimeManifestPath, result.runtimeManifest());
        writeText(runtimeMarkdownPath, runtimeMarkdown(result.runtimeManifest()));
    }

    static AiNewsQualityEvaluator.QualityCase toQualityCase(LiveBenchmarkCase benchmarkCase, CaseRun run) {
        OutputDecision output = parseDecision(run.assistantContent());
        ToolAssessment tools = assessTools(benchmarkCase.toolExpectation(), run.toolCalls());
        boolean labelsMatch = labelsMatch(benchmarkCase, output);
        boolean citationsMatch = citationsMatch(benchmarkCase, output);
        boolean transportCompleted = run.httpStatus() == 200 && "completed".equalsIgnoreCase(run.streamStatus());
        boolean taskSucceeded = transportCompleted && run.responseFormatAcknowledged()
                && run.serverContractSatisfied()
                && output.valid() && labelsMatch && citationsMatch
                && tools.selectionCorrect() && tools.parametersCorrect() && tools.executionSucceeded();
        AiNewsQualityEvaluator.Prediction prediction = new AiNewsQualityEvaluator.Prediction(
                output.sourceTier(), output.verificationEligible(), output.citationAllowed(),
                output.claimQuoteSupported(), null, taskSucceeded, tools.selectionCorrect(),
                benchmarkCase.toolExpectation().scoresParameters() ? tools.parametersCorrect() : null,
                output.humanReviewRequested());
        return new AiNewsQualityEvaluator.QualityCase(benchmarkCase.id(), benchmarkCase.slices(),
                benchmarkCase.gold(), prediction);
    }

    private static CaseRun annotateFailures(LiveBenchmarkCase benchmarkCase, CaseRun run) {
        List<String> reasons = new ArrayList<>(run.failureReasons());
        OutputDecision output = parseDecision(run.assistantContent());
        ToolAssessment tools = assessTools(benchmarkCase.toolExpectation(), run.toolCalls());
        if (run.httpStatus() != 200) reasons.add("HTTP status was " + run.httpStatus());
        if (!"completed".equalsIgnoreCase(run.streamStatus())) {
            reasons.add("stream status was " + emptyAs(run.streamStatus(), "missing"));
        }
        if (!output.valid()) {
            reasons.addAll(output.validationFailures().stream().map(item -> "response-format: " + item).toList());
        }
        if (run.jsonContractRequested()) {
            if (!"json_object".equalsIgnoreCase(run.observedResponseFormat())) {
                reasons.add("server did not acknowledge responseFormat=json_object in stream_started");
            }
            StructuredContractResult contract = run.structuredOutputContract();
            if (contract == null || !contract.present()) {
                reasons.add("server emitted no structured_output contract event");
            } else {
                if (!"json_object".equalsIgnoreCase(contract.requestedFormat())) {
                    reasons.add("server contract event reported requestedFormat="
                            + emptyAs(contract.requestedFormat(), "missing"));
                }
                if (!Boolean.TRUE.equals(contract.terminalAnswerReached())) {
                    reasons.add("server contract event did not reach a terminal assistant answer");
                }
                if (!Boolean.TRUE.equals(contract.valid()) || !"valid".equalsIgnoreCase(contract.status())) {
                    reasons.add("server structured-output contract status was "
                            + emptyAs(contract.status(), "missing") + ": "
                            + emptyAs(contract.failureReason(), "no reason reported"));
                }
                if (Boolean.TRUE.equals(contract.valid()) != output.valid()) {
                    reasons.add("server contract result disagreed with independent strict parser");
                }
            }
        }
        if (!labelsMatch(benchmarkCase, output)) reasons.add("policy labels did not match frozen gold");
        if (!citationsMatch(benchmarkCase, output)) reasons.add("citation ids or citation decision violated task contract");
        if (!tools.selectionCorrect()) reasons.add("tool selection/order did not match task contract");
        if (!tools.parametersCorrect()) reasons.add("tool arguments did not match task contract");
        if (!tools.executionSucceeded()) reasons.add("required tool did not complete successfully");
        return run.withFailureReasons(reasons);
    }

    private static boolean labelsMatch(LiveBenchmarkCase item, OutputDecision actual) {
        AiNewsQualityEvaluator.GoldLabel expected = item.gold();
        return Objects.equals(normalizeTier(expected.sourceTier()), normalizeTier(actual.sourceTier()))
                && Objects.equals(expected.verificationEligible(), actual.verificationEligible())
                && Objects.equals(expected.citationAllowed(), actual.citationAllowed())
                && Objects.equals(expected.claimQuoteSupported(), actual.claimQuoteSupported())
                && Objects.equals(expected.refusalRequired(), actual.refusalIssued())
                && Objects.equals(expected.humanReviewRequired(), actual.humanReviewRequested());
    }

    private static boolean citationsMatch(LiveBenchmarkCase item, OutputDecision actual) {
        Set<String> allowed = new LinkedHashSet<>(item.allowedCitationIds());
        Set<String> cited = new LinkedHashSet<>(actual.citationIds());
        if (Boolean.TRUE.equals(item.gold().citationAllowed())) {
            return actual.citationAllowed() && !item.requestedCitationId().isBlank()
                    && cited.contains(item.requestedCitationId()) && allowed.containsAll(cited);
        }
        return !actual.citationAllowed() && cited.isEmpty();
    }

    private static ToolAssessment assessTools(ToolExpectation expected, List<ToolCall> calls) {
        ToolExpectation expectation = expected == null ? ToolExpectation.forbidden() : expected;
        if (expectation.forbidsTools()) {
            return new ToolAssessment(calls.isEmpty(), true, true);
        }
        if (calls.size() != 1) return new ToolAssessment(false, false, false);
        ToolCall call = calls.getFirst();
        boolean selected = expectation.toolName().equals(call.toolName());
        boolean parameters = selected && argumentsMatch(expectation.arguments(), call.arguments());
        return new ToolAssessment(selected, parameters, parameters && call.successful());
    }

    private static boolean argumentsMatch(Map<String, String> expected, String actualArguments) {
        if (expected == null || expected.isEmpty()) return actualArguments == null || actualArguments.isBlank()
                || "{}".equals(actualArguments.trim());
        try {
            JsonNode actual = JSON.readTree(actualArguments);
            if (actual == null || !actual.isObject() || actual.size() != expected.size()) return false;
            for (Map.Entry<String, String> entry : expected.entrySet()) {
                JsonNode value = actual.get(entry.getKey());
                if (value == null || !Objects.equals(entry.getValue(), value.asText())) return false;
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    static OutputDecision parseDecision(String content) {
        if (content == null || content.isBlank()) return OutputDecision.invalid("empty assistant content");
        try {
            JsonEnvelope envelope = unwrapJsonEnvelope(content);
            JsonNode root = STRICT_JSON.readValue(envelope.json());
            if (root == null || !root.isObject()) return OutputDecision.invalid("assistant content is not a JSON object");
            List<String> failures = new ArrayList<>();
            if (!envelope.strict()) failures.add("answer must be one JSON object without a Markdown fence");
            String sourceTier = stringField(root, "sourceTier", failures, "missing");
            if (!("official".equals(sourceTier) || "media".equals(sourceTier) || "community".equals(sourceTier))) {
                failures.add("sourceTier must be official/media/community");
            }
            Boolean verification = booleanField(root, "verificationEligible", failures);
            Boolean citation = booleanField(root, "citationAllowed", failures);
            Boolean support = booleanField(root, "claimQuoteSupported", failures);
            Boolean refusal = booleanField(root, "refusalIssued", failures);
            Boolean review = booleanField(root, "humanReviewRequested", failures);
            List<String> citations = stringArrayField(root, "citationIds", failures);
            return new OutputDecision(sourceTier, verification == null ? false : verification,
                    citation == null ? false : citation, support == null ? false : support,
                    refusal == null ? false : refusal, review == null ? false : review, citations,
                    failures.isEmpty(), List.copyOf(failures));
        } catch (Exception e) {
            return OutputDecision.invalid("invalid strict JSON: " + conciseMessage(e));
        }
    }

    private static JsonEnvelope unwrapJsonEnvelope(String content) {
        String candidate = content.trim();
        if (!candidate.startsWith("```")) return new JsonEnvelope(candidate, true);
        int firstLineBreak = candidate.indexOf('\n');
        if (firstLineBreak < 0 || !candidate.endsWith("```")) return new JsonEnvelope(candidate, true);
        String language = candidate.substring(3, firstLineBreak).trim();
        if (!(language.isEmpty() || "json".equalsIgnoreCase(language))) return new JsonEnvelope(candidate, true);
        String inner = candidate.substring(firstLineBreak + 1, candidate.length() - 3).trim();
        return new JsonEnvelope(inner, false);
    }

    private static String stringField(JsonNode root, String name, List<String> failures, String fallback) {
        JsonNode value = root.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            failures.add(name + " must be a nonblank string");
            return fallback;
        }
        return value.asText().trim().toLowerCase(Locale.ROOT);
    }

    private static Boolean booleanField(JsonNode root, String name, List<String> failures) {
        JsonNode value = root.get(name);
        if (value == null || !value.isBoolean()) {
            failures.add(name + " must be boolean");
            return null;
        }
        return value.booleanValue();
    }

    private static List<String> stringArrayField(JsonNode root, String name, List<String> failures) {
        JsonNode value = root.get(name);
        if (value == null || !value.isArray()) {
            failures.add(name + " must be a string array");
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isBlank()) {
                failures.add(name + " must contain only nonblank strings");
                continue;
            }
            values.add(item.asText().trim());
        }
        return List.copyOf(values);
    }

    private static LiveRuntimeManifest runtimeManifest(LiveBenchmark benchmark, LiveConfig config, String runId,
                                                        List<CaseRun> runs) {
        List<Long> elapsed = runs.stream().map(CaseRun::elapsedMs).filter(Objects::nonNull).toList();
        List<Long> ttft = runs.stream().map(CaseRun::timeToFirstContentMs).filter(Objects::nonNull).toList();
        List<Long> toolTime = runs.stream().map(CaseRun::toolExecutionMs).filter(Objects::nonNull).toList();
        long httpSuccess = runs.stream().filter(run -> run.httpStatus() == 200).count();
        long completed = runs.stream().filter(run -> "completed".equalsIgnoreCase(run.streamStatus())).count();
        long validStructured = runs.stream().filter(run -> parseDecision(run.assistantContent()).valid()).count();
        long taskSuccess = runs.stream().filter(run -> toQualityCase(findCase(benchmark, run.caseId()), run)
                .prediction().taskSucceeded()).count();
        long toolCalls = runs.stream().mapToLong(run -> run.toolCalls().size()).sum();
        long toolSucceeded = runs.stream().flatMap(run -> run.toolCalls().stream())
                .filter(ToolCall::successful).count();
        long promptTokens = runs.stream().map(CaseRun::promptTokens).filter(Objects::nonNull)
                .mapToLong(Long::longValue).sum();
        long completionTokens = runs.stream().map(CaseRun::completionTokens).filter(Objects::nonNull)
                .mapToLong(Long::longValue).sum();
        long reasoningTokens = runs.stream().map(CaseRun::reasoningTokens).filter(Objects::nonNull)
                .mapToLong(Long::longValue).sum();
        long jsonRequested = runs.stream().filter(CaseRun::jsonContractRequested).count();
        long formatAcknowledged = runs.stream().filter(CaseRun::jsonContractRequested)
                .filter(run -> "json_object".equalsIgnoreCase(run.observedResponseFormat())).count();
        long contractEvents = runs.stream().filter(CaseRun::jsonContractRequested)
                .filter(run -> run.structuredOutputContract() != null
                        && run.structuredOutputContract().present()).count();
        long contractValid = runs.stream().filter(CaseRun::jsonContractRequested)
                .filter(CaseRun::serverContractSatisfied).count();
        long contractParserAgreement = runs.stream().filter(CaseRun::jsonContractRequested)
                .filter(run -> run.structuredOutputContract() != null
                        && run.structuredOutputContract().present()
                        && Boolean.TRUE.equals(run.structuredOutputContract().valid())
                        == parseDecision(run.assistantContent()).valid()).count();
        Map<String, RuntimeMetric> metrics = new LinkedHashMap<>();
        metrics.put("http200Rate", RuntimeMetric.rate(runs.size(), httpSuccess));
        metrics.put("streamCompletedRate", RuntimeMetric.rate(runs.size(), completed));
        metrics.put("structuredResponseValidRate", RuntimeMetric.rate(runs.size(), validStructured));
        metrics.put("jsonObjectContractRequestedRate", RuntimeMetric.rate(runs.size(), jsonRequested));
        metrics.put("responseFormatAcknowledgedRate", RuntimeMetric.rate(jsonRequested, formatAcknowledged));
        metrics.put("serverContractEventRate", RuntimeMetric.rate(jsonRequested, contractEvents));
        metrics.put("serverContractValidRate", RuntimeMetric.rate(jsonRequested, contractValid));
        metrics.put("serverContractParserAgreementRate", RuntimeMetric.rate(jsonRequested, contractParserAgreement));
        metrics.put("taskSuccessRate", RuntimeMetric.rate(runs.size(), taskSuccess));
        metrics.put("toolExecutionSuccessRate", RuntimeMetric.rate(toolCalls, toolSucceeded));
        metrics.put("endToEndLatencyMs", RuntimeMetric.percentiles(elapsed));
        metrics.put("timeToFirstContentMs", RuntimeMetric.percentiles(ttft));
        metrics.put("toolExecutionMs", RuntimeMetric.percentiles(toolTime));
        metrics.put("promptTokens", RuntimeMetric.sum(promptTokens, runs.size()));
        metrics.put("completionTokens", RuntimeMetric.sum(completionTokens, runs.size()));
        metrics.put("reasoningTokens", RuntimeMetric.sum(reasoningTokens, runs.size()));

        List<RuntimeCase> cases = runs.stream().map(run -> new RuntimeCase(run.caseId(), run.conversationId(),
                run.httpStatus(), run.streamStatus(), run.elapsedMs(), run.timeToFirstContentMs(),
                run.toolExecutionMs(), run.promptTokens(), run.completionTokens(), run.reasoningTokens(),
                run.runtimeProvider(), run.runtimeModel(), run.toolCalls().stream().map(ToolCall::summary).toList(),
                run.rawTracePath(), run.rawTraceSha256(), sha256(run.assistantContent()),
                run.requestedResponseFormat(), run.observedResponseFormat(), run.structuredOutputContract(),
                run.failureReasons()))
                .toList();
        return new LiveRuntimeManifest("2.0", benchmark.evaluationScope(), benchmark.datasetId(),
                benchmark.datasetVersion(), Instant.now().toString(), config.gitCommit(), runId,
                config.agentId(), config.workspaceId(), metrics, cases, benchmark.limitations());
    }

    private static LiveBenchmarkCase findCase(LiveBenchmark benchmark, String id) {
        return benchmark.cases().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalStateException("missing benchmark case " + id));
    }

    private static String runtimeMarkdown(LiveRuntimeManifest manifest) {
        StringBuilder out = new StringBuilder();
        out.append("# AI News Controlled Live Agent Runtime\n\n");
        out.append("- Scope: `").append(manifest.evaluationScope()).append("`\n");
        out.append("- Dataset: `").append(manifest.datasetId()).append("@").append(manifest.datasetVersion()).append("`\n");
        out.append("- Git commit: `").append(manifest.gitCommit()).append("`\n");
        out.append("- Run: `").append(manifest.runId()).append("`\n");
        out.append("- Agent / workspace: `").append(manifest.agentId()).append(" / ")
                .append(manifest.workspaceId()).append("`\n\n");
        out.append("## Runtime Metrics\n\n");
        out.append("| Metric | N | Value | P50 | P95 | Total |\n");
        out.append("| --- | ---: | ---: | ---: | ---: | ---: |\n");
        manifest.metrics().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            RuntimeMetric metric = entry.getValue();
            out.append("| `").append(entry.getKey()).append("` | ").append(metric.evaluated())
                    .append(" | ").append(format(metric.value())).append(" | ")
                    .append(format(metric.p50())).append(" | ").append(format(metric.p95())).append(" | ")
                    .append(metric.total() == null ? "n/a" : metric.total()).append(" |\n");
        });
        out.append("\n## Per-case Execution\n\n");
        out.append("| Case | HTTP | Stream | E2E ms | TTFT ms | Tools | Route | Notes |\n");
        out.append("| --- | ---: | --- | ---: | ---: | ---: | --- | --- |\n");
        manifest.cases().stream().sorted(Comparator.comparing(RuntimeCase::caseId)).forEach(item -> out
                .append("| `").append(item.caseId()).append("` | ").append(item.httpStatus())
                .append(" | `").append(emptyAs(item.streamStatus(), "unknown")).append("` | ")
                .append(valueOrNa(item.elapsedMs())).append(" | ").append(valueOrNa(item.timeToFirstContentMs()))
                .append(" | ").append(item.toolCalls().size()).append(" | `")
                .append(emptyAs(item.runtimeProvider(), "unknown")).append(" / ")
                .append(emptyAs(item.runtimeModel(), "unknown")).append("` | ")
                .append(escape(String.join("; ", item.failureReasons()))).append(" |\n"));
        out.append("\n## Boundaries\n\n");
        out.append("- This is a sequential, controlled online Agent benchmark. P50/P95 describe this run only; they are not QPS, capacity, SLA, or production traffic claims.\n");
        out.append("- The benchmark uses synthetic frozen evidence packets and read-only tool probes. It does not measure open-web discovery accuracy, user satisfaction, delivery success, or production cost.\n");
        out.append("- Raw SSE files are retained under the caller-selected output directory and are excluded from Git.\n");
        for (String limitation : manifest.limitations()) out.append("- ").append(limitation).append("\n");
        return out.toString();
    }

    private static String observedRoutes(List<CaseRun> runs) {
        Set<String> routes = new LinkedHashSet<>();
        for (CaseRun run : runs) {
            if (run.runtimeProvider() == null && run.runtimeModel() == null) continue;
            routes.add(emptyAs(run.runtimeProvider(), "unknown") + "::" + emptyAs(run.runtimeModel(), "unknown"));
        }
        return routes.isEmpty() ? "unknown" : String.join(",", routes);
    }

    private static void validateBenchmark(LiveBenchmark benchmark) {
        if (benchmark == null || blank(benchmark.datasetId()) || blank(benchmark.datasetVersion())
                || blank(benchmark.evaluationScope())) {
            throw new IllegalArgumentException("live benchmark must declare datasetId, datasetVersion, and evaluationScope");
        }
        if (benchmark.cases() == null || benchmark.cases().size() < 30) {
            throw new IllegalArgumentException("controlled live benchmark requires at least 30 cases");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (LiveBenchmarkCase item : benchmark.cases()) {
            if (item == null || blank(item.id()) || blank(item.prompt()) || item.gold() == null) {
                throw new IllegalArgumentException("each live benchmark case needs id, prompt, and gold labels");
            }
            if (!ids.add(item.id())) throw new IllegalArgumentException("duplicate live benchmark case: " + item.id());
            if (item.gold().sourceTier() == null || item.gold().verificationEligible() == null
                    || item.gold().citationAllowed() == null || item.gold().claimQuoteSupported() == null
                    || item.gold().refusalRequired() == null || item.gold().taskSucceeded() == null
                    || item.gold().toolSelectionCorrect() == null || item.gold().humanReviewRequired() == null) {
                throw new IllegalArgumentException("live benchmark case has incomplete gold labels: " + item.id());
            }
            if (item.requestedCitationId() == null || item.allowedCitationIds() == null) {
                throw new IllegalArgumentException("live benchmark case requires citation request metadata: " + item.id());
            }
        }
    }

    private static void writeJson(Path target, Object value) throws IOException {
        if (target == null) return;
        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.writeString(target, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }

    private static void writeText(Path target, String value) throws IOException {
        if (target == null) return;
        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.writeString(target, value == null ? "" : value);
    }

    private static String relative(Path parent, Path child) {
        try {
            return parent.toAbsolutePath().relativize(child.toAbsolutePath()).toString();
        } catch (Exception ignored) {
            return child.getFileName().toString();
        }
    }

    private static String safeFileName(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    static String sha256(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) out.append(String.format(Locale.ROOT, "%02x", item));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String conciseMessage(Exception e) {
        String value = e.getMessage();
        return value == null || value.isBlank() ? e.getClass().getSimpleName()
                : value.substring(0, Math.min(240, value.length()));
    }

    private static String normalizeTier(String value) {
        return defaultText(value, "missing").trim().toLowerCase(Locale.ROOT);
    }

    private static String defaultText(String value, String fallback) {
        return blank(value) ? fallback : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String emptyAs(String value, String fallback) {
        return blank(value) ? fallback : value;
    }

    private static String valueOrNa(Long value) {
        return value == null ? "n/a" : String.valueOf(value);
    }

    private static String format(Double value) {
        return value == null ? "n/a" : String.format(Locale.ROOT, "%.4f", value);
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    record LiveConfig(String baseUrl, String username, String password, long agentId, long workspaceId,
                      Duration timeout, int maxCases, String gitCommit, String evaluationTree, Path rawDirectory,
                      String responseFormat) {
        LiveConfig {
            if (blank(baseUrl) || blank(username) || blank(password) || agentId <= 0 || workspaceId <= 0) {
                throw new IllegalArgumentException("live evaluation requires base URL, credentials, agent, and workspace");
            }
            timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofMinutes(4) : timeout;
            responseFormat = blank(responseFormat) ? "text" : responseFormat.trim().toLowerCase(Locale.ROOT);
            if (!("text".equals(responseFormat) || "json_object".equals(responseFormat))) {
                throw new IllegalArgumentException("live evaluation response format must be text or json_object");
            }
        }
    }

    record LiveBenchmark(String datasetId, String datasetVersion, String evaluationScope,
                         Map<String, String> executionMetadata, List<String> limitations,
                         List<LiveBenchmarkCase> cases) {
        LiveBenchmark {
            executionMetadata = executionMetadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(executionMetadata));
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
            cases = cases == null ? List.of() : List.copyOf(cases);
        }
    }

    record LiveBenchmarkCase(String id, Map<String, String> slices, String prompt,
                             List<String> allowedCitationIds, String requestedCitationId,
                             ToolExpectation toolExpectation, AiNewsQualityEvaluator.GoldLabel gold) {
        LiveBenchmarkCase {
            id = id == null ? "" : id.trim();
            slices = slices == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(slices));
            prompt = prompt == null ? "" : prompt.trim();
            allowedCitationIds = allowedCitationIds == null ? List.of() : List.copyOf(allowedCitationIds);
            requestedCitationId = requestedCitationId == null ? "" : requestedCitationId.trim();
            toolExpectation = toolExpectation == null ? ToolExpectation.forbidden() : toolExpectation;
        }
    }

    record ToolExpectation(String mode, String toolName, Map<String, String> arguments) {
        ToolExpectation {
            mode = mode == null ? "forbidden" : mode.trim().toLowerCase(Locale.ROOT);
            toolName = toolName == null ? "" : toolName.trim();
            arguments = arguments == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(arguments));
            if (!("forbidden".equals(mode) || "required".equals(mode))) {
                throw new IllegalArgumentException("tool expectation mode must be forbidden or required");
            }
            if ("required".equals(mode) && toolName.isBlank()) {
                throw new IllegalArgumentException("required tool expectation needs a toolName");
            }
        }

        static ToolExpectation forbidden() {
            return new ToolExpectation("forbidden", "", Map.of());
        }

        boolean forbidsTools() {
            return "forbidden".equals(mode);
        }

        boolean scoresParameters() {
            return "required".equals(mode);
        }
    }

    record OutputDecision(String sourceTier, boolean verificationEligible, boolean citationAllowed,
                          boolean claimQuoteSupported, boolean refusalIssued, boolean humanReviewRequested,
                          List<String> citationIds, boolean valid, List<String> validationFailures) {
        static OutputDecision invalid(String reason) {
            return new OutputDecision("missing", false, false, false, false, false, List.of(), false, List.of(reason));
        }
    }

    record ToolAssessment(boolean selectionCorrect, boolean parametersCorrect, boolean executionSucceeded) {
    }

    record ToolCall(String toolCallId, String toolName, String arguments, Boolean reportedSuccess, String result) {
        boolean successful() {
            if (!Boolean.TRUE.equals(reportedSuccess)) return false;
            String normalized = result == null ? "" : result.trim().replaceAll("^\"|\"$", "").toLowerCase(Locale.ROOT);
            return !(normalized.startsWith("error:") || normalized.startsWith("[error]")
                    || normalized.contains("tool execution failed"));
        }

        ToolCallSummary summary() {
            return new ToolCallSummary(toolCallId, toolName, arguments, successful(), sha256(result));
        }
    }

    record ToolCallSummary(String toolCallId, String toolName, String arguments, boolean successful,
                           String resultSha256) {
    }

    record CaseRun(String caseId, String conversationId, int httpStatus, String streamStatus,
                   Long elapsedMs, Long timeToFirstContentMs, Long toolExecutionMs, Long promptTokens,
                   Long completionTokens, Long reasoningTokens, String runtimeProvider, String runtimeModel,
                   String assistantContent, List<ToolCall> toolCalls, String rawSse, String rawTracePath,
                   String rawTraceSha256, String requestedResponseFormat, String observedResponseFormat,
                   StructuredContractResult structuredOutputContract, List<String> failureReasons) {
        CaseRun {
            streamStatus = streamStatus == null ? "" : streamStatus;
            assistantContent = assistantContent == null ? "" : assistantContent;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            requestedResponseFormat = blank(requestedResponseFormat) ? "text" : requestedResponseFormat;
            observedResponseFormat = observedResponseFormat == null ? "" : observedResponseFormat;
            failureReasons = failureReasons == null ? List.of() : List.copyOf(failureReasons);
        }

        static CaseRun transportFailure(String caseId, String conversationId,
                                        String requestedResponseFormat, Exception e) {
            return new CaseRun(caseId, conversationId, 0, "transport_error", null, null, null,
                    null, null, null, null, null, "", List.of(), "", null, null,
                    requestedResponseFormat, "", null,
                    List.of("transport: " + conciseMessage(e)));
        }

        CaseRun withRawTrace(String path, String hash) {
            return new CaseRun(caseId, conversationId, httpStatus, streamStatus, elapsedMs, timeToFirstContentMs,
                    toolExecutionMs, promptTokens, completionTokens, reasoningTokens, runtimeProvider, runtimeModel,
                    assistantContent, toolCalls, rawSse, path, hash, requestedResponseFormat,
                    observedResponseFormat, structuredOutputContract, failureReasons);
        }

        CaseRun withFailureReasons(List<String> reasons) {
            return new CaseRun(caseId, conversationId, httpStatus, streamStatus, elapsedMs, timeToFirstContentMs,
                    toolExecutionMs, promptTokens, completionTokens, reasoningTokens, runtimeProvider, runtimeModel,
                    assistantContent, toolCalls, rawSse, rawTracePath, rawTraceSha256, requestedResponseFormat,
                    observedResponseFormat, structuredOutputContract,
                    List.copyOf(new LinkedHashSet<>(reasons)));
        }

        boolean jsonContractRequested() {
            return "json_object".equalsIgnoreCase(requestedResponseFormat);
        }

        boolean responseFormatAcknowledged() {
            return !jsonContractRequested()
                    || "json_object".equalsIgnoreCase(observedResponseFormat);
        }

        boolean serverContractSatisfied() {
            return !jsonContractRequested() || (structuredOutputContract != null
                    && structuredOutputContract.present()
                    && "json_object".equalsIgnoreCase(structuredOutputContract.requestedFormat())
                    && Boolean.TRUE.equals(structuredOutputContract.valid())
                    && Boolean.TRUE.equals(structuredOutputContract.terminalAnswerReached())
                    && "valid".equalsIgnoreCase(structuredOutputContract.status()));
        }
    }

    record RuntimeMetric(long evaluated, Long successes, Double value, Double p50, Double p95, Long total) {
        static RuntimeMetric rate(long evaluated, long successes) {
            return new RuntimeMetric(evaluated, successes, evaluated == 0 ? null : (double) successes / evaluated,
                    null, null, null);
        }

        static RuntimeMetric percentiles(Collection<Long> values) {
            List<Long> sorted = values.stream().sorted().toList();
            if (sorted.isEmpty()) return new RuntimeMetric(0, null, null, null, null, null);
            return new RuntimeMetric(sorted.size(), null, null, percentile(sorted, 0.50), percentile(sorted, 0.95),
                    sorted.stream().mapToLong(Long::longValue).sum());
        }

        static RuntimeMetric sum(long total, long evaluated) {
            return new RuntimeMetric(evaluated, null, null, null, null, total);
        }

        private static double percentile(List<Long> sorted, double percentile) {
            int index = Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1);
            return sorted.get(index);
        }
    }

    record RuntimeCase(String caseId, String conversationId, int httpStatus, String streamStatus,
                       Long elapsedMs, Long timeToFirstContentMs, Long toolExecutionMs, Long promptTokens,
                       Long completionTokens, Long reasoningTokens, String runtimeProvider, String runtimeModel,
                       List<ToolCallSummary> toolCalls, String rawTracePath, String rawTraceSha256,
                       String outputSha256, String requestedResponseFormat, String observedResponseFormat,
                       StructuredContractResult structuredOutputContract, List<String> failureReasons) {
        RuntimeCase {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            failureReasons = failureReasons == null ? List.of() : List.copyOf(failureReasons);
        }
    }

    record StructuredContractResult(String requestedFormat, String enforcement, String status,
                                    Boolean valid, Boolean terminalAnswerReached, String failureReason) {
        StructuredContractResult {
            requestedFormat = requestedFormat == null ? "" : requestedFormat;
            enforcement = enforcement == null ? "" : enforcement;
            status = status == null ? "" : status;
            failureReason = failureReason == null ? "" : failureReason;
        }

        boolean present() {
            return !status.isBlank();
        }
    }

    record LiveRuntimeManifest(String schemaVersion, String evaluationScope, String datasetId, String datasetVersion,
                               String generatedAt, String gitCommit, String runId, long agentId, long workspaceId,
                               Map<String, RuntimeMetric> metrics, List<RuntimeCase> cases,
                               List<String> limitations) {
        LiveRuntimeManifest {
            metrics = metrics == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metrics));
            cases = cases == null ? List.of() : List.copyOf(cases);
            limitations = limitations == null ? List.of() : List.copyOf(limitations);
        }
    }

    record LiveEvaluationResult(AiNewsQualityEvaluator.QualityDataset qualityDataset,
                                AiNewsQualityEvaluator.EvaluationReport qualityReport,
                                LiveRuntimeManifest runtimeManifest, List<CaseRun> runs) {
        LiveEvaluationResult {
            runs = runs == null ? List.of() : List.copyOf(runs);
        }

        long completedStreams() {
            return runs.stream().filter(run -> "completed".equalsIgnoreCase(run.streamStatus())).count();
        }
    }

    private static final class LiveApiClient {
        private final String baseUrl;
        private final Duration timeout;
        private final HttpClient client;

        private LiveApiClient(String baseUrl, Duration timeout) {
            this.baseUrl = baseUrl.replaceAll("/+$", "");
            this.timeout = timeout;
            this.client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        }

        private String login(String username, String password) throws Exception {
            String body = JSON.writeValueAsString(Map.of("username", username, "password", password));
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(endpoint("/api/v1/auth/login"))
                    .header("Content-Type", "application/json")
                    .timeout(timeout).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IOException("login returned HTTP " + response.statusCode());
            }
            JsonNode root = JSON.readTree(response.body());
            String token = root.path("data").path("token").asText("");
            if (token.isBlank()) throw new IOException("login response did not include a JWT token");
            return token;
        }

        private CaseRun execute(String token, long workspaceId, long agentId, String conversationId,
                                LiveBenchmarkCase benchmarkCase, String responseFormat) throws Exception {
            String requestBody = JSON.writeValueAsString(Map.of(
                    "agentId", agentId,
                    "conversationId", conversationId,
                    "message", renderPrompt(benchmarkCase),
                    "responseFormat", responseFormat));
            long start = System.nanoTime();
            HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(endpoint("/api/v1/chat/stream"))
                    .header("Authorization", "Bearer " + token)
                    .header("X-Workspace-Id", String.valueOf(workspaceId))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .timeout(timeout).POST(HttpRequest.BodyPublishers.ofString(requestBody)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                SseCapture capture = readSse(body, start);
                long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
                return capture.toCaseRun(benchmarkCase.id(), conversationId, response.statusCode(), elapsed,
                        responseFormat);
            }
        }

        private URI endpoint(String path) {
            return URI.create(baseUrl + path);
        }
    }

    private static String renderPrompt(LiveBenchmarkCase item) {
        String toolInstruction = item.toolExpectation().forbidsTools()
                ? "Do not call any tool. Do not search, write an event, create a Wiki page, create content, "
                + "send a message, request approval, or cause any external side effect."
                : "Before returning JSON, call exactly one tool: `" + item.toolExpectation().toolName()
                + "` with exactly these JSON arguments: " + compactJson(item.toolExpectation().arguments())
                + ". Do not call any other tool. This required probe is read-only; do not write, publish, "
                + "send messages, create Wiki/content, or request approval.";
        return """
                This is a controlled NewsClaw Agent quality evaluation. Treat only the supplied evidence packet as facts.
                Do not use background knowledge, infer unstated facts, or invent citations. Set sourceTier to the strongest source represented in the packet, independently of whether its quote proves the claim. `claimQuoteSupported` is true only when a trustworthy quote directly entails the primary claim; a hedge, anonymous assertion, lookalike domain, or unrelated quote is not support. A source is verification-eligible only when the packet contains direct official support for the claim or two independent trusted-media supports; unresolved conflicts must block verification. A citation is allowed only when verification is eligible, the requested citation id is inside the packet, and its quote supports the claim. `refusalIssued=true` means the claim must not be marked verified or sent to content production. `humanReviewRequested=true` means an operator must resolve ambiguity or conflict.

                Return exactly one JSON object, with no Markdown and no other text:
                {"sourceTier":"official|media|community","verificationEligible":true|false,"citationAllowed":true|false,"claimQuoteSupported":true|false,"refusalIssued":true|false,"humanReviewRequested":true|false,"citationIds":["evidence-id",...]}

                %s

                Case:
                %s
                """.formatted(toolInstruction, item.prompt());
    }

    private static String compactJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("unable to render controlled tool instruction", e);
        }
    }

    static SseCapture readSse(InputStream input, long startedNanos) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder raw = new StringBuilder();
            StringBuilder answer = new StringBuilder();
            Map<String, MutableToolCall> toolCalls = new LinkedHashMap<>();
            List<String> failures = new ArrayList<>();
            MutableSseEvent event = new MutableSseEvent();
            String streamStatus = "";
            String provider = null;
            String model = null;
            Long promptTokens = null;
            Long completionTokens = null;
            Long reasoningTokens = null;
            Long toolExecutionMs = null;
            Long firstContentMs = null;
            String observedResponseFormat = "";
            StructuredContractResult structuredOutputContract = null;
            String line;
            while ((line = reader.readLine()) != null) {
                raw.append(line).append('\n');
                if (line.isEmpty()) {
                    if (event.hasData()) {
                        SseEventResult outcome = processEvent(event.name, event.data.toString(), answer, toolCalls,
                                failures, startedNanos, firstContentMs);
                        firstContentMs = outcome.firstContentMs() == null ? firstContentMs : outcome.firstContentMs();
                        if (outcome.streamStatus() != null) streamStatus = outcome.streamStatus();
                        if (outcome.provider() != null) provider = outcome.provider();
                        if (outcome.model() != null) model = outcome.model();
                        if (outcome.promptTokens() != null) promptTokens = outcome.promptTokens();
                        if (outcome.completionTokens() != null) completionTokens = outcome.completionTokens();
                        if (outcome.reasoningTokens() != null) reasoningTokens = outcome.reasoningTokens();
                        if (outcome.toolExecutionMs() != null) toolExecutionMs = outcome.toolExecutionMs();
                        if (outcome.observedResponseFormat() != null) {
                            observedResponseFormat = outcome.observedResponseFormat();
                        }
                        if (outcome.structuredOutputContract() != null) {
                            structuredOutputContract = outcome.structuredOutputContract();
                        }
                    }
                    event = new MutableSseEvent();
                    continue;
                }
                if (line.startsWith("event:")) event.name = line.substring("event:".length()).trim();
                else if (line.startsWith("data:")) {
                    if (!event.data.isEmpty()) event.data.append('\n');
                    event.data.append(line.substring("data:".length()).trim());
                }
            }
            if (event.hasData()) {
                SseEventResult outcome = processEvent(event.name, event.data.toString(), answer, toolCalls,
                        failures, startedNanos, firstContentMs);
                firstContentMs = outcome.firstContentMs() == null ? firstContentMs : outcome.firstContentMs();
                if (outcome.streamStatus() != null) streamStatus = outcome.streamStatus();
                if (outcome.provider() != null) provider = outcome.provider();
                if (outcome.model() != null) model = outcome.model();
                if (outcome.promptTokens() != null) promptTokens = outcome.promptTokens();
                if (outcome.completionTokens() != null) completionTokens = outcome.completionTokens();
                if (outcome.reasoningTokens() != null) reasoningTokens = outcome.reasoningTokens();
                if (outcome.toolExecutionMs() != null) toolExecutionMs = outcome.toolExecutionMs();
                if (outcome.observedResponseFormat() != null) {
                    observedResponseFormat = outcome.observedResponseFormat();
                }
                if (outcome.structuredOutputContract() != null) {
                    structuredOutputContract = outcome.structuredOutputContract();
                }
            }
            if (streamStatus.isBlank()) failures.add("SSE stream had no done event");
            return new SseCapture(raw.toString(), answer.toString(), toolCalls.values().stream()
                    .map(MutableToolCall::toImmutable).toList(), streamStatus, provider, model,
                    promptTokens, completionTokens, reasoningTokens, toolExecutionMs, firstContentMs,
                    observedResponseFormat, structuredOutputContract, failures);
        }
    }

    private static SseEventResult processEvent(String eventName, String data, StringBuilder answer,
                                                Map<String, MutableToolCall> toolCalls, List<String> failures,
                                                long startedNanos, Long firstContentMs) {
        String name = eventName == null ? "" : eventName.trim();
        JsonNode payload;
        try {
            payload = JSON.readTree(data);
        } catch (JsonProcessingException e) {
            if ("error".equals(name)) failures.add("SSE error: malformed payload");
            return SseEventResult.empty(firstContentMs);
        }
        if ("content_delta".equals(name)) {
            if (firstContentMs == null) firstContentMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
            answer.append(payload.path("delta").asText(""));
        } else if ("tool_call_started".equals(name) || "tool_start".equals(name)) {
            String id = payload.path("toolCallId").asText(payload.path("id").asText(""));
            if (id.isBlank()) id = "anonymous-" + toolCalls.size();
            MutableToolCall call = toolCalls.computeIfAbsent(id, MutableToolCall::new);
            call.toolName = payload.path("toolName").asText(payload.path("name").asText(""));
            call.arguments = payload.path("arguments").asText(payload.path("args").asText("{}"));
        } else if ("tool_call_completed".equals(name) || "tool_end".equals(name)) {
            String id = payload.path("toolCallId").asText(payload.path("id").asText(""));
            if (id.isBlank()) id = "anonymous-" + toolCalls.size();
            MutableToolCall call = toolCalls.computeIfAbsent(id, MutableToolCall::new);
            if (call.toolName.isBlank()) call.toolName = payload.path("toolName").asText(payload.path("name").asText(""));
            if (call.arguments.isBlank()) call.arguments = payload.path("arguments").asText(payload.path("args").asText("{}"));
            call.reportedSuccess = payload.path("success").isBoolean() ? payload.path("success").booleanValue() : null;
            call.result = payload.path("result").asText("");
        } else if ("done".equals(name)) {
            String status = payload.path("status").asText("");
            return new SseEventResult(firstContentMs, status, payload.path("runtimeProvider").asText(null),
                    payload.path("runtimeModel").asText(null), optionalLong(payload, "promptTokens"),
                    optionalLong(payload, "completionTokens"), optionalLong(payload, "reasoningTokens"), null,
                    null, null);
        } else if ("perf_summary".equals(name) && "tool_execution".equals(payload.path("phase").asText())) {
            return new SseEventResult(firstContentMs, null, null, null, null, null, null,
                    optionalLong(payload, "tool_exec_ms"), null, null);
        } else if ("stream_started".equals(name)) {
            return new SseEventResult(firstContentMs, null, null, null, null, null, null,
                    null, payload.path("responseFormat").asText(""), null);
        } else if ("structured_output".equals(name)) {
            StructuredContractResult contract = new StructuredContractResult(
                    payload.path("requestedFormat").asText(""),
                    payload.path("enforcement").asText(""),
                    payload.path("status").asText(""),
                    payload.path("valid").isBoolean() ? payload.path("valid").booleanValue() : null,
                    payload.path("terminalAnswerReached").isBoolean()
                            ? payload.path("terminalAnswerReached").booleanValue() : null,
                    payload.path("failureReason").asText(""));
            return new SseEventResult(firstContentMs, null, null, null, null, null, null,
                    null, null, contract);
        } else if ("error".equals(name)) {
            failures.add("SSE error: " + payload.path("message").asText("unknown"));
        }
        return SseEventResult.empty(firstContentMs);
    }

    private static Long optionalLong(JsonNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || value.isNull()) return null;
        if (value.isNumber()) return value.longValue();
        if (value.isTextual()) {
            try {
                return Long.parseLong(value.asText());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static final class MutableSseEvent {
        private String name = "message";
        private final StringBuilder data = new StringBuilder();

        private boolean hasData() {
            return !data.isEmpty();
        }
    }

    private static final class MutableToolCall {
        private final String id;
        private String toolName = "";
        private String arguments = "";
        private Boolean reportedSuccess;
        private String result = "";

        private MutableToolCall(String id) {
            this.id = id;
        }

        private ToolCall toImmutable() {
            return new ToolCall(id, toolName, arguments, reportedSuccess, result);
        }
    }

    private record SseEventResult(Long firstContentMs, String streamStatus, String provider, String model,
                                  Long promptTokens, Long completionTokens, Long reasoningTokens,
                                  Long toolExecutionMs, String observedResponseFormat,
                                  StructuredContractResult structuredOutputContract) {
        private static SseEventResult empty(Long firstContentMs) {
            return new SseEventResult(firstContentMs, null, null, null, null, null, null, null,
                    null, null);
        }
    }

    private record JsonEnvelope(String json, boolean strict) {
    }

    record SseCapture(String rawSse, String assistantContent, List<ToolCall> toolCalls, String streamStatus,
                      String runtimeProvider, String runtimeModel, Long promptTokens, Long completionTokens,
                      Long reasoningTokens, Long toolExecutionMs, Long timeToFirstContentMs,
                      String observedResponseFormat, StructuredContractResult structuredOutputContract,
                      List<String> failureReasons) {
        SseCapture {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            failureReasons = failureReasons == null ? List.of() : List.copyOf(failureReasons);
        }

        CaseRun toCaseRun(String caseId, String conversationId, int httpStatus, long elapsedMs,
                          String requestedResponseFormat) {
            return new CaseRun(caseId, conversationId, httpStatus, streamStatus, elapsedMs, timeToFirstContentMs,
                    toolExecutionMs, promptTokens, completionTokens, reasoningTokens, runtimeProvider, runtimeModel,
                    assistantContent, toolCalls, rawSse, null, null, requestedResponseFormat,
                    observedResponseFormat, structuredOutputContract, failureReasons);
        }
    }
}
