package com.szsemicon.hr.employee.application;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.shared.security.CurrentPrincipalProvider;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmployeeListQueryService {

    private static final int MAX_PAGE_SIZE = 100;

    private final CurrentCapabilityService capabilityService;
    private final CurrentPrincipalProvider principalProvider;
    private final EmployeeReadRepository repository;
    private final Clock clock;

    public EmployeeListQueryService(
            CurrentCapabilityService capabilityService,
            CurrentPrincipalProvider principalProvider,
            EmployeeReadRepository repository,
            Clock clock) {
        this.capabilityService = capabilityService;
        this.principalProvider = principalProvider;
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public EmployeePage query(int page, int size) {
        return query(page, size, null, null, null, null, null);
    }

    @Transactional(readOnly = true)
    public EmployeePage query(
            int page,
            int size,
            String query,
            String organizationId,
            String status,
            LocalDate asOf,
            String sort) {
        return query(page, size, query, organizationId, null, status, asOf, sort);
    }

    @Transactional(readOnly = true)
    public EmployeePage query(
            int page,
            int size,
            String query,
            String organizationId,
            String companyId,
            String status,
            LocalDate asOf,
            String sort) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page must be non-negative and size must be between 1 and 100");
        }
        if (query != null && query.length() > 100) {
            throw new IllegalArgumentException("query must not exceed 100 characters");
        }
        if (status != null && !Set.of("ACTIVE", "INACTIVE", "TERMINATED").contains(status)) {
            throw new IllegalArgumentException("invalid employee status");
        }
        String safeSort = sort == null ? "displayName" : sort;
        if (!Set.of(
                        "employeeNumber", "displayName", "employmentStatus",
                        "organizationName", "updatedAt")
                .contains(safeSort)) {
            throw new IllegalArgumentException("invalid employee sort");
        }
        capabilityService.require(CapabilityCodes.MASTER_DATA_READ);
        return repository.findVisibleTo(
                principalProvider.currentPrincipalId(),
                CapabilityCodes.MASTER_DATA_READ,
                asOf == null
                        ? clock.instant()
                        : asOf.atStartOfDay(ZoneOffset.UTC).toInstant(),
                query == null || query.isBlank() ? null : query.trim(),
                organizationId,
                companyId,
                status,
                safeSort,
                page,
                size);
    }
}
