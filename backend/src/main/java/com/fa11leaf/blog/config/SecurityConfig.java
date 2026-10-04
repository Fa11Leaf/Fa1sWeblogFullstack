package com.fa11leaf.blog.config;

import java.io.IOException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.fa11leaf.blog.auth.AuthTokenFilter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 安全配置。
 *
 * <p>默认拒绝：只有下面显式放行的路径能匿名访问，其余一律要求登录。
 * 这样后续每加一个接口，默认就是受保护的，不会因为忘记配置而裸奔 ——
 * 这比"先全部放开、以后再收紧"安全得多。
 */
@Configuration
public class SecurityConfig {

    private final AuthTokenFilter authTokenFilter;

    public SecurityConfig(AuthTokenFilter authTokenFilter) {
        this.authTokenFilter = authTokenFilter;
    }

    @Bean
    public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        http
                // 纯无状态 JSON 接口，不用 Cookie 会话，因此没有 CSRF 攻击面。
                // 若将来改成 Cookie 认证，这一行必须去掉，否则会被 CSRF 打穿。
                .csrf(AbstractHttpConfigurer::disable)

                // 跨域规则在 CorsConfig 里定义，这里只负责把 CORS 过滤器接进链子。
                .cors(Customizer.withDefaults())

                // 不创建也不使用 HttpSession。
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // 不需要浏览器弹出的 Basic 认证框和表单登录页，关掉以保持响应形状统一。
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)

                .authorizeHttpRequests(auth -> auth
                        // 健康检查要能在未登录时探测，否则容器/监控探针用不了。
                        .requestMatchers("/api/health", "/actuator/health").permitAll()
                        // 登录接口本身当然不能要求登录。
                        // 这里限定 POST：避免将来有人加个 GET /api/auth/login 视图把它带出去。
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .anyRequest().authenticated())

                // 未登录 / 权限不足也要返回和业务接口一致的三层 JSON。
                // 否则前面所有接口都规规矩矩返回 {code,message,data}，
                // 偏偏这里蹦出一段 Spring 默认的 HTML 或空响应体，前端得写两套解析逻辑。
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(SecurityConfig::writeUnauthorized)
                        .accessDeniedHandler(SecurityConfig::writeForbidden))

                // 令牌过滤器要早于用户名口令过滤器执行；我们不用表单登录，
                // 放在它前面只是取一个"足够早"的位置。
                .addFilterBefore(authTokenFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /** 401：未登录或令牌失效。错误码与 ErrorCode.UNAUTHORIZED 保持一致。 */
    private static void writeUnauthorized(HttpServletRequest request,
                                          HttpServletResponse response,
                                          org.springframework.security.core.AuthenticationException ex)
            throws IOException {

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"code\":40101,\"message\":\"未登录或登录已过期\",\"data\":null}");
    }

    /** 403：已登录但权限不足。 */
    private static void writeForbidden(HttpServletRequest request,
                                       HttpServletResponse response,
                                       org.springframework.security.access.AccessDeniedException ex)
            throws IOException {

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"code\":40301,\"message\":\"已登录但权限不足\",\"data\":null}");
    }
}
