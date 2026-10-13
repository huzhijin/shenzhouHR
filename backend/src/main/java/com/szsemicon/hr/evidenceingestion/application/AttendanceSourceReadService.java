package com.szsemicon.hr.evidenceingestion.application;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.Clock;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
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
            listOaDocuments(String sourceId, int page, int size, String documentType) {
        requirePage(page, size);
        if (sourceId == null || sourceId.isBlank() || sourceId.length() > 128) {
            throw new IllegalArgumentException("invalid source id");
        }
        String type = normalizeDocumentType(documentType);
        capabilities.require(CapabilityCodes.ATTENDANCE_SOURCE_READ);
        String principal = principalProvider.currentPrincipalId();
        var at = clock.instant();
        try {
            long total = repository.countOaDocuments(
                    principal,
                    CapabilityCodes.ATTENDANCE_SOURCE_READ,
                    sourceId,
                    at,
                    type);
            return AttendanceSourceReadModels.Page.of(
                    repository.listOaDocuments(
                            principal,
                            CapabilityCodes.ATTENDANCE_SOURCE_READ,
                            sourceId,
                            at,
                            type,
                            size,
                            Math.multiplyExact(page, size)),
                    page,
                    size,
                    total);
        } catch (DataIntegrityViolationException conflict) {
            throw new ApiProblemException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "OA_DOCUMENT_LIST_UNAVAILABLE",
                    "OA 单据列表暂时无法读取，请改用查询报表补签页",
                    true);
        }
    }

    private static String normalizeDocumentType(String documentType) {
        if (documentType == null || documentType.isBlank()) {
            return null;
        }
        String normalized = documentType.trim();
        return switch (normalized) {
            case "LEAVE", "LEAVE_REVOCATION", "OVERTIME", "TRIP", "OUTING",
                    "PUNCH_CORRECTION", "TIME_OFF", "EXEMPT_PUNCH" -> normalized;
            default -> throw new IllegalArgumentException("invalid document type");
        };
    }

    private static void requirePage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "page must be non-negative and size must be between 1 and 100");
        }
    }
}
