package com.fa11leaf.blog.health;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fa11leaf.blog.python.PythonClient;

/**
 * 汇总各依赖的连通状态，供 /api/health 使用。
 *
 * <p>它与 Spring Boot Actuator 的 /actuator/health 是两个用途：
 * Actuator 给运维探针看（只回 UP/DOWN），这里的返回是给我自己看的，
 * 要能一眼分辨"数据库没起"和"Python 没起"。
 */
@Service
public class HealthService {

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);

    /** 与 python-service 的版本号同步维护，方便一眼看出两边是否配套。 */
    private static final String SERVICE_VERSION = "0.1.0";

    private final JdbcTemplate jdbcTemplate;
    private final PythonClient pythonClient;

    public HealthService(JdbcTemplate jdbcTemplate, PythonClient pythonClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.pythonClient = pythonClient;
    }

    public Map<String, Object> gather() {
        // LinkedHashMap 保证 JSON 字段顺序稳定，肉眼对比两次输出时省事。
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("service", "weblog-backend");
        result.put("version", SERVICE_VERSION);
        result.put("database", database());
        result.put("python", pythonClient.health());
        return result;
    }

    /**
     * 顺带汇报已执行的迁移数量——它同时证明了 Flyway 真的跑过，
     * 而不只是"TCP 连得上"。
     */
    private Map<String, Object> database() {
        Map<String, Object> info = new LinkedHashMap<>();
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);

            Integer applied = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class);

            info.put("reachable", true);
            info.put("migrations", applied);
            info.put("error", null);

        } catch (DataAccessException ex) {
            log.warn("数据库探活失败: {}", ex.getMessage());
            info.put("reachable", false);
            info.put("migrations", null);
            // 只取最具体的一层原因，完整堆栈太长，不适合放进接口响应
            info.put("error", ex.getMostSpecificCause().getMessage());
        }
        return info;
    }
}
