package com.yqsoft.exception;

import com.yqsoft.resource.common.RequestContext;
import com.yqsoft.resource.common.Rows;

import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> business(BusinessException exception) {
        return response(
                exception.getStatus(), exception.getCode(), exception.getMessage(), List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(
            MethodArgumentNotValidException exception) {
        List<Map<String, Object>> fields =
                exception.getBindingResult().getFieldErrors().stream()
                        .map(
                                error ->
                                        Rows.map(
                                                "field",
                                                error.getField(),
                                                "message",
                                                error.getDefaultMessage()))
                        .toList();
        return response(422, "VALIDATION_FAILED", "请检查请求字段", fields);
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        ConstraintViolationException.class,
        ServletRequestBindingException.class,
        HandlerMethodValidationException.class,
        IllegalArgumentException.class
    })
    public ResponseEntity<Map<String, Object>> malformed(Exception exception) {
        return response(422, "VALIDATION_FAILED", "请求格式错误、字段缺失或包含不支持的值", List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> missingRoute(NoResourceFoundException exception) {
        return response(404, "NOT_FOUND", "请求资源不存在", List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> unsupportedMethod(
            HttpRequestMethodNotSupportedException exception) {
        return response(405, "METHOD_NOT_ALLOWED", "此资源不支持该请求方法", List.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> unsupportedMedia(
            HttpMediaTypeNotSupportedException exception) {
        return response(415, "UNSUPPORTED_MEDIA_TYPE", "请求内容类型不受支持", List.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> oversized(MaxUploadSizeExceededException exception) {
        return response(413, "PAYLOAD_TOO_LARGE", "上传内容超过允许大小", List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> integrity(
            DataIntegrityViolationException exception) {
        return response(409, "DATA_INTEGRITY_CONFLICT", "数据唯一性或关联约束冲突，请重新读取并确认", List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception exception) {
        LOGGER.error(
                "Request {} failed with {}",
                RequestContext.requestId(),
                exception.getClass().getSimpleName());
        return response(500, "INTERNAL_ERROR", "服务暂时无法完成请求，请凭请求编号联系维护人员", List.of());
    }

    private ResponseEntity<Map<String, Object>> response(
            int status, String code, String message, List<Map<String, Object>> fields) {
        return ResponseEntity.status(status)
                .body(
                        Rows.map(
                                "code",
                                code,
                                "message",
                                message,
                                "data",
                                null,
                                "requestId",
                                RequestContext.requestId(),
                                "serverTime",
                                Instant.now(),
                                "error",
                                Rows.map(
                                        "fieldErrors",
                                        fields,
                                        "retryable",
                                        status == 429 || status >= 500,
                                        "retryAfterSeconds",
                                        status == 429 ? 60 : null,
                                        "conflict",
                                        null)));
    }
}
