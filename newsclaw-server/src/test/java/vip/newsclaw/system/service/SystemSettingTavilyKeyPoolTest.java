package vip.newsclaw.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.newsclaw.plugin.PluginManager;
import vip.newsclaw.system.model.SystemSettingEntity;
import vip.newsclaw.system.model.SystemSettingsDTO;
import vip.newsclaw.system.repository.SystemSettingMapper;
import vip.newsclaw.tool.search.SearchProviderRegistry;
import vip.newsclaw.workspace.core.config.WorkspaceSandboxProperties;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemSettingTavilyKeyPoolTest {

    @Mock
    private SystemSettingMapper mapper;

    private SettingCrypto crypto;
    private SystemSettingService service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                SystemSettingEntity.class);
    }

    @BeforeEach
    void setUp() {
        System.setProperty("NEWSCLAW_ENV_CONFIG_ENABLED", "false");
        crypto = new SettingCrypto("tavily-pool-test-key");
        service = new SystemSettingService(
                mapper,
                new SearchProviderRegistry(List.of()),
                crypto,
                new WorkspaceSandboxProperties(),
                mock(PluginManager.class));
    }

    @AfterEach
    void clearEnvironmentOverride() {
        System.clearProperty("NEWSCLAW_ENV_CONFIG_ENABLED");
    }

    @Test
    void savesAStableDeduplicatedPoolEncryptedAtRest() {
        when(mapper.selectOne(any())).thenReturn(null);
        SystemSettingsDTO request = new SystemSettingsDTO();
        request.setTavilyApiKey(" key-a\nkey-b, key-a ; key-c ");

        service.saveSettings(request);

        ArgumentCaptor<SystemSettingEntity> captor = ArgumentCaptor.forClass(SystemSettingEntity.class);
        verify(mapper).insert(captor.capture());
        SystemSettingEntity stored = captor.getValue();
        assertEquals("tavilyApiKey", stored.getSettingKey());
        assertFalse(stored.getSettingValue().contains("key-a"));
        assertEquals("key-a\nkey-b\nkey-c", crypto.decrypt(stored.getSettingValue()));
    }

    @Test
    void publicSettingsExposeOnlyPoolCountAndGenericMask() {
        String encryptedPool = crypto.encrypt("key-a\nkey-b\nkey-a");
        when(mapper.selectOne(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            LambdaQueryWrapper<SystemSettingEntity> query = invocation.getArgument(0);
            // Lambda wrapper parameters are materialized when its SQL segment
            // is rendered (normally MyBatis performs this step).
            query.getSqlSegment();
            if (query.getParamNameValuePairs().containsValue("tavilyApiKey")) {
                SystemSettingEntity row = new SystemSettingEntity();
                row.setSettingKey("tavilyApiKey");
                row.setSettingValue(encryptedPool);
                return row;
            }
            return null;
        });

        SystemSettingsDTO settings = service.getSettings();

        assertEquals(2, settings.getTavilyApiKeyCount());
        assertEquals("********", settings.getTavilyApiKeyMasked());
        assertEquals(null, settings.getTavilyApiKey());
    }
}
