package vip.newsclaw.plugin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.newsclaw.channel.ChannelManager;
import vip.newsclaw.llm.service.ModelProviderService;
import vip.newsclaw.memory.spi.MemoryManager;
import vip.newsclaw.plugin.api.PluginException;
import vip.newsclaw.plugin.api.PluginManifest;
import vip.newsclaw.plugin.api.NewsClawPlugin;
import vip.newsclaw.plugin.api.PluginContext;
import vip.newsclaw.plugin.api.search.PluginSearchProvider;
import vip.newsclaw.plugin.api.search.PluginSearchQuery;
import vip.newsclaw.plugin.api.search.PluginSearchResult;
import vip.newsclaw.tool.ToolRegistry;
import vip.newsclaw.tool.search.SearchProviderRegistry;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * PluginContextImpl#registerSearchProvider: wraps the plugin SPI in a bridge,
 * registers it into SearchProviderRegistry, and records the id on LoadedPlugin
 * so disable/rollback can unregister it.
 */
class PluginContextImplSearchTest {

    private SearchProviderRegistry registry;
    private PluginContextImpl context;
    private LoadedPlugin loadedPlugin;

    @BeforeEach
    void setUp() {
        registry = new SearchProviderRegistry(List.of());

        PluginManifest manifest = new PluginManifest();
        manifest.setName("test-plugin");
        manifest.setVersion("1.0.0");
        manifest.setType("search");
        manifest.setEntrypoint("x.Y");

        NewsClawPlugin plugin = new NewsClawPlugin() {
            @Override public void onLoad(PluginContext ctx) { }
            @Override public void onEnable() { }
            @Override public void onDisable() { }
        };
        loadedPlugin = new LoadedPlugin(manifest, plugin,
                new URLClassLoader(new URL[0], getClass().getClassLoader()));

        context = new PluginContextImpl(
                loadedPlugin, manifest,
                mock(ToolRegistry.class), mock(ChannelManager.class),
                mock(MemoryManager.class), mock(ModelProviderService.class),
                registry,
                null);
    }

    private static PluginSearchProvider provider(String id) {
        return new PluginSearchProvider() {
            @Override public String id() { return id; }
            @Override public String label() { return id; }
            @Override public boolean isAvailable() { return true; }
            @Override public List<PluginSearchResult> search(PluginSearchQuery query) {
                return List.of();
            }
        };
    }

    @Test
    @DisplayName("registers into the registry and records the id on LoadedPlugin")
    void registersAndRecords() {
        context.registerSearchProvider(provider("my-search"));

        assertNotNull(registry.getById("my-search"));
        assertEquals(List.of("my-search"), loadedPlugin.getRegisteredSearchProviders());
    }

    @Test
    @DisplayName("id conflict surfaces as PluginException and is not recorded")
    void conflictBecomesPluginException() {
        context.registerSearchProvider(provider("my-search"));

        assertThrows(PluginException.class,
                () -> context.registerSearchProvider(provider("my-search")));
        assertEquals(1, loadedPlugin.getRegisteredSearchProviders().size());
    }

    @Test
    @DisplayName("blank id is rejected with PluginException")
    void blankIdRejected() {
        assertThrows(PluginException.class,
                () -> context.registerSearchProvider(provider("  ")));
        assertTrue(loadedPlugin.getRegisteredSearchProviders().isEmpty());
    }
}
