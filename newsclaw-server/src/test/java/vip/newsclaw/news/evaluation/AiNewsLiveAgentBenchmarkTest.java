package vip.newsclaw.news.evaluation;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Entry point used by the opt-in live Agent evaluation script. */
class AiNewsLiveAgentBenchmarkTest {

    @Test
    @DisplayName("controlled live AI-news Agent benchmark emits quality and runtime artifacts")
    void runControlledLiveBenchmark() throws Exception {
        String input = System.getProperty("ai.news.live.benchmark");
        Assumptions.assumeTrue(input != null && !input.isBlank(),
                "set -Dai.news.live.benchmark=/path/to/live-agent-benchmark.json to run online evaluation");
        String username = System.getenv("NEWSCLAW_EVAL_USERNAME");
        String password = System.getenv("NEWSCLAW_EVAL_PASSWORD");
        Assumptions.assumeTrue(username != null && !username.isBlank() && password != null && !password.isBlank(),
                "set NEWSCLAW_EVAL_USERNAME and NEWSCLAW_EVAL_PASSWORD; credentials are read only from environment");

        Path benchmark = Path.of(input).toAbsolutePath();
        Path rawDirectory = optionalPath("ai.news.live.raw-directory");
        AiNewsLiveAgentBenchmarkRunner.LiveConfig config = new AiNewsLiveAgentBenchmarkRunner.LiveConfig(
                System.getProperty("ai.news.live.base-url", "http://127.0.0.1:18080"),
                username, password, longProperty("ai.news.live.agent-id", 0L),
                longProperty("ai.news.live.workspace-id", 1L),
                Duration.ofSeconds(longProperty("ai.news.live.timeout-seconds", 240L)),
                intProperty("ai.news.live.max-cases", 0),
                System.getProperty("git.commit", "unknown"),
                System.getProperty("ai.news.live.evaluation-tree", "unknown"), rawDirectory,
                System.getProperty("ai.news.live.response-format", "text"));
        AiNewsLiveAgentBenchmarkRunner.LiveEvaluationResult result =
                AiNewsLiveAgentBenchmarkRunner.run(benchmark, config);

        AiNewsLiveAgentBenchmarkRunner.writeArtifacts(result,
                optionalPath("ai.news.live.trace-dataset"),
                optionalPath("ai.news.live.quality-manifest"),
                optionalPath("ai.news.live.quality-markdown"),
                optionalPath("ai.news.live.runtime-manifest"),
                optionalPath("ai.news.live.runtime-markdown"));

        assertFalse(result.runs().isEmpty(), "configured live benchmark must execute at least one case");
        assertTrue(result.completedStreams() > 0,
                "no benchmark stream completed; inspect the emitted runtime artifact for deployment/configuration failures");
        System.out.printf("AI_NEWS_LIVE_AGENT_EVAL dataset=%s@%s cases=%d completed=%d badcases=%d%n",
                result.qualityReport().manifest().datasetId(), result.qualityReport().manifest().datasetVersion(),
                result.runs().size(), result.completedStreams(), result.qualityReport().badcases().size());
    }

    private static Path optionalPath(String property) {
        String value = System.getProperty(property);
        return value == null || value.isBlank() ? null : Path.of(value).toAbsolutePath();
    }

    private static long longProperty(String property, long fallback) {
        try {
            return Long.parseLong(System.getProperty(property, String.valueOf(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int intProperty(String property, int fallback) {
        try {
            return Integer.parseInt(System.getProperty(property, String.valueOf(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
