package vip.newsclaw.plugin;

import lombok.Data;
import vip.newsclaw.plugin.api.NewsClawPlugin;
import vip.newsclaw.plugin.api.PluginManifest;

import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * Internal state holder for a loaded plugin.
 *
 * @author NewsClaw Team
 */
@Data
public class LoadedPlugin {

    private final PluginManifest manifest;
    private final NewsClawPlugin plugin;
    private final URLClassLoader classLoader;

    /** Set after construction (circular reference with PluginContextImpl) */
    private PluginContextImpl context;

    /** Names of tools registered by this plugin */
    private final List<String> registeredTools = new ArrayList<>();

    /** Channel types registered by this plugin */
    private final List<String> registeredChannels = new ArrayList<>();

    /** Search provider ids registered by this plugin */
    private final List<String> registeredSearchProviders = new ArrayList<>();

    /** Provider ID registered by this plugin (null if none) */
    private String registeredProvider;

    /** Memory provider ID registered by this plugin (null if none) */
    private String registeredMemoryProvider;

    /** Whether the plugin is currently enabled */
    private boolean enabled = true;

    public LoadedPlugin(PluginManifest manifest, NewsClawPlugin plugin,
                        URLClassLoader classLoader) {
        this.manifest = manifest;
        this.plugin = plugin;
        this.classLoader = classLoader;
    }
}
