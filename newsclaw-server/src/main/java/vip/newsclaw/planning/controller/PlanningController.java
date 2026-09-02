package vip.newsclaw.planning.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import vip.newsclaw.common.result.R;
import vip.newsclaw.agent.model.AgentEntity;
import vip.newsclaw.agent.repository.AgentMapper;
import vip.newsclaw.planning.model.PlanEntity;
import vip.newsclaw.planning.service.PlanningService;
import vip.newsclaw.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Objects;

/**
 * 任务规划接口
 *
 * @author NewsClaw Team
 */
@Tag(name = "任务规划")
@RestController
@RequestMapping("/api/v1/plans")
@RequiredArgsConstructor
public class PlanningController {

    private final PlanningService planningService;
    private final AgentMapper agentMapper;

    @Operation(summary = "获取计划列表（带 agentId 则按员工，否则跨员工取最近 N 条）")
    @GetMapping
    @RequireWorkspaceRole("viewer")
    public R<List<PlanEntity>> list(@RequestParam(required = false) String agentId,
                                    @RequestParam(required = false, defaultValue = "100") int limit,
                                    @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        long ws = workspaceId == null || workspaceId <= 0 ? 1L : workspaceId;
        if (agentId != null && !agentId.isBlank()) {
            if (!agentInWorkspace(agentId, ws)) return R.fail(403, "Agent does not belong to this workspace");
            return R.ok(planningService.listPlansByAgent(agentId));
        }
        Set<String> scopedAgents = agentMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentEntity>()
                        .eq(AgentEntity::getWorkspaceId, ws)
                        .eq(AgentEntity::getDeleted, 0))
                .stream().map(a -> String.valueOf(a.getId())).collect(Collectors.toSet());
        return R.ok(planningService.listRecentPlans(scopedAgents, limit));
    }

    @Operation(summary = "获取计划详情（含步骤）")
    @GetMapping("/{id}")
    @RequireWorkspaceRole("viewer")
    public R<PlanEntity> getPlan(@PathVariable Long id,
                                 @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        long ws = workspaceId == null || workspaceId <= 0 ? 1L : workspaceId;
        PlanEntity plan = planningService.getPlanWithSteps(id);
        if (plan == null) return R.fail(404, "Plan not found");
        if (!agentInWorkspace(plan.getAgentId(), ws)) return R.fail(403, "Plan does not belong to this workspace");
        return R.ok(plan);
    }

    private boolean agentInWorkspace(String agentId, long workspaceId) {
        try {
            AgentEntity agent = agentMapper.selectById(Long.parseLong(agentId.trim()));
            return agent != null && Objects.equals(agent.getWorkspaceId(), workspaceId);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
