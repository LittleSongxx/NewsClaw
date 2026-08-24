package vip.newsclaw.auth.model;

import lombok.Data;

/**
 * 登录请求
 *
 * @author NewsClaw Team
 */
@Data
public class LoginRequest {
    private String username;
    private String password;
}
