package vip.newsclaw.skill.knowledge;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.lang.Nullable;

import java.util.function.Function;
import java.util.function.BiFunction;

/**
 * RFC-090 §14.4 — generic {@link ToolCallback} adapter for skill-scoped
 * wrapper tools.
 *
 * <p>The factory ({@link WikiSkillWrapperToolFactory}) constructs one
 * instance per (skill, op) pair, with the bound {@code kbId} captured
 * inside the {@link Function} body. The LLM sees only the wrapper name
 * (e.g. {@code kb_tcm_classics_search}); the {@code kbId} is never
 * passed via {@link ToolContext} or a ThreadLocal — both of which
 * §14.4 explicitly bans.
 *
 * <p>Why a single class instead of an anonymous lambda: the
 * {@link ToolDefinition} surface is verbose enough that anonymous
 * inner classes would duplicate the same getter wiring everywhere.
 */
public class SkillScopedToolCallback implements ToolCallback {

    private final ToolDefinition definition;
    private final BiFunction<String, ToolContext, String> contextHandler;

    public SkillScopedToolCallback(String name,
                                    String description,
                                    String inputSchema,
                                    Function<String, String> handler) {
        this(name, description, inputSchema,
                (input, ignored) -> handler.apply(input));
    }

    /** Context-aware variant used by wrappers whose backing resource is tenant-scoped. */
    public SkillScopedToolCallback(String name,
                                   String description,
                                   String inputSchema,
                                   BiFunction<String, ToolContext, String> handler) {
        this.definition = ToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema(inputSchema)
                .build();
        this.contextHandler = handler;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return ToolCallback.super.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return contextHandler.apply(toolInput, null);
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        return contextHandler.apply(toolInput, toolContext);
    }
}
