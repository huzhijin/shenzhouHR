package com.szsemicon.hr.evidenceingestion.application;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeliSyncStatusApplicationService {

    private final CurrentCapabilityService capabilities;
    private final DeliSyncLogRepository repository;

    public DeliSyncStatusApplicationService(
            CurrentCapabilityService capabilities,
            DeliSyncLogRepository repository) {
        this.capabilities = capabilities;
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public DeliSyncLogModels.SyncStatus latestStatus() {
        capabilities.require(CapabilityCodes.ATTENDANCE_SOURCE_READ);
        return repository.findLatestCompleted()
                .orElseGet(() -> new DeliSyncLogModels.SyncStatus(
                        null, 0, "NOT_RUN", null));
    }
}
