package com.szsemicon.hr.evidenceingestion.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.evidenceingestion.application.AttendanceSourceSyncDispatchService;
import com.szsemicon.hr.evidenceingestion.application.AttendanceSourceSyncModels;
import com.szsemicon.hr.evidenceingestion.application.DeliPunchSyncApplicationService;
import com.szsemicon.hr.shared.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AttendanceSourceSyncControllerTest {

    @Test
    void formalStartEndpointUsesTheSourceTypeDispatcher() {
        AttendanceSourceSyncDispatchService dispatcher =
                mock(AttendanceSourceSyncDispatchService.class);
        DeliPunchSyncApplicationService jobService =
                mock(DeliPunchSyncApplicationService.class);
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        var expected = status();
        when(servletRequest.getAttribute(
                        CorrelationIdFilter.REQUEST_ATTRIBUTE))
                .thenReturn("request-1");
        when(dispatcher.run("oa-source-1", "request-1"))
                .thenReturn(expected);
        var controller = new AttendanceSourceSyncController(
                dispatcher, jobService);

        var response = controller.run(
                new AttendanceSourceSyncController.StartSyncRequest(
                        "oa-source-1"),
                servletRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isSameAs(expected);
        verify(dispatcher).run("oa-source-1", "request-1");
    }

    private static AttendanceSourceSyncModels.JobStatus status() {
        Instant now = Instant.parse("2026-08-17T05:00:00Z");
        return new AttendanceSourceSyncModels.JobStatus(
                "job-1",
                "oa-source-1",
                "OA",
                "OA_ATTENDANCE",
                "SUCCEEDED",
                1,
                2,
                0,
                null,
                now,
                now,
                3);
    }
}
