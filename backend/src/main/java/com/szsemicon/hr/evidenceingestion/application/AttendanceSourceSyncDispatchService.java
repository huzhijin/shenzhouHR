package com.szsemicon.hr.evidenceingestion.application;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.security.ResourceNotAvailableAccessDeniedException;
import java.time.Clock;
import org.springframework.stereotype.Service;

/** Routes the authenticated sync command to the adapter for its source type. */
@Service
public class AttendanceSourceSyncDispatchService {

    private final CurrentCapabilityService capabilities;
    private final CurrentPrincipalProvider principalProvider;
    private final AttendanceSourceSyncRepository repository;
    private final DeliPunchSyncApplicationService deliSyncService;
    private final OaDocumentSyncApplicationService oaSyncService;
    private final AuditService auditService;
    private final Clock clock;

    public AttendanceSourceSyncDispatchService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principalProvider,
            AttendanceSourceSyncRepository repository,
            DeliPunchSyncApplicationService deliSyncService,
            OaDocumentSyncApplicationService oaSyncService,
            AuditService auditService,
            Clock clock) {
        this.capabilities = capabilities;
        this.principalProvider = principalProvider;
        this.repository = repository;
        this.deliSyncService = deliSyncService;
        this.oaSyncService = oaSyncService;
        this.auditService = auditService;
        this.clock = clock;
    }

    public AttendanceSourceSyncModels.JobStatus run(
            String sourceId, String correlationId) {
        requireReference(sourceId, 36);
        requireReference(correlationId, 64);
        capabilities.require(CapabilityCodes.ATTENDANCE_SOURCE_RUN);
        String principalId = principalProvider.currentPrincipalId();
        String sourceType = repository.findAuthorizedActiveSourceType(
                        sourceId,
                        principalId,
                        CapabilityCodes.ATTENDANCE_SOURCE_RUN,
                        clock.instant())
                .orElseThrow(() -> unavailable(principalId, sourceId));

        return switch (sourceType) {
            case "DELI_CLOUD" -> deliSyncService.run(sourceId, correlationId);
            case "OA_ATTENDANCE" -> oaSyncService.run(sourceId, correlationId);
            default -> throw unavailable(principalId, sourceId);
        };
    }

    private ResourceNotAvailableAccessDeniedException unavailable(
            String principalId, String sourceId) {
        auditService.recordFailure(
                principalId,
                "ATTENDANCE_SOURCE_SYNC_REQUEST",
                "ATTENDANCE_SOURCE",
                sourceId,
                "DENIED",
                "RESOURCE_UNAVAILABLE");
        return new ResourceNotAvailableAccessDeniedException();
    }

    private static void requireReference(String value, int maximumLength) {
        if (value == null
                || value.isBlank()
                || value.length() > maximumLength
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid sync reference");
        }
    }
}
