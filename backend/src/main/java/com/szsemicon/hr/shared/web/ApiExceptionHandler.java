package com.szsemicon.hr.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import com.szsemicon.hr.shared.security.ResourceNotAvailableAccessDeniedException;

@RestControllerAdvice
public final class ApiExceptionHandler {

    @ExceptionHandler(ResourceNotAvailableAccessDeniedException.class)
    ResponseEntity<ApiErrorResponse> handleResourceUnavailable(
            ResourceNotAvailableAccessDeniedException exception,
            HttpServletRequest request) {
        return response(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_AVAILABLE",
                "请求的资源不可用",
                request,
                false);
    }

    @ExceptionHandler(ApiProblemException.class)
    ResponseEntity<ApiErrorResponse> handleApiProblem(
            ApiProblemException exception,
            HttpServletRequest request) {
        return response(
                exception.status(),
                exception.code(),
                exception.getMessage(),
                request,
                exception.retryable());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiErrorResponse> handleAccessDenied(
            AccessDeniedException exception,
            HttpServletRequest request) {
        return response(
                HttpStatus.FORBIDDEN,
                "ACCESS_DENIED",
                "无权执行此操作",
                request,
                false);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiErrorResponse> handleInvalidArgument(
            IllegalArgumentException exception,
            HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                "请求参数无效",
                request,
                false);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "请求字段校验失败",
                request,
                false);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> handleUnreadableMessage(
            HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "请求 JSON 结构或字段类型无效",
                request,
                false);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ApiErrorResponse> handleOptimisticLock(
            OptimisticLockingFailureException exception,
            HttpServletRequest request) {
        return response(
                HttpStatus.CONFLICT,
                "VERSION_CONFLICT",
                "数据已被其他操作更新",
                request,
                true);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiErrorResponse> handleDataIntegrity(
            DataIntegrityViolationException exception,
            HttpServletRequest request) {
        return response(
                HttpStatus.CONFLICT,
                "DATA_CONFLICT",
                "写入与当前数据状态冲突",
                request,
                true);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiErrorResponse> handleUploadTooLarge(
            MaxUploadSizeExceededException exception,
            HttpServletRequest request) {
        return response(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "PEOPLE_IMPORT_FILE_TOO_LARGE",
                "上传文件超过 20MB 限制",
                request,
                false);
    }

    private ResponseEntity<ApiErrorResponse> response(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request,
            boolean retryable) {
        Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
        String correlationId = value == null ? "unavailable" : value.toString();
        return ResponseEntity.status(status)
                .header("Cache-Control", "no-store")
                .body(new ApiErrorResponse(code, message, correlationId, retryable));
    }
}
