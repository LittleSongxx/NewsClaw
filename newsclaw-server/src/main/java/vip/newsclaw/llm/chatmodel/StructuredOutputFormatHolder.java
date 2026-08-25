package vip.newsclaw.llm.chatmodel;

/**
 * Request-scoped bridge carrying an explicit response contract to graph nodes.
 *
 * <p>The holder mirrors {@link ThinkingLevelHolder}: the cached Agent remains
 * immutable while one Web/API turn can request a stricter output contract.
 */
public final class StructuredOutputFormatHolder {

    private static final ThreadLocal<StructuredOutputFormat> HOLDER = new ThreadLocal<>();

    private StructuredOutputFormatHolder() {
    }

    public static void set(StructuredOutputFormat format) {
        if (format == null || format == StructuredOutputFormat.TEXT) {
            HOLDER.remove();
            return;
        }
        HOLDER.set(format);
    }

    public static StructuredOutputFormat get() {
        StructuredOutputFormat value = HOLDER.get();
        return value == null ? StructuredOutputFormat.TEXT : value;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
