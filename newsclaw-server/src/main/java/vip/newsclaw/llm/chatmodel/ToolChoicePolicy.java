package vip.newsclaw.llm.chatmodel;

import org.springframework.ai.openai.api.OpenAiApi;
import vip.newsclaw.exception.NewsClawException;

import java.util.Collection;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Per-request tool-selection contract for native OpenAI-compatible Agent
 * calls. The policy never widens an Agent's tool scope: a function selection
 * is accepted only when the requested function is already in the active
 * callback list assembled by the Agent.
 */
public final class ToolChoicePolicy {

    public enum Mode {
        AUTO,
        NONE,
        REQUIRED,
        FUNCTION
    }

    private static final Pattern FUNCTION_NAME = Pattern.compile("[A-Za-z0-9_.\\-$]{1,128}");

    public static final ToolChoicePolicy AUTO = new ToolChoicePolicy(Mode.AUTO, null);
    public static final ToolChoicePolicy NONE = new ToolChoicePolicy(Mode.NONE, null);
    public static final ToolChoicePolicy REQUIRED = new ToolChoicePolicy(Mode.REQUIRED, null);

    private final Mode mode;
    private final String functionName;

    private ToolChoicePolicy(Mode mode, String functionName) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.functionName = functionName;
    }

    /**
     * Parse the compact public wire contract:
     * {@code auto}, {@code none}, {@code required}, or
     * {@code function:&lt;exact-active-function-name&gt;}.
     */
    public static ToolChoicePolicy fromWire(String raw) {
        if (raw == null || raw.isBlank() || "auto".equalsIgnoreCase(raw.trim())) {
            return AUTO;
        }
        String value = raw.trim();
        if ("none".equalsIgnoreCase(value)) return NONE;
        if ("required".equalsIgnoreCase(value)) return REQUIRED;

        int delimiter = value.indexOf(':');
        if (delimiter > 0 && "function".equalsIgnoreCase(value.substring(0, delimiter).trim())) {
            String name = value.substring(delimiter + 1).trim();
            if (FUNCTION_NAME.matcher(name).matches()) {
                return new ToolChoicePolicy(Mode.FUNCTION, name);
            }
        }
        throw new IllegalArgumentException("toolChoice must be auto, none, required, or function:<exact-tool-name>");
    }

    public Mode mode() {
        return mode;
    }

    public String functionName() {
        return functionName;
    }

    public boolean isAuto() {
        return mode == Mode.AUTO;
    }

    public boolean isExplicit() {
        return mode != Mode.AUTO;
    }

    /**
     * Whether the Agent must force one tool-producing assistant step before it
     * can generate the terminal answer. {@code none} is explicit but does not
     * create a two-stage turn.
     */
    public boolean requiresInitialToolCall() {
        return mode == Mode.REQUIRED || mode == Mode.FUNCTION;
    }

    public String wireValue() {
        return switch (mode) {
            case AUTO -> "auto";
            case NONE -> "none";
            case REQUIRED -> "required";
            case FUNCTION -> "function:" + functionName;
        };
    }

    /**
     * Produce the OpenAI wire value after validating the Agent's active tool
     * surface. This validation is deliberately adjacent to prompt assembly,
     * after permission filtering and progressive disclosure have taken effect.
     */
    public Object toOpenAiToolChoice(Collection<String> activeFunctionNames) {
        return switch (mode) {
            case AUTO -> null;
            case NONE -> OpenAiApi.ChatCompletionRequest.ToolChoiceBuilder.NONE;
            case REQUIRED -> {
                if (activeFunctionNames == null || activeFunctionNames.isEmpty()) {
                    throw unavailable("required");
                }
                yield "required";
            }
            case FUNCTION -> {
                boolean active = activeFunctionNames != null
                        && activeFunctionNames.stream().anyMatch(functionName::equals);
                if (!active) {
                    throw unavailable("function:" + functionName);
                }
                yield OpenAiApi.ChatCompletionRequest.ToolChoiceBuilder.function(functionName);
            }
        };
    }

    private static NewsClawException unavailable(String requested) {
        return new NewsClawException(422,
                "requested toolChoice '" + requested + "' is not available in this Agent's active tool scope");
    }
}
