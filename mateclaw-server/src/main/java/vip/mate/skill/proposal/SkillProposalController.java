package vip.mate.skill.proposal;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.mate.common.result.R;
import vip.mate.workspace.core.annotation.RequireWorkspaceRole;

/** Review API for proposal-first Skill evolution. */
@Tag(name = "Skill 候选变更")
@RestController
@RequestMapping("/api/v1/skills/proposals")
@RequiredArgsConstructor
public class SkillProposalController {

    private final SkillChangeProposalService proposalService;

    @Operation(summary = "分页列出 Skill 变更候选")
    @GetMapping
    @RequireWorkspaceRole("member")
    public R<IPage<SkillChangeProposalEntity>> list(
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        return R.ok(proposalService.page(workspaceId, page, size, status));
    }

    @Operation(summary = "查看 Skill 变更候选详情和 diff")
    @GetMapping("/{id}")
    @RequireWorkspaceRole("member")
    public R<SkillChangeProposalEntity> get(@PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(proposalService.get(workspaceId, id));
    }

    @Operation(summary = "批准候选 Skill 变更，可选择立即应用")
    @PostMapping("/{id}/approve")
    @RequireWorkspaceRole("admin")
    public R<SkillChangeProposalEntity> approve(@PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestBody(required = false) SkillProposalReviewRequest request) {
        return R.ok(proposalService.approve(workspaceId, id, request));
    }

    @Operation(summary = "拒绝候选 Skill 变更")
    @PostMapping("/{id}/reject")
    @RequireWorkspaceRole("admin")
    public R<SkillChangeProposalEntity> reject(@PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestBody(required = false) SkillProposalReviewRequest request) {
        return R.ok(proposalService.reject(workspaceId, id, request));
    }

    @Operation(summary = "应用已批准的候选 Skill 变更")
    @PostMapping("/{id}/apply")
    @RequireWorkspaceRole("admin")
    public R<SkillChangeProposalEntity> apply(@PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId) {
        return R.ok(proposalService.apply(workspaceId, id));
    }

    @Operation(summary = "回滚已应用的候选 Skill 变更")
    @PostMapping("/{id}/rollback")
    @RequireWorkspaceRole("admin")
    public R<SkillChangeProposalEntity> rollback(@PathVariable Long id,
            @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
            @RequestBody(required = false) SkillProposalReviewRequest request) {
        return R.ok(proposalService.rollback(workspaceId, id,
                request == null ? null : request.reviewer(), request == null ? null : request.note()));
    }
}
