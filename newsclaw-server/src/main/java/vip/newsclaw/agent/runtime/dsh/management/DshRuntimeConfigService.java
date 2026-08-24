package vip.newsclaw.agent.runtime.dsh.management;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import vip.newsclaw.system.service.SystemSettingService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads the current DSH configuration without requiring a backend restart. */
@Service
public class DshRuntimeConfigService {
    private static final String[] MANAGED_KEYS = {
            "dsh.executable_path", "dsh.cordis_config_path", "dsh.working_directory",
            "dsh.base_url", "dsh.model_name", SystemSettingService.DSH_API_KEY_KEY
    };

    private final SystemSettingService settings;
    private final Map<String, String> properties;

    public DshRuntimeConfigService(
            SystemSettingService settings,
            @Value("${newsclaw.agent.runtime.dsh.command:}") String command,
            @Value("${newsclaw.agent.runtime.dsh.cordis-config:}") String cordisConfig,
            @Value("${newsclaw.agent.runtime.dsh.working-directory:}") String workingDirectory,
            @Value("${newsclaw.agent.runtime.dsh.base-url:}") String baseUrl,
            @Value("${newsclaw.agent.runtime.dsh.model-name:}") String modelName,
            @Value("${newsclaw.agent.runtime.dsh.api-key:}") String apiKey) {
        this.settings = settings;
        this.properties = Map.of(
                "newsclaw.agent.runtime.dsh.command", command,
                "newsclaw.agent.runtime.dsh.cordis-config", cordisConfig,
                "newsclaw.agent.runtime.dsh.working-directory", workingDirectory,
                "newsclaw.agent.runtime.dsh.base-url", baseUrl,
                "newsclaw.agent.runtime.dsh.model-name", modelName,
                "newsclaw.agent.runtime.dsh.api-key", apiKey);
    }

    public DshRuntimeConfiguration resolve() {
        Map<String, String> managed = new LinkedHashMap<>();
        for (String key : MANAGED_KEYS) {
            String defaultValue = key.equals("dsh.working_directory") ? "" : null;
            managed.put(key, settings.getString(key, defaultValue));
        }
        DshRuntimeConfiguration resolved = DshRuntimeConfigResolver.resolve(managed, properties, System.getenv());
        String workingDirectory = resolved.workingDirectory();
        if (workingDirectory == null || workingDirectory.isBlank()) workingDirectory = System.getProperty("user.dir");
        String cordisConfig = normalizeCordisConfig(resolved.cordisConfigPath());
        if (cordisConfig.isBlank()) cordisConfig = discoverCordisConfig(resolved.executablePath());
        return new DshRuntimeConfiguration(resolved.executablePath(), cordisConfig, workingDirectory,
                resolved.baseUrl(), resolved.modelName(), resolved.apiKey());
    }

    private String discoverCordisConfig(String executable) {
        if (executable == null || executable.isBlank()) return "";
        Path binary = Path.of(executable.split("\\s+")[0]).toAbsolutePath().normalize();
        Path packageRoot = binary.getParent();
        if (packageRoot == null) return "";
        Path[] candidates = {
                packageRoot.resolve("runtime/cordis.yml"),
                packageRoot.resolve("../runtime/cordis.yml").normalize(),
                packageRoot.resolve("../examples/jsonrpc-agent/cordis.yml").normalize(),
                packageRoot.resolve("../../examples/jsonrpc-agent/cordis.yml").normalize()
        };
        for (Path candidate : candidates) if (Files.isRegularFile(candidate)) return candidate.toString();
        return "";
    }

    private String normalizeCordisConfig(String configured) {
        if (configured == null || configured.isBlank()) return "";
        Path path = Path.of(configured).toAbsolutePath().normalize();
        if (Files.isRegularFile(path)) return path.toString();
        Path packageDirectory = Files.isDirectory(path) ? path : path.getParent();
        if (packageDirectory == null) return path.toString();
        Path packagedConfig = packageDirectory.resolve("runtime/cordis.yml");
        return Files.isRegularFile(packagedConfig) ? packagedConfig.toString() : path.toString();
    }

    public Map<String, String> managedValues() {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : MANAGED_KEYS) {
            String value = settings.getString(key, "");
            if (SystemSettingService.DSH_API_KEY_KEY.equals(key)) {
                values.put(key, settings.maskSecret(value));
            } else {
                values.put(key, value == null ? "" : value);
            }
        }
        return values;
    }

    public void save(Map<String, String> values) {
        if (values == null) return;
        save(values, "dsh.executable_path");
        save(values, "dsh.cordis_config_path");
        save(values, "dsh.working_directory");
        save(values, "dsh.base_url");
        save(values, "dsh.model_name");
        String apiKey = values.get(SystemSettingService.DSH_API_KEY_KEY);
        if (apiKey != null && !apiKey.isBlank() && !apiKey.startsWith("****")) {
            settings.saveString(SystemSettingService.DSH_API_KEY_KEY, apiKey.trim(), "DeepSeek API key for DSH");
        }
    }

    private void save(Map<String, String> values, String key) {
        if (values.containsKey(key)) {
            settings.saveString(key, values.get(key), "Managed DeepSeek Harness runtime setting");
        }
    }
}
