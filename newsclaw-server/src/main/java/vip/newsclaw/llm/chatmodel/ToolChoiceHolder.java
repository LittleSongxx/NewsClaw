package vip.newsclaw.llm.chatmodel;

/**
 * Request-scoped bridge carrying a caller-declared tool-selection policy into
 * a cached native Agent graph. Mirrors {@link StructuredOutputFormatHolder}:
 * the graph remains immutable while an individual turn can request an explicit
 * provider tool contract.
 */
public final class ToolChoiceHolder {

    private static final ThreadLocal<ToolChoicePolicy> HOLDER = new ThreadLocal<>();

    private ToolChoiceHolder() {
    }

    public static void set(ToolChoicePolicy policy) {
        if (policy == null || policy.isAuto()) {
            HOLDER.remove();
            return;
        }
        HOLDER.set(policy);
    }

    public static ToolChoicePolicy get() {
        ToolChoicePolicy value = HOLDER.get();
        return value == null ? ToolChoicePolicy.AUTO : value;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
