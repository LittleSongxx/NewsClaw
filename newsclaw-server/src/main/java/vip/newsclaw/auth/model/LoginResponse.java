package vip.newsclaw.auth.model;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 登录响应
 *
 * @author NewsClaw Team
 */
@Data
@AllArgsConstructor
public class LoginResponse {
    private Long id;
    private String token;
    private String username;
    private String nickname;
    private String role;
}
