package vip.newsclaw.wiki.job.strategy;

import vip.newsclaw.wiki.job.WikiJobStep;
import vip.newsclaw.wiki.job.model.WikiProcessingJobEntity;
import vip.newsclaw.wiki.model.WikiKnowledgeBaseEntity;

/**
 * RFC-030: Strategy for selecting a model for a specific processing step.
 * Implementations are evaluated in @Order priority; first non-null result wins.
 */
public interface WikiStepModelStrategy {

    boolean supports(WikiJobStep step);

    /**
     * Select a model ID for the given job and step.
     *
     * @return model ID, or null to defer to the next strategy
     */
    Long selectModelId(WikiProcessingJobEntity job, WikiKnowledgeBaseEntity kb, WikiJobStep step);
}
