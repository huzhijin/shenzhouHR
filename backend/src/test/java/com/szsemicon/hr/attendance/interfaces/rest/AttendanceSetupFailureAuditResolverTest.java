package com.szsemicon.hr.attendance.interfaces.rest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;

class AttendanceSetupFailureAuditResolverTest {

    @Test
    void audits_bean_validation_failures() {
        assertAudited(
                new ConstraintViolationException(Set.of()),
                "VALIDATION_ERROR");
    }

    @Test
    void audits_unreadable_json_failures() {
        assertAudited(
                new HttpMessageNotReadableException(
                        "invalid json", mock(HttpInputMessage.class)),
                "VALIDATION_ERROR");
    }

    @Test
    void audits_missing_required_headers() {
        assertAudited(
                new MissingRequestHeaderException("If-Match", null),
                "MISSING_REQUIRED_HEADER");
    }

    private void assertAudited(Exception exception, String expectedReason) {
        AuditService auditService = mock(AuditService.class);
        CurrentPrincipalProvider principalProvider =
                mock(CurrentPrincipalProvider.class);
        when(principalProvider.currentPrincipalId()).thenReturn("actor-1");
        AttendanceSetupFailureAuditResolver resolver =
                new AttendanceSetupFailureAuditResolver(
                        auditService, principalProvider);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI())
                .thenReturn("/api/v1/attendance-setup/policy-bindings");
        when(request.getMethod()).thenReturn("PUT");

        resolver.resolveException(
                request, mock(HttpServletResponse.class), null, exception);

        verify(auditService).recordFailure(
                "actor-1",
                "ATTENDANCE_SETUP_PUT_FAILURE",
                "ATTENDANCE_SETUP_REQUEST",
                AttendanceSetupFailureAuditResolver.requestResourceId(request),
                "FAILURE",
                expectedReason);
    }
}
