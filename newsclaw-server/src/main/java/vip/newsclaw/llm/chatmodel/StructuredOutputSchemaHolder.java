package vip.newsclaw.llm.chatmodel;

/** Request-scoped bridge carrying the semantic structured-output schema. */
public final class StructuredOutputSchemaHolder {

    private static final ThreadLocal<StructuredOutputSchema> HOLDER = new ThreadLocal<>();

    private StructuredOutputSchemaHolder() {
    }

    public static void set(StructuredOutputSchema schema) {
        if (schema == null || schema == StructuredOutputSchema.GENERIC) {
            HOLDER.remove();
            return;
        }
        HOLDER.set(schema);
    }

    public static StructuredOutputSchema get() {
        StructuredOutputSchema value = HOLDER.get();
        return value == null ? StructuredOutputSchema.GENERIC : value;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
