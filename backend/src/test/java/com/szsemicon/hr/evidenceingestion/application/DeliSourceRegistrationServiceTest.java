package com.szsemicon.hr.evidenceingestion.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.evidenceingestion.application.DeliSourceRegistrationRepository.RegistrationResult;
import com.szsemicon.hr.evidenceingestion.application.DeliSourceRegistrationRepository.RegistrationState;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.security.ResourceNotAvailableAccessDeniedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeliSourceRegistrationServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-07-29T02:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String PRINCIPAL = "principal-1";

    private final DeliSourceRegistrationRepository repository =
            mock(DeliSourceRegistrationRepository.class);
    private final AuditService auditService = mock(AuditService.class);

    @Test
    void registersOnlyExternalCredentialReferenceUnderConfigureCapability() {
        var source = source(false);
        when(repository.register(
                        eq(command()),
                        eq(PRINCIPAL),
                        eq(CapabilityCodes.ATTENDANCE_SOURCE_CONFIGURE),
                        eq("idem-key-00000001"),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(NOW)))
                .thenReturn(RegistrationResult.source(
                        RegistrationState.CREATED, source));

        var result = service(Set.of(
                        CapabilityCodes.ATTENDANCE_SOURCE_CONFIGURE))
                .register(command(), "idem-key-00000001");

        assertThat(result).isEqualTo(source);
        assertThat(result.secretReferenceName())
                .isEqualTo("DELI_EPLUS_APP_CREDENTIALS");
        verify(auditService).record(
                PRINCIPAL,
                "DELI_SOURCE_REGISTER",
                "ATTENDANCE_SOURCE",
                "source-1",
                "SUCCESS",
                "DELI_SOURCE_REGISTERED");
        verify(repository).register(
                eq(command()),
                eq(PRINCIPAL),
                eq(CapabilityCodes.ATTENDANCE_SOURCE_CONFIGURE),
                eq("idem-key-00000001"),
                org.mockito.ArgumentMatchers.matches("[0-9a-f]{64}"),
                any(),
                any(),
                any(),
                org.mockito.ArgumentMatchers.matches("[0-9a-f]{64}"),
                eq(NOW));
    }

    @Test
    void replayIsMarkedAndScopeFailureDoesNotBecomeAConflict() {
        when(repository.register(
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any()))
                .thenReturn(RegistrationResult.source(
                        RegistrationState.REPLAYED, source(false)))
                .thenReturn(RegistrationResult.of(
                        RegistrationState.RESOURCE_UNAVAILABLE));
        var service = service(Set.of(
                CapabilityCodes.ATTENDANCE_SOURCE_CONFIGURE));

        assertThat(service.register(command(), "idem-key-00000001").replayed())
                .isTrue();
        verify(auditService).record(
                PRINCIPAL,
                "DELI_SOURCE_REGISTER",
                "ATTENDANCE_SOURCE",
                "source-1",
                "SUCCESS",
                "IDEMPOTENCY_REPLAY");
        assertThatThrownBy(() ->
                        service.register(command(), "idem-key-00000002"))
                .isInstanceOf(
                        ResourceNotAvailableAccessDeniedException.class);
    }

    @Test
    void missingConfigureCapabilityStopsBeforeRepositoryAccess() {
        var service = service(Set.of(
                CapabilityCodes.ATTENDANCE_SOURCE_READ));

        assertThatThrownBy(() ->
                        service.register(command(), "idem-key-00000001"))
                .isInstanceOf(
                        org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void requestCannotContainCredentialsOrUnboundedSettings() {
        var invalid = new DeliSourceRegistrationModels.Command(
                "legal-1",
                "DELI_MAIN",
                "正式得力 E+",
                "Asia/Shanghai",
                501,
                60,
                5,
                "not-a-safe-secret-value",
                "初始化正式来源");

        assertThatThrownBy(() ->
                        service(Set.of()).register(
                                invalid, "idem-key-00000001"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    private DeliSourceRegistrationService service(
            Set<String> capabilityCodes) {
        CurrentPrincipalProvider principals = () -> PRINCIPAL;
        var capabilities = new CurrentCapabilityService(
                principals,
                (principalId, at) -> capabilityCodes,
                CLOCK);
        return new DeliSourceRegistrationService(
                capabilities,
                principals,
                repository,
                auditService,
                CLOCK);
    }

    private static DeliSourceRegistrationModels.Command command() {
        return new DeliSourceRegistrationModels.Command(
                "legal-1",
                "DELI_MAIN",
                "正式得力 E+",
                "Asia/Shanghai",
                500,
                60,
                5,
                "DELI_EPLUS_APP_CREDENTIALS",
                "初始化正式来源");
    }

    private static DeliSourceRegistrationModels.SourceView source(
            boolean replayed) {
        return new DeliSourceRegistrationModels.SourceView(
                "source-1",
                "legal-1",
                "DELI_MAIN",
                "正式得力 E+",
                "DELI_CLOUD",
                "ACTIVE",
                "DELI_EPLUS_CHECKIN_QUERY",
                "Asia/Shanghai",
                500,
                60,
                5,
                "DELI_EPLUS_APP_CREDENTIALS",
                1,
                NOW,
                0,
                replayed);
    }
}
