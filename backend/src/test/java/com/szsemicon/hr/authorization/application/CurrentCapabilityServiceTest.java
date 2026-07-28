package com.szsemicon.hr.authorization.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        CapabilityRepository repository = (principalId, at) -> Set.of("B:READ", "A:READ");
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
    void deniesMissingCapability() {
        CurrentCapabilityService service = new CurrentCapabilityService(
                () -> "principal-1",
                (principalId, at) -> Set.of(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.require("MASTER_DATA:READ"))
                .isInstanceOf(AccessDeniedException.class);
    }
}

