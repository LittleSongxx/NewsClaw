package vip.newsclaw.llm.chatmodel;

/** Request-scoped bridge for {@link ToolCandidatePolicy}. */
public final class ToolCandidateHolder {

    private static final ThreadLocal<ToolCandidatePolicy> HOLDER = new ThreadLocal<>();

    private ToolCandidateHolder() {
    }

    public static void set(ToolCandidatePolicy policy) {
        if (policy == null || !policy.restricted()) {
            HOLDER.remove();
            return;
        }
        HOLDER.set(policy);
    }

    public static ToolCandidatePolicy get() {
        ToolCandidatePolicy value = HOLDER.get();
        return value == null ? ToolCandidatePolicy.UNRESTRICTED : value;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
