package vip.newsclaw.llm.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.newsclaw.config.EnvironmentConfig;
import vip.newsclaw.llm.model.ModelConfigEntity;
import vip.newsclaw.llm.model.ModelProviderEntity;
import vip.newsclaw.llm.service.ModelConfigService;
import vip.newsclaw.llm.service.ModelProviderService;

import java.util.List;

/**
 * Materializes the model chain declared in .env after Flyway and the regular
 * database seed have completed.
 *
 * <p>Provider credentials remain environment-only.  Only a missing model
 * catalog row (and, when necessary, its enabled flag) is persisted so a newly
 * released model can be selected on every restart without an admin UI click.
 * The runtime resolver still reads the environment on every model build, so
 * changing the chain does not require writing a secret or relying on stale DB
 * default flags.</p>
 */
@Slf4j
@Component
@Order(110)
@RequiredArgsConstructor
public class EnvironmentModelSelectionRunner implements ApplicationRunner {

    private final ModelConfigService modelConfigService;
    private final ModelProviderService modelProviderService;

    @Override
    public void run(ApplicationArguments args) {
        List<EnvironmentConfig.ModelSelection> chain = EnvironmentConfig.configuredModelChain();
        if (chain.isEmpty()) {
            return;
        }

        for (EnvironmentConfig.ModelSelection selection : chain) {
            try {
                ModelProviderEntity provider = modelProviderService.getProviderConfig(selection.providerId());
                if (!Boolean.TRUE.equals(provider.getEnabled())) {
                    modelProviderService.setEnabled(selection.providerId(), true);
                }
                ModelConfigEntity model = modelConfigService.ensureEnvironmentModel(
                        selection.providerId(), selection.modelName());
                if (model == null) {
                    log.warn("[ModelConfig] skipped environment model {}/{}: model row could not be created",
                            selection.providerId(), selection.modelName());
                }
            } catch (Exception e) {
                // A provider may not exist in an older database or its seed may
                // still be running. Keep the server available and let the
                // database/UI fallback handle the request until the next boot.
                log.warn("[ModelConfig] skipped environment model {}/{}: {}",
                        selection.providerId(), selection.modelName(), e.getMessage());
            }
        }
        log.info("[ModelConfig] environment model chain loaded: {} entries", chain.size());
    }
}
