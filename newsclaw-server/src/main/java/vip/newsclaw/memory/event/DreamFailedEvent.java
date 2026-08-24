package vip.newsclaw.memory.event;

import vip.newsclaw.memory.service.DreamReport;

/**
 * Published when a dream consolidation fails.
 *
 * @param report the structured dream report (status=FAILED)
 * @author NewsClaw Team
 */
public record DreamFailedEvent(DreamReport report) {}
