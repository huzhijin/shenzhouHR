package com.szsemicon.hr.attendance.interfaces.rest;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.web.ApiProblemException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.core.Ordered;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

@Component
final class AttendanceSetupFailureAuditResolver
        implements HandlerExceptionResolver, Ordered {

    private static final String ROUTE_PREFIX = "/api/v1/attendance-setup";

    private final AuditService auditService;
    private final CurrentPrincipalProvider principalProvider;

    AttendanceSetupFailureAuditResolver(
            AuditService auditService,
            CurrentPrincipalProvider principalProvider) {
        this.auditService = auditService;
        this.principalProvider = principalProvider;
    }

    @Override
    public ModelAndView resolveException(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception exception) {
        if (!request.getRequestURI().startsWith(ROUTE_PREFIX)) {
            return null;
        }
        Outcome outcome = outcome(exception);
        if (outcome == null) {
            return null;
        }
        auditService.recordFailure(
                actor(),
                "ATTENDANCE_SETUP_" + request.getMethod() + "_" + outcome.result(),
                "ATTENDANCE_SETUP_REQUEST",
                requestResourceId(request),
                outcome.result(),
                outcome.reason());
        return null;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private Outcome outcome(Exception exception) {
        if (exception instanceof AccessDeniedException) {
            return new Outcome("DENIED", "ACCESS_DENIED");
        }
        if (exception instanceof ApiProblemException problem
                && problem.status().is4xxClientError()) {
            return new Outcome("FAILURE", problem.code());
        }
        if (exception instanceof OptimisticLockingFailureException) {
            return new Outcome("FAILURE", "VERSION_CONFLICT");
        }
        if (exception instanceof DataIntegrityViolationException) {
            return new Outcome("FAILURE", "DATA_CONFLICT");
        }
        if (exception instanceof MethodArgumentNotValidException
                || exception instanceof HandlerMethodValidationException
                || exception instanceof ConstraintViolationException
                || exception instanceof HttpMessageNotReadableException
                || exception instanceof MethodArgumentTypeMismatchException) {
            return new Outcome("FAILURE", "VALIDATION_ERROR");
        }
        if (exception instanceof MissingRequestHeaderException) {
            return new Outcome("FAILURE", "MISSING_REQUIRED_HEADER");
        }
        if (exception instanceof ServletRequestBindingException) {
            return new Outcome("FAILURE", "REQUEST_BINDING_ERROR");
        }
        if (exception instanceof IllegalArgumentException) {
            return new Outcome("FAILURE", "INVALID_REQUEST");
        }
        if (exception instanceof ErrorResponse error
                && error.getStatusCode().is4xxClientError()) {
            return new Outcome("FAILURE", "REQUEST_REJECTED");
        }
        return null;
    }

    private String actor() {
        try {
            return principalProvider.currentPrincipalId();
        } catch (RuntimeException unauthenticated) {
            return null;
        }
    }

    static String requestResourceId(HttpServletRequest request) {
        String requestTarget = request.getMethod() + " " + request.getRequestURI();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(requestTarget.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is required by the JVM", unavailable);
        }
    }

    private record Outcome(String result, String reason) {
    }
}
