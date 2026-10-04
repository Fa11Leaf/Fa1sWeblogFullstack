package com.fa11leaf.blog.common;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：让控制器里只写正常路径，不必每个方法都套 try-catch。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 可预期的业务失败，按错误码对应的 HTTP 状态返回。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        ErrorCode ec = ex.errorCode();
        // 业务失败是正常流程的一部分，记 WARN 而不是 ERROR，避免日志里全是噪声。
        log.warn("业务异常 code={} message={}", ec.code(), ex.getMessage());
        return ResponseEntity.status(ec.httpStatus())
                .body(ApiResponse.fail(ec, ex.getMessage()));
    }

    /** @Valid 校验失败：把每个字段的原因列出来，前端可以直接高亮对应输入框。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleInvalidArgument(
            MethodArgumentNotValidException ex) {

        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of(
                        "field", fe.getField(),
                        "message", String.valueOf(fe.getDefaultMessage())))
                .toList();

        log.warn("参数校验失败: {}", errors);
        return ResponseEntity.status(ErrorCode.INVALID_PARAM.httpStatus())
                .body(new ApiResponse<>(
                        ErrorCode.INVALID_PARAM.code(),
                        "字段校验失败",
                        Map.of("errors", errors)));
    }

    /** 兜底分支。堆栈写进日志，但不返回给前端——异常信息常含内部路径与 SQL 片段。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("未预期的异常", ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR, "服务内部错误，请查看后端日志"));
    }
}
