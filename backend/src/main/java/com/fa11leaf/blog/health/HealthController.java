package com.fa11leaf.blog.health;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fa11leaf.blog.common.ApiResponse;

/**
 * 健康检查接口。
 *
 * <p>P0 的验收目标就是它：一条请求能看到 Spring Boot、MySQL、Python 三者的状态。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(healthService.gather());
    }
}
