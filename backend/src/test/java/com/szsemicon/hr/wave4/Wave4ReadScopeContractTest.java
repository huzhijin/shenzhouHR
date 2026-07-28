package com.szsemicon.hr.wave4;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.evidenceingestion.application.AttendanceSourceReadRepository;
import com.szsemicon.hr.evidenceingestion.application.AttendanceSourceReadService;
import com.szsemicon.hr.punchimport.application.PunchImportReadRepository;
import com.szsemicon.hr.punchimport.application.PunchImportReadService;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class Wave4ReadScopeContractTest {

    private static final String PRINCIPAL = "principal-w4";
    private static final Instant NOW = Instant.parse("2026-07-28T04:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void sourceReadPassesPrincipalCapabilityAndBoundedPaginationToSqlScope() {
        AttendanceSourceReadRepository repository =
                mock(AttendanceSourceReadRepository.class);
        when(repository.countSources(
                PRINCIPAL, CapabilityCodes.ATTENDANCE_SOURCE_READ, NOW))
                .thenReturn(0L);
        when(repository.listSources(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_SOURCE_READ,
                NOW,
                20,
                40))
                .thenReturn(List.of());
        var service = new AttendanceSourceReadService(
                capabilities(Set.of(CapabilityCodes.ATTENDANCE_SOURCE_READ)),
                principals(),
                repository,
                CLOCK);

        service.listSources(2, 20);

        verify(repository).countSources(
                PRINCIPAL, CapabilityCodes.ATTENDANCE_SOURCE_READ, NOW);
        verify(repository).listSources(
                PRINCIPAL,
                CapabilityCodes.ATTENDANCE_SOURCE_READ,
                NOW,
                20,
                40);
    }

    @Test
    void rawRowReadRequiresItsSeparateCapabilityBeforeRepositoryAccess() {
        PunchImportReadRepository repository =
                mock(PunchImportReadRepository.class);
        var service = new PunchImportReadService(
                capabilities(Set.of(CapabilityCodes.ATTENDANCE_PUNCH_IMPORT_READ)),
                principals(),
                repository,
                CLOCK);

        assertThatThrownBy(() -> service.rows("batch-1", 0, 20))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void unboundedPageIsRejectedBeforeCapabilityOrDatabaseWork() {
        AttendanceSourceReadRepository repository =
                mock(AttendanceSourceReadRepository.class);
        var service = new AttendanceSourceReadService(
                capabilities(Set.of(CapabilityCodes.ATTENDANCE_SOURCE_READ)),
                principals(),
                repository,
                CLOCK);

        assertThatThrownBy(() -> service.listJobs(0, 101))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    private static CurrentPrincipalProvider principals() {
        return () -> PRINCIPAL;
    }

    private static CurrentCapabilityService capabilities(Set<String> codes) {
        return new CurrentCapabilityService(
                principals(),
                (principalId, at) -> codes,
                CLOCK);
    }
}
