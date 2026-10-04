package com.fa11leaf.blog.health;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /api/health 的接口契约测试。
 *
 * <p>用 MockMvc 而不是起真实服务器：MockMvc 直接把请求交给 DispatcherServlet，
 * 不绑定端口。这样在受限环境里也能验证"路由 + 安全规则 + 响应形状"三件事，
 * 而这三点恰恰是只有真正发出请求才会暴露的。
 */
@SpringBootTest
@AutoConfigureMockMvc
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    /** 健康检查必须免认证可访问，否则容器探针和启动脚本都用不了。 */
    @Test
    void healthIsPublicAndReportsDependencies() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.service").value("weblog-backend"))
                .andExpect(jsonPath("$.data.version").exists())
                // 本机 MySQL 已在运行，这一项应为 true；顺便验证了 JDBC 真的通
                .andExpect(jsonPath("$.data.database.reachable").value(true))
                .andExpect(jsonPath("$.data.database.migrations").isNumber())
                // Python 服务不一定在跑，只断言字段存在、不绑定具体取值
                .andExpect(jsonPath("$.data.python.reachable").exists());
    }

    /** 除健康检查外的路径都要认证。只断言"不是 200"，避免绑死 401 还是 403。 */
    @Test
    void otherPathsAreNotPubliclyAccessible() throws Exception {
        mockMvc.perform(get("/api/not-implemented-yet"))
                .andExpect(status().is4xxClientError());
    }

    /** Actuator 的健康端点也放行了，供运维探针使用。 */
    @Test
    void actuatorHealthIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists());
    }
}
