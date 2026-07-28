package com.szsemicon.hr.policy.interfaces.rest;

import com.szsemicon.hr.policy.application.PolicyExceptions;
import com.szsemicon.hr.policy.domain.PolicyModels.ValidationIssue;
import com.szsemicon.hr.shared.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(-10)
@RestControllerAdvice(assignableTypes = PolicyController.class)
public final class PolicyExceptionHandler {

    @ExceptionHandler(PolicyExceptions.ValidationFailed.class)
    ResponseEntity<PolicyApiError> validation(
            PolicyExceptions.ValidationFailed exception,
            HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "规则字段校验失败",
                request,
                exception.issues());
    }

    @ExceptionHandler(PolicyExceptions.StaleVersion.class)
    ResponseEntity<PolicyApiError> stale(
            PolicyExceptions.StaleVersion exception,
            HttpServletRequest request) {
        return response(
                HttpStatus.CONFLICT,
                "STALE_VERSION",
                "规则版本已被其他操作更新",
                request,
                List.of());
    }

    @ExceptionHandler(PolicyExceptions.Conflict.class)
    ResponseEntity<PolicyApiError> conflict(
            PolicyExceptions.Conflict exception,
            HttpServletRequest request) {
        return response(
                HttpStatus.CONFLICT,
                exception.code(),
                "规则状态或作用范围冲突",
                request,
                List.of());
    }

    @ExceptionHandler(PolicyExceptions.NotFound.class)
    ResponseEntity<PolicyApiError> notFound(
            PolicyExceptions.NotFound exception,
            HttpServletRequest request) {
        return response(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_AVAILABLE",
                "请求的资源不可用",
                request,
                List.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<PolicyApiError> denied(
            AccessDeniedException exception,
            HttpServletRequest request) {
        return response(
                HttpStatus.FORBIDDEN,
                "ACCESS_DENIED",
                "当前主体无权执行该操作",
                request,
                List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<PolicyApiError> integrity(
            DataIntegrityViolationException exception,
            HttpServletRequest request) {
        return response(
                HttpStatus.CONFLICT,
                "POLICY_CONFLICT",
                "规则写入与当前数据库状态冲突",
                request,
                List.of());
    }

    private ResponseEntity<PolicyApiError> response(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request,
            List<ValidationIssue> issues) {
        Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
        String correlationId = value == null ? "unavailable" : value.toString();
        List<FieldError> fieldErrors = issues.stream()
                .map(issue -> new FieldError(issue.field(), issue.code(), issue.message()))
                .toList();
        return ResponseEntity.status(status)
                .header("Cache-Control", "no-store")
                .body(new PolicyApiError(
                        code,
                        message,
                        correlationId,
                        false,
                        fieldErrors));
    }

    record PolicyApiError(
            String code,
            String message,
            String correlationId,
            boolean retryable,
            List<FieldError> fieldErrors) {
    }

    record FieldError(String field, String code, String message) {
    }
}
