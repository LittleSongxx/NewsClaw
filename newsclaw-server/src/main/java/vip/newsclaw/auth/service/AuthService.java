package vip.newsclaw.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import vip.newsclaw.auth.model.LoginRequest;
import vip.newsclaw.auth.model.LoginResponse;
import vip.newsclaw.auth.model.UserEntity;
import vip.newsclaw.auth.repository.UserMapper;
import vip.newsclaw.exception.NewsClawException;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/**
 * 认证服务（JWT）
 *
 * @author NewsClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    public static final String BOOTSTRAP_USERNAME = "admin";
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    @Value("${newsclaw.jwt.secret:}")
    private String jwtSecret;

    @Value("${newsclaw.jwt.expiration:86400000}")
    private long jwtExpiration;

    @Value("${newsclaw.bootstrap.password:}")
    private String bootstrapPassword;

    @Value("${newsclaw.jwt.renewal-threshold:7200000}")
    private long renewalThreshold;

    /**
     * 登录
     */
    public LoginResponse login(LoginRequest request) {
        if (request == null || request.getUsername() == null || request.getPassword() == null) {
            throw new NewsClawException("err.auth.invalid_credentials", 401, "用户名或密码错误");
        }
        UserEntity user = userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, request.getUsername())
                .eq(UserEntity::getEnabled, true));

        if (user == null || user.getPassword() == null
                || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new NewsClawException("err.auth.invalid_credentials", 401, "用户名或密码错误");
        }

        String token = generateToken(user);
        return new LoginResponse(user.getId(), token, user.getUsername(), user.getNickname(),
                user.getRole(), mustChangePassword(user));
    }

    /**
     * 获取用户列表（管理员）
     */
    public List<UserEntity> listUsers() {
        return userMapper.selectList(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getEnabled, true));
    }

    /**
     * 创建用户
     */
    public UserEntity createUser(UserEntity user) {
        // 检查用户名是否已存在
        Long count = userMapper.selectCount(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, user.getUsername()));
        if (count > 0) {
            throw new NewsClawException("err.auth.username_exists", "用户名已存在: " + user.getUsername());
        }
        if (user.getPassword() == null || user.getPassword().isBlank()) {
            throw new NewsClawException("err.auth.password_required", "Password is required");
        }
        validatePassword(user, user.getPassword());
        user.setPassword(passwordEncoder.encode(user.getPassword().trim()));
        user.setEnabled(true);
        if (user.getRole() == null) {
            user.setRole("user");
        }
        userMapper.insert(user);
        user.setPassword(null);
        return user;
    }

    /**
     * Reset password (admin operation — no old password required).
     * Used when an admin wants to set/reset a member's password.
     */
    public void resetPassword(Long userId, String newPassword) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw new NewsClawException("err.auth.user_not_found", "用户不存在");
        }
        validatePassword(user, newPassword);
        user.setPassword(passwordEncoder.encode(newPassword.trim()));
        userMapper.updateById(user);
    }

    /**
     * 修改密码
     */
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        verifyCurrentUserPassword(userId, oldPassword);
        UserEntity user = userMapper.selectById(userId);
        validatePassword(user, newPassword);
        user.setPassword(passwordEncoder.encode(newPassword.trim()));
        userMapper.updateById(user);
    }

    public boolean mustChangePassword(String username) {
        return mustChangePassword(findByUsername(username));
    }

    public boolean mustChangePassword(UserEntity user) {
        return user != null && BOOTSTRAP_USERNAME.equals(user.getUsername())
                && user.getPassword() != null && bootstrapPassword != null
                && !bootstrapPassword.isBlank()
                && passwordEncoder.matches(bootstrapPassword, user.getPassword());
    }

    private void validatePassword(UserEntity user, String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new NewsClawException("err.auth.password_required", 400, "Password is required");
        }
        String password = rawPassword.trim();
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new NewsClawException("err.auth.password_too_short", 400,
                    "Password must contain at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (user != null && BOOTSTRAP_USERNAME.equals(user.getUsername())
                && bootstrapPassword != null && !bootstrapPassword.isBlank()
                && bootstrapPassword.equals(password)) {
            throw new NewsClawException("err.auth.bootstrap_password_forbidden", 400,
                    "The bootstrap administrator password must be changed");
        }
    }

    /**
     * Step-up authentication: confirms that {@code rawPassword} matches the
     * user's currently stored password without changing anything.
     * <p>
     * Used by sensitive operations that require re-confirmation of identity
     * (e.g. creating a workspace-wide all-tool auto-approve grant). Throws
     * the same {@link NewsClawException} keys as {@link #changePassword} so
     * the user-facing error message stays consistent.
     *
     * @throws NewsClawException {@code err.auth.user_not_found} when the user
     *         doesn't exist, or {@code err.auth.wrong_password} when the
     *         password doesn't match.
     */
    public void verifyCurrentUserPassword(Long userId, String rawPassword) {
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            // 404: target user no longer exists; surfacing as 401 would mask the cause.
            throw new NewsClawException("err.auth.user_not_found", 404, "用户不存在");
        }
        if (rawPassword == null || !passwordEncoder.matches(rawPassword, user.getPassword())) {
            // 403, not 401: 401 would trigger the global http interceptor's
            // handleAuthFailure() and log the user out, but this is a step-up
            // re-confirmation (token is still valid). Falling through to the
            // default 500 looks like a server fault on the client; 403 cleanly
            // communicates "valid session, wrong second-factor".
            throw new NewsClawException("err.auth.wrong_password", 403, "原密码错误");
        }
    }

    /**
     * 解析 Token 获取用户名
     */
    public String parseToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(getSignKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return claims.getSubject();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析 Token 获取完整 Claims（含过期时间）
     */
    public Claims parseClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(getSignKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 判断 Token 是否接近过期（剩余有效期 < renewalThreshold）
     */
    public boolean isNearExpiry(Claims claims) {
        if (claims == null || claims.getExpiration() == null) {
            return false;
        }
        long remaining = claims.getExpiration().getTime() - System.currentTimeMillis();
        return remaining > 0 && remaining < renewalThreshold;
    }

    /**
     * 根据用户名续签 Token
     */
    public String renewToken(String username) {
        UserEntity user = findByUsername(username);
        if (user != null && Boolean.TRUE.equals(user.getEnabled())) {
            return generateToken(user);
        }
        return null;
    }

    /**
     * 根据用户名查询用户
     */
    public UserEntity findByUsername(String username) {
        return userMapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, username));
    }

    /**
     * 根据 ID 查询用户
     */
    public UserEntity findById(Long userId) {
        return userMapper.selectById(userId);
    }

    /**
     * 生成 JWT token。SSO 登录路径复用此方法签发格式一致的 token。
     */
    public String generateToken(UserEntity user) {
        return Jwts.builder()
                .subject(user.getUsername())
                .claim("userId", user.getId())
                .claim("role", user.getRole())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + jwtExpiration))
                .signWith(getSignKey())
                .compact();
    }

    private SecretKey getSignKey() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException("JWT secret is not configured");
        }
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        // 确保密钥长度至少 32 字节（HMAC-SHA256）
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
            keyBytes = padded;
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
