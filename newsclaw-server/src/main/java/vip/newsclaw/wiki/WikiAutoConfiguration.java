package vip.newsclaw.wiki;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import vip.newsclaw.wiki.job.WikiProcessingJobService;
import vip.newsclaw.wiki.service.WikiRawMaterialService;

/**
 * Wiki module auto-configuration
 *
 * @author NewsClaw Team
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(WikiProperties.class)
@RequiredArgsConstructor
public class WikiAutoConfiguration {

    private final WikiProcessingJobService wikiProcessingJobService;
    private final WikiRawMaterialService wikiRawMaterialService;

    /**
     * Recover stuck wiki state on startup:
     * 1. Job table: routing/*_running → queued (RFC-030)
     * 2. Raw material table: processing → pending (avoids forever-spinning progress bars)
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverWikiJobs(ApplicationReadyEvent event) {
        wikiProcessingJobService.recoverOnStartup();
        int recovered = wikiRawMaterialService.recoverStuckRawMaterialsOnStartup();
        if (recovered > 0) {
            log.info("[Wiki] Recovered {} stuck raw materials on startup", recovered);
        }
    }
}
