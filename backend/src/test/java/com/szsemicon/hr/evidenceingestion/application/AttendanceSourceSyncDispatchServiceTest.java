package com.szsemicon.hr.evidenceingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.security.ResourceNotAvailableAccessDeniedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AttendanceSourceSyncDispatchServiceTest {

    private static final String SOURCE_ID = "source-1";
    private static final String PRINCIPAL_ID = "principal-1";
    private static final String CORRELATION_ID = "request-1";
    private static final Instant NOW = Instant.parse("2026-08-17T05:00:00Z");

    private final CurrentCapabilityService capabilities =
            mock(CurrentCapabilityService.class);
    private final CurrentPrincipalProvider principalProvider =
            mock(CurrentPrincipalProvider.class);
    private final AttendanceSourceSyncRepository repository =
            mock(AttendanceSourceSyncRepository.class);
    private final DeliPunchSyncApplicationService deliService =
            mock(DeliPunchSyncApplicationService.class);
    private final OaDocumentSyncApplicationService oaService =
            mock(OaDocumentSyncApplicationService.class);
    private final AuditService auditService = mock(AuditService.class);

    @Test
    void routesAuthorizedOaSourceToOaService() {
        var expected = status("OA_ATTENDANCE");
        prepareType("OA_ATTENDANCE");
        when(oaService.run(SOURCE_ID, CORRELATION_ID)).thenReturn(expected);

        var result = service().run(SOURCE_ID, CORRELATION_ID);

        assertThat(result).isSameAs(expected);
        verify(capabilities).require(CapabilityCodes.ATTENDANCE_SOURCE_RUN);
        verify(oaService).run(SOURCE_ID, CORRELATION_ID);
        verify(deliService, never()).run(SOURCE_ID, CORRELATION_ID);
    }

    @Test
    void routesAuthorizedDeliSourceToDeliService() {
        var expected = status("DELI_CLOUD");
        prepareType("DELI_CLOUD");
        when(deliService.run(SOURCE_ID, CORRELATION_ID)).thenReturn(expected);

        var result = service().run(SOURCE_ID, CORRELATION_ID);

        assertThat(result).isSameAs(expected);
        verify(deliService).run(SOURCE_ID, CORRELATION_ID);
        verify(oaService, never()).run(SOURCE_ID, CORRELATION_ID);
    }

    @Test
    void unavailableOrOutOfScopeSourceIsFailClosedBeforeDispatch() {
        when(principalProvider.currentPrincipalId()).thenReturn(PRINCIPAL_ID);
        when(repository.findAuthorizedActiveSourceType(
                        SOURCE_ID,
                        PRINCIPAL_ID,
                        CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                        NOW))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().run(SOURCE_ID, CORRELATION_ID))
                .isInstanceOf(ResourceNotAvailableAccessDeniedException.class);

        verify(auditService).recordFailure(
                PRINCIPAL_ID,
                "ATTENDANCE_SOURCE_SYNC_REQUEST",
                "ATTENDANCE_SOURCE",
                SOURCE_ID,
                "DENIED",
                "RESOURCE_UNAVAILABLE");
        verify(deliService, never()).run(SOURCE_ID, CORRELATION_ID);
        verify(oaService, never()).run(SOURCE_ID, CORRELATION_ID);
    }

    private void prepareType(String sourceType) {
        when(principalProvider.currentPrincipalId()).thenReturn(PRINCIPAL_ID);
        when(repository.findAuthorizedActiveSourceType(
                        SOURCE_ID,
                        PRINCIPAL_ID,
                        CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                        NOW))
                .thenReturn(Optional.of(sourceType));
    }

    private AttendanceSourceSyncDispatchService service() {
        return new AttendanceSourceSyncDispatchService(
                capabilities,
                principalProvider,
                repository,
                deliService,
                oaService,
                auditService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static AttendanceSourceSyncModels.JobStatus status(
            String sourceType) {
        return new AttendanceSourceSyncModels.JobStatus(
                "job-1",
                SOURCE_ID,
                "Source",
                sourceType,
                "SUCCEEDED",
                1,
                2,
                0,
                null,
                NOW,
                NOW,
                3);
    }
}
