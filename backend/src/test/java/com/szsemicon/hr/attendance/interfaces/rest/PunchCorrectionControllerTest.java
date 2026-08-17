package com.szsemicon.hr.attendance.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.attendance.application.PunchCorrectionApplicationService;
import com.szsemicon.hr.attendance.application.PunchCorrectionQuotaService.QuotaStatus;
import com.szsemicon.hr.attendance.domain.PunchCorrectionRequest;
import com.szsemicon.hr.attendance.domain.PunchCorrectionRequest.PunchSide;
import com.szsemicon.hr.attendance.interfaces.rest.PunchCorrectionController.ApprovalRequest;
import com.szsemicon.hr.attendance.interfaces.rest.PunchCorrectionController.SubmitRequest;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class PunchCorrectionControllerTest {

    @Test
    void submitQuotaAndApprovalEndpointsReturnNoStoreDtos() {
        PunchCorrectionApplicationService service =
                mock(PunchCorrectionApplicationService.class);
        PunchCorrectionRequest pending = pending();
        PunchCorrectionRequest approved = pending.approve(
                "hr-001", Instant.parse("2026-08-17T02:00:00Z"), "同意");
        when(service.submit(any())).thenReturn(pending);
        when(service.quota(any(), any()))
                .thenReturn(new QuotaStatus(0, 1, 1));
        when(service.approve(any(), any())).thenReturn(approved);
        PunchCorrectionController controller =
                new PunchCorrectionController(service);

        var submit = controller.submit(new SubmitRequest(
                "employee-001",
                LocalDate.parse("2026-08-15"),
                PunchSide.BOTH,
                "忘记打卡"));
        var quota = controller.quota("employee-001", "2026-08");
        var approval = controller.approve(
                pending.requestId(), new ApprovalRequest("同意"));

        assertThat(submit.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(submit.getBody().status()).isEqualTo("PENDING");
        assertThat(quota.getBody())
                .isEqualTo(new QuotaStatus(0, 1, 1));
        assertThat(approval.getBody().status()).isEqualTo("APPROVED");
        assertThat(submit.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(quota.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(approval.getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    @Test
    void pathsAndCapabilitiesCoverTaskAndSpecificationAliases()
            throws Exception {
        RequestMapping root = PunchCorrectionController.class
                .getAnnotation(RequestMapping.class);
        assertThat(root.value()).containsExactly(
                "/api/v1/attendance/punch-corrections",
                "/api/v1/attendance/punch-supplement",
                "/api/attendance/punch-corrections",
                "/api/attendance/punch-supplement");

        var submit = PunchCorrectionController.class.getDeclaredMethod(
                "submit", SubmitRequest.class);
        assertThat(submit.getAnnotation(PostMapping.class).value())
                .containsExactly("", "/apply");
        assertThat(submit.getAnnotation(PreAuthorize.class).value())
                .contains("ATTENDANCE_PUNCH_CORRECTION:CREATE");

        var quota = PunchCorrectionController.class.getDeclaredMethod(
                "quota", String.class, String.class);
        assertThat(quota.getAnnotation(GetMapping.class).value())
                .containsExactly("/quota/{employeeId}/{month}");
        assertThat(quota.getAnnotation(PreAuthorize.class).value())
                .contains("ATTENDANCE_PUNCH_CORRECTION:READ");

        var approve = PunchCorrectionController.class.getDeclaredMethod(
                "approve", String.class, ApprovalRequest.class);
        assertThat(approve.getAnnotation(PutMapping.class).value())
                .containsExactly("/{requestId}/approve");
        assertThat(approve.getAnnotation(PreAuthorize.class).value())
                .contains("ATTENDANCE_PUNCH_CORRECTION:APPROVE");
    }

    private PunchCorrectionRequest pending() {
        return PunchCorrectionRequest.pending(
                "request-001",
                "employee-001",
                LocalDate.parse("2026-08-15"),
                PunchSide.BOTH,
                "忘记打卡",
                Instant.parse("2026-08-17T01:00:00Z"),
                "principal-001");
    }
}
