package com.fa11leaf.blog.config;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * 跨域配置：只允许写作后台的来源访问接口。
 *
 * <p>刻意不使用通配符 "*"。带着凭据的请求如果允许任意来源，
 * 任何网站都能在用户浏览器里替你调接口——这是最常见的低级漏洞之一。
 * 开发期是 Vite 的 5174 端口；若将来把后台放到别处，往列表里加确切来源即可。
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration conf = new CorsConfiguration();

        // localhost 与 127.0.0.1 是浏览器眼里的两个不同来源，必须都写。
        conf.setAllowedOrigins(List.of(
                "http://localhost:5174",
                "http://127.0.0.1:5174"));

        conf.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        conf.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        conf.setAllowCredentials(true);

        // 预检请求结果缓存 1 小时，避免每个请求都先发一次 OPTIONS。
        conf.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", conf);
        return source;
    }
}
