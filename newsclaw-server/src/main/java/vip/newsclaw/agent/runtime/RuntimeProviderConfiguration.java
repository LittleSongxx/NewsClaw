package vip.newsclaw.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import vip.newsclaw.agent.runtime.contract.AgentRuntimeCoordinator;
import vip.newsclaw.agent.runtime.contract.AgentRuntimeProvider;
import vip.newsclaw.agent.runtime.contract.RuntimeProviderRegistry;

import java.util.List;

/** Spring wiring for the runtime SPI. Native execution remains owned by AgentService. */
@Configuration
public class RuntimeProviderConfiguration {
    @Bean
    RuntimeProviderRegistry runtimeProviderRegistry(List<AgentRuntimeProvider> providers) {
        return new RuntimeProviderRegistry(providers);
    }

    @Bean
    AgentRuntimeCoordinator agentRuntimeCoordinator(RuntimeProviderRegistry registry,
                                                    ObjectMapper objectMapper) {
        return new AgentRuntimeCoordinator(registry, objectMapper);
    }
}
