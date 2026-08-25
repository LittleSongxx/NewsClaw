package vip.newsclaw.news.workflow;

import vip.newsclaw.trigger.model.TriggerEntity;

import java.util.List;

/** Rendered, workspace-aware NewsClaw workflow template preview. */
public record AiNewsWorkflowTemplate(
        String templateId,
        String name,
        String description,
        String draftJson,
        List<TriggerDraft> triggerDrafts,
        List<String> missingFields,
        boolean readyForPublish
) {
    public AiNewsWorkflowTemplate {
        triggerDrafts = triggerDrafts == null ? List.of() : List.copyOf(triggerDrafts);
        missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
    }

    public record TriggerDraft(
            String stableKey,
            String name,
            String patternType,
            String patternJson,
            String payloadTemplate
    ) {
    }
}
