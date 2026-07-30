package com.szsemicon.hr.people.interfaces.rest;

import com.szsemicon.hr.people.application.PeopleManagementService.EmployeeDetail;
import com.szsemicon.hr.people.application.PeopleManagementService.PriorServicePage;
import com.szsemicon.hr.people.application.PeoplePage;
import com.szsemicon.hr.people.domain.PeopleModels.EmployeeVersion;
import com.szsemicon.hr.people.domain.PeopleModels.EmploymentPeriod;
import com.szsemicon.hr.people.domain.PeopleModels.OrganizationVersion;
import com.szsemicon.hr.people.domain.PeopleModels.PriorServiceRecord;
import com.szsemicon.hr.people.domain.PeopleModels.PriorServiceReplay;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

final class PeopleManagementDtos {

    private static final String MIGRATION_ACTOR =
            "00000000-0000-0000-0000-000000000000";

    private PeopleManagementDtos() {
    }

    record OrganizationCreateRequest(
            @NotBlank String companyId,
            String parentOrganizationId,
            @NotBlank @Size(max = 128) String code,
            @NotBlank @Size(max = 200) String name,
            @NotBlank String organizationType,
            @NotNull LocalDate effectiveFrom,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    record OrganizationUpdateRequest(
            String parentOrganizationId,
            @NotBlank @Size(max = 128) String code,
            @NotBlank @Size(max = 200) String name,
            @NotBlank String organizationType,
            @NotBlank String status,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    record EmployeeCreateRequest(
            @NotBlank String companyId,
            @NotBlank @Size(max = 128) String employeeNumber,
            @NotBlank @Size(max = 100) String displayName,
            @Size(max = 128) String externalEmployeeId,
            @NotNull LocalDate effectiveFrom,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    record EmployeeUpdateRequest(
            @NotBlank @Size(max = 128) String employeeNumber,
            @NotBlank @Size(max = 100) String displayName,
            @NotBlank String status,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    record EmploymentCreateRequest(
            @NotBlank String organizationId,
            String positionId,
            @NotNull LocalDate startDate,
            LocalDate terminationDate,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    record EmploymentUpdateRequest(
            @NotBlank String organizationId,
            String positionId,
            @NotNull LocalDate startDate,
            LocalDate terminationDate,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    record PriorServiceAdjustmentRequest(
            @Min(-36500) @Max(36500) int amountDays,
            @NotNull LocalDate businessDate,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    record ReasonRequest(@NotBlank @Size(min = 2, max = 500) String reason) {
    }

    record OrganizationView(
            String organizationVersionId,
            String organizationId,
            String parentOrganizationId,
            String code,
            String name,
            String organizationType,
            String status,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String sourceAuthority,
            String sourceBatchId,
            long rowVersion,
            String changeReason,
            String createdBy,
            Instant createdAt,
            int childCount,
            String auditResourceId) {
    }

    record OrganizationVersionPage(
            List<OrganizationView> items, long total, int page, int size) {
    }

    record EmployeeVersionView(
            String employeeVersionId,
            String employeeId,
            String employeeNumber,
            String displayName,
            String status,
            String externalEmployeeId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String sourceAuthority,
            String sourceBatchId,
            long rowVersion,
            String changeReason,
            String createdBy,
            Instant createdAt) {
    }

    record EmployeeView(
            String employeeVersionId,
            String employeeId,
            String employeeNumber,
            String displayName,
            String status,
            String externalEmployeeId,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String sourceAuthority,
            String sourceBatchId,
            long rowVersion,
            String changeReason,
            String createdBy,
            Instant createdAt,
            List<EmploymentView> employmentPeriods,
            PriorServiceReplayView priorService,
            String auditResourceId) {
    }

    record EmployeeVersionPage(
            List<EmployeeVersionView> items, long total, int page, int size) {
    }

    record EmploymentView(
            String employmentPeriodId,
            String employeeId,
            String organizationId,
            String positionId,
            LocalDate startDate,
            LocalDate terminationDate,
            LocalDate endExclusive,
            String sourceBatchId,
            long rowVersion,
            String changeReason,
            String createdBy,
            Instant createdAt) {
    }

    record EmploymentPage(
            List<EmploymentView> items, long total, int page, int size) {
    }

    record PriorServiceRecordView(
            String priorServiceRecordId,
            String employeeId,
            String recordType,
            int amountDays,
            String reason,
            LocalDate businessDate,
            String sourceBatchId,
            String reversalOfRecordId,
            int resultingTotalDays,
            String actorId,
            Instant occurredAt,
            String requestId) {
    }

    record PriorServiceRecordPage(
            List<PriorServiceRecordView> items,
            int totalDays,
            String replayDigest,
            long total,
            int page,
            int size) {
    }

    record PriorServiceReplayView(
            String employeeId,
            int totalDays,
            int recordCount,
            String replayDigest,
            Instant recalculatedAt) {
    }

    static OrganizationView organization(OrganizationVersion version) {
        return new OrganizationView(
                version.organizationVersionId(), version.organizationId(),
                version.parentOrganizationId(), version.code(), version.name(),
                version.organizationType(), version.status(), version.effectiveFrom(),
                version.effectiveTo(), version.sourceAuthority(), version.sourceBatchId(),
                version.rowVersion(), version.changeReason(), actor(version.createdBy()),
                version.createdAt(), version.childCount(), version.organizationId());
    }

    static OrganizationVersionPage organizationPage(
            PeoplePage<OrganizationVersion> page) {
        return new OrganizationVersionPage(
                page.items().stream().map(PeopleManagementDtos::organization).toList(),
                page.total(), page.page(), page.size());
    }

    static EmployeeVersionView employeeVersion(EmployeeVersion version) {
        return new EmployeeVersionView(
                version.employeeVersionId(), version.employeeId(), version.employeeNumber(),
                version.displayName(), version.status(), version.externalEmployeeId(),
                version.effectiveFrom(), version.effectiveTo(), version.sourceAuthority(),
                version.sourceBatchId(), version.rowVersion(), version.changeReason(),
                actor(version.createdBy()), version.createdAt());
    }

    static EmployeeView employee(EmployeeDetail detail) {
        EmployeeVersion version = detail.employee();
        return new EmployeeView(
                version.employeeVersionId(), version.employeeId(), version.employeeNumber(),
                version.displayName(), version.status(), version.externalEmployeeId(),
                version.effectiveFrom(), version.effectiveTo(), version.sourceAuthority(),
                version.sourceBatchId(), version.rowVersion(), version.changeReason(),
                actor(version.createdBy()), version.createdAt(),
                detail.employmentPeriods().stream()
                        .map(PeopleManagementDtos::employment).toList(),
                replay(detail.priorService()), version.employeeId());
    }

    static EmployeeVersionPage employeePage(PeoplePage<EmployeeVersion> page) {
        return new EmployeeVersionPage(
                page.items().stream().map(PeopleManagementDtos::employeeVersion).toList(),
                page.total(), page.page(), page.size());
    }

    static EmploymentView employment(EmploymentPeriod period) {
        return new EmploymentView(
                period.employmentPeriodId(), period.employeeId(), period.organizationId(),
                period.positionId(), period.startDate(), period.terminationDate(),
                period.endExclusive(), period.sourceBatchId(), period.rowVersion(),
                period.changeReason(), actor(period.createdBy()), period.createdAt());
    }

    static EmploymentPage employmentPage(PeoplePage<EmploymentPeriod> page) {
        return new EmploymentPage(
                page.items().stream().map(PeopleManagementDtos::employment).toList(),
                page.total(), page.page(), page.size());
    }

    static PriorServiceRecordView priorService(PriorServiceRecord record) {
        return new PriorServiceRecordView(
                record.priorServiceRecordId(), record.employeeId(), record.recordType(),
                record.amountDays(), record.reason(), record.businessDate(),
                record.sourceBatchId(), record.reversalOfRecordId(),
                record.resultingTotalDays(), record.actorId(),
                record.occurredAt(), record.requestId());
    }

    static PriorServiceRecordPage priorServicePage(PriorServicePage page) {
        return new PriorServiceRecordPage(
                page.items().stream().map(PeopleManagementDtos::priorService).toList(),
                page.totalDays(), page.replayDigest(), page.total(), page.page(), page.size());
    }

    static PriorServiceReplayView replay(PriorServiceReplay replay) {
        return new PriorServiceReplayView(
                replay.employeeId(), replay.totalDays(), replay.recordCount(),
                replay.replayDigest(), replay.recalculatedAt());
    }

    private static String actor(String value) {
        return value == null ? MIGRATION_ACTOR : value;
    }
}
