package com.fa11leaf.blog.common;

/**
 * 业务异常：把"可预期的失败"与"代码缺陷"分开。
 *
 * <p>约定：凡是能用 {@link ErrorCode} 说清楚的失败，一律抛这个异常，
 * 由 {@link GlobalExceptionHandler} 统一转成响应。
 * 其余异常走兜底分支，返回 50099，且不把堆栈信息暴露给前端。
 */
public class BusinessException extends RuntimeException {

    private final transient ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
