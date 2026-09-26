package com.szsemicon.hr.authorization.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class CurrentCapabilityServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");

    @Test
    void returnsSortedImmutableCapabilitiesForCurrentPrincipal() {
        CurrentPrincipalProvider principalProvider = () -> "principal-1";
        CapabilityRepository repository = (principalId, at) ->
                Set.of("B:READ", "PAYROLL:RESERVATION_READ", "A:READ");
        CurrentCapabilityService service = new CurrentCapabilityService(
                principalProvider,
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC));

        Set<String> result = service.currentCapabilities();

        assertThat(result).containsExactly("A:READ", "B:READ");
        assertThatThrownBy(() -> result.add("C:READ"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void internalPayrollCapabilityCanAuthorizeWithoutBecomingDiscoverable() {
        CurrentCapabilityService service = new CurrentCapabilityService(
                () -> "principal-1",
                (principalId, at) -> Set.of("PAYROLL:RESERVATION_READ"),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(service.currentCapabilities()).isEmpty();
        service.require("PAYROLL:RESERVATION_READ");
    }

    @Test
    void explicitPrincipalLookupReturnsRawSortedImmutableCapabilities() {
        CurrentCapabilityService service = new CurrentCapabilityService(
                () -> "current-principal",
                (principalId, at) -> Set.of(
                        "B:READ",
                        "PAYROLL:FUTURE_INTERNAL",
                        "A:READ"),
                Clock.fixed(NOW, ZoneOffset.UTC));

        Set<String> result =
                service.activeCapabilities("target-principal", NOW);

        assertThat(result)
                .containsExactly(
                        "A:READ",
                        "B:READ",
                        "PAYROLL:FUTURE_INTERNAL");
        assertThatThrownBy(() -> result.add("C:READ"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void explicitPrincipalLookupFailsClosedForMissingAuthorityCoordinates() {
        CurrentCapabilityService service = new CurrentCapabilityService(
                () -> "current-principal",
                (principalId, at) -> {
                    throw new AssertionError("repository must not be called");
                },
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(service.activeCapabilities(null, NOW)).isEmpty();
        assertThat(service.activeCapabilities(" ", NOW)).isEmpty();
        assertThat(service.activeCapabilities("principal-1", null)).isEmpty();
    }

    @Test
    void hidesEveryInternalPayrollCapabilityAndInvalidDiscoveryCode() {
        CurrentCapabilityService service = new CurrentCapabilityService(
                () -> "principal-1",
                (principalId, at) -> Set.of(
                        "PAYROLL:FUTURE_INTERNAL",
                        "ATTENDANCE_REPORT:READ"),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(service.currentCapabilities())
                .containsExactly("ATTENDANCE_REPORT:READ");
        assertThat(CapabilityCodes.isExternallyDiscoverable(null))
                .isFalse();
        assertThat(CapabilityCodes.isExternallyDiscoverable(" "))
                .isFalse();
    }

    @Test
    void deniesMissingCapability() {
        CurrentCapabilityService service = new CurrentCapabilityService(
                () -> "principal-1",
                (principalId, at) -> Set.of(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.require("MASTER_DATA:READ"))
                .isInstanceOf(AccessDeniedException.class);
    }
}
