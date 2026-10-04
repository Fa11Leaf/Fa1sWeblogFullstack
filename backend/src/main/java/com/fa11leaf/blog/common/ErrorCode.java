package com.fa11leaf.blog.common;

/**
 * 业务错误码。
 *
 * <p>为什么不用 HTTP 状态码就够了：HTTP 状态码只有几十个，而且描述的是"传输层"发生了什么，
 * 无法区分"slug 重复"和"资源被引用"这类业务语义。两者一起返回，前端按 code 精确处理，
 * 按 HTTP 状态码做统一兜底。
 *
 * <p>编号规则：4xxxx 为客户端错误、5xxxx 为服务端错误，后三位是同一大类内的序号。
 * 数值与文档 §6.7 的错误码表一一对应，改动必须同步改文档。
 */
public enum ErrorCode {

    /** 成功。 */
    OK(0, 200),

    /** 参数校验失败，data 中会带每个字段的具体原因。 */
    INVALID_PARAM(40001, 400),
    /** Markdown 的 Front Matter 格式错误或缺少必填字段。 */
    FRONT_MATTER_INVALID(40002, 400),

    /** 未登录，或令牌已过期。 */
    UNAUTHORIZED(40101, 401),
    /** 已登录但权限不足。 */
    FORBIDDEN(40301, 403),

    /** 文章不存在。 */
    POST_NOT_FOUND(40401, 404),
    /** 媒体资源不存在。 */
    MEDIA_NOT_FOUND(40402, 404),

    /** 资源仍被其他数据引用，不能删除。 */
    RESOURCE_IN_USE(40901, 409),
    /** slug 已被占用。 */
    SLUG_CONFLICT(40902, 409),

    /** 调用 GitHub API 失败。 */
    GITHUB_API_FAILED(50001, 500),
    /** Python 能力服务不可用。 */
    PYTHON_UNAVAILABLE(50002, 500),
    /** 数据库操作异常。 */
    DATABASE_ERROR(50003, 500),
    /** 兜底：未预期的内部错误。 */
    INTERNAL_ERROR(50099, 500);

    private final int code;
    private final int httpStatus;

    ErrorCode(int code, int httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public int code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
