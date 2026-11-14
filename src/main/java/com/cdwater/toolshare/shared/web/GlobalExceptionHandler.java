package com.cdwater.toolshare.shared.web;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常出口：HTTP 状态码语义化，body 仍是 {@link Result} envelope。
 * 业务 code 保持不变，前端可继续按 code 处理。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 业务异常：按业务 code 映射 HTTP 状态
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException e) {
        return ResponseEntity.status(statusFor(e.getCode()))
                .body(Result.fail(e.getCode(), e.getMessage()));
    }

    // 401
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<Result<Void>> handleNotLogin(NotLoginException e) {
        return fail(ResultCode.UNAUTHORIZED);
    }

    // 403
    @ExceptionHandler({NotRoleException.class, NotPermissionException.class})
    public ResponseEntity<Result<Void>> handleNotRole(Exception e) {
        return fail(ResultCode.FORBIDDEN);
    }

    // 404
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResource(NoResourceFoundException e) {
        return fail(ResultCode.NOT_FOUND);
    }

    // 422：参数校验失败（body code 仍为 C400，保持旧客户端兼容）
    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    public ResponseEntity<Result<Void>> handleValidation(Exception e) {
        log.warn("validation failed: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Result.fail(ResultCode.BAD_REQUEST));
    }

    // 400：请求格式 / 用法错误
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MultipartException.class,
            MissingServletRequestPartException.class,
            HttpRequestMethodNotSupportedException.class,
            HttpMediaTypeNotSupportedException.class,
    })
    public ResponseEntity<Result<Void>> handleBadRequest(Exception e) {
        log.warn("bad request: {}", e.getMessage());
        return fail(ResultCode.BAD_REQUEST);
    }

    // 兜底
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOther(Exception e) {
        log.error("unexpected error", e);
        return fail(ResultCode.INTERNAL_SERVER_ERROR);
    }

    private ResponseEntity<Result<Void>> fail(ResultCode resultCode) {
        return ResponseEntity.status(statusFor(resultCode.getCode()))
                .body(Result.fail(resultCode));
    }

    /** 业务 code → HTTP 状态；未识别的 code 按前缀退化：S 开头 500，其余 400。 */
    static HttpStatus statusFor(String code) {
        if (code == null) {
            return HttpStatus.BAD_REQUEST;
        }
        return switch (code) {
            case "C400" -> HttpStatus.BAD_REQUEST;
            case "C401" -> HttpStatus.UNAUTHORIZED;
            case "C403" -> HttpStatus.FORBIDDEN;
            case "C404" -> HttpStatus.NOT_FOUND;
            case "C409" -> HttpStatus.CONFLICT;
            case "C429" -> HttpStatus.TOO_MANY_REQUESTS;
            case "S500" -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> code.startsWith("S") ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.BAD_REQUEST;
        };
    }
}
