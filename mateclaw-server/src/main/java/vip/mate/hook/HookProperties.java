package vip.mate.hook;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Hook 系统全局配置（RFC-017）。
 *
 * <pre>
 * mateclaw:
 *   hooks:
 *     enabled: true
 *     global-rate-limit: 200       # 全局每秒最大触发数
 *     global-concurrency: 32       # Semaphore 上限
 *     dispatch-deadline: 5s        # 单事件派发总预算
 *     trusted-domains:             # HttpAction 允许调用的域名（精确或后缀匹配）
 *       - example.com
 *     http:
 *       connect-timeout: 2s
 *       read-timeout: 3s
 *     audit:
 *       enabled: true              # 每次派发写 mate_hook_run
 *       retain-days: 7
 * </pre>
 */
@ConfigurationProperties(prefix = "mateclaw.hooks")
public class HookProperties {

    private boolean enabled = true;
    private int globalRateLimit = 200;
    private int globalConcurrency = 32;
    private Duration dispatchDeadline = Duration.ofSeconds(5);
    private List<String> trustedDomains = new ArrayList<>();
    private final Http http = new Http();
    private final Audit audit = new Audit();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getGlobalRateLimit() { return globalRateLimit; }
    public void setGlobalRateLimit(int v) { this.globalRateLimit = v; }

    public int getGlobalConcurrency() { return globalConcurrency; }
    public void setGlobalConcurrency(int v) { this.globalConcurrency = v; }

    public Duration getDispatchDeadline() { return dispatchDeadline; }
    public void setDispatchDeadline(Duration v) { this.dispatchDeadline = v; }

    public List<String> getTrustedDomains() { return trustedDomains; }
    public void setTrustedDomains(List<String> trustedDomains) { this.trustedDomains = trustedDomains; }

    public Http getHttp() { return http; }
    public Audit getAudit() { return audit; }

    public static class Http {
        private Duration connectTimeout = Duration.ofSeconds(2);
        private Duration readTimeout = Duration.ofSeconds(3);
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration v) { this.connectTimeout = v; }
        public Duration getReadTimeout() { return readTimeout; }
        public void setReadTimeout(Duration v) { this.readTimeout = v; }
    }

    public static class Audit {
        private boolean enabled = true;
        private int retainDays = 7;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public int getRetainDays() { return retainDays; }
        public void setRetainDays(int v) { this.retainDays = v; }
    }
}
