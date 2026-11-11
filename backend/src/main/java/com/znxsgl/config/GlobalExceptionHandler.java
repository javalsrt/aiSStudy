package com.znxsgl.config;

import com.znxsgl.exception.RateLimitException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理器：将 Validation 异常、上传超限等错误统一转为 JSON 响应。
 *
 * 响应格式：
 * {
 *   "timestamp": "2026-07-24T10:00:00",
 *   "status": 400,
 *   "error": "Bad Request",
 *   "message": "账号不能为空",
 *   "path": "/api/auth/login"
 * }
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** @Valid 校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex,
                                                                 HttpServletRequest request) {
        FieldError fe = ex.getBindingResult().getFieldError();
        String message = fe != null ? fe.getDefaultMessage() : "参数校验失败";

        Map<String, Object> body = baseBody(HttpStatus.BAD_REQUEST, request, message);
        if (fe != null) {
            body.put("field", fe.getField());
        }
        log.warn("参数校验失败：path={}, field={}, msg={}", request.getRequestURI(),
                fe != null ? fe.getField() : "null", message);
        return ResponseEntity.badRequest().body(body);
    }

    /** 上传文件大小超限 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleUpload(MaxUploadSizeExceededException ex,
                                                             HttpServletRequest request) {
        Map<String, Object> body = baseBody(HttpStatus.PAYLOAD_TOO_LARGE, request,
                "上传文件过大，请压缩后再试");
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
    }

    /** 业务参数非法 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArg(IllegalArgumentException ex,
                                                                HttpServletRequest request) {
        Map<String, Object> body = baseBody(HttpStatus.BAD_REQUEST, request, ex.getMessage());
        log.warn("业务参数非法：path={}, msg={}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.badRequest().body(body);
    }

    /** 业务状态非法（如课时超限、排课冲突、课程未关联班级等） */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex,
                                                                  HttpServletRequest request) {
        Map<String, Object> body = baseBody(HttpStatus.BAD_REQUEST, request, ex.getMessage());
        log.warn("业务状态非法：path={}, msg={}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.badRequest().body(body);
    }

    /** 认证失败（如 token 无效、未登录） */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthentication(AuthenticationException ex,
                                                                    HttpServletRequest request) {
        Map<String, Object> body = baseBody(HttpStatus.UNAUTHORIZED, request, "请先登录");
        log.warn("认证失败：path={}, msg={}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    /** 权限不足（@PreAuthorize 拒绝） */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex,
                                                                  HttpServletRequest request) {
        Map<String, Object> body = baseBody(HttpStatus.FORBIDDEN, request, "无权限访问该资源");
        log.warn("权限不足：path={}, msg={}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    /** 限流拦截：返回 HTTP 429 Too Many Requests */
    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimit(RateLimitException ex,
                                                               HttpServletRequest request) {
        Map<String, Object> body = baseBody(HttpStatus.TOO_MANY_REQUESTS, request, ex.getMessage());
        log.warn("接口限流：path={}, msg={}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
    }

    /** 静态资源不存在（如上传的照片已被清理 / 容器重建丢失） */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(
            org.springframework.web.servlet.resource.NoResourceFoundException ex,
            HttpServletRequest request) {
        Map<String, Object> body = baseBody(HttpStatus.NOT_FOUND, request, "文件不存在或已被清理");
        log.warn("静态资源不存在：path={}", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /** 请求方法不支持（如对只支持 POST 的接口发 GET） */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        Map<String, Object> body = baseBody(HttpStatus.METHOD_NOT_ALLOWED, request, "请求方法不支持");
        log.warn("请求方法不支持：path={}, method={}, supported={}",
                request.getRequestURI(), request.getMethod(), ex.getSupportedMethods());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(body);
    }

    /** 兜底异常 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAll(Exception ex, HttpServletRequest request) {
        Map<String, Object> body = baseBody(HttpStatus.INTERNAL_SERVER_ERROR, request, "服务器内部错误");
        log.error("未捕获异常：path={}", request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private Map<String, Object> baseBody(HttpStatus status, HttpServletRequest request, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("path", request.getRequestURI());
        return body;
    }
}
