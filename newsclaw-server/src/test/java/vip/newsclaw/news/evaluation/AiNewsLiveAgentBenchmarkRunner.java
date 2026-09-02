package vip.newsclaw.news.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import vip.newsclaw.news.contract.AiNewsEvidenceAssessmentContract;
import vip.newsclaw.news.model.AiNewsEvidenceRelation;
import vip.newsclaw.news.service.AiNewsDecisionPolicy;
import vip.newsclaw.news.service.AiNewsSourceRegistry;

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
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Set<String> DECISION_FIELDS = Set.of(
            "sourceTier", "verificationEligible", "citationAllowed", "claimQuoteSupported",
            "refusalIssued", "humanReviewRequested", "citationIds");
    private static final String AI_NEWS_DECISION_SCHEMA = "ai_news_decision_v1";
    private static final String AI_NEWS_RELATIONS_SCHEMA = "ai_news_evidence_relations_v2";
    private static final AiNewsDecisionPolicy DECISION_POLICY =
            new AiNewsDecisionPolicy(new AiNewsEvaluationSourceRegistry());
    private static final Set<String> SUPPORTED_PROMPT_VERSIONS = Set.of(
            "live-agent-evidence-v1", "live-agent-evidence-v2", "live-agent-evidence-v3",
            "live-agent-evidence-v4-development", "live-agent-evidence-v4-holdout",
            "live-agent-evidence-v5-development", "live-agent-evidence-v6-development",
            "live-agent-evidence-v7-development", "live-agent-evidence-v8-development",
            "live-agent-evidence-v9-relations-development",
            "live-agent-evidence-v10-relations-development");

    /**
     * Keep the model-facing registry snapshot explicit.  A short list of
     * "examples" is not enough for a deterministic source-tier task: the
     * registry contains media hosts, subdomains, and official GitHub prefixes
     * that are easy to misclassify when the model has to rely on memory.
     */
    private static final String SOURCE_REGISTRY_SNAPSHOT = """
            AUTHORITATIVE source_registry.yml snapshot (use this exact table; it is not illustrative):
            official hosts = openai.com, anthropic.com, mistral.ai, deepseek.com, deepmind.google, blog.google,
            ai.meta.com, about.fb.com, qwenlm.github.io, alibabagroup.com, zhipuai.cn, bigmodel.cn,
            huggingface.co, seed.bytedance.com, volcengine.com, baidu.com, hunyuan.tencent.com, tencent.com,
            huawei.com, hiascend.com, xiaomi.com, unitree.com, ubtrobot.com, agibot.com, fftai.com,
            figure.ai, bostondynamics.com, tesla.com, developer.nvidia.com, nvidia.com, amd.com, cambricon.com.
            official URL prefixes = https://github.com/deepseek-ai/, https://github.com/QwenLM/.
            trusted media hosts = jiqizhixin.com, qbitai.com, 36kr.com, geekpark.net, cls.cn, stcn.com,
            reuters.com, bloomberg.com, techcrunch.com, theverge.com, wired.com, wsj.com, engadget.com,
            venturebeat.com, technologyreview.com, caixin.com.
            Match the complete lower-case URL host (a registered host or its subdomain) and the complete
            normalized URL prefix. Publisher text never upgrades an unregistered host. Anything matching
            neither the official host/prefix list nor the trusted-media host list is community.
            """;

    private AiNewsLiveAgentBenchmarkRunner() {
    }

    static LiveEvaluationResult run(Path benchmarkPath, LiveConfig config) throws Exception {
        Objects.requireNonNull(benchmarkPath, "benchmarkPath");
        Objects.requireNonNull(config, "config");
        String benchmarkJson = Files.readString(benchmarkPath);
        LiveBenchmark benchmark = JSON.readValue(benchmarkJson, LiveBenchmark.class);
        validateBenchmark(benchmark);

        List<LiveBenchmarkCase> orderedCases = orderedCases(benchmark.cases(), config.caseOrder());
        int maxCases = config.maxCases() <= 0 ? orderedCases.size()
                : Math.min(config.maxCases(), orderedCases.size());
        List<LiveBenchmarkCase> selectedCases = orderedCases.subList(0, maxCases);
        String declaredPromptVersion = normalizePromptVersion(
                benchmark.executionMetadata().getOrDefault("promptVersion", "live-agent-evidence-v1"));
        String promptVersion = normalizePromptVersion(defaultText(config.promptVersion(), declaredPromptVersion));
        boolean promptOverride = !declaredPromptVersion.equals(promptVersion);
        validateRunProtocol(config, benchmark, selectedCases.size(), promptOverride);
        String promptContractSha256 = sha256(benchmark.cases().stream()
                .map(item -> item.id() + "\n" + renderPrompt(item, promptVersion))
                .reduce("", (left, right) -> left + "\n\u0000\n" + right));
        LiveApiClient client = new LiveApiClient(config.baseUrl(), config.timeout());
        String token = client.login(config.username(), config.password());
        String toolChoicePolicy = benchmark.executionMetadata().getOrDefault("toolChoicePolicy", "auto");
        List<String> toolCandidates = requestedToolCandidates(benchmark.executionMetadata());
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
            String requestedToolChoice = requestedToolChoice(benchmarkCase, toolChoicePolicy);
            CaseRun run;
            try {
                run = client.execute(token, config.workspaceId(), config.agentId(), conversationId,
                        benchmarkCase, config.responseFormat(), promptVersion, requestedToolChoice,
                        config.thinkingLevel(), toolCandidates);
            } catch (Exception e) {
                run = CaseRun.transportFailure(benchmarkCase.id(), conversationId,
                        config.responseFormat(), requestedToolChoice, toolCandidates, e);
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
        metadata.put("availableCases", String.valueOf(benchmark.cases().size()));
        metadata.put("sampling", samplingDescription(
                selectedCases.size(), benchmark.cases().size(), config.caseOrder()));
        metadata.put("caseOrder", config.caseOrder());
        metadata.put("requestTimeoutSeconds", String.valueOf(config.timeout().toSeconds()));
        metadata.put("requestedResponseFormat", config.responseFormat());
        metadata.put("declaredPromptVersion", declaredPromptVersion);
        metadata.put("promptVersion", promptVersion);
        metadata.put("promptOverride", String.valueOf(promptOverride));
        metadata.put("runClass", config.runClass());
        metadata.put("thinkingLevel", defaultText(config.thinkingLevel(), "model-default"));
        metadata.put("toolChoicePolicy", toolChoicePolicy);
        metadata.put("requestedToolCandidates", toolCandidates == null
                ? "unrestricted" : String.join(",", toolCandidates));
        metadata.put("evaluationTree", defaultText(config.evaluationTree(), "unknown"));
        metadata.put("benchmarkSha256", sha256(benchmarkJson));
        metadata.put("promptContractSha256", promptContractSha256);
        metadata.put("evaluationSourceFingerprint",
                defaultText(config.evaluationSourceFingerprint(), "unknown"));
        metadata.put("serverRevision", defaultText(config.serverRevision(), "unknown"));
        metadata.put("primaryReviewSignoff",
                defaultText(config.primaryReviewSignoff(), "not-recorded"));
        metadata.put("independentReviewSignoff",
                defaultText(config.independentReviewSignoff(), "not-recorded"));
        metadata.put("formalProtocolSatisfied", String.valueOf("formal".equals(config.runClass())));
        metadata.put("observedModelRoutes", observedRoutes(runs));
        metadata.putIfAbsent("labelProvenance",
                "frozen synthetic evidence-policy labels; not human ratings of production user traffic");
        metadata.put("rawTraceRetention", rawDirectory == null
                ? "disabled" : "caller-supplied-output-directory; not repository input");

        List<String> limitations = new ArrayList<>(benchmark.limitations());
        if (!"clean".equalsIgnoreCase(config.evaluationTree())) {
            limitations.add("The evaluated source tree was not clean; this run is diagnostic and cannot be a formal baseline.");
        }
        if (promptOverride) {
            limitations.add("The declared frozen Prompt was overridden; this dataset reuse is development evidence only.");
        }
        if (!"formal".equals(config.runClass())) {
            limitations.add("runClass=" + config.runClass()
                    + "; the artifact was not admitted through the formal-run protocol.");
        }
        AiNewsQualityEvaluator.QualityDataset qualityDataset = new AiNewsQualityEvaluator.QualityDataset(
                benchmark.datasetId(), benchmark.datasetVersion(), benchmark.evaluationScope(), metadata,
                scoredCases, limitations);
        AiNewsQualityEvaluator.EvaluationReport qualityReport = new AiNewsQualityEvaluator().evaluate(
                qualityDataset, config.gitCommit(), "./scripts/run-ai-news-live-agent-eval.sh");
        LiveRuntimeManifest runtime = runtimeManifest(benchmark, config, runId, runs, metadata, limitations);
        return new LiveEvaluationResult(qualityDataset, qualityReport, runtime, runs);
    }

    static void validateRunProtocol(LiveConfig config, LiveBenchmark benchmark,
                                    int selectedCases, boolean promptOverride) {
        if (!"formal".equals(config.runClass())) return;
        List<String> failures = new ArrayList<>();
        if (!"clean".equalsIgnoreCase(config.evaluationTree())) {
            failures.add("evaluationTree must be clean");
        }
        if (selectedCases != benchmark.cases().size()) {
            failures.add("all frozen cases must be selected");
        }
        if (promptOverride) {
            failures.add("the dataset-declared Prompt must not be overridden");
        }
        if (blank(config.evaluationSourceFingerprint())) {
            failures.add("evaluationSourceFingerprint is required");
        }
        if (blank(config.serverRevision())) {
            failures.add("serverRevision is required");
        }
        if (!predeclaredCaseOrders(benchmark).contains(config.caseOrder())) {
            failures.add("caseOrder must be predeclared by the frozen dataset");
        }
        if (!"two-independent-reviewers-complete".equalsIgnoreCase(
                benchmark.executionMetadata().getOrDefault("labelReviewStatus", ""))) {
            failures.add("dataset labelReviewStatus must be two-independent-reviewers-complete");
        }
        if (blank(config.primaryReviewSignoff())) {
            failures.add("primaryReviewSignoff is required");
        }
        if (blank(config.independentReviewSignoff())) {
            failures.add("independentReviewSignoff is required");
        } else if (!blank(config.primaryReviewSignoff())
                && config.primaryReviewSignoff().equalsIgnoreCase(config.independentReviewSignoff())) {
            failures.add("primary and independent review signoffs must be distinct");
        }
        if (!failures.isEmpty()) {
            throw new IllegalArgumentException("formal live evaluation rejected: " + String.join("; ", failures));
        }
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
        OutputDecision output = parseOutput(benchmarkCase, run.assistantContent());
        ToolAssessment tools = assessTools(benchmarkCase.toolExpectation(), run.toolCalls());
        boolean labelsMatch = labelsMatch(benchmarkCase, output);
        boolean citationsMatch = citationsMatch(benchmarkCase, output);
        boolean relationsMatch = relationsMatch(benchmarkCase, output);
        boolean transportCompleted = run.httpStatus() == 200 && "completed".equalsIgnoreCase(run.streamStatus());
        boolean taskSucceeded = transportCompleted && run.responseFormatAcknowledged()
                && run.responseSchemaAcknowledged(expectedResponseSchema(benchmarkCase))
                && toolChoiceAcknowledged(benchmarkCase, run)
                && run.toolCandidatesAcknowledged()
                && run.serverContractSatisfied()
                && output.valid() && labelsMatch && citationsMatch && relationsMatch
                && tools.selectionCorrect() && tools.parametersCorrect() && tools.executionSucceeded();
        AiNewsQualityEvaluator.Prediction prediction = new AiNewsQualityEvaluator.Prediction(
                output.sourceTier(), output.verificationEligible(), output.citationAllowed(),
                output.claimQuoteSupported(), null, taskSucceeded, tools.selectionCorrect(),
                benchmarkCase.toolExpectation().scoresParameters() ? tools.parametersCorrect() : null,
                output.humanReviewRequested(), output.refusalIssued(), output.valid());
        return new AiNewsQualityEvaluator.QualityCase(benchmarkCase.id(), benchmarkCase.slices(),
                benchmarkCase.gold(), prediction);
    }

    private static CaseRun annotateFailures(LiveBenchmarkCase benchmarkCase, CaseRun run) {
        List<String> reasons = new ArrayList<>(run.failureReasons());
        OutputDecision output = parseOutput(benchmarkCase, run.assistantContent());
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
            String expectedSchema = expectedResponseSchema(benchmarkCase);
            if (!expectedSchema.equalsIgnoreCase(run.observedResponseSchema())) {
                reasons.add("server did not acknowledge responseSchema=" + expectedSchema
                        + " in stream_started");
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
        if (!toolChoiceAcknowledged(benchmarkCase, run)) {
            reasons.add("server did not acknowledge requested toolChoice=" + run.requestedToolChoice()
                    + " in stream_started");
        }
        if (!run.toolCandidatesAcknowledged()) {
            reasons.add("server did not acknowledge requested toolCandidates="
                    + run.requestedToolCandidates() + " in stream_started");
        }
        if (!labelsMatch(benchmarkCase, output)) reasons.add("policy labels did not match frozen gold");
        if (!citationsMatch(benchmarkCase, output)) reasons.add("citation ids or citation decision violated task contract");
        if (!relationsMatch(benchmarkCase, output)) reasons.add("semantic relations did not match development gold");
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

    static boolean citationsMatch(LiveBenchmarkCase item, OutputDecision actual) {
        Set<String> allowed = new LinkedHashSet<>(item.allowedCitationIds());
        if (Boolean.TRUE.equals(item.gold().citationAllowed())) {
            return Boolean.TRUE.equals(actual.citationAllowed()) && !item.requestedCitationId().isBlank()
                    && allowed.contains(item.requestedCitationId())
                    && actual.citationIds().equals(List.of(item.requestedCitationId()));
        }
        return Boolean.FALSE.equals(actual.citationAllowed()) && actual.citationIds().isEmpty();
    }

    private static boolean relationsMatch(LiveBenchmarkCase item, OutputDecision actual) {
        if (item.policyPacket() == null) return true;
        if (!actual.valid()) return false;
        if (actual.semanticRelations().size() != item.policyPacket().evidence().size()) return false;
        for (PolicyEvidence evidence : item.policyPacket().evidence()) {
            AiNewsEvidenceRelation expected = AiNewsEvidenceRelation.from(evidence.expectedRelation());
            if (actual.semanticRelations().get(evidence.id()) != expected) return false;
        }
        return true;
    }

    private static boolean toolChoiceAcknowledged(LiveBenchmarkCase item, CaseRun run) {
        if (item.toolExpectation().autonomous()) {
            return "auto".equalsIgnoreCase(run.observedToolChoice());
        }
        return run.toolChoiceAcknowledged();
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
            Set<String> actualFields = new LinkedHashSet<>();
            root.fieldNames().forEachRemaining(actualFields::add);
            Set<String> unexpectedFields = new LinkedHashSet<>(actualFields);
            unexpectedFields.removeAll(DECISION_FIELDS);
            if (!unexpectedFields.isEmpty()) {
                failures.add("unexpected decision fields: " + unexpectedFields);
            }
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
            return new OutputDecision(sourceTier, verification, citation, support, refusal, review, citations,
                    Map.of(), failures.isEmpty(), List.copyOf(failures));
        } catch (Exception e) {
            return OutputDecision.invalid("invalid strict JSON: " + conciseMessage(e));
        }
    }

    static OutputDecision parseOutput(LiveBenchmarkCase item, String content) {
        if (item == null || item.policyPacket() == null) return parseDecision(content);
        List<String> expectedIds = item.policyPacket().evidence().stream().map(PolicyEvidence::id).toList();
        AiNewsEvidenceAssessmentContract.ParseResult parsed =
                AiNewsEvidenceAssessmentContract.parseExact(content, expectedIds);
        if (!parsed.valid()) return OutputDecision.invalid(parsed.failureReason());
        Map<String, AiNewsEvidenceAssessmentContract.RelationAssessment> byId = new LinkedHashMap<>();
        Map<String, AiNewsEvidenceRelation> relations = new LinkedHashMap<>();
        for (AiNewsEvidenceAssessmentContract.RelationAssessment assessment
                : parsed.assessment().relations()) {
            byId.put(assessment.evidenceId(), assessment);
            relations.put(assessment.evidenceId(), assessment.relation());
        }
        List<AiNewsDecisionPolicy.EvidenceFact> facts = new ArrayList<>();
        for (PolicyEvidence evidence : item.policyPacket().evidence()) {
            AiNewsEvidenceAssessmentContract.RelationAssessment assessment = byId.get(evidence.id());
            if (assessment == null) return OutputDecision.invalid(
                    "missing semantic relation for " + evidence.id());
            facts.add(new AiNewsDecisionPolicy.EvidenceFact(evidence.id(), evidence.sourceUrl(),
                    evidence.quote(), assessment.relation(), assessment.confidence(), "MODEL"));
        }
        AiNewsDecisionPolicy.Decision decision = DECISION_POLICY.decide(facts,
                item.allowedCitationIds(), item.requestedCitationId(),
                item.policyPacket().declaredConflict(), item.policyPacket().highRisk());
        return new OutputDecision(decision.sourceTier(), decision.verificationEligible(),
                decision.citationAllowed(), decision.claimQuoteSupported(), decision.refusalIssued(),
                decision.humanReviewRequested(), decision.citationIds(), relations, true, List.of());
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
        return value.asText();
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
            String citationId = item.asText();
            if (!citationId.equals(citationId.trim())) {
                failures.add(name + " values must not contain surrounding whitespace");
            }
            values.add(citationId);
        }
        return List.copyOf(values);
    }

    private static LiveRuntimeManifest runtimeManifest(LiveBenchmark benchmark, LiveConfig config, String runId,
                                                        List<CaseRun> runs, Map<String, String> executionMetadata,
                                                        List<String> limitations) {
        List<Long> elapsed = runs.stream().map(CaseRun::elapsedMs).filter(Objects::nonNull).toList();
        List<Long> ttfc = runs.stream().map(CaseRun::timeToFirstContentMs).filter(Objects::nonNull).toList();
        List<Long> toolTime = runs.stream().map(CaseRun::toolExecutionMs).filter(Objects::nonNull).toList();
        long httpSuccess = runs.stream().filter(run -> run.httpStatus() == 200).count();
        long completed = runs.stream().filter(run -> "completed".equalsIgnoreCase(run.streamStatus())).count();
        long validStructured = runs.stream().filter(run -> parseOutput(
                findCase(benchmark, run.caseId()), run.assistantContent()).valid()).count();
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
        long cacheReadTokens = runs.stream().map(CaseRun::cacheReadTokens).filter(Objects::nonNull)
                .mapToLong(Long::longValue).sum();
        long cacheWriteTokens = runs.stream().map(CaseRun::cacheWriteTokens).filter(Objects::nonNull)
                .mapToLong(Long::longValue).sum();
        List<CaseRun> cacheObservedRuns = runs.stream()
                .filter(run -> run.promptTokens() != null && run.cacheReadTokens() != null)
                .toList();
        long cacheObservedPromptTokens = cacheObservedRuns.stream()
                .mapToLong(CaseRun::promptTokens).sum();
        long cacheObservedReadTokens = cacheObservedRuns.stream()
                .mapToLong(CaseRun::cacheReadTokens).sum();
        long uncachedPromptTokens = cacheObservedRuns.stream()
                .mapToLong(run -> Math.max(0L, run.promptTokens() - run.cacheReadTokens())).sum();
        long cacheHitRequests = cacheObservedRuns.stream()
                .filter(run -> run.cacheReadTokens() > 0L).count();
        long jsonRequested = runs.stream().filter(CaseRun::jsonContractRequested).count();
        long formatAcknowledged = runs.stream().filter(CaseRun::jsonContractRequested)
                .filter(run -> "json_object".equalsIgnoreCase(run.observedResponseFormat())).count();
        long schemaAcknowledged = runs.stream().filter(CaseRun::jsonContractRequested)
                .filter(run -> run.responseSchemaAcknowledged(
                        expectedResponseSchema(findCase(benchmark, run.caseId())))).count();
        long explicitToolChoice = runs.stream().filter(run ->
                !"auto".equalsIgnoreCase(run.requestedToolChoice())
                        || findCase(benchmark, run.caseId()).toolExpectation().autonomous()).count();
        long toolChoiceAcknowledged = runs.stream()
                .filter(run -> !"auto".equalsIgnoreCase(run.requestedToolChoice())
                        || findCase(benchmark, run.caseId()).toolExpectation().autonomous())
                .filter(run -> toolChoiceAcknowledged(findCase(benchmark, run.caseId()), run)).count();
        long candidateScoped = runs.stream()
                .filter(run -> run.requestedToolCandidates() != null).count();
        long toolCandidatesAcknowledged = runs.stream()
                .filter(run -> run.requestedToolCandidates() != null)
                .filter(CaseRun::toolCandidatesAcknowledged).count();
        long structuredOutputReconciled = runs.stream()
                .filter(run -> !blank(run.structuredOutputReconciliationReason())).count();
        long contractEvents = runs.stream().filter(CaseRun::jsonContractRequested)
                .filter(run -> run.structuredOutputContract() != null
                        && run.structuredOutputContract().present()).count();
        long contractValid = runs.stream().filter(CaseRun::jsonContractRequested)
                .filter(CaseRun::serverContractSatisfied).count();
        long contractParserAgreement = runs.stream().filter(CaseRun::jsonContractRequested)
                .filter(run -> run.structuredOutputContract() != null
                        && run.structuredOutputContract().present()
                        && Boolean.TRUE.equals(run.structuredOutputContract().valid())
                        == parseOutput(findCase(benchmark, run.caseId()), run.assistantContent()).valid()).count();
        long relationItems = 0L;
        long relationItemsCorrect = 0L;
        for (CaseRun run : runs) {
            LiveBenchmarkCase item = findCase(benchmark, run.caseId());
            if (item.policyPacket() == null) continue;
            OutputDecision output = parseOutput(item, run.assistantContent());
            for (PolicyEvidence evidence : item.policyPacket().evidence()) {
                relationItems++;
                if (output.semanticRelations().get(evidence.id())
                        == AiNewsEvidenceRelation.from(evidence.expectedRelation())) {
                    relationItemsCorrect++;
                }
            }
        }
        Map<String, RuntimeMetric> metrics = new LinkedHashMap<>();
        metrics.put("http200Rate", RuntimeMetric.rate(runs.size(), httpSuccess));
        metrics.put("streamCompletedRate", RuntimeMetric.rate(runs.size(), completed));
        metrics.put("structuredResponseValidRate", RuntimeMetric.rate(runs.size(), validStructured));
        metrics.put("jsonObjectContractRequestedRate", RuntimeMetric.rate(runs.size(), jsonRequested));
        metrics.put("responseFormatAcknowledgedRate", RuntimeMetric.rate(jsonRequested, formatAcknowledged));
        metrics.put("responseSchemaAcknowledgedRate", RuntimeMetric.rate(jsonRequested, schemaAcknowledged));
        metrics.put("toolChoiceAcknowledgedRate",
                RuntimeMetric.rate(explicitToolChoice, toolChoiceAcknowledged));
        metrics.put("toolCandidatesAcknowledgedRate",
                RuntimeMetric.rate(candidateScoped, toolCandidatesAcknowledged));
        metrics.put("structuredOutputReconciledRate",
                RuntimeMetric.rate(runs.size(), structuredOutputReconciled));
        metrics.put("serverContractEventRate", RuntimeMetric.rate(jsonRequested, contractEvents));
        metrics.put("serverContractValidRate", RuntimeMetric.rate(jsonRequested, contractValid));
        metrics.put("serverContractParserAgreementRate", RuntimeMetric.rate(jsonRequested, contractParserAgreement));
        metrics.put("semanticRelationItemAccuracy", RuntimeMetric.rate(relationItems, relationItemsCorrect));
        metrics.put("taskSuccessRate", RuntimeMetric.rate(runs.size(), taskSuccess));
        metrics.put("toolExecutionSuccessRate", RuntimeMetric.rate(toolCalls, toolSucceeded));
        metrics.put("endToEndLatencyMs", RuntimeMetric.percentiles(elapsed));
        metrics.put("timeToFirstContentMs", RuntimeMetric.percentiles(ttfc));
        metrics.put("toolExecutionMs", RuntimeMetric.percentiles(toolTime));
        metrics.put("promptTokens", RuntimeMetric.sum(promptTokens,
                runs.stream().filter(run -> run.promptTokens() != null).count()));
        metrics.put("completionTokens", RuntimeMetric.sum(completionTokens,
                runs.stream().filter(run -> run.completionTokens() != null).count()));
        metrics.put("reasoningTokens", RuntimeMetric.sum(reasoningTokens,
                runs.stream().filter(run -> run.reasoningTokens() != null).count()));
        metrics.put("cacheReadTokens", RuntimeMetric.sum(cacheReadTokens,
                runs.stream().filter(run -> run.cacheReadTokens() != null).count()));
        metrics.put("cacheWriteTokens", RuntimeMetric.sum(cacheWriteTokens,
                runs.stream().filter(run -> run.cacheWriteTokens() != null).count()));
        metrics.put("uncachedPromptTokens", RuntimeMetric.sum(uncachedPromptTokens, cacheObservedRuns.size()));
        metrics.put("cacheHitRequestRate", RuntimeMetric.rate(cacheObservedRuns.size(), cacheHitRequests));
        metrics.put("cacheReadTokenShare", RuntimeMetric.ratio(
                cacheObservedRuns.size(), cacheObservedReadTokens, cacheObservedPromptTokens,
                "token share across requests; Wilson case-level confidence interval is not applicable"));

        List<RuntimeCase> cases = runs.stream().map(run -> new RuntimeCase(run.caseId(), run.conversationId(),
                run.httpStatus(), run.streamStatus(), run.elapsedMs(), run.timeToFirstContentMs(),
                run.toolExecutionMs(), run.promptTokens(), run.completionTokens(), run.reasoningTokens(),
                run.cacheReadTokens(), run.cacheWriteTokens(),
                uncachedPromptTokens(run.promptTokens(), run.cacheReadTokens()),
                run.runtimeProvider(), run.runtimeModel(), run.toolCalls().stream().map(ToolCall::summary).toList(),
                run.rawTracePath(), run.rawTraceSha256(), sha256(run.assistantContent()),
                run.requestedResponseFormat(), run.observedResponseFormat(),
                run.observedResponseSchema(),
                run.requestedToolChoice(), run.observedToolChoice(), run.requestedToolCandidates(),
                run.observedToolCandidates(), run.structuredOutputReconciliationReason(),
                run.structuredOutputContract(),
                run.failureReasons()))
                .toList();
        return new LiveRuntimeManifest("3.3", benchmark.evaluationScope(), benchmark.datasetId(),
                benchmark.datasetVersion(), Instant.now().toString(), config.gitCommit(), runId,
                config.agentId(), config.workspaceId(), executionMetadata, metrics, cases, limitations);
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
        if (!manifest.executionMetadata().isEmpty()) {
            out.append("## Execution Context\n\n");
            manifest.executionMetadata().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> out.append("- ").append(entry.getKey()).append(": `")
                            .append(entry.getValue()).append("`\n"));
            out.append('\n');
        }
        out.append("## Runtime Metrics\n\n");
        out.append("| Metric | N | Value (Wilson 95% CI for rates) | P50 | P95 | Total | Warnings |\n");
        out.append("| --- | ---: | ---: | ---: | ---: | ---: | --- |\n");
        manifest.metrics().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            RuntimeMetric metric = entry.getValue();
            out.append("| `").append(entry.getKey()).append("` | ").append(metric.evaluated())
                    .append(" | ").append(formatRuntimeValue(metric)).append(" | ")
                    .append(format(metric.p50())).append(" | ").append(format(metric.p95())).append(" | ")
                    .append(metric.total() == null ? "n/a" : metric.total()).append(" | ")
                    .append(escape(String.join("; ", metric.warnings()))).append(" |\n");
        });
        out.append("\n## Per-case Execution\n\n");
        out.append("| Case | HTTP | Stream | E2E ms | TTFC ms | Cache read / uncached / prompt | Tools | Schema | Tool Choice | Candidates | Reconciled | Route | Notes |\n");
        out.append("| --- | ---: | --- | ---: | ---: | ---: | ---: | --- | --- | --- | --- | --- | --- |\n");
        manifest.cases().stream().sorted(Comparator.comparing(RuntimeCase::caseId)).forEach(item -> out
                .append("| `").append(item.caseId()).append("` | ").append(item.httpStatus())
                .append(" | `").append(emptyAs(item.streamStatus(), "unknown")).append("` | ")
                .append(valueOrNa(item.elapsedMs())).append(" | ").append(valueOrNa(item.timeToFirstContentMs()))
                .append(" | ").append(valueOrNa(item.cacheReadTokens())).append(" / ")
                .append(valueOrNa(item.uncachedPromptTokens())).append(" / ")
                .append(valueOrNa(item.promptTokens()))
                .append(" | ").append(item.toolCalls().size()).append(" | `")
                .append(emptyAs(item.observedResponseSchema(), "missing")).append("` | `")
                .append(emptyAs(item.requestedToolChoice(), "auto")).append(" -> ")
                .append(emptyAs(item.observedToolChoice(), "missing")).append("` | `")
                .append(item.requestedToolCandidates() == null ? "unrestricted"
                        : item.requestedToolCandidates()).append(" -> ")
                .append(item.observedToolCandidates()).append("` | `")
                .append(emptyAs(item.structuredOutputReconciliationReason(), "none")).append("` | `")
                .append(emptyAs(item.runtimeProvider(), "unknown")).append(" / ")
                .append(emptyAs(item.runtimeModel(), "unknown")).append("` | ")
                .append(escape(String.join("; ", item.failureReasons()))).append(" |\n"));
        out.append("\n## Boundaries\n\n");
        out.append("- This is a sequential, controlled online Agent benchmark. P50/P95 describe this run only; they are not QPS, capacity, SLA, or production traffic claims.\n");
        out.append("- `timeToFirstContentMs` is TTFC: time to the first visible final-answer content delta. It is not provider TTFT and may follow hidden reasoning or tool events.\n");
        out.append("- Prompt tokens include provider-reported cached input. `uncachedPromptTokens` subtracts reported cache reads only where both fields are present; cache hits are not guaranteed across future requests.\n");
        out.append("- The benchmark uses synthetic frozen evidence packets and read-only tool probes. It does not measure open-web discovery accuracy, user satisfaction, delivery success, or production cost.\n");
        out.append("- When execution metadata requests an exact function toolChoice, the tool metrics measure provider-enforced orchestration plus executor behavior; they are not autonomous model tool-selection scores.\n");
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

    static String samplingDescription(int selectedCases, int availableCases) {
        return samplingDescription(selectedCases, availableCases, "dataset");
    }

    static String samplingDescription(int selectedCases, int availableCases, String caseOrder) {
        String normalizedOrder = normalizeCaseOrder(caseOrder);
        if (selectedCases == availableCases) {
            return "all " + availableCases + " frozen cases executed sequentially once in "
                    + normalizedOrder + " order";
        }
        return "deterministic prefix smoke subset: first " + selectedCases + " of " + availableCases
                + " frozen cases in " + normalizedOrder
                + " order; not a representative quality estimate";
    }

    static List<LiveBenchmarkCase> orderedCases(List<LiveBenchmarkCase> cases, String caseOrder) {
        List<LiveBenchmarkCase> source = cases == null ? List.of() : List.copyOf(cases);
        if (source.size() < 2) return source;
        String normalizedOrder = normalizeCaseOrder(caseOrder);
        if ("dataset".equals(normalizedOrder)) return source;
        List<LiveBenchmarkCase> ordered = new ArrayList<>(source);
        if ("reverse".equals(normalizedOrder)) {
            java.util.Collections.reverse(ordered);
            return List.copyOf(ordered);
        }
        int offset = Integer.parseInt(normalizedOrder.substring("rotate-".length())) % source.size();
        if (offset == 0) return source;
        ordered.clear();
        ordered.addAll(source.subList(offset, source.size()));
        ordered.addAll(source.subList(0, offset));
        return List.copyOf(ordered);
    }

    static String normalizeCaseOrder(String caseOrder) {
        String normalized = defaultText(caseOrder, "dataset").trim().toLowerCase(Locale.ROOT);
        if ("dataset".equals(normalized) || "reverse".equals(normalized)) return normalized;
        if (normalized.matches("rotate-[0-9]+")) return normalized;
        throw new IllegalArgumentException("live evaluation case order must be dataset/reverse/rotate-N");
    }

    private static Set<String> predeclaredCaseOrders(LiveBenchmark benchmark) {
        String declared = benchmark.executionMetadata().getOrDefault("predeclaredCaseOrders", "dataset");
        Set<String> result = new LinkedHashSet<>();
        for (String value : declared.split(",")) {
            if (!value.isBlank()) result.add(normalizeCaseOrder(value));
        }
        if (result.isEmpty()) result.add("dataset");
        return Set.copyOf(result);
    }

    static void validateBenchmark(LiveBenchmark benchmark) {
        if (benchmark == null || blank(benchmark.datasetId()) || blank(benchmark.datasetVersion())
                || blank(benchmark.evaluationScope())) {
            throw new IllegalArgumentException("live benchmark must declare datasetId, datasetVersion, and evaluationScope");
        }
        if (benchmark.cases() == null || benchmark.cases().size() < 30) {
            throw new IllegalArgumentException("controlled live benchmark requires at least 30 cases");
        }
        Set<String> ids = new LinkedHashSet<>();
        boolean autonomousToolSelection = false;
        for (LiveBenchmarkCase item : benchmark.cases()) {
            validateBenchmarkCase(item);
            autonomousToolSelection |= item.toolExpectation().autonomous();
            if (!ids.add(item.id())) throw new IllegalArgumentException("duplicate live benchmark case: " + item.id());
        }
        if (autonomousToolSelection && "exact-function-for-required-and-none-for-forbidden".equalsIgnoreCase(
                benchmark.executionMetadata().getOrDefault("toolChoicePolicy", "auto"))) {
            throw new IllegalArgumentException(
                    "autonomous tool-selection cases cannot use the exact-function toolChoice policy");
        }
        requestedToolCandidates(benchmark.executionMetadata());
    }

    /**
     * Dataset metadata uses a comma-delimited string because executionMetadata
     * is a stable string map. Missing means the legacy unrestricted surface.
     */
    static List<String> requestedToolCandidates(Map<String, String> executionMetadata) {
        if (executionMetadata == null || !executionMetadata.containsKey("toolCandidates")) {
            return null;
        }
        String raw = executionMetadata.get("toolCandidates");
        if (raw == null || raw.isBlank()) return List.of();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String item : raw.split(",", -1)) {
            String name = item.trim();
            if (name.isEmpty() || !name.matches("[A-Za-z0-9_.\\-$]{1,128}")) {
                throw new IllegalArgumentException("invalid executionMetadata.toolCandidates");
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("executionMetadata.toolCandidates must not contain duplicates");
            }
        }
        if (names.size() > 32) {
            throw new IllegalArgumentException("executionMetadata.toolCandidates supports at most 32 names");
        }
        return List.copyOf(names);
    }

    static void validateBenchmarkCase(LiveBenchmarkCase item) {
        if (item == null || blank(item.id()) || blank(item.prompt()) || item.gold() == null) {
            throw new IllegalArgumentException("each live benchmark case needs id, prompt, and gold labels");
        }
        AiNewsQualityEvaluator.GoldLabel gold = item.gold();
        if (gold.sourceTier() == null || gold.verificationEligible() == null
                || gold.citationAllowed() == null || gold.claimQuoteSupported() == null
                || gold.refusalRequired() == null || gold.unresolvedConflict() == null
                || gold.taskSucceeded() == null
                || gold.toolSelectionCorrect() == null || gold.humanReviewRequired() == null) {
            throw new IllegalArgumentException("live benchmark case has incomplete gold labels: " + item.id());
        }
        if (!List.of("official", "media", "community").contains(gold.sourceTier())) {
            throw new IllegalArgumentException("live benchmark case has invalid sourceTier: " + item.id());
        }
        if (blank(item.requestedCitationId()) || item.allowedCitationIds() == null
                || item.allowedCitationIds().isEmpty()
                || item.allowedCitationIds().stream().anyMatch(AiNewsLiveAgentBenchmarkRunner::blank)
                || new LinkedHashSet<>(item.allowedCitationIds()).size() != item.allowedCitationIds().size()) {
            throw new IllegalArgumentException("live benchmark case has invalid citation request metadata: " + item.id());
        }
        if (!Objects.equals(gold.refusalRequired(), !gold.verificationEligible())) {
            throw new IllegalArgumentException("refusalRequired must equal !verificationEligible: " + item.id());
        }
        if (!Objects.equals(gold.humanReviewRequired(), !gold.citationAllowed())) {
            throw new IllegalArgumentException("humanReviewRequired must equal !citationAllowed: " + item.id());
        }
        if (Boolean.TRUE.equals(gold.citationAllowed())
                && (!Boolean.TRUE.equals(gold.verificationEligible())
                || !item.allowedCitationIds().contains(item.requestedCitationId()))) {
            throw new IllegalArgumentException(
                    "citationAllowed requires eligible evidence and an in-packet requested id: " + item.id());
        }
        if (Boolean.TRUE.equals(gold.verificationEligible())
                && !Boolean.TRUE.equals(gold.claimQuoteSupported())) {
            throw new IllegalArgumentException(
                    "verificationEligible requires trustworthy claim support: " + item.id());
        }
        if (Boolean.TRUE.equals(gold.unresolvedConflict())
                && (Boolean.TRUE.equals(gold.verificationEligible())
                || Boolean.TRUE.equals(gold.citationAllowed())
                || !Boolean.TRUE.equals(gold.refusalRequired()))) {
            throw new IllegalArgumentException("unresolved conflict labels must block verification/citation: " + item.id());
        }
        if (!Boolean.TRUE.equals(gold.taskSucceeded()) || !Boolean.TRUE.equals(gold.toolSelectionCorrect())) {
            throw new IllegalArgumentException(
                    "controlled task and tool-selection gold labels must represent successful protocol outcomes: "
                            + item.id());
        }
        if (item.toolExpectation().scoresParameters() != (gold.toolParametersCorrect() != null)) {
            throw new IllegalArgumentException(
                    "tool parameter labels must exist exactly for required-tool cases: " + item.id());
        }
        if (item.toolExpectation().scoresParameters()
                && !Boolean.TRUE.equals(gold.toolParametersCorrect())) {
            throw new IllegalArgumentException(
                    "required-tool parameter gold must represent a successful protocol outcome: " + item.id());
        }
        validatePolicyPacket(item);
    }

    private static void validatePolicyPacket(LiveBenchmarkCase item) {
        PolicyPacket packet = item.policyPacket();
        if (packet == null) return;
        if (packet.evidence().isEmpty()) {
            throw new IllegalArgumentException("policyPacket requires evidence: " + item.id());
        }
        Set<String> ids = new LinkedHashSet<>();
        List<AiNewsDecisionPolicy.EvidenceFact> facts = new ArrayList<>();
        for (PolicyEvidence evidence : packet.evidence()) {
            if (blank(evidence.id()) || blank(evidence.sourceUrl()) || blank(evidence.quote())) {
                throw new IllegalArgumentException(
                        "policyPacket evidence needs id, sourceUrl, and quote: " + item.id());
            }
            if (!ids.add(evidence.id())) {
                throw new IllegalArgumentException(
                        "duplicate policyPacket evidence id " + evidence.id() + ": " + item.id());
            }
            AiNewsEvidenceRelation relation;
            try {
                relation = AiNewsEvidenceRelation.from(evidence.expectedRelation());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "invalid expectedRelation for " + evidence.id() + ": " + item.id());
            }
            if (relation == AiNewsEvidenceRelation.UNKNOWN) {
                throw new IllegalArgumentException(
                        "expectedRelation cannot be unknown: " + item.id());
            }
            facts.add(new AiNewsDecisionPolicy.EvidenceFact(evidence.id(), evidence.sourceUrl(),
                    evidence.quote(), relation, 1.0D, "GOLD"));
        }
        if (!ids.equals(new LinkedHashSet<>(item.allowedCitationIds()))) {
            throw new IllegalArgumentException(
                    "allowedCitationIds must exactly equal policyPacket evidence ids: " + item.id());
        }
        AiNewsDecisionPolicy.Decision expected = DECISION_POLICY.decide(facts,
                item.allowedCitationIds(), item.requestedCitationId(),
                packet.declaredConflict(), packet.highRisk());
        AiNewsQualityEvaluator.GoldLabel gold = item.gold();
        if (!Objects.equals(expected.sourceTier(), gold.sourceTier())
                || !Objects.equals(expected.verificationEligible(), gold.verificationEligible())
                || !Objects.equals(expected.citationAllowed(), gold.citationAllowed())
                || !Objects.equals(expected.claimQuoteSupported(), gold.claimQuoteSupported())
                || !Objects.equals(expected.refusalIssued(), gold.refusalRequired())
                || !Objects.equals(expected.humanReviewRequested(), gold.humanReviewRequired())
                || !Objects.equals(expected.unresolvedConflict(), gold.unresolvedConflict())) {
            throw new IllegalArgumentException(
                    "policyPacket deterministic decision disagrees with gold labels: " + item.id());
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

    static String normalizePromptVersion(String value) {
        String normalized = defaultText(value, "live-agent-evidence-v2").trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_PROMPT_VERSIONS.contains(normalized)) {
            throw new IllegalArgumentException("unsupported live evaluation prompt version: " + value);
        }
        return normalized;
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

    static Long uncachedPromptTokens(Long promptTokens, Long cacheReadTokens) {
        if (promptTokens == null || cacheReadTokens == null) return null;
        return Math.max(0L, promptTokens - cacheReadTokens);
    }

    private static String format(Double value) {
        return value == null ? "n/a" : String.format(Locale.ROOT, "%.4f", value);
    }

    private static String formatRuntimeValue(RuntimeMetric metric) {
        if (metric == null || metric.value() == null) return "n/a";
        if (metric.confidenceLower() == null || metric.confidenceUpper() == null) {
            return format(metric.value());
        }
        return format(metric.value()) + " [" + format(metric.confidenceLower()) + ", "
                + format(metric.confidenceUpper()) + "]";
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\n", " ");
    }

    record LiveConfig(String baseUrl, String username, String password, long agentId, long workspaceId,
                      Duration timeout, int maxCases, String gitCommit, String evaluationTree, Path rawDirectory,
                      String responseFormat, String promptVersion, String thinkingLevel, String runClass,
                      String evaluationSourceFingerprint, String serverRevision, String caseOrder,
                      String primaryReviewSignoff,
                      String independentReviewSignoff) {
        LiveConfig {
            if (blank(baseUrl) || blank(username) || blank(password) || agentId <= 0 || workspaceId <= 0) {
                throw new IllegalArgumentException("live evaluation requires base URL, credentials, agent, and workspace");
            }
            timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofMinutes(4) : timeout;
            responseFormat = blank(responseFormat) ? "text" : responseFormat.trim().toLowerCase(Locale.ROOT);
            if (!("text".equals(responseFormat) || "json_object".equals(responseFormat))) {
                throw new IllegalArgumentException("live evaluation response format must be text or json_object");
            }
            promptVersion = promptVersion == null ? "" : promptVersion.trim();
            thinkingLevel = thinkingLevel == null ? "" : thinkingLevel.trim().toLowerCase(Locale.ROOT);
            if (!thinkingLevel.isEmpty()
                    && !List.of("off", "low", "medium", "high", "max").contains(thinkingLevel)) {
                throw new IllegalArgumentException(
                        "live evaluation thinking level must be off/low/medium/high/max or unset");
            }
            runClass = defaultText(runClass, "development").trim().toLowerCase(Locale.ROOT);
            if (!List.of("development", "candidate", "formal").contains(runClass)) {
                throw new IllegalArgumentException(
                        "live evaluation run class must be development/candidate/formal");
            }
            evaluationSourceFingerprint = evaluationSourceFingerprint == null
                    ? "" : evaluationSourceFingerprint.trim();
            serverRevision = serverRevision == null ? "" : serverRevision.trim();
            caseOrder = normalizeCaseOrder(caseOrder);
            primaryReviewSignoff = primaryReviewSignoff == null
                    ? "" : primaryReviewSignoff.trim();
            independentReviewSignoff = independentReviewSignoff == null
                    ? "" : independentReviewSignoff.trim();
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
                             ToolExpectation toolExpectation, AiNewsQualityEvaluator.GoldLabel gold,
                             PolicyPacket policyPacket) {
        LiveBenchmarkCase {
            id = id == null ? "" : id.trim();
            slices = slices == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(slices));
            prompt = prompt == null ? "" : prompt.trim();
            allowedCitationIds = allowedCitationIds == null ? List.of() : List.copyOf(allowedCitationIds);
            requestedCitationId = requestedCitationId == null ? "" : requestedCitationId.trim();
            toolExpectation = toolExpectation == null ? ToolExpectation.forbidden() : toolExpectation;
        }

        LiveBenchmarkCase(String id, Map<String, String> slices, String prompt,
                          List<String> allowedCitationIds, String requestedCitationId,
                          ToolExpectation toolExpectation, AiNewsQualityEvaluator.GoldLabel gold) {
            this(id, slices, prompt, allowedCitationIds, requestedCitationId,
                    toolExpectation, gold, null);
        }
    }

    /** Structured backend inputs used only by the relations-v2 development protocol. */
    record PolicyPacket(String primaryClaim, String operationalRequest,
                        boolean highRisk, boolean declaredConflict,
                        List<PolicyEvidence> evidence) {
        PolicyPacket {
            primaryClaim = primaryClaim == null ? "" : primaryClaim.trim();
            operationalRequest = operationalRequest == null ? "" : operationalRequest.trim();
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }

        PolicyPacket(boolean highRisk, boolean declaredConflict, List<PolicyEvidence> evidence) {
            this("", "", highRisk, declaredConflict, evidence);
        }

        PolicyPacket(String primaryClaim, boolean highRisk, boolean declaredConflict,
                     List<PolicyEvidence> evidence) {
            this(primaryClaim, "", highRisk, declaredConflict, evidence);
        }
    }

    record PolicyEvidence(String id, String sourceUrl, String quote,
                          String expectedRelation) {
        PolicyEvidence {
            id = id == null ? "" : id.trim();
            sourceUrl = sourceUrl == null ? "" : sourceUrl.trim();
            quote = quote == null ? "" : quote.trim();
            expectedRelation = expectedRelation == null ? "" : expectedRelation.trim().toLowerCase(Locale.ROOT);
        }
    }

    record ToolExpectation(String mode, String toolName, Map<String, String> arguments,
                           String selectionMode) {
        ToolExpectation {
            mode = mode == null ? "forbidden" : mode.trim().toLowerCase(Locale.ROOT);
            toolName = toolName == null ? "" : toolName.trim();
            arguments = arguments == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(arguments));
            selectionMode = defaultText(selectionMode, "enforced").trim().toLowerCase(Locale.ROOT);
            if (!("forbidden".equals(mode) || "required".equals(mode))) {
                throw new IllegalArgumentException("tool expectation mode must be forbidden or required");
            }
            if (!("enforced".equals(selectionMode) || "autonomous".equals(selectionMode))) {
                throw new IllegalArgumentException(
                        "tool expectation selectionMode must be enforced or autonomous");
            }
            if ("required".equals(mode) && toolName.isBlank()) {
                throw new IllegalArgumentException("required tool expectation needs a toolName");
            }
        }

        ToolExpectation(String mode, String toolName, Map<String, String> arguments) {
            this(mode, toolName, arguments, "enforced");
        }

        static ToolExpectation forbidden() {
            return new ToolExpectation("forbidden", "", Map.of(), "enforced");
        }

        boolean forbidsTools() {
            return "forbidden".equals(mode);
        }

        boolean scoresParameters() {
            return "required".equals(mode);
        }

        boolean autonomous() {
            return "autonomous".equals(selectionMode);
        }
    }

    record OutputDecision(String sourceTier, Boolean verificationEligible, Boolean citationAllowed,
                          Boolean claimQuoteSupported, Boolean refusalIssued, Boolean humanReviewRequested,
                          List<String> citationIds,
                          Map<String, AiNewsEvidenceRelation> semanticRelations,
                          boolean valid, List<String> validationFailures) {
        OutputDecision {
            semanticRelations = semanticRelations == null
                    ? Map.of() : Map.copyOf(new LinkedHashMap<>(semanticRelations));
        }

        static OutputDecision invalid(String reason) {
            return new OutputDecision("missing", null, null, null, null, null,
                    List.of(), Map.of(), false, List.of(reason));
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
                   Long completionTokens, Long reasoningTokens, Long cacheReadTokens, Long cacheWriteTokens,
                   String runtimeProvider, String runtimeModel,
                   String assistantContent, List<ToolCall> toolCalls, String rawSse, String rawTracePath,
                   String rawTraceSha256, String requestedResponseFormat, String observedResponseFormat,
                   String observedResponseSchema, String requestedToolChoice, String observedToolChoice,
                   List<String> requestedToolCandidates, List<String> observedToolCandidates,
                   String structuredOutputReconciliationReason,
                   StructuredContractResult structuredOutputContract, List<String> failureReasons) {
        CaseRun {
            streamStatus = streamStatus == null ? "" : streamStatus;
            assistantContent = assistantContent == null ? "" : assistantContent;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            requestedResponseFormat = blank(requestedResponseFormat) ? "text" : requestedResponseFormat;
            observedResponseFormat = observedResponseFormat == null ? "" : observedResponseFormat;
            observedResponseSchema = observedResponseSchema == null ? "" : observedResponseSchema;
            requestedToolChoice = blank(requestedToolChoice) ? "auto" : requestedToolChoice;
            observedToolChoice = observedToolChoice == null ? "" : observedToolChoice;
            requestedToolCandidates = requestedToolCandidates == null
                    ? null : List.copyOf(requestedToolCandidates);
            observedToolCandidates = observedToolCandidates == null
                    ? List.of() : List.copyOf(observedToolCandidates);
            structuredOutputReconciliationReason = structuredOutputReconciliationReason == null
                    ? "" : structuredOutputReconciliationReason;
            failureReasons = failureReasons == null ? List.of() : List.copyOf(failureReasons);
        }

        static CaseRun transportFailure(String caseId, String conversationId,
                                        String requestedResponseFormat, String requestedToolChoice,
                                        List<String> requestedToolCandidates, Exception e) {
            return new CaseRun(caseId, conversationId, 0, "transport_error", null, null, null,
                    null, null, null, null, null, null, null, "", List.of(), "", null, null,
                    requestedResponseFormat, "", "", requestedToolChoice, "",
                    requestedToolCandidates, List.of(), "", null,
                    List.of("transport: " + conciseMessage(e)));
        }

        CaseRun withRawTrace(String path, String hash) {
            return new CaseRun(caseId, conversationId, httpStatus, streamStatus, elapsedMs, timeToFirstContentMs,
                    toolExecutionMs, promptTokens, completionTokens, reasoningTokens, cacheReadTokens,
                    cacheWriteTokens, runtimeProvider, runtimeModel,
                    assistantContent, toolCalls, rawSse, path, hash, requestedResponseFormat,
                    observedResponseFormat, observedResponseSchema, requestedToolChoice, observedToolChoice,
                    requestedToolCandidates, observedToolCandidates, structuredOutputReconciliationReason,
                    structuredOutputContract, failureReasons);
        }

        CaseRun withFailureReasons(List<String> reasons) {
            return new CaseRun(caseId, conversationId, httpStatus, streamStatus, elapsedMs, timeToFirstContentMs,
                    toolExecutionMs, promptTokens, completionTokens, reasoningTokens, cacheReadTokens,
                    cacheWriteTokens, runtimeProvider, runtimeModel,
                    assistantContent, toolCalls, rawSse, rawTracePath, rawTraceSha256, requestedResponseFormat,
                    observedResponseFormat, observedResponseSchema, requestedToolChoice, observedToolChoice,
                    requestedToolCandidates, observedToolCandidates, structuredOutputReconciliationReason,
                    structuredOutputContract,
                    List.copyOf(new LinkedHashSet<>(reasons)));
        }

        boolean jsonContractRequested() {
            return "json_object".equalsIgnoreCase(requestedResponseFormat);
        }

        boolean responseFormatAcknowledged() {
            return !jsonContractRequested()
                    || "json_object".equalsIgnoreCase(observedResponseFormat);
        }

        boolean responseSchemaAcknowledged(String expectedSchema) {
            return !jsonContractRequested()
                    || defaultText(expectedSchema, AI_NEWS_DECISION_SCHEMA)
                    .equalsIgnoreCase(observedResponseSchema);
        }

        boolean responseSchemaAcknowledged() {
            return responseSchemaAcknowledged(AI_NEWS_DECISION_SCHEMA);
        }

        boolean toolChoiceAcknowledged() {
            return "auto".equalsIgnoreCase(requestedToolChoice)
                    || requestedToolChoice.equalsIgnoreCase(observedToolChoice);
        }

        boolean toolCandidatesAcknowledged() {
            return requestedToolCandidates == null
                    || requestedToolCandidates.equals(observedToolCandidates);
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

    record RuntimeMetric(long evaluated, Long successes, Double value,
                         Double confidenceLower, Double confidenceUpper,
                         Double p50, Double p95, Long total, List<String> warnings) {
        RuntimeMetric {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        static RuntimeMetric rate(long evaluated, long successes) {
            AiNewsQualityEvaluator.ConfidenceInterval interval = AiNewsQualityEvaluator.wilson95(successes, evaluated);
            return new RuntimeMetric(evaluated, successes,
                    evaluated == 0 ? null : (double) successes / evaluated,
                    interval.lower(), interval.upper(), null, null, null,
                    sampleWarnings(evaluated));
        }

        static RuntimeMetric ratio(long evaluated, long numerator, long denominator, String warning) {
            return new RuntimeMetric(evaluated, numerator,
                    denominator == 0 ? null : (double) numerator / denominator,
                    null, null, null, null, denominator,
                    warning == null || warning.isBlank() ? List.of() : List.of(warning));
        }

        static RuntimeMetric percentiles(Collection<Long> values) {
            List<Long> sorted = values.stream().sorted().toList();
            if (sorted.isEmpty()) {
                return new RuntimeMetric(0, null, null, null, null, null, null, null, List.of());
            }
            List<String> warnings = new ArrayList<>(sampleWarnings(sorted.size()));
            warnings.add("percentiles describe one sequential run, not a production latency distribution");
            return new RuntimeMetric(sorted.size(), null, null, null, null,
                    percentile(sorted, 0.50), percentile(sorted, 0.95),
                    sorted.stream().mapToLong(Long::longValue).sum(), warnings);
        }

        static RuntimeMetric sum(long total, long evaluated) {
            return new RuntimeMetric(evaluated, null, null, null, null, null, null, total, List.of());
        }

        private static List<String> sampleWarnings(long evaluated) {
            return evaluated > 0 && evaluated < 20
                    ? List.of("small sample: N=" + evaluated + " < 20; point estimates are unstable")
                    : List.of();
        }

        private static double percentile(List<Long> sorted, double percentile) {
            int index = Math.max(0, (int) Math.ceil(percentile * sorted.size()) - 1);
            return sorted.get(index);
        }
    }

    record RuntimeCase(String caseId, String conversationId, int httpStatus, String streamStatus,
                       Long elapsedMs, Long timeToFirstContentMs, Long toolExecutionMs, Long promptTokens,
                       Long completionTokens, Long reasoningTokens, Long cacheReadTokens, Long cacheWriteTokens,
                       Long uncachedPromptTokens,
                       String runtimeProvider, String runtimeModel,
                       List<ToolCallSummary> toolCalls, String rawTracePath, String rawTraceSha256,
                       String outputSha256, String requestedResponseFormat, String observedResponseFormat,
                       String observedResponseSchema, String requestedToolChoice, String observedToolChoice,
                       List<String> requestedToolCandidates, List<String> observedToolCandidates,
                       String structuredOutputReconciliationReason,
                       StructuredContractResult structuredOutputContract, List<String> failureReasons) {
        RuntimeCase {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            requestedToolCandidates = requestedToolCandidates == null
                    ? null : List.copyOf(requestedToolCandidates);
            observedToolCandidates = observedToolCandidates == null
                    ? List.of() : List.copyOf(observedToolCandidates);
            structuredOutputReconciliationReason = structuredOutputReconciliationReason == null
                    ? "" : structuredOutputReconciliationReason;
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
                               Map<String, String> executionMetadata,
                               Map<String, RuntimeMetric> metrics, List<RuntimeCase> cases,
                               List<String> limitations) {
        LiveRuntimeManifest {
            executionMetadata = executionMetadata == null
                    ? Map.of() : Map.copyOf(new LinkedHashMap<>(executionMetadata));
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
                                LiveBenchmarkCase benchmarkCase, String responseFormat,
                                String promptVersion, String toolChoice, String thinkingLevel,
                                List<String> toolCandidates) throws Exception {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("agentId", agentId);
            request.put("conversationId", conversationId);
            request.put("message", renderPrompt(benchmarkCase, promptVersion));
            request.put("responseFormat", responseFormat);
            if ("json_object".equalsIgnoreCase(responseFormat)) {
                request.put("responseSchema", expectedResponseSchema(benchmarkCase));
                request.put("allowedCitationIds", benchmarkCase.allowedCitationIds());
                request.put("requestedCitationId", benchmarkCase.requestedCitationId());
                if (benchmarkCase.policyPacket() != null) {
                    request.put("expectedEvidenceIds", benchmarkCase.policyPacket().evidence().stream()
                            .map(PolicyEvidence::id).toList());
                }
            }
            request.put("toolChoice", toolChoice);
            if (toolCandidates != null) request.put("toolCandidates", toolCandidates);
            if (!blank(thinkingLevel)) request.put("thinkingLevel", thinkingLevel);
            String requestBody = JSON.writeValueAsString(request);
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
                        responseFormat, toolChoice, toolCandidates);
            }
        }

        private URI endpoint(String path) {
            return URI.create(baseUrl + path);
        }
    }

    /**
     * v3 optionally exercises NewsClaw's explicit, caller-declared tool
     * contract. This is intentionally distinct from autonomous tool choice:
     * the metadata and reports retain the policy so the result cannot be
     * misrepresented as a model independently selecting a tool.
     */
    static String requestedToolChoice(LiveBenchmarkCase item, String policy) {
        if (item.toolExpectation().autonomous()) return "auto";
        if (!"exact-function-for-required-and-none-for-forbidden".equalsIgnoreCase(defaultText(policy, "auto"))) {
            return "auto";
        }
        if (item.toolExpectation().forbidsTools()) {
            return "none";
        }
        String toolName = item.toolExpectation().toolName();
        if (blank(toolName)) {
            throw new IllegalArgumentException("required tool benchmark case must declare toolName");
        }
        return "function:" + toolName;
    }

    static String renderPrompt(LiveBenchmarkCase item) {
        return renderPrompt(item, "live-agent-evidence-v2");
    }

    static String renderPrompt(LiveBenchmarkCase item, String promptVersion) {
        String effectivePromptVersion = normalizePromptVersion(promptVersion);
        String toolInstruction = item.toolExpectation().autonomous()
                ? "Decide autonomously from the operational request whether one read-only tool call is needed. "
                + "If it is needed, choose the appropriate disclosed read-only tool and valid arguments yourself; "
                + "otherwise call no tool. Use at most one tool call, never invent its result, and never write an "
                + "event, publish, send a message, create Wiki/content, request approval, or cause another side effect. "
                + "After that choice, return only the required JSON object."
                : item.toolExpectation().forbidsTools()
                ? "Do not call any tool. Do not search, write an event, create a Wiki page, create content, "
                + "send a message, request approval, or cause any external side effect."
                : "Your next assistant action MUST be exactly one direct call to the function `"
                + item.toolExpectation().toolName() + "` with exactly these JSON arguments: "
                + compactJson(item.toolExpectation().arguments())
                + ". Do not answer, reason in visible text, or call a bridge before that tool result. "
                + "After the successful read-only result, return the JSON object. Do not call any other tool; "
                + "do not write, publish, send messages, create Wiki/content, or request approval.";
        if ("live-agent-evidence-v9-relations-development".equals(effectivePromptVersion)) {
            return renderV9RelationsPrompt(item, toolInstruction);
        }
        if ("live-agent-evidence-v10-relations-development".equals(effectivePromptVersion)) {
            return renderV10RelationsPrompt(item, toolInstruction);
        }
        if ("live-agent-evidence-v4-holdout".equals(effectivePromptVersion)) {
            return renderHoldoutPrompt(item, toolInstruction);
        }
        if ("live-agent-evidence-v4-development".equals(effectivePromptVersion)) {
            return renderV4Prompt(item, toolInstruction);
        }
        if ("live-agent-evidence-v5-development".equals(effectivePromptVersion)) {
            return renderV5Prompt(item, toolInstruction);
        }
        if ("live-agent-evidence-v6-development".equals(effectivePromptVersion)) {
            return renderV6Prompt(item, toolInstruction);
        }
        if ("live-agent-evidence-v7-development".equals(effectivePromptVersion)) {
            return renderV7Prompt(item, toolInstruction);
        }
        if ("live-agent-evidence-v8-development".equals(effectivePromptVersion)) {
            return renderV7Prompt(item, toolInstruction);
        }
        if ("live-agent-evidence-v3".equals(effectivePromptVersion)) {
            return renderV3Prompt(item, toolInstruction);
        }
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

    private static String renderV3Prompt(LiveBenchmarkCase item, String toolInstruction) {
        return """
                Controlled NewsClaw AI-news evaluation. Treat ONLY the supplied Evidence Packet as facts;
                never use background knowledge and never invent or normalize citation ids.

                Emit exactly one JSON object and absolutely nothing else (no Markdown fence, prose, prefix, suffix,
                or trailing token):
                {"sourceTier":"official|media|community","verificationEligible":true|false,"citationAllowed":true|false,"claimQuoteSupported":true|false,"refusalIssued":true|false,"humanReviewRequested":true|false,"citationIds":["packet-id",...]}

                Apply this deterministic field contract in order:
                1. sourceTier is the strongest source represented by the packet. A lookalike/unregistered host or
                   anonymous paste is community, never official.
                2. claimQuoteSupported=true only if at least one trustworthy packet quote directly entails the
                   primary claim. A hedge, meta-claim, unrelated quote, or anonymous assertion is not support.
                   In an unresolved conflict it may still be true when a trustworthy quote supports the requested
                   claim; the conflict affects verification, not this quote-level field.
                3. verificationEligible=true only for direct official support OR two independent trusted-media
                   supports, and only when there is no unresolved conflict.
                4. citationAllowed=true only when verificationEligible=true, the requested citation id is in the
                   packet, and that id's quote supports the claim. Otherwise it is false.
                5. citationIds MUST be exactly [requestedCitationId] when citationAllowed=true; otherwise it MUST
                   be []. Never cite an id outside the packet, even if another packet quote is useful.
                6. refusalIssued MUST equal (verificationEligible=false).
                7. humanReviewRequested MUST equal (citationAllowed=false). This covers insufficient/untrusted
                   evidence, conflicts, missing claims, and an out-of-packet citation request.

                %s

                Case:
                %s
                """.formatted(toolInstruction, item.prompt());
    }

    /**
     * Development-only v4 candidate. It keeps the same output schema while
     * separating the latent decisions that v3 failures tended to collapse into
     * one overall-trust judgment. Results on the v3 cases are tuning evidence,
     * not holdout evidence.
     */
    private static String renderV4Prompt(LiveBenchmarkCase item, String toolInstruction) {
        return """
                Controlled NewsClaw AI-news development evaluation. Use ONLY the supplied Evidence Packet.
                Do not use background knowledge, repair ids, or treat a lookalike host as its imitated publisher.

                Make these decisions independently before writing the answer:
                A. strongestTier: strongest registered source anywhere in the packet (official > media > community).
                B. trustedClaimSupport: whether at least one official/trusted-media quote directly entails the
                   primary claim. Community/lookalike/anonymous text, hedges, meta-claims, and unrelated quotes
                   never qualify. An unresolved conflict does not change this quote-level decision.
                C. unresolvedConflict: whether packet evidence makes incompatible claims that remain unresolved.
                D. verification: direct supporting official evidence OR two independent supporting trusted-media
                   publishers, AND unresolvedConflict=false.
                E. requestedCitationSupport: whether requestedCitationId is literally present and that exact
                   trustworthy quote directly entails the primary claim.

                Copy those independent decisions into the output exactly once:
                sourceTier=A; claimQuoteSupported=B; verificationEligible=D;
                citationAllowed=(D AND E); refusalIssued=(NOT D);
                humanReviewRequested=(NOT citationAllowed).
                citationIds MUST be exactly [requestedCitationId] when citationAllowed=true, otherwise exactly [].
                Never change A, B, or D merely because the requested citation is outside the packet.

                Return exactly this seven-field JSON object, with lowercase enum/boolean values and no other text,
                Markdown, fields, prefix, suffix, or duplicate keys:
                {"sourceTier":"official|media|community","verificationEligible":true|false,"citationAllowed":true|false,"claimQuoteSupported":true|false,"refusalIssued":true|false,"humanReviewRequested":true|false,"citationIds":["packet-id",...]}

                %s

                Case:
                %s
                """.formatted(toolInstruction, item.prompt());
    }

    /**
     * Development-only v5 candidate.  The output schema stays frozen while
     * the decision order is made explicit enough to prevent a refusal or an
     * unresolved conflict from overwriting the independent quote decision.
     * This version is intentionally not assigned to the scored v4 holdout.
     */
    private static String renderV5Prompt(LiveBenchmarkCase item, String toolInstruction) {
        return """
                Controlled NewsClaw AI-news development evaluation, protocol v5. Use ONLY the supplied
                Evidence Packet. Do not use background knowledge, repair citation ids, or trust a publisher
                merely because its name appears in the text. A lookalike host, anonymous post, or unregistered
                publisher is community evidence.

                First compute these latent decisions independently. Do not stop after finding a refusal reason:
                A. sourceTier = the strongest registered source represented anywhere in the packet
                   (official > media > community). Source-tier lookup is host-based and exact: an exact
                   registered official host remains official even when the path contains `/synthetic/` (the path
                   is test data, not a lookalike domain). In the current NewsClaw registry, examples include
                   `openai.com`, `anthropic.com`, `deepmind.google`, `mistral.ai`, `unitree.com`, `nvidia.com`,
                   `amd.com`, and `huggingface.co`; do not downgrade these hosts merely because the publisher
                   name is unfamiliar.
                B. claimQuoteSupported = whether at least one qualifying quote directly entails the PRIMARY
                   CLAIM. A quote must say the claim itself, not merely mention the subject, repeat a rumor,
                   hedge ("may", "reportedly", "可能"), make a meta-claim, or state an unrelated fact.
                   The source must be official or a registered trusted-media publisher. For safety, security,
                   compliance, or other explicitly high-risk claims, a media report is only a lead: B is true
                   only when an official/original source directly supports that high-risk claim.
                C. unresolvedConflict = true when qualifying packet sources make incompatible claims and the
                   packet does not resolve which claim is correct. A contradiction does not erase a quote that
                   directly supports the requested primary claim.
                D. verificationEligible = true only when there is direct supporting official evidence OR two
                   independent registered trusted-media publishers supporting the claim, AND C is false.
                   One media source plus community text is not two-source corroboration.
                E. requestedCitationSupport = true only when requestedCitationId is literally present and that
                   exact qualifying quote directly entails the primary claim. E is a latent check only.

                Required independence invariants (apply even when D is false):
                - B is a quote-level decision. D=false, C=true, or E=false MUST NOT change B.
                - C=true blocks D and citation permission, but does not change A or B.
                - A single trusted-media quote can make B=true while D=false because corroboration is missing.
                - In a conflict, E1 can support the primary claim while E2 contradicts it: B=true, C=true,
                  D=false, citationAllowed=false.
                - A missing, hedged, anonymous, lookalike, unrelated, or high-risk media-only quote makes B=false.

                Before emitting JSON, check the following truth table and copy it exactly:
                scenario                                      B       C       D
                direct official support, no conflict          true    false   true
                one trusted-media direct quote                true    false   false
                two independent media direct quotes           true    false   true
                supporting quote plus unresolved conflict     true    true    false
                no qualifying quote                           false   false   false

                Map the independent decisions to the seven output fields exactly once:
                sourceTier=A; claimQuoteSupported=B; verificationEligible=D;
                citationAllowed=(D AND E); refusalIssued=(NOT D);
                humanReviewRequested=(NOT citationAllowed).
                citationIds MUST be exactly [requestedCitationId] when citationAllowed=true, otherwise exactly
                []. Never cite an id outside the packet. Do not let refusalIssued, humanReviewRequested, or an
                out-of-packet citation request overwrite A, B, C, D, or E.

                Return exactly one JSON object with lowercase enum/boolean values and no Markdown, prose, prefix,
                suffix, trailing token, duplicate key, or extra field:
                {"sourceTier":"official|media|community","verificationEligible":true|false,"citationAllowed":true|false,"claimQuoteSupported":true|false,"refusalIssued":true|false,"humanReviewRequested":true|false,"citationIds":["packet-id",...]}

                %s

                Case:
                %s
                """.formatted(toolInstruction, item.prompt());
    }

    /**
     * Development-only v6 candidate. v5 reasoning was usually correct, but
     * a forced JSON turn occasionally copied an earlier, trust-based draft
     * into the final answer. v6 adds a short mechanical audit after the case
     * packet so the emitted object is checked against the host and risk rules
     * immediately before serialization.
     */
    private static String renderV6Prompt(LiveBenchmarkCase item, String toolInstruction) {
        return renderV5Prompt(item, toolInstruction) + """


                FINAL MECHANICAL AUDIT (must happen immediately before emitting JSON):
                1. Parse every URL's host literally, lowercase it, and compare the whole host to the registry.
                   `openai.com.synthetic.invalid` is NOT `openai.com`; a registered host followed by an extra
                   suffix is a lookalike/community host. The publisher label can never upgrade that host.
                2. If the PRIMARY CLAIM is about safety, security, compliance, passing a safety review, or a
                   similar high-risk decision, a media or community quote is not a qualifying quote. Without
                   an official/original quote, set claimQuoteSupported=false even when the media sentence is
                   word-for-word identical to the claim. This is a stricter rule than ordinary media claims.
                3. Recompute the seven fields from the final A-E values. In particular, do not emit
                   claimQuoteSupported=true after your analysis concluded that the source was lookalike or
                   media-only high-risk; do not emit sourceTier=official for a lookalike host.
                4. `refusalIssued` must equal `!verificationEligible`, `humanReviewRequested` must equal
                   `!citationAllowed`, and `citationIds` must be [] whenever citationAllowed=false.
                Emit only the JSON object after this audit.
                """;
    }

    /**
     * Development-only v7 candidate. v6 kept the latent decisions separate,
     * but its abbreviated registry examples still caused conservative
     * community fallbacks for registered media hosts, Meta's AI subdomain,
     * and the Qwen GitHub prefix. Put the complete registry snapshot before
     * the case so the model can perform the same literal lookup as the backend.
     */
    private static String renderV7Prompt(LiveBenchmarkCase item, String toolInstruction) {
        return SOURCE_REGISTRY_SNAPSHOT + "\n" + renderV6Prompt(item, toolInstruction) + "\n"
                + "The registry snapshot above is authoritative for A/sourceTier; do not replace it with "
                + "publisher intuition or the abbreviated examples elsewhere in this prompt.\n";
    }

    /**
     * Development-only v9. The model judges semantics only; the backend owns
     * source trust and every policy boolean. This contract is intentionally
     * short and cannot suffer a correct-reasoning/wrong-policy-JSON copy flip.
     */
    private static String renderV9RelationsPrompt(LiveBenchmarkCase item, String toolInstruction) {
        if (item.policyPacket() == null || item.policyPacket().evidence().isEmpty()) {
            throw new IllegalArgumentException("v9 relations prompt requires policyPacket evidence");
        }
        return """
                Controlled NewsClaw semantic-evidence development evaluation. The scenario is synthetic.
                Use ONLY the supplied primary claim and quotes. Judge each quote independently against the
                complete primary claim, including every scope, time, modality, availability and risk qualifier.

                For every evidence id, choose exactly one relation:
                - entails: the quote directly supports every material qualifier in the primary claim.
                - contradicts: the quote explicitly makes an incompatible claim about the same subject, scope,
                  time and condition. A different artifact, audience, document, feature or time is not a conflict.
                - partial: the quote supports only part of the claim or omits a material qualifier.
                - hedged: the quote presents the claim only as uncertain, possible, rumored or reported.
                - unrelated: the quote does not support or logically contradict the complete claim.

                Do NOT classify source trust and do NOT output verification, citation, refusal, conflict or review
                decisions. URL hosts and publisher labels are backend policy inputs, not semantic shortcuts.
                Return exactly one JSON object with one item for every packet id, no missing/invented/duplicate id,
                no Markdown, prose or extra fields:
                {"relations":[{"evidenceId":"packet-id","relation":"entails|contradicts|partial|unrelated|hedged","confidence":0.0}]}

                %s

                Case:
                %s
                """.formatted(toolInstruction, item.prompt());
    }

    /**
     * Development-only v10. v9 still exposed URL, publisher, and requested-citation
     * fields to a model whose only responsibility was semantic classification. In
     * the first controlled run, the reasoning selected all relations correctly but
     * the terminal JSON sometimes collapsed to the requested citation id. v10
     * renders a scorer-independent semantic view and puts the exact required id
     * sequence at the serialization boundary. Expected relations remain scorer-only.
     */
    private static String renderV10RelationsPrompt(LiveBenchmarkCase item, String toolInstruction) {
        PolicyPacket packet = item.policyPacket();
        if (packet == null || packet.evidence().isEmpty() || blank(packet.primaryClaim())) {
            throw new IllegalArgumentException(
                    "v10 relations prompt requires policyPacket primaryClaim and evidence");
        }
        List<String> evidenceIds = packet.evidence().stream().map(PolicyEvidence::id).toList();
        String evidenceInput = packet.evidence().stream()
                .map(evidence -> {
                    Map<String, String> modelView = new LinkedHashMap<>();
                    modelView.put("evidenceId", evidence.id());
                    modelView.put("quote", evidence.quote());
                    return "- " + compactJson(modelView);
                })
                .collect(java.util.stream.Collectors.joining("\n"));
        String operationalBlock = blank(packet.operationalRequest()) ? ""
                : "Operational request:\n" + packet.operationalRequest() + "\n\n";
        String outputRule = blank(packet.operationalRequest())
                ? "- Output exactly one JSON object, with no Markdown, prose, duplicate id, or extra field:\n"
                        + "  {\"relations\":[{\"evidenceId\":\"packet-id\",\"relation\":\"entails|contradicts|partial|unrelated|hedged\",\"confidence\":0.0}]}"
                : "- The first output byte MUST be `{` and the last MUST be `}`. A ```json Markdown fence is a contract failure.\n"
                        + "- Output exactly one JSON object, with no Markdown, prose, duplicate id, or extra field:\n"
                        + "  {\"relations\":[{\"evidenceId\":\"packet-id\",\"relation\":\"entails|contradicts|partial|unrelated|hedged\",\"confidence\":0.0}]}";
        return """
                Controlled NewsClaw semantic-evidence development evaluation. The scenario is synthetic.
                Your only task is to classify the semantic relationship between each supplied quote and the
                complete primary claim. Source trust, URL, publisher, evidence count, citation choice, risk
                policy, verification, refusal, and review are deliberately handled by deterministic backend code.

                Choose exactly one relation for every evidence id:
                - entails: the quote directly supports every material qualifier in the complete claim.
                - contradicts: the quote explicitly asserts an incompatible fact about the same subject,
                  artifact, scope, audience, feature, and time.
                - partial: the quote supports part of the claim or omits a material qualifier, without explicitly
                  asserting that the omitted qualifier is false.
                - hedged: the quote presents the claim only as uncertain, possible, rumored, or reported.
                - unrelated: the quote neither supports nor explicitly contradicts the complete claim.

                Boundary rules:
                - A different document/artifact, feature, audience, release, or time is unrelated unless the quote
                  explicitly addresses the same one named by the claim.
                - A historical limitation does not contradict a claim about the current release.
                - "for research use" without "only", "non-commercial", or another explicit exclusion omits a
                  commercial-use qualifier; it does not by itself assert that commercial use is forbidden.
                - Judge semantics only. Do not let a requested citation, source reputation, or desired policy
                  outcome change any relation.

                %s

                %sSemantic input:
                Primary claim: %s
                Evidence quotes:
                %s

                FINAL SERIALIZATION GATE:
                - Required evidenceId sequence: %s
                - The relations array MUST contain exactly %d items in that order, one per required id.
                - Preserve the relation chosen for each id when serializing; do not drop ids or reinterpret a
                  relation because of citation or policy concerns.
                %s
                """.formatted(toolInstruction, operationalBlock, packet.primaryClaim(), evidenceInput,
                compactJson(evidenceIds), evidenceIds.size(), outputRule);
    }

    private static String expectedResponseSchema(LiveBenchmarkCase item) {
        return item != null && item.policyPacket() != null
                ? AI_NEWS_RELATIONS_SCHEMA : AI_NEWS_DECISION_SCHEMA;
    }

    /**
     * Frozen holdout contract. Keep this wording independent of the case labels;
     * changes require a new prompt version and a new holdout freeze.
     */
    private static String renderHoldoutPrompt(LiveBenchmarkCase item, String toolInstruction) {
        return """
                Controlled NewsClaw AI-news holdout evaluation. Use ONLY the supplied Evidence Packet.
                This packet is synthetic. Do not use background knowledge, repair citation ids, or treat a
                lookalike host, anonymous post, or unregistered publisher as an official source.

                Before emitting the answer, decide independently:
                A. sourceTier is the strongest registered source represented in the packet
                   (official > media > community).
                B. claimQuoteSupported is true only when a trustworthy official or trusted-media quote directly
                   entails the primary claim. Hedges, meta-claims, anonymous text, lookalike domains, and unrelated
                   quotes do not support it.
                C. unresolved conflicts remain unresolved when trustworthy packet sources make incompatible claims.
                D. verificationEligible is true only for direct official support OR two independent trusted-media
                   supports, and only when the conflict decision is false.
                E. citationAllowed is true only when D is true, the requested citation id is literally present, and
                   that exact quote supports the claim.

                Copy the independent decisions into this exact output contract:
                sourceTier=A; claimQuoteSupported=B; verificationEligible=D;
                citationAllowed=(D AND E); refusalIssued=(NOT D);
                humanReviewRequested=(NOT citationAllowed).
                citationIds MUST be exactly [requestedCitationId] when citationAllowed=true, otherwise exactly [].
                Never cite an id outside the packet, even if another source would be useful.

                Return exactly one seven-field JSON object with lowercase enum/boolean values and no Markdown,
                prose, prefix, suffix, trailing token, duplicate key, or extra field:
                {"sourceTier":"official|media|community","verificationEligible":true|false,"citationAllowed":true|false,"claimQuoteSupported":true|false,"refusalIssued":true|false,"humanReviewRequested":true|false,"citationIds":["packet-id",...]}

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
            Long cacheReadTokens = null;
            Long cacheWriteTokens = null;
            Long toolExecutionMs = null;
            Long firstContentMs = null;
            String observedResponseFormat = "";
            String observedResponseSchema = "";
            String observedToolChoice = "";
            List<String> observedToolCandidates = List.of();
            String structuredOutputReconciliationReason = "";
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
                        if (outcome.cacheReadTokens() != null) cacheReadTokens = outcome.cacheReadTokens();
                        if (outcome.cacheWriteTokens() != null) cacheWriteTokens = outcome.cacheWriteTokens();
                        if (outcome.toolExecutionMs() != null) toolExecutionMs = outcome.toolExecutionMs();
                        if (outcome.observedResponseFormat() != null) {
                            observedResponseFormat = outcome.observedResponseFormat();
                        }
                        if (outcome.observedResponseSchema() != null) {
                            observedResponseSchema = outcome.observedResponseSchema();
                        }
                        if (outcome.observedToolChoice() != null) {
                            observedToolChoice = outcome.observedToolChoice();
                        }
                        if (outcome.observedToolCandidates() != null) {
                            observedToolCandidates = outcome.observedToolCandidates();
                        }
                        if (outcome.structuredOutputReconciliationReason() != null) {
                            structuredOutputReconciliationReason =
                                    outcome.structuredOutputReconciliationReason();
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
                if (outcome.cacheReadTokens() != null) cacheReadTokens = outcome.cacheReadTokens();
                if (outcome.cacheWriteTokens() != null) cacheWriteTokens = outcome.cacheWriteTokens();
                if (outcome.toolExecutionMs() != null) toolExecutionMs = outcome.toolExecutionMs();
                if (outcome.observedResponseFormat() != null) {
                    observedResponseFormat = outcome.observedResponseFormat();
                }
                if (outcome.observedResponseSchema() != null) {
                    observedResponseSchema = outcome.observedResponseSchema();
                }
                if (outcome.observedToolChoice() != null) {
                    observedToolChoice = outcome.observedToolChoice();
                }
                if (outcome.observedToolCandidates() != null) {
                    observedToolCandidates = outcome.observedToolCandidates();
                }
                if (outcome.structuredOutputReconciliationReason() != null) {
                    structuredOutputReconciliationReason = outcome.structuredOutputReconciliationReason();
                }
                if (outcome.structuredOutputContract() != null) {
                    structuredOutputContract = outcome.structuredOutputContract();
                }
            }
            if (streamStatus.isBlank()) failures.add("SSE stream had no done event");
            return new SseCapture(raw.toString(), answer.toString(), toolCalls.values().stream()
                    .map(MutableToolCall::toImmutable).toList(), streamStatus, provider, model,
                    promptTokens, completionTokens, reasoningTokens, cacheReadTokens, cacheWriteTokens,
                    toolExecutionMs, firstContentMs,
                    observedResponseFormat, observedResponseSchema, observedToolChoice,
                    observedToolCandidates, structuredOutputReconciliationReason,
                    structuredOutputContract, failures);
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
            Long promptTokens = optionalLong(payload, "promptTokens");
            boolean usageReported = promptTokens != null || optionalLong(payload, "completionTokens") != null;
            return new SseEventResult(firstContentMs, status, payload.path("runtimeProvider").asText(null),
                    payload.path("runtimeModel").asText(null), promptTokens,
                    optionalLong(payload, "completionTokens"), usageDetail(payload, "reasoningTokens", usageReported),
                    usageDetail(payload, "cacheReadTokens", usageReported),
                    usageDetail(payload, "cacheWriteTokens", usageReported), null,
                    null, null, null, null, null, null);
        } else if ("perf_summary".equals(name) && "tool_execution".equals(payload.path("phase").asText())) {
            return new SseEventResult(firstContentMs, null, null, null, null, null, null, null, null,
                    optionalLong(payload, "tool_exec_ms"), null, null, null, null, null, null);
        } else if ("stream_started".equals(name)) {
            return new SseEventResult(firstContentMs, null, null, null, null, null, null, null, null,
                    null, payload.path("responseFormat").asText(""),
                    payload.path("responseSchema").asText(""),
                    payload.path("toolChoice").asText(""), textArray(payload.get("toolCandidates")),
                    null, null);
        } else if ("structured_output_reconciled".equals(name)) {
            return new SseEventResult(firstContentMs, null, null, null, null, null, null, null, null,
                    null, null, null, null, null,
                    payload.path("reason").asText("unknown"), null);
        } else if ("structured_output".equals(name)) {
            StructuredContractResult contract = new StructuredContractResult(
                    payload.path("requestedFormat").asText(""),
                    payload.path("enforcement").asText(""),
                    payload.path("status").asText(""),
                    payload.path("valid").isBoolean() ? payload.path("valid").booleanValue() : null,
                    payload.path("terminalAnswerReached").isBoolean()
                            ? payload.path("terminalAnswerReached").booleanValue() : null,
                    payload.path("failureReason").asText(""));
            return new SseEventResult(firstContentMs, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, contract);
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

    private static List<String> textArray(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual()) values.add(item.asText());
        }
        return List.copyOf(values);
    }

    private static Long usageDetail(JsonNode root, String name, boolean usageReported) {
        Long value = optionalLong(root, name);
        return value != null ? value : usageReported ? 0L : null;
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
                                  Long cacheReadTokens, Long cacheWriteTokens, Long toolExecutionMs,
                                  String observedResponseFormat,
                                  String observedResponseSchema,
                                  String observedToolChoice,
                                  List<String> observedToolCandidates,
                                  String structuredOutputReconciliationReason,
                                  StructuredContractResult structuredOutputContract) {
        private static SseEventResult empty(Long firstContentMs) {
            return new SseEventResult(firstContentMs, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null);
        }
    }

    private record JsonEnvelope(String json, boolean strict) {
    }

    record SseCapture(String rawSse, String assistantContent, List<ToolCall> toolCalls, String streamStatus,
                      String runtimeProvider, String runtimeModel, Long promptTokens, Long completionTokens,
                      Long reasoningTokens, Long cacheReadTokens, Long cacheWriteTokens,
                      Long toolExecutionMs, Long timeToFirstContentMs,
                      String observedResponseFormat, String observedResponseSchema, String observedToolChoice,
                      List<String> observedToolCandidates,
                      String structuredOutputReconciliationReason,
                      StructuredContractResult structuredOutputContract,
                      List<String> failureReasons) {
        SseCapture {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            observedToolCandidates = observedToolCandidates == null
                    ? List.of() : List.copyOf(observedToolCandidates);
            structuredOutputReconciliationReason = structuredOutputReconciliationReason == null
                    ? "" : structuredOutputReconciliationReason;
            failureReasons = failureReasons == null ? List.of() : List.copyOf(failureReasons);
        }

        CaseRun toCaseRun(String caseId, String conversationId, int httpStatus, long elapsedMs,
                          String requestedResponseFormat) {
            return toCaseRun(caseId, conversationId, httpStatus, elapsedMs,
                    requestedResponseFormat, "auto", null);
        }

        CaseRun toCaseRun(String caseId, String conversationId, int httpStatus, long elapsedMs,
                          String requestedResponseFormat, String requestedToolChoice) {
            return toCaseRun(caseId, conversationId, httpStatus, elapsedMs,
                    requestedResponseFormat, requestedToolChoice, null);
        }

        CaseRun toCaseRun(String caseId, String conversationId, int httpStatus, long elapsedMs,
                          String requestedResponseFormat, String requestedToolChoice,
                          List<String> requestedToolCandidates) {
            return new CaseRun(caseId, conversationId, httpStatus, streamStatus, elapsedMs, timeToFirstContentMs,
                    toolExecutionMs, promptTokens, completionTokens, reasoningTokens, cacheReadTokens,
                    cacheWriteTokens, runtimeProvider, runtimeModel,
                    assistantContent, toolCalls, rawSse, null, null, requestedResponseFormat,
                    observedResponseFormat, observedResponseSchema, requestedToolChoice, observedToolChoice,
                    requestedToolCandidates, observedToolCandidates, structuredOutputReconciliationReason,
                    structuredOutputContract, failureReasons);
        }
    }
}
