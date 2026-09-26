package com.szsemicon.hr.evidenceingestion.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.evidenceingestion.application.DeliSyncLogModels;
import com.szsemicon.hr.evidenceingestion.application.DeliSyncStatusApplicationService;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;

class DeliSyncStatusControllerTest {

    @Test
    void endpointReturnsOnlyTheNeutralDisplayStatusFields() throws Exception {
        Instant lastSyncTime = Instant.parse("2026-08-17T07:00:00Z");
        DeliSyncStatusApplicationService service =
                mock(DeliSyncStatusApplicationService.class);
        when(service.latestStatus()).thenReturn(
                new DeliSyncLogModels.SyncStatus(
                        lastSyncTime, 156, "SUCCESS", null));

        var response = new DeliSyncStatusController(service).status();

        assertThat(response.getBody()).isEqualTo(new DeliSyncStatusDto(
                lastSyncTime, 156, "SUCCESS", null));
        assertThat(response.getHeaders().getCacheControl())
                .isEqualTo("no-store");
        assertThat(Arrays.stream(DeliSyncStatusDto.class.getRecordComponents())
                .map(component -> component.getName()))
                .containsExactly(
                        "lastSyncTime",
                        "recordCount",
                        "status",
                        "errorMessage");
    }

    @Test
    void endpointPathAndReadCapabilityAreExplicit() throws Exception {
        var method = DeliSyncStatusController.class
                .getDeclaredMethod("status");

        assertThat(method.getAnnotation(GetMapping.class).value())
                .containsExactly("/api/attendance/deli-sync/status");
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAuthority('ATTENDANCE_SOURCE:READ')");
    }
}
