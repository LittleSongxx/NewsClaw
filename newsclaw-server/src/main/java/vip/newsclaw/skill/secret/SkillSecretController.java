package vip.newsclaw.skill.secret;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.Authentication;
import vip.newsclaw.common.result.R;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.skill.model.SkillEntity;
import vip.newsclaw.skill.service.SkillService;
import vip.newsclaw.workspace.core.annotation.RequireWorkspaceRole;

import java.util.List;
import java.util.Map;

/**
 * RFC-091 settings bridge — admin REST endpoints for managing
 * per-skill secrets independently of the wizard.
 *
 * <p>Lets users edit / delete / re-set credentials after a skill is
 * already installed (e.g. when an API key rotates) without having to
 * tear the skill down and re-run the wizard.
 *
 * <p>Listing returns masked previews only — full plaintext is never
 * shipped over HTTP, even to authenticated callers.
 */
@Tag(name = "Skill Secrets")
@RestController
@RequestMapping("/api/v1/skills/{skillId}/secrets")
@RequiredArgsConstructor
public class SkillSecretController {

    private final SkillSecretService skillSecretService;
    private final SkillService skillService;

    @Operation(summary = "List secret keys + masked previews for a skill")
    @GetMapping
    @RequireWorkspaceRole("admin")
    public R<List<SkillSecretService.SecretSummary>> list(@PathVariable Long skillId,
                                                           @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
                                                           Authentication authentication) {
        verifySkillWorkspace(skillId, workspaceId, authentication);
        return R.ok(skillSecretService.listSummaries(skillId));
    }

    @Operation(summary = "Upsert a secret value (empty value deletes it)")
    @PostMapping
    @RequireWorkspaceRole("admin")
    public R<Void> put(@PathVariable Long skillId, @RequestBody Map<String, String> body,
                       @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
                       Authentication authentication) {
        verifySkillWorkspace(skillId, workspaceId, authentication);
        if (body == null) return R.fail(400, "request body is required");
        skillSecretService.put(skillId, body.get("key"), body.get("value"));
        return R.ok();
    }

    @Operation(summary = "Delete a single secret by key")
    @DeleteMapping("/{key}")
    @RequireWorkspaceRole("admin")
    public R<Void> remove(@PathVariable Long skillId, @PathVariable String key,
                          @RequestHeader(value = "X-Workspace-Id", required = false) Long workspaceId,
                          Authentication authentication) {
        verifySkillWorkspace(skillId, workspaceId, authentication);
        skillSecretService.remove(skillId, key);
        return R.ok();
    }

    private void verifySkillWorkspace(Long skillId, Long workspaceId, Authentication authentication) {
        SkillEntity skill = skillService.getSkill(skillId);
        if (skill == null) {
            throw new NewsClawException("err.skill.not_found", 404, "Skill not found");
        }
        long ws = workspaceId == null || workspaceId <= 0 ? SkillService.DEFAULT_WORKSPACE_ID : workspaceId;
        boolean globalAdmin = authentication != null && authentication.getAuthorities() != null
                && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        if (Boolean.TRUE.equals(skill.getBuiltin()) && !globalAdmin) {
            throw new NewsClawException("err.skill.global_secret_admin", 403,
                    "Builtin skill secrets require a global administrator");
        }
        if (!Boolean.TRUE.equals(skill.getBuiltin())
                && (skill.getWorkspaceId() == null || !skill.getWorkspaceId().equals(ws))) {
            throw new NewsClawException("err.common.wrong_workspace", 403,
                    "Skill does not belong to this workspace");
        }
    }
}
