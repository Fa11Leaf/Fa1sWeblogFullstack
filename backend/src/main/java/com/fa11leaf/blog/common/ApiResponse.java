package com.fa11leaf.blog.common;

/**
 * 统一响应外层。
 *
 * <p>所有接口都返回这一个形状，前端只需写一次解包逻辑。
 *
 * @param code    0 表示成功，非 0 见 {@link ErrorCode}
 * @param message 面向开发者，用于排查问题。前端展示文案应按 code 自行映射，不直接显示它
 * @param data    业务数据；失败时为 null
 * @param <T>     业务数据类型
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.OK.code(), "ok", data);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.code(), message, null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.code(), errorCode.name(), null);
    }
}
