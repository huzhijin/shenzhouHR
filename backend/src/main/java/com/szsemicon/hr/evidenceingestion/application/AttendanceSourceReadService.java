package com.szsemicon.hr.evidenceingestion.application;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttendanceSourceReadService {

    private static final int MAX_PAGE_SIZE = 100;

    private final CurrentCapabilityService capabilities;
    private final CurrentPrincipalProvider principalProvider;
    private final AttendanceSourceReadRepository repository;
    private final Clock clock;

    public AttendanceSourceReadService(
            CurrentCapabilityService capabilities,
            CurrentPrincipalProvider principalProvider,
            AttendanceSourceReadRepository repository,
            Clock clock) {
        this.capabilities = capabilities;
        this.principalProvider = principalProvider;
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AttendanceSourceReadModels.Page<AttendanceSourceReadModels.SourceView>
            listSources(int page, int size) {
        requirePage(page, size);
        capabilities.require(CapabilityCodes.ATTENDANCE_SOURCE_READ);
        String principal = principalProvider.currentPrincipalId();
        var at = clock.instant();
        long total = repository.countSources(
                principal, CapabilityCodes.ATTENDANCE_SOURCE_READ, at);
        return AttendanceSourceReadModels.Page.of(
                repository.listSources(
                        principal,
                        CapabilityCodes.ATTENDANCE_SOURCE_READ,
                        at,
                        size,
                        Math.multiplyExact(page, size)),
                page,
                size,
                total);
    }

    @Transactional(readOnly = true)
    public AttendanceSourceReadModels.Page<AttendanceSourceReadModels.JobView>
            listJobs(int page, int size) {
        requirePage(page, size);
        capabilities.require(CapabilityCodes.ATTENDANCE_SOURCE_READ);
        String principal = principalProvider.currentPrincipalId();
        var at = clock.instant();
        long total = repository.countJobs(
                principal, CapabilityCodes.ATTENDANCE_SOURCE_READ, at);
        return AttendanceSourceReadModels.Page.of(
                repository.listJobs(
                        principal,
                        CapabilityCodes.ATTENDANCE_SOURCE_READ,
                        at,
                        size,
                        Math.multiplyExact(page, size)),
                page,
                size,
                total);
    }

    @Transactional(readOnly = true)
    public AttendanceSourceReadModels.Page<AttendanceSourceReadModels.OaDocumentView>
            listOaDocuments(String sourceId, int page, int size) {
        requirePage(page, size);
        if (sourceId == null || sourceId.isBlank() || sourceId.length() > 128) {
            throw new IllegalArgumentException("invalid source id");
        }
        capabilities.require(CapabilityCodes.ATTENDANCE_SOURCE_READ);
        String principal = principalProvider.currentPrincipalId();
        var at = clock.instant();
        long total = repository.countOaDocuments(
                principal,
                CapabilityCodes.ATTENDANCE_SOURCE_READ,
                sourceId,
                at);
        return AttendanceSourceReadModels.Page.of(
                repository.listOaDocuments(
                        principal,
                        CapabilityCodes.ATTENDANCE_SOURCE_READ,
                        sourceId,
                        at,
                        size,
                        Math.multiplyExact(page, size)),
                page,
                size,
                total);
    }

    private static void requirePage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "page must be non-negative and size must be between 1 and 100");
        }
    }
}
