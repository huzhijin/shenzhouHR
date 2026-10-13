package com.szsemicon.hr.evidenceingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class DeliSyncStatusApplicationServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-08-17T07:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void readCapabilityReturnsLatestCompletedLog() {
        DeliSyncLogRepository repository = mock(DeliSyncLogRepository.class);
        var expected = new DeliSyncLogModels.SyncStatus(
                NOW, 156, "SUCCESS", null);
        when(repository.findLatestCompleted())
                .thenReturn(Optional.of(expected));
        DeliSyncStatusApplicationService service = service(
                Set.of(CapabilityCodes.ATTENDANCE_SOURCE_READ), repository);

        assertThat(service.latestStatus()).isEqualTo(expected);
        verify(repository).findLatestCompleted();
    }

    @Test
    void missingReadCapabilityDoesNotExposeGlobalSyncStatus() {
        DeliSyncLogRepository repository = mock(DeliSyncLogRepository.class);
        DeliSyncStatusApplicationService service = service(
                Set.of(), repository);

        assertThatThrownBy(service::latestStatus)
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void noCompletedRunProducesNeutralNotRunStatus() {
        DeliSyncLogRepository repository = mock(DeliSyncLogRepository.class);
        when(repository.findLatestCompleted()).thenReturn(Optional.empty());
        DeliSyncStatusApplicationService service = service(
                Set.of(CapabilityCodes.ATTENDANCE_SOURCE_READ), repository);

        assertThat(service.latestStatus()).isEqualTo(
                new DeliSyncLogModels.SyncStatus(
                        null, 0, "NOT_RUN", null));
    }

    private static DeliSyncStatusApplicationService service(
            Set<String> capabilityCodes,
            DeliSyncLogRepository repository) {
        CurrentPrincipalProvider principals = () -> "principal-1";
        CurrentCapabilityService capabilities = new CurrentCapabilityService(
                principals,
                (principalId, at) -> capabilityCodes,
                CLOCK);
        return new DeliSyncStatusApplicationService(
                capabilities, repository);
    }
}
