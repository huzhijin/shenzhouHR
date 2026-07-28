package com.szsemicon.hr.authorization.application;

import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.shared.security.ResourceNotAvailableAccessDeniedException;

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
        return activeCapabilities(
                principalProvider.currentPrincipalId(), clock.instant());
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
        if (!currentCapabilities().contains(capabilityCode)) {
            if (CapabilityCodes.MASTER_DATA_READ.equals(capabilityCode)) {
                throw new ResourceNotAvailableAccessDeniedException();
            }
            throw new AccessDeniedException("required capability is not granted");
        }
    }
}
