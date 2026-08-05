package com.szsemicon.hr.employee.infrastructure.persistence;

import com.szsemicon.hr.employee.application.EmployeePage;
import com.szsemicon.hr.employee.application.EmployeeReadRepository;
import com.szsemicon.hr.employee.domain.EmployeeSummary;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisEmployeeReadRepository implements EmployeeReadRepository {

    private final EmployeeReadMapper mapper;

    public MyBatisEmployeeReadRepository(EmployeeReadMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public EmployeePage findVisibleTo(
            String principalId,
            String capabilityCode,
            Instant at,
            int page,
            int size) {
        return findVisibleTo(
                principalId, capabilityCode, at,
                null, null, null, null, "displayName", page, size);
    }

    @Override
    public EmployeePage findVisibleTo(
            String principalId,
            String capabilityCode,
            Instant at,
            String query,
            String organizationId,
            String status,
            String sort,
            int page,
            int size) {
        return findVisibleTo(
                principalId, capabilityCode, at, query, organizationId, null,
                status, sort, page, size);
    }

    @Override
    public EmployeePage findVisibleTo(
            String principalId,
            String capabilityCode,
            Instant at,
            String query,
            String organizationId,
            String companyId,
            String status,
            String sort,
            int page,
            int size) {
        long total = mapper.countVisibleTo(
                principalId, capabilityCode, at, query, organizationId,
                companyId, status);
        long offset = Math.multiplyExact((long) page, size);
        List<EmployeeSummary> items = mapper.findVisibleTo(
                        principalId,
                        capabilityCode,
                        at,
                        query,
                        organizationId,
                        companyId,
                        status,
                        sort,
                        size,
                        offset).stream()
                .map(this::toDomain)
                .toList();
        return new EmployeePage(items, total, page, size);
    }

    private EmployeeSummary toDomain(EmployeeSummaryRow row) {
        return new EmployeeSummary(
                row.employeeId(),
                row.employeeVersionId(),
                row.employeeNumber(),
                row.displayName(),
                row.employmentStatus(),
                row.organizationId(),
                row.organizationName(),
                row.organizationCode(),
                row.seeyonOaCode(),
                row.bindingStatus(),
                row.assignmentEffectiveFrom(),
                row.assignmentEffectiveTo(),
                row.sourceAuthority(),
                row.rowVersion());
    }
}
