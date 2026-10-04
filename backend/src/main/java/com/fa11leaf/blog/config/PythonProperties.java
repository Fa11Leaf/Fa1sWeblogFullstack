package com.fa11leaf.blog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Python 能力服务的连接参数，对应 application.yml 里的 app.python.*。
 *
 * @param baseUrl       服务地址。本机开发是 http://127.0.0.1:8000
 * @param internalToken 与 Python 服务共享的内部令牌，通过 X-Internal-Token 头发送
 */
@ConfigurationProperties(prefix = "app.python")
public record PythonProperties(String baseUrl, String internalToken) {

    public PythonProperties {
        // 给一个本机可用的兜底地址，避免漏配时拿到 null 而报出难以理解的 NPE。
        // 令牌不给兜底：漏配就该立刻失败，而不是用一个猜出来的值去调用。
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://127.0.0.1:8000";
        }
    }
}
