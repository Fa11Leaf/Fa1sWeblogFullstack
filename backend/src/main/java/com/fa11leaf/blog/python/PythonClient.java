package com.fa11leaf.blog.python;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fa11leaf.blog.common.BusinessException;
import com.fa11leaf.blog.common.ErrorCode;
import com.fa11leaf.blog.common.Json;
import com.fa11leaf.blog.config.PythonProperties;

/**
 * 对 Python 能力服务的唯一出口。
 *
 * <p>把 HTTP 细节收在这一个类里，业务代码只看到方法调用。好处是将来要加超时、
 * 重试、熔断，只改这里；也便于测试时用假实现替换。
 *
 * <p>注意：Python 服务只监听回环地址且要求 X-Internal-Token —— 它没有认证层，
 * 相当于后端的"内部函数"，绝不能暴露给浏览器。
 *
 * <p><b>两个方法的失败语义刻意不同：</b>
 * {@link #health()} 把失败当成一个有效结论返回（探活接口就是用来报告状态的）；
 * 而 {@link #render} 与 {@link #optimizeImage} 失败时抛业务异常，
 * 因为调用方是真的需要那个结果，拿不到就必须让上层知道。
 */
@Component
public class PythonClient {

    private static final Logger log = LoggerFactory.getLogger(PythonClient.class);

    /** multipart 的分隔串。只要不出现在文件内容里就行，固定值即可。 */
    private static final String BOUNDARY = "----weblogFormBoundary4f3a9c1d7e2b";

    private static final String CRLF = "\r\n";

    private final RestClient client;

    public PythonClient(RestClient.Builder builder, PythonProperties properties) {
        // Builder 由 AppConfig 提供（Boot 4 不再自动装配它，见那里的注释）。
        // 用注入而不是自己 new，是为了以后加超时、连接池时只改一个地方。
        this.client = builder
                .baseUrl(properties.baseUrl())
                .defaultHeader("X-Internal-Token",
                        properties.internalToken() == null ? "" : properties.internalToken())
                .build();

        log.info("Python 能力服务地址: {}", properties.baseUrl());
    }

    /**
     * 探测 Python 服务。
     *
     * <p>刻意不抛异常：健康检查的语义是"报告状态"，不是"失败"。
     * 服务没起来本身就是一个有效结论，调用方需要把它展示出来。
     */
    public PythonHealth health() {
        try {
            Map<String, Object> body = get("/health");
            String version = body == null ? null : String.valueOf(body.get("version"));
            return new PythonHealth(true, version, null);

        } catch (RestClientException ex) {
            log.warn("Python 服务探活失败: {}", ex.getMessage());
            return new PythonHealth(false, null, ex.getMessage());
        }
    }

    /**
     * Markdown → HTML。
     *
     * <p>放在 Python 侧而不是 Java 侧，是因为 Markdown 解析器在 Python 生态里更成熟，
     * 而且"文本处理"本来就归 Python。这样 Java 不需要引任何 Markdown 库。
     *
     * <p><b>请求体是手拼的 JSON 字符串，而不是直接传 Map。</b>这里踩过一次坑：
     * 用 {@code .body(Map.of(...))} 时请求确实发出去了，但 body 是空的，
     * FastAPI 报 {@code 422 {"loc":["body"],"msg":"Field required"}}。
     * 原因是自己 new 出来的 RestClient.Builder 只带了 Spring 的默认转换器，
     * 没有 JSON 写入器（读没问题，所以健康检查一直正常）。
     * 与其去猜该注册哪个转换器类，不如把这段只有两个字段的 JSON 直接写出来 ——
     * String 有内置转换器，行为完全确定。
     */
    public RenderResult render(String markdown) {
        try {
            Map<String, Object> body = client.post()
                    .uri("/render")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"markdown\":" + Json.string(markdown == null ? "" : markdown)
                            + ",\"with_meta\":true}")
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });

            if (body == null || body.get("html") == null) {
                throw new BusinessException(ErrorCode.PYTHON_UNAVAILABLE, "渲染服务返回了空结果");
            }

            return new RenderResult(
                    String.valueOf(body.get("html")),
                    asInt(body.get("words")),
                    asInt(body.get("reading_minutes")),
                    asInt(body.get("headings")),
                    Boolean.TRUE.equals(body.get("has_code")));

        } catch (RestClientException ex) {
            log.error("调用 Python 渲染接口失败", ex);
            throw new BusinessException(ErrorCode.PYTHON_UNAVAILABLE,
                    "渲染服务不可用，请确认 python-service 已启动：" + ex.getMessage(), ex);
        }
    }

    /**
     * 压缩图片并统一转成 WebP。
     *
     * <p><b>为什么走 multipart：</b>它是二进制的原样传输，而 JSON 里传图得先 base64，
     * 体积凭空涨三分之一 —— 图片正是这里最大的负载，不值得为省一点代码复杂度付这个代价。
     * 返回方向用 base64 是可以接受的，那时已经是压缩后的小图。
     *
     * <p><b>为什么 multipart 是手拼的：</b>用 Spring 的 MultipartBodyBuilder
     * 实测会抛 {@code NoClassDefFoundError: org/reactivestreams/Publisher} ——
     * Spring 7 写 multipart 的那条路径依赖响应式接口，而本项目是纯 Servlet 栈，
     * 没有 reactive-streams。与其为一次上传引一套响应式依赖，
     * 不如按 RFC 7578 把这几十个字节拼出来：结构固定，行为完全确定。
     * （这也是本项目第二次在"别依赖自动装配"上得到同样的结论，另一次见 {@link Json}。）
     */
    public OptimizedImage optimizeImage(byte[] bytes, String filename, int maxWidth) {
        try {
            Map<String, Object> body = client.post()
                    .uri("/images/optimize")
                    .contentType(MediaType.parseMediaType(
                            "multipart/form-data; boundary=" + BOUNDARY))
                    .body(multipartBody(bytes, filename, maxWidth))
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });

            if (body == null || body.get("data_base64") == null) {
                throw new BusinessException(ErrorCode.PYTHON_UNAVAILABLE, "图片处理服务返回了空结果");
            }

            return new OptimizedImage(
                    Base64.getDecoder().decode(String.valueOf(body.get("data_base64"))),
                    String.valueOf(body.getOrDefault("content_type", "image/webp")),
                    asInt(body.get("width")),
                    asInt(body.get("height")));

        } catch (RestClientException ex) {
            log.error("调用 Python 图片处理接口失败", ex);
            throw new BusinessException(ErrorCode.PYTHON_UNAVAILABLE,
                    "图片处理服务不可用，请确认 python-service 已启动：" + ex.getMessage(), ex);
        }
    }

    private Map<String, Object> get(String uri) {
        return client.get()
                .uri(uri)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {
                });
    }

    /**
     * 按 RFC 7578 拼出 multipart/form-data 请求体。
     *
     * <p>两个 part：文件本身（二进制原样嵌入）和一个文本字段 max_width。
     */
    private static byte[] multipartBody(byte[] fileBytes, String filename, int maxWidth) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(fileBytes.length + 512);
        try {
            out.write(("--" + BOUNDARY + CRLF
                    + "Content-Disposition: form-data; name=\"file\"; filename=\""
                    + safeFilename(filename) + "\"" + CRLF
                    + "Content-Type: application/octet-stream" + CRLF
                    + CRLF).getBytes(StandardCharsets.UTF_8));
            out.write(fileBytes);
            out.write(CRLF.getBytes(StandardCharsets.UTF_8));

            out.write(("--" + BOUNDARY + CRLF
                    + "Content-Disposition: form-data; name=\"max_width\"" + CRLF
                    + CRLF
                    + maxWidth + CRLF).getBytes(StandardCharsets.UTF_8));

            // 结束标记比普通分隔线多两个连字符
            out.write(("--" + BOUNDARY + "--" + CRLF).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            // ByteArrayOutputStream 不会真的抛 IO 异常，这里是编译器要求的兜底
            throw new IllegalStateException("拼装 multipart 请求体失败", ex);
        }
        return out.toByteArray();
    }

    /**
     * 文件名消毒。
     *
     * <p>文件名会被拼进 HTTP 头（{@code Content-Disposition}）。如果原样带进去一个
     * 换行或引号，攻击者就能伪造出额外的头 —— 这是典型的 header injection。
     * 只保留字母数字和 {@code . _ -}，其余一律换成下划线。
     */
    private static String safeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "image";
        }
        String cleaned = filename.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.length() > 120 ? cleaned.substring(cleaned.length() - 120) : cleaned;
    }

    private static int asInt(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /**
     * Python 服务的健康状态。
     *
     * @param reachable 能否连通并正确响应
     * @param version   对端版本号，不可达时为 null
     * @param error     失败原因，成功时为 null
     */
    public record PythonHealth(boolean reachable, String version, String error) {
    }

    /**
     * 渲染结果。
     *
     * @param html           正文 HTML（原文里的裸 HTML 已被转义）
     * @param words          字数
     * @param readingMinutes 预计阅读分钟数
     * @param headings       标题数量，编辑器可据此提示结构是否合理
     * @param hasCode        正文里是否出现了代码块
     */
    public record RenderResult(String html, int words, int readingMinutes, int headings,
                               boolean hasCode) {
    }

    /**
     * 处理后的图片。
     *
     * @param bytes       压缩后的文件内容
     * @param contentType 输出格式，通常为 image/webp
     * @param width       处理后宽度
     * @param height      处理后高度
     */
    public record OptimizedImage(byte[] bytes, String contentType, int width, int height) {
    }
}
