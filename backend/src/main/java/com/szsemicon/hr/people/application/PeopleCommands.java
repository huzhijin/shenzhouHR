package com.szsemicon.hr.people.application;

import com.szsemicon.hr.people.domain.PeopleModels.MappingEntry;
import com.szsemicon.hr.people.domain.PeopleModels.TemplateType;
import java.time.LocalDate;
import java.util.List;

public final class PeopleCommands {

    private PeopleCommands() {
    }

    public record CreateImportBatch(
            String legalEntityId,
            TemplateType templateType,
            String templateVersion,
            String reason) {
    }

    public record ReplaceMapping(List<MappingEntry> entries, String reason) {
    }

    public record PublishImport(
            String reason,
            String confirmedFileSha256,
            long confirmedPrecheckVersion) {
    }

    public record RollbackImport(String reason, String confirmedPublicationId) {
    }

    public record CreateOrganization(
            String legalEntityId,
            String parentOrganizationId,
            String code,
            String name,
            String organizationType,
            LocalDate effectiveFrom,
            String reason) {
    }

    public record UpdateOrganization(
            String parentOrganizationId,
            String code,
            String name,
            String organizationType,
            String status,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String reason) {
    }

    public record CreateEmployee(
            String legalEntityId,
            String employeeNumber,
            String displayName,
            String externalEmployeeId,
            LocalDate effectiveFrom,
            String reason) {
    }

    public record UpdateEmployee(
            String employeeNumber,
            String displayName,
            String status,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String reason) {
    }

    public record CreateEmployment(
            String organizationId,
            String positionId,
            LocalDate startDate,
            LocalDate terminationDate,
            String reason) {
    }

    public record UpdateEmployment(
            String organizationId,
            String positionId,
            LocalDate startDate,
            LocalDate terminationDate,
            String reason) {
    }

    public record AdjustPriorService(
            int amountDays,
            LocalDate businessDate,
            String reason) {
    }
}
