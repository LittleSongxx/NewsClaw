package vip.newsclaw.llm.chatmodel;

import org.springframework.ai.tool.ToolCallback;
import vip.newsclaw.exception.NewsClawException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Request-scoped provider-visible tool candidate set.
 *
 * <p>The policy is deliberately an intersection over the Agent's already
 * permission-filtered and progressively disclosed callbacks. It can reduce
 * the provider-visible schema surface, but can never add a callback or bypass
 * Tool Guard, argument validation, approval, or executor scope.</p>
 */
public final class ToolCandidatePolicy {

    private static final int MAX_CANDIDATES = 32;
    private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z0-9_.\\-$]{1,128}");

    /** Null on the wire preserves the legacy full active callback surface. */
    public static final ToolCandidatePolicy UNRESTRICTED =
            new ToolCandidatePolicy(false, List.of());

    private final boolean restricted;
    private final List<String> names;

    private ToolCandidatePolicy(boolean restricted, List<String> names) {
        this.restricted = restricted;
        this.names = List.copyOf(names);
    }

    /**
     * Parse the optional JSON array. Null means unrestricted; an explicit
     * empty array means no provider-visible tools for this request.
     */
    public static ToolCandidatePolicy fromWire(Collection<String> rawNames) {
        if (rawNames == null) return UNRESTRICTED;
        if (rawNames.size() > MAX_CANDIDATES) {
            throw new IllegalArgumentException("toolCandidates supports at most 32 names");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String raw : rawNames) {
            if (raw == null || raw.isBlank() || !raw.equals(raw.trim())
                    || !TOOL_NAME.matcher(raw).matches()) {
                throw new IllegalArgumentException(
                        "toolCandidates must contain only trimmed valid tool names");
            }
            if (!unique.add(raw)) {
                throw new IllegalArgumentException("toolCandidates must not contain duplicates");
            }
        }
        return new ToolCandidatePolicy(true, new ArrayList<>(unique));
    }

    public boolean restricted() {
        return restricted;
    }

    public List<String> names() {
        return names;
    }

    /**
     * Restrict an already-active callback list and fail closed on stale or
     * misspelled candidate names. Callback order remains the Agent's order.
     */
    public List<ToolCallback> restrict(Collection<ToolCallback> activeCallbacks) {
        List<ToolCallback> active = activeCallbacks == null
                ? List.of()
                : activeCallbacks.stream().filter(Objects::nonNull).toList();
        if (!restricted) return active;

        Set<String> requested = new LinkedHashSet<>(names);
        Set<String> activeNames = new LinkedHashSet<>();
        List<ToolCallback> selected = new ArrayList<>();
        for (ToolCallback callback : active) {
            if (callback.getToolDefinition() == null) continue;
            String name = callback.getToolDefinition().name();
            if (name != null) activeNames.add(name);
            if (requested.contains(name)) selected.add(callback);
        }

        Set<String> unavailable = new LinkedHashSet<>(requested);
        unavailable.removeAll(activeNames);
        if (!unavailable.isEmpty()) {
            throw new NewsClawException(422,
                    "requested toolCandidates are not available in this Agent's active tool scope: "
                            + unavailable);
        }
        return List.copyOf(selected);
    }
}
