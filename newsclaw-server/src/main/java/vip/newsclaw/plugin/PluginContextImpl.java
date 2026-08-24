package vip.newsclaw.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import vip.newsclaw.channel.ChannelManager;
import vip.newsclaw.llm.service.ModelProviderService;
import vip.newsclaw.memory.spi.MemoryManager;
import vip.newsclaw.plugin.api.PluginContext;
import vip.newsclaw.plugin.api.PluginException;
import vip.newsclaw.plugin.api.PluginManifest;
import vip.newsclaw.plugin.api.channel.PluginChannelAdapter;
import vip.newsclaw.plugin.api.memory.PluginMemoryProvider;
import vip.newsclaw.plugin.api.search.PluginSearchProvider;
import vip.newsclaw.plugin.bridge.PluginChannelBridge;
import vip.newsclaw.plugin.bridge.PluginMemoryBridge;
import vip.newsclaw.plugin.bridge.PluginSearchBridge;
import vip.newsclaw.tool.ToolRegistry;
import vip.newsclaw.tool.search.SearchProviderRegistry;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Platform-side implementation of {@link PluginContext}.
 * Bridges plugin registrations to the corresponding platform services.
 *
 * @author NewsClaw Team
 */
public class PluginContextImpl implements PluginContext {

    private final LoadedPlugin loadedPlugin;
    private final PluginManifest manifest;
    private final ToolRegistry toolRegistry;
    private final ChannelManager channelManager;
    private final MemoryManager memoryManager;
    private final ModelProviderService modelProviderService;
    private final SearchProviderRegistry searchProviderRegistry;
    /**
     * Live config view: replaced wholesale by {@link #refreshConfig} when an admin
     * saves new values, so {@link #getConfig} reflects updates without a plugin
     * restart. Volatile reference to an immutable map — readers either see the old
     * snapshot or the new one, never a torn state.
     */
    private volatile Map<String, Object> configMap;
    private final Logger logger;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PluginContextImpl(LoadedPlugin loadedPlugin,
                             PluginManifest manifest,
                             ToolRegistry toolRegistry,
                             ChannelManager channelManager,
                             MemoryManager memoryManager,
                             ModelProviderService modelProviderService,
                             SearchProviderRegistry searchProviderRegistry,
                             String configJson) {
        this.loadedPlugin = loadedPlugin;
        this.manifest = manifest;
        this.toolRegistry = toolRegistry;
        this.channelManager = channelManager;
        this.memoryManager = memoryManager;
        this.modelProviderService = modelProviderService;
        this.searchProviderRegistry = searchProviderRegistry;
        this.logger = LoggerFactory.getLogger("plugin." + manifest.getName());
        this.configMap = parseConfig(configJson);
    }

    /**
     * Re-parse and swap the live config after {@code PluginManager.updateConfig}
     * persists new values, so the running plugin's {@code getConfig} calls pick up
     * the change immediately instead of serving load-time values until a restart.
     */
    void refreshConfig(String configJson) {
        this.configMap = parseConfig(configJson);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(configJson, Map.class);
        } catch (Exception e) {
            logger.warn("Failed to parse plugin config JSON: {}", e.getMessage());
            return Map.of();
        }
    }

    @Override
    public void registerTool(ToolCallback tool) {
        registerTool(tool, () -> true);
    }

    @Override
    public void registerTool(ToolCallback tool, Supplier<Boolean> availabilityCheck) {
        toolRegistry.registerPluginTool(tool, availabilityCheck);
        loadedPlugin.getRegisteredTools().add(tool.getToolDefinition().name());
    }

    @Override
    public void registerProvider(String providerId, ChatModel chatModel) {
        modelProviderService.registerPluginChatModel(providerId, chatModel);
        loadedPlugin.setRegisteredProvider(providerId);
    }

    @Override
    public void registerChannel(PluginChannelAdapter channel) {
        PluginChannelBridge bridge = new PluginChannelBridge(channel);
        channelManager.registerPluginChannel(manifest.getName(), bridge);
        loadedPlugin.getRegisteredChannels().add(channel.getChannelType());
    }

    @Override
    public void registerMemoryProvider(PluginMemoryProvider provider) {
        if (memoryManager.hasExternalProvider()) {
            throw new PluginException(
                    "Only one external memory provider allowed. Current: " +
                            memoryManager.getExternalProviderName());
        }
        PluginMemoryBridge bridge = new PluginMemoryBridge(provider);
        memoryManager.registerPluginProvider(bridge);
        loadedPlugin.setRegisteredMemoryProvider(provider.id());
    }

    @Override
    public void registerSearchProvider(PluginSearchProvider provider) {
        if (provider == null || provider.id() == null || provider.id().isBlank()) {
            throw new PluginException("Search provider id must not be blank");
        }
        try {
            searchProviderRegistry.registerPluginProvider(new PluginSearchBridge(provider));
        } catch (IllegalArgumentException e) {
            throw new PluginException(e.getMessage(), e);
        }
        loadedPlugin.getRegisteredSearchProviders().add(provider.id());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getConfig(String key, Class<T> type) {
        Object value = configMap.get(key);
        if (value == null) return null;
        if (type.isInstance(value)) return (T) value;
        return objectMapper.convertValue(value, type);
    }

    @Override
    public Logger getLogger() {
        return logger;
    }
}
