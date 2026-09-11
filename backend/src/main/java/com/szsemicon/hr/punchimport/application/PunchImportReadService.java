package com.szsemicon.hr.punchimport.application;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.security.ResourceNotAvailableAccessDeniedException;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PunchImportReadService {

    private final CurrentCapabilityService capabilities;
    private final CurrentPrincipalProvider principals;
    private final PunchImportReadRepository repository;
    private final Clock clock;

    public PunchImportReadService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principals,
            PunchImportReadRepository repository,
            Clock clock) {
        this.capabilities = capabilities;
        this.principals = principals;
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PunchImportReadModels.Page<PunchImportReadModels.BatchView> list(
            int page,
            int size) {
        requirePage(page, size);
        capabilities.require(CapabilityCodes.ATTENDANCE_PUNCH_IMPORT_READ);
        String principal = principals.currentPrincipalId();
        var at = clock.instant();
        long total = repository.countBatches(
                principal, CapabilityCodes.ATTENDANCE_PUNCH_IMPORT_READ, at);
        return PunchImportReadModels.Page.of(
                repository.listBatches(
                        principal,
                        CapabilityCodes.ATTENDANCE_PUNCH_IMPORT_READ,
                        at,
                        size,
                        Math.multiplyExact(page, size)),
                page,
                size,
                total);
    }

    @Transactional(readOnly = true)
    public PunchImportReadModels.BatchView find(String batchId) {
        requireId(batchId);
        capabilities.require(CapabilityCodes.ATTENDANCE_PUNCH_IMPORT_READ);
        return repository.findBatch(
                        principals.currentPrincipalId(),
                        CapabilityCodes.ATTENDANCE_PUNCH_IMPORT_READ,
                        batchId,
                        clock.instant())
                .orElseThrow(ResourceNotAvailableAccessDeniedException::new);
    }

    @Transactional(readOnly = true)
    public PunchImportReadModels.Page<PunchImportReadModels.IssueView> issues(
            String batchId,
            int page,
            int size) {
        requireId(batchId);
        requirePage(page, size);
        capabilities.require(CapabilityCodes.ATTENDANCE_PUNCH_IMPORT_READ);
        String principal = principals.currentPrincipalId();
        var at = clock.instant();
        long total = repository.countIssues(
                principal,
                CapabilityCodes.ATTENDANCE_PUNCH_IMPORT_READ,
                batchId,
                at);
        return PunchImportReadModels.Page.of(
                repository.listIssues(
                        principal,
                        CapabilityCodes.ATTENDANCE_PUNCH_IMPORT_READ,
                        batchId,
                        at,
                        size,
                        Math.multiplyExact(page, size)),
                page,
                size,
                total);
    }

    @Transactional(readOnly = true)
    public PunchImportReadModels.Page<PunchImportReadModels.RowView> rows(
            String batchId,
            int page,
            int size) {
        requireId(batchId);
        requirePage(page, size);
        capabilities.require(CapabilityCodes.ATTENDANCE_PUNCH_IMPORT_RAW_ROW_READ);
        String principal = principals.currentPrincipalId();
        var at = clock.instant();
        long total = repository.countRows(
                principal,
                CapabilityCodes.ATTENDANCE_PUNCH_IMPORT_RAW_ROW_READ,
                batchId,
                at);
        return PunchImportReadModels.Page.of(
                repository.listRows(
                        principal,
                        CapabilityCodes.ATTENDANCE_PUNCH_IMPORT_RAW_ROW_READ,
                        batchId,
                        at,
                        size,
                        Math.multiplyExact(page, size)),
                page,
                size,
                total);
    }

    private static void requirePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException(
                    "page must be non-negative and size must be between 1 and 100");
        }
    }

    private static void requireId(String id) {
        if (id == null || id.isBlank() || id.length() > 128) {
            throw new IllegalArgumentException("invalid batch id");
        }
    }
}
