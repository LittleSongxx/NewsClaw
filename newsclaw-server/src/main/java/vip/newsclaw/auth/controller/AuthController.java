package vip.newsclaw.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import vip.newsclaw.auth.model.LoginRequest;
import vip.newsclaw.auth.model.LoginResponse;
import vip.newsclaw.auth.model.UserEntity;
import vip.newsclaw.auth.service.AuthService;
import vip.newsclaw.common.result.R;
import vip.newsclaw.exception.NewsClawException;
import vip.newsclaw.workspace.core.annotation.RequireGlobalAdmin;

import java.util.List;

/**
 * 认证接口
 *
 * @author NewsClaw Team
 */
@Tag(name = "认证管理")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public R<LoginResponse> login(@RequestBody LoginRequest request) {
        if (request == null) return R.fail(400, "login request is required");
        return R.ok(authService.login(request));
    }

    @Operation(summary = "获取用户列表")
    @GetMapping("/users")
    @RequireGlobalAdmin
    public R<List<UserEntity>> listUsers() {
        return R.ok(authService.listUsers());
    }

    @Operation(summary = "创建用户")
    @PostMapping("/users")
    @RequireGlobalAdmin
    public R<UserEntity> createUser(@RequestBody UserEntity user) {
        if (user == null) return R.fail(400, "user is required");
        return R.ok(authService.createUser(user));
    }

    @Operation(summary = "修改密码")
    @PutMapping("/users/{id}/password")
    public R<Void> changePassword(
            @PathVariable Long id,
            @RequestParam String oldPassword,
            @RequestParam String newPassword,
            Authentication auth) {
        // Resolve user from the JWT principal — the {id} path segment is
        // informational. A user may only change their own password.
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            throw new NewsClawException("err.auth.unauthenticated", 401, "Authentication required");
        }
        UserEntity me = authService.findByUsername(auth.getName());
        if (me == null) {
            throw new NewsClawException("err.auth.user_not_found", "用户不存在");
        }
        authService.changePassword(me.getId(), oldPassword, newPassword);
        return R.ok();
    }
}
