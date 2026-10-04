package com.fa11leaf.blog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 后台账号与会话配置。
 *
 * @param username      登录名
 * @param password      初始密码；为空时由 AdminSeeder 生成一次性随机口令并打日志提示
 * @param displayName   显示名
 * @param tokenTtlHours 登录令牌有效期（小时）
 */
@ConfigurationProperties(prefix = "app.admin")
public record AdminProperties(
        String username,
        String password,
        String displayName,
        int tokenTtlHours) {

    /** 是否已由使用者显式配置了密码。 */
    public boolean hasConfiguredPassword() {
        return password != null && !password.isBlank();
    }
}
