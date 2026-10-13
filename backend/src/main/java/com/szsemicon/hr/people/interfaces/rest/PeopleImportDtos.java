package com.szsemicon.hr.people.interfaces.rest;

import com.szsemicon.hr.people.application.PeoplePage;
import com.szsemicon.hr.people.domain.PeopleModels.ImportBatch;
import com.szsemicon.hr.people.domain.PeopleModels.ImportDiff;
import com.szsemicon.hr.people.domain.PeopleModels.ImportFile;
import com.szsemicon.hr.people.domain.PeopleModels.ImportIssue;
import com.szsemicon.hr.people.domain.PeopleModels.MappingEntry;
import com.szsemicon.hr.people.domain.PeopleModels.Publication;
import com.szsemicon.hr.people.domain.PeopleModels.Rollback;
import com.szsemicon.hr.people.domain.PeopleModels.TemplateField;
import com.szsemicon.hr.people.domain.PeopleModels.TemplateVersion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

final class PeopleImportDtos {

    private PeopleImportDtos() {
    }

    record CreateRequest(
            @NotBlank String companyId,
            @NotNull com.szsemicon.hr.people.domain.PeopleModels.TemplateType templateType,
            @NotBlank String templateVersion,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    record MappingEntryRequest(
            @NotBlank @Size(max = 100) String sourceColumn,
            @NotBlank @Pattern(regexp = "^[a-z][a-zA-Z0-9]*$") String targetField) {
    }

    record MappingRequest(
            @NotEmpty List<@Valid MappingEntryRequest> entries,
            @NotBlank @Size(min = 2, max = 500) String reason) {
    }

    record PublishRequest(
            @NotBlank @Size(min = 2, max = 500) String reason,
            @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String confirmedFileSha256,
            @Min(1) long confirmedPrecheckVersion) {
    }

    record RollbackRequest(
            @NotBlank @Size(min = 2, max = 500) String reason,
            @NotBlank String confirmedPublicationId) {
    }

    record ReasonRequest(@NotBlank @Size(min = 2, max = 500) String reason) {
    }

    record TemplateFieldView(
            String key,
            String label,
            boolean required,
            String valueType,
            boolean matchKey,
            String description,
            List<String> enumValues) {
    }

    record TemplateVersionView(
            String templateType,
            String templateVersion,
            String fileName,
            String sha256,
            Instant publishedAt,
            List<TemplateFieldView> fields) {
    }

    record TemplatePage(
            List<TemplateVersionView> items, long total, int page, int size) {
    }

    record PrecheckSummaryView(
            int added,
            int updated,
            int unchanged,
            int conflict,
            int error,
            int blockingIssueCount) {
    }

    record FileView(
            String fileId,
            String originalFileName,
            String mediaType,
            long sizeBytes,
            String sha256,
            String uploadedBy,
            Instant uploadedAt) {
    }

    record PublicationView(
            String publicationId,
            String batchId,
            String snapshotDigest,
            List<String> localVersionIds,
            boolean deduplicated,
            String duplicateOfPublicationId,
            String publishedBy,
            Instant publishedAt) {
    }

    record BatchView(
            String batchId,
            String companyId,
            String templateType,
            String templateVersion,
            String status,
            String fileSha256,
            Long precheckVersion,
            PrecheckSummaryView precheckSummary,
            long rowVersion,
            String createdBy,
            Instant createdAt,
            Instant updatedAt,
            Instant publishedAt,
            FileView file,
            List<MappingEntry> mapping,
            PublicationView publication,
            String auditResourceId) {
    }

    record BatchPage(List<BatchView> items, long total, int page, int size) {
    }

    record DiffView(
            String diffId,
            int rowNumber,
            String entityType,
            String category,
            String matchedResourceId,
            Map<String, Object> sourceValues,
            Map<String, Object> currentValues,
            Map<String, Object> proposedValues) {
    }

    record DiffPage(
            PrecheckSummaryView summary,
            List<DiffView> items,
            long total,
            int page,
            int size) {
    }

    record IssueView(
            String issueId,
            int rowNumber,
            String field,
            String code,
            String message,
            String severity,
            List<String> candidateEmployeeIds) {
    }

    record IssuePage(List<IssueView> items, long total, int page, int size) {
    }

    record RollbackView(
            String rollbackId,
            String batchId,
            String sourcePublicationId,
            String restoredSnapshotDigest,
            List<String> createdVersionIds,
            String rolledBackBy,
            Instant rolledBackAt) {
    }

    static TemplatePage templatePage(PeoplePage<TemplateVersion> page) {
        return new TemplatePage(
                page.items().stream().map(PeopleImportDtos::template).toList(),
                page.total(), page.page(), page.size());
    }

    static TemplateVersionView template(TemplateVersion version) {
        return new TemplateVersionView(
                version.templateType().name(),
                version.templateVersion(),
                version.fileName(),
                version.sha256(),
                version.publishedAt(),
                version.fields().stream().map(PeopleImportDtos::field).toList());
    }

    static TemplateFieldView field(TemplateField field) {
        return new TemplateFieldView(
                field.key(), field.label(), field.required(), field.valueType(),
                field.matchKey(), field.description(), field.enumValues());
    }

    static BatchView batch(ImportBatch batch, Publication publication) {
        return new BatchView(
                batch.batchId(),
                batch.companyId(),
                batch.templateType().name(),
                batch.templateVersion(),
                batch.status().name(),
                batch.fileSha256(),
                batch.precheckVersion(),
                batch.precheckVersion() == null ? null : summary(batch),
                batch.rowVersion(),
                batch.createdBy(),
                batch.createdAt(),
                batch.updatedAt(),
                batch.publishedAt(),
                file(batch.file()),
                batch.mapping(),
                publication(publication),
                batch.batchId());
    }

    static BatchPage batchPage(PeoplePage<ImportBatch> page) {
        return new BatchPage(
                page.items().stream().map(batch -> batch(batch, null)).toList(),
                page.total(), page.page(), page.size());
    }

    static FileView file(ImportFile file) {
        return file == null ? null : new FileView(
                file.fileId(), file.originalFileName(), file.mediaType(), file.sizeBytes(),
                file.sha256(), file.uploadedBy(), file.uploadedAt());
    }

    static PrecheckSummaryView summary(ImportBatch batch) {
        return new PrecheckSummaryView(
                batch.summary().added(), batch.summary().updated(),
                batch.summary().unchanged(), batch.summary().conflict(),
                batch.summary().error(), batch.summary().blockingIssueCount());
    }

    static PublicationView publication(Publication publication) {
        return publication == null ? null : new PublicationView(
                publication.publicationId(),
                publication.batchId(),
                publication.snapshotDigest(),
                publication.localVersionIds(),
                publication.deduplicated(),
                publication.duplicateOfPublicationId(),
                publication.publishedBy(),
                publication.publishedAt());
    }

    static DiffPage diffPage(PeoplePage<ImportDiff> page, ImportBatch batch) {
        return new DiffPage(
                summary(batch),
                page.items().stream().map(PeopleImportDtos::diff).toList(),
                page.total(), page.page(), page.size());
    }

    static DiffView diff(ImportDiff diff) {
        return new DiffView(
                diff.diffId(), diff.rowNumber(), diff.entityType().name(),
                diff.category().name(), diff.matchedResourceId(),
                diff.sourceValues(), diff.currentValues(), diff.proposedValues());
    }

    static IssuePage issuePage(PeoplePage<ImportIssue> page) {
        return new IssuePage(
                page.items().stream().map(PeopleImportDtos::issue).toList(),
                page.total(), page.page(), page.size());
    }

    static IssueView issue(ImportIssue issue) {
        return new IssueView(
                issue.issueId(), issue.rowNumber(), issue.field(), issue.code(),
                issue.message(), issue.severity().name(), issue.candidateEmployeeIds());
    }

    static RollbackView rollback(Rollback rollback) {
        return new RollbackView(
                rollback.rollbackId(), rollback.batchId(), rollback.sourcePublicationId(),
                rollback.restoredSnapshotDigest(), rollback.createdVersionIds(),
                rollback.rolledBackBy(), rollback.rolledBackAt());
    }
}
