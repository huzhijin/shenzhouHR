package com.szsemicon.hr.authorization.application;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.security.ResourceNotAvailableAccessDeniedException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CurrentCapabilityService {

    private final CurrentPrincipalProvider principalProvider;
    private final CapabilityRepository capabilityRepository;
    private final Clock clock;

    public CurrentCapabilityService(
            CurrentPrincipalProvider principalProvider,
            CapabilityRepository capabilityRepository,
            Clock clock) {
        this.principalProvider = principalProvider;
        this.capabilityRepository = capabilityRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Set<String> currentCapabilities() {
        Set<String> externallyDiscoverable = new TreeSet<>();
        for (String capability : activeCapabilities(
                principalProvider.currentPrincipalId(), clock.instant())) {
            if (CapabilityCodes.isExternallyDiscoverable(capability)) {
                externallyDiscoverable.add(capability);
            }
        }
        return Collections.unmodifiableSet(externallyDiscoverable);
    }

    @Transactional(readOnly = true)
    public Set<String> activeCapabilities(String principalId, Instant at) {
        if (principalId == null
                || principalId.isBlank()
                || at == null) {
            return Set.of();
        }
        Set<String> capabilities =
                capabilityRepository.findActiveCodes(principalId, at);
        return Collections.unmodifiableSet(new TreeSet<>(capabilities));
    }

    @Transactional(readOnly = true)
    public void require(String capabilityCode) {
        Set<String> active = activeCapabilities(
                principalProvider.currentPrincipalId(), clock.instant());
        if (!active.contains(capabilityCode)) {
            if (CapabilityCodes.MASTER_DATA_READ.equals(capabilityCode)) {
                throw new ResourceNotAvailableAccessDeniedException();
            }
            throw new AccessDeniedException("required capability is not granted");
        }
    }
}
