package com.fa11leaf.blog.auth;

import java.time.LocalDateTime;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fa11leaf.blog.common.ApiResponse;
import com.fa11leaf.blog.common.BusinessException;
import com.fa11leaf.blog.common.ErrorCode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录相关接口。
 *
 * <p>整个后台只有三类接口不需要登录：{@code /api/auth/login}、{@code /api/health}、
 * {@code /actuator/health}。其余一律要求携带 Bearer 令牌。
 *
 * <p>前端拿到的令牌放在 localStorage 里，每次请求由 axios 拦截器加上
 * {@code Authorization} 头。因为没有用 Cookie，所以不存在 CSRF 攻击面，
 * 后端的 CSRF 保护是关闭的（见 SecurityConfig 的说明）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String PREFIX = "Bearer ";

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    public record LoginRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "口令不能为空") String password) {
    }

    /** 对外暴露的用户信息：刻意不含 passwordHash，也不含任何令牌。 */
    public record UserView(Long id, String username, String displayName, String role) {
        static UserView of(User user) {
            return new UserView(user.getId(), user.getUsername(),
                    user.getDisplayName(), user.getRole());
        }
    }

    /** 令牌原文只在登录成功这一次返回。 */
    public record LoginResponse(String token, LocalDateTime expiresAt, UserView user) {
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthService.Session session = authService.login(request.username(), request.password());
        return ApiResponse.ok(new LoginResponse(
                session.token(), session.expiresAt(), UserView.of(session.user())));
    }

    /**
     * 用当前令牌换回用户信息。
     *
     * <p>前端刷新页面后靠它判断"这个令牌还有效吗"。让接口自己判断而不是靠 401 —— 这样
     * 未登录时得到的是一个明确的业务码，前端不用去解析 HTTP 状态码。
     */
    @GetMapping("/me")
    public ApiResponse<UserView> me(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                                    String authorization) {
        return authService.resolve(extractToken(authorization))
                .map(user -> ApiResponse.ok(UserView.of(user)))
                // 这里刻意不回 401：前端在启动阶段调用它，未登录是完全正常的状态
                .orElseGet(() -> ApiResponse.ok(null));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                                    String authorization) {
        authService.logout(extractToken(authorization));
        return ApiResponse.ok(null);
    }

    public record ChangePasswordRequest(
            @NotBlank(message = "当前口令不能为空") String currentPassword,
            @NotBlank(message = "新口令不能为空")
            @Size(min = 8, max = 100, message = "新口令长度需在 8～100 之间") String newPassword) {
    }

    /**
     * 修改口令。
     *
     * <p>用 Authentication 取当前用户名——它的值由 AuthTokenFilter 从令牌里解出来。
     * 这个接口在安全配置里要求登录，所以这里一定不是匿名身份。
     */
    @PostMapping("/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                           Authentication authentication) {
        authService.changePassword(authentication.getName(),
                request.currentPassword(), request.newPassword());
        // 令牌已被全部作废，前端必须重新登录，因此这里不回新令牌
        return ApiResponse.ok(null);
    }

    private static String extractToken(String authorization) {
        if (authorization == null || !authorization.startsWith(PREFIX)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "缺少 Authorization: Bearer 头");
        }
        return authorization.substring(PREFIX.length()).trim();
    }
}
