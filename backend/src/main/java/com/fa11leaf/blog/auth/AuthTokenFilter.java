package com.fa11leaf.blog.auth;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 把 {@code Authorization: Bearer <token>} 换成 SecurityContext 里的登录态。
 *
 * <p>这个过滤器<b>不负责拒绝请求</b>：令牌无效时它只是不设置登录态，
 * 继续放行。真正的拦截交给 SecurityConfig 的授权规则去判断。
 * 这样"哪些路径需要登录"只有一处定义，不会散落在过滤器里。
 */
@Component
public class AuthTokenFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final AuthService authService;

    public AuthTokenFilter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(PREFIX)) {
            String rawToken = header.substring(PREFIX.length()).trim();

            authService.resolve(rawToken).ifPresent(user -> {
                var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole()));
                var authentication = new UsernamePasswordAuthenticationToken(
                        user.getUsername(), null, authorities);
                // 把用户名放进 details 之外的 principal 里，控制器可直接拿来用
                authentication.setDetails(user.getId());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }

        chain.doFilter(request, response);
    }
}
