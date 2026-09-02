package vip.newsclaw.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA 前端路由 Fallback
 * <p>
 * 将不含扩展名的 GET 请求转发到 index.html，由 Vue Router 接管客户端路由。
 * 路径段正则 {@code [^\\.]*} 排除含 "." 的路径（静态资源如 .js/.css/.ico），
 * 同时 Spring MVC 会优先匹配 @RestController 精确路由，因此不会影响 /api/** 接口。
 *
 * @author NewsClaw Team
 */
@Controller
public class SpaForwardController {

    /** Public, read-only portfolio entry. It never boots the authenticated SPA. */
    @GetMapping("/")
    public String showcaseHome() {
        return "forward:/showcase/index.html";
    }

    /** Keep an explicit bookmarkable path for static hosts and reverse proxies. */
    @GetMapping({"/showcase", "/showcase/"})
    public String showcase() {
        return "forward:/showcase/index.html";
    }

    @GetMapping(value = {
        "/{path:[^\\.]*}",
        "/{path1:[^\\.]*}/{path2:[^\\.]*}",
        "/{path1:[^\\.]*}/{path2:[^\\.]*}/{path3:[^\\.]*}"
    })
    public String forward() {
        return "forward:/index.html";
    }
}
