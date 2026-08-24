package vip.newsclaw.wiki.job.fallback;

import vip.newsclaw.wiki.job.WikiJobStep;
import vip.newsclaw.wiki.job.model.WikiProcessingJobEntity;

import java.util.Optional;

/**
 * RFC-030: Chain of responsibility for model fallback selection.
 */
public interface ModelFallbackHandler {

    Optional<Long> handle(WikiProcessingJobEntity job, WikiJobStep step, String errorCode);

    void setNext(ModelFallbackHandler next);
}
