package vip.newsclaw.memory.event;

import vip.newsclaw.memory.service.DreamReport;

/**
 * Published when a dream consolidation completes successfully.
 *
 * @param report the structured dream report
 * @author NewsClaw Team
 */
public record DreamCompletedEvent(DreamReport report) {}
