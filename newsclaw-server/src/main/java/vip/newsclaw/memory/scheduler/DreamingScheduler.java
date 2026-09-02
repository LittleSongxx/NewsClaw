package vip.newsclaw.memory.scheduler;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import vip.newsclaw.agent.AgentService;
import vip.newsclaw.agent.model.AgentEntity;
import vip.newsclaw.memory.MemoryProperties;
import vip.newsclaw.memory.service.MemoryEmergenceService;
import vip.newsclaw.workspace.document.WorkspaceFileService;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Dreaming 定时调度器
 * <p>
 * 按配置的 cron 表达式定期执行记忆整合，
 * 遍历所有启用的 Agent，对每个 Agent 执行评分驱动的 emergence。
 *
 * @author NewsClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DreamingScheduler {

    private final AgentService agentService;
    private final MemoryEmergenceService emergenceService;
    private final MemoryProperties properties;
    private final WorkspaceFileService workspaceFileService;

    /** 上次 dreaming 执行时间（供状态 API 读取） */
    @Getter
    private volatile LocalDateTime lastRunTime;

    @Scheduled(cron = "${newsclaw.memory.dreaming-cron:0 0 3 * * ?}")
    public void runDreaming() {
        if (!properties.isDreamingEnabled()) {
            log.debug("[Dreaming] Scheduled dreaming is disabled, skipping");
            return;
        }

        log.info("[Dreaming] Starting scheduled dreaming cycle");
        List<AgentEntity> agents = agentService.listAgents();

        int success = 0;
        int failed = 0;

        for (AgentEntity agent : agents) {
            if (!Boolean.TRUE.equals(agent.getEnabled())) {
                continue;
            }
            try {
                emergenceService.consolidate(agent.getId(), vip.newsclaw.memory.service.DreamMode.NIGHTLY, null);
                success++;
                long ownerLimit = properties.getStructuredConsolidationMaxOwnersPerRun() > 0
                        ? properties.getStructuredConsolidationMaxOwnersPerRun() : Long.MAX_VALUE;
                workspaceFileService.listPersonalFiles(agent.getId()).stream()
                        .map(file -> file.getOwnerKey())
                        .filter(java.util.Objects::nonNull)
                        .filter(owner -> !owner.isBlank())
                        .distinct()
                        .limit(ownerLimit)
                        .forEach(owner -> emergenceService.consolidate(
                                agent.getId(), vip.newsclaw.memory.service.DreamMode.NIGHTLY, null, owner));
            } catch (Exception e) {
                failed++;
                log.warn("[Dreaming] Failed for agent={} ({}): {}",
                        agent.getId(), agent.getName(), e.getMessage());
            }
        }

        lastRunTime = LocalDateTime.now();
        log.info("[Dreaming] Cycle completed: {} succeeded, {} failed", success, failed);
    }
}
