package vip.newsclaw.news.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import vip.newsclaw.llm.model.ModelConfigEntity;
import vip.newsclaw.llm.service.ModelConfigService;
import vip.newsclaw.news.model.AiNewsModelRole;
import vip.newsclaw.news.model.AiNewsModelRoute;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * News-domain model routing policy.
 *
 * <p>Generic {@code ProviderRouter} remains responsible for skill capability
 * requirements.  This service adds the vertical responsibility split used by
 * the news workflow: cheap discovery, stronger verification, long-context
 * editorial work, multimodal visual work and deterministic/light delivery.
 * Every decision returns a snapshot that can be persisted with the task, so a
 * later review can tell which model was actually selected.</p>
 *
 * <p>Role overrides are configured as
 * {@code newsclaw.ai-news.models.<role>=provider/model} (or just a model id).
 * An invalid override never prevents a run; it is reported in {@link
 * AiNewsModelRoute#reason()} and the enabled default model is used.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiNewsModelRouter {

    private static final String PREFIX = "newsclaw.ai-news.models.";

    private final ModelConfigService modelConfigService;
    private final Environment environment;

    public AiNewsModelRoute route(AiNewsModelRole role) {
        AiNewsModelRole effectiveRole = role == null ? AiNewsModelRole.DELIVERY : role;
        String configured = environment.getProperty(PREFIX + effectiveRole.token());
        if (StringUtils.hasText(configured)) {
            ModelConfigEntity selected = resolveReference(configured.trim());
            if (selected != null) {
                return snapshot(effectiveRole, selected, true, false,
                        "role override " + configured.trim());
            }
            log.warn("AI-news model override for {} does not resolve to an enabled model: {}",
                    effectiveRole.token(), configured);
        }

        ModelConfigEntity fallback = selectFallback(effectiveRole);
        if (fallback != null) {
            String reason = StringUtils.hasText(configured)
                    ? "invalid role override; fell back to enabled model"
                    : "no role override; selected deterministic fallback";
            return snapshot(effectiveRole, fallback, false, true, reason);
        }
        return new AiNewsModelRoute(effectiveRole, null, null, null,
                false, true, "no enabled chat model is configured");
    }

    /** Resolve a provider/model reference for tests, admin previews and task metadata. */
    ModelConfigEntity resolveReference(String reference) {
        if (!StringUtils.hasText(reference)) return null;
        String value = reference.trim();
        String provider = null;
        String model = value;
        int slash = value.indexOf('/');
        int colon = value.indexOf(':');
        int separator = slash >= 0 ? slash : colon;
        if (separator > 0 && separator < value.length() - 1) {
            provider = value.substring(0, separator).trim();
            model = value.substring(separator + 1).trim();
        }
        if (provider != null) {
            return modelConfigService.findEnabledModel(provider, model);
        }
        final String modelReference = model;
        return modelConfigService.listEnabledModels().stream()
                .filter(item -> item.getModelName() != null
                        && item.getModelName().equalsIgnoreCase(modelReference))
                .findFirst()
                .orElse(null);
    }

    private ModelConfigEntity selectFallback(AiNewsModelRole role) {
        try {
            List<ModelConfigEntity> enabled = modelConfigService.listEnabledModels();
            if (enabled == null || enabled.isEmpty()) {
                return modelConfigService.getDefaultModel();
            }
            Comparator<ModelConfigEntity> stable = Comparator
                    .comparing((ModelConfigEntity item) -> Boolean.TRUE.equals(item.getIsDefault()))
                    .reversed()
                    .thenComparing(item -> item.getProvider() == null ? "" : item.getProvider())
                    .thenComparing(item -> item.getModelName() == null ? "" : item.getModelName());
            Comparator<ModelConfigEntity> comparator = switch (role) {
                case DISCOVERY, DELIVERY -> Comparator
                        .comparingInt(this::maxTokensOrLarge)
                        .thenComparing(stable);
                case EDITORIAL -> Comparator
                        .comparingInt(this::contextWindowOrSmall)
                        .reversed()
                        .thenComparing(stable);
                case VERIFICATION -> Comparator
                        .comparingInt(this::contextWindowOrSmall)
                        .reversed()
                        .thenComparing(stable);
                case VISUAL -> stable;
            };
            return enabled.stream().min(comparator).orElseGet(() -> {
                try {
                    return modelConfigService.getDefaultModel();
                } catch (Exception ignored) {
                    return null;
                }
            });
        } catch (Exception e) {
            log.debug("AI-news model fallback resolution failed: {}", e.getMessage());
            return null;
        }
    }

    private int maxTokensOrLarge(ModelConfigEntity item) {
        Integer value = item == null ? null : item.getMaxTokens();
        return value == null || value <= 0 ? Integer.MAX_VALUE : value;
    }

    private int contextWindowOrSmall(ModelConfigEntity item) {
        Integer value = item == null ? null : item.getMaxInputTokens();
        return value == null || value <= 0 ? 0 : value;
    }

    private static AiNewsModelRoute snapshot(AiNewsModelRole role, ModelConfigEntity model,
                                             boolean configured, boolean fallback, String reason) {
        return new AiNewsModelRoute(role,
                model.getProvider(), model.getModelName(), model.getId(),
                configured, fallback, reason);
    }
}
