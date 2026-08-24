package vip.mate.llm.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TestResult {
    private boolean success;
    private long latencyMs;
    private String message;
    private String errorMessage;

    public static TestResult ok(long latencyMs, String message) {
        return new TestResult(true, latencyMs, message, null);
    }

    public static TestResult fail(long latencyMs, String errorMessage) {
        return new TestResult(false, latencyMs, null, errorMessage);
    }
}
