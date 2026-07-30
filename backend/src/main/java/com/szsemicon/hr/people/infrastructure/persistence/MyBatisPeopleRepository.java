package com.szsemicon.hr.people.infrastructure.persistence;

import com.szsemicon.hr.people.application.PeopleRepository;
import com.szsemicon.hr.people.domain.PeopleModels.BatchStatus;
import com.szsemicon.hr.people.domain.PeopleModels.DiffCategory;
import com.szsemicon.hr.people.domain.PeopleModels.EmployeeVersion;
import com.szsemicon.hr.people.domain.PeopleModels.EmploymentPeriod;
import com.szsemicon.hr.people.domain.PeopleModels.IdempotencyRecord;
import com.szsemicon.hr.people.domain.PeopleModels.ImportBatch;
import com.szsemicon.hr.people.domain.PeopleModels.ImportDiff;
import com.szsemicon.hr.people.domain.PeopleModels.ImportFile;
import com.szsemicon.hr.people.domain.PeopleModels.ImportIssue;
import com.szsemicon.hr.people.domain.PeopleModels.IssueSeverity;
import com.szsemicon.hr.people.domain.PeopleModels.MappingEntry;
import com.szsemicon.hr.people.domain.PeopleModels.OrganizationVersion;
import com.szsemicon.hr.people.domain.PeopleModels.PrecheckSummary;
import com.szsemicon.hr.people.domain.PeopleModels.PriorServiceRecord;
import com.szsemicon.hr.people.domain.PeopleModels.Publication;
import com.szsemicon.hr.people.domain.PeopleModels.Rollback;
import com.szsemicon.hr.people.domain.PeopleModels.TemplateType;
import com.szsemicon.hr.shared.security.ResourceNotAvailableAccessDeniedException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
public class MyBatisPeopleRepository implements PeopleRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(MyBatisPeopleRepository.class);

    private static final TypeReference<List<MappingEntry>> MAPPING_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final PeopleMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisPeopleRepository(PeopleMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean canAccessCompany(
            String principalId, String capability, String companyId, Instant at) {
        return mapper.canAccessCompany(principalId, capability, companyId, at);
    }

    @Override
    public boolean canAccessOrganization(
            String principalId, String capability, String organizationId, Instant at) {
        return mapper.canAccessOrganization(principalId, capability, organizationId, at);
    }

    @Override
    public boolean canAccessEmployee(
            String principalId, String capability, String employeeId, LocalDate asOf, Instant at) {
        return mapper.canAccessEmployee(principalId, capability, employeeId, asOf, at);
    }

    @Override
    public boolean companyExists(String companyId) {
        return mapper.companyExists(companyId);
    }

    @Override
    public void lockCompany(String companyId) {
        if (mapper.lockCompany(companyId) == null) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
    }

    @Override
    public Optional<IdempotencyRecord> findIdempotency(
            String actorId, String actionCode, String idempotencyKey) {
        return Optional.ofNullable(mapper.findIdempotency(actorId, actionCode, idempotencyKey))
                .map(row -> new IdempotencyRecord(
                        row.actorId(),
                        row.actionCode(),
                        row.idempotencyKey(),
                        row.requestDigest(),
                        row.resourceId(),
                        row.resultJson()));
    }

    @Override
    public void saveIdempotency(
            String id, String actorId, String actionCode, String idempotencyKey,
            String requestDigest, String resourceId, String resultJson, Instant at) {
        mapper.insertIdempotency(
                id, actorId, actionCode, idempotencyKey, requestDigest, resourceId, resultJson, at);
    }

    @Override
    public void createBatch(ImportBatch batch) {
        mapper.insertBatch(toBatchRow(batch));
    }

    @Override
    public Optional<ImportBatch> findBatch(String batchId) {
        return Optional.ofNullable(mapper.findBatch(batchId)).map(this::toBatch);
    }

    @Override
    public List<ImportBatch> listBatches(
            String principalId, String capability, String status, int limit, int offset, Instant at) {
        return mapper.listBatches(principalId, capability, status, limit, offset, at).stream()
                .map(this::toBatch)
                .toList();
    }

    @Override
    public long countBatches(
            String principalId, String capability, String status, Instant at) {
        return mapper.countBatches(principalId, capability, status, at);
    }

    @Override
    public boolean updateBatchState(
            String batchId, long expectedVersion, String status, String actorId, Instant at,
            Long precheckVersion, int added, int updated, int unchanged, int conflict, int error,
            int blocking, Instant publishedAt, Instant voidedAt,
            String duplicateOfPublicationId) {
        return mapper.updateBatchState(
                batchId, expectedVersion, status, actorId, at, precheckVersion,
                added, updated, unchanged, conflict, error, blocking,
                publishedAt, voidedAt, duplicateOfPublicationId) == 1;
    }

    @Override
    public boolean saveBatchMapping(
            String batchId, long expectedVersion, String mappingJson, String actorId, Instant at) {
        return mapper.updateBatchMapping(
                batchId, expectedVersion, mappingJson, actorId, at) == 1;
    }

    @Override
    @Transactional
    public boolean saveFile(
            ImportFile file, long expectedVersion, String actorId, Instant at) {
        PeopleRows.BatchRow row = new PeopleRows.BatchRow(
                file.batchId(), null, null, null, null, null, null, null,
                0, 0, 0, 0, 0, 0, null, 0, null, null, null, null,
                null, null, null, file.fileId(), file.originalFileName(), file.mediaType(),
                file.sizeBytes(), file.sha256(), file.content(), file.uploadedBy(),
                file.uploadedAt());
        mapper.insertFile(row);
        if (mapper.attachFile(
                file.batchId(), expectedVersion, file.sha256(), actorId, at) != 1) {
            throw new OptimisticLockingFailureException("people import file version conflict");
        }
        return true;
    }

    @Override
    public List<ImportDiff> listDiffs(
            String batchId, String category, int limit, int offset) {
        return mapper.listDiffs(batchId, category, limit, offset).stream()
                .map(this::toDiff)
                .toList();
    }

    @Override
    public long countDiffs(String batchId, String category) {
        return mapper.countDiffs(batchId, category);
    }

    @Override
    public List<ImportIssue> listIssues(
            String batchId, String severity, int limit, int offset) {
        return mapper.listIssues(batchId, severity, limit, offset).stream()
                .map(this::toIssue)
                .toList();
    }

    @Override
    public long countIssues(String batchId, String severity) {
        return mapper.countIssues(batchId, severity);
    }

    @Override
    @Transactional
    public void replacePrecheck(
            String batchId, List<ImportDiff> diffs, List<ImportIssue> issues) {
        mapper.deletePrecheckDiffs(batchId);
        mapper.deletePrecheckIssues(batchId);
        diffs.forEach(diff -> mapper.insertDiff(new PeopleRows.DiffRow(
                diff.diffId(),
                diff.batchId(),
                diff.rowNumber(),
                diff.entityType().name(),
                diff.category().name(),
                diff.matchedResourceId(),
                writeJson(diff.sourceValues()),
                writeNullableJson(diff.currentValues()),
                writeNullableJson(diff.proposedValues()))));
        issues.forEach(issue -> mapper.insertIssue(new PeopleRows.IssueRow(
                issue.issueId(),
                issue.batchId(),
                issue.rowNumber(),
                issue.field(),
                issue.code(),
                issue.message(),
                issue.severity().name(),
                writeJson(issue.candidateEmployeeIds()))));
    }

    @Override
    public Optional<Publication> findPublicationByBatch(String batchId) {
        return Optional.ofNullable(mapper.findPublicationByBatch(batchId))
                .map(row -> toPublication(row, false, null));
    }

    @Override
    public Optional<Publication> findPublicationById(String publicationId) {
        return Optional.ofNullable(mapper.findPublicationById(publicationId))
                .map(row -> toPublication(row, false, null));
    }

    @Override
    public Optional<Publication> findPublicationByFileHash(
            String companyId,
            String templateType,
            String templateVersion,
            String fileSha256) {
        return Optional.ofNullable(mapper.findPublicationByFileHash(
                        companyId, templateType, templateVersion, fileSha256))
                .map(row -> toPublication(row, false, null));
    }

    @Override
    public void savePublication(
            Publication publication, String idempotencyKey, String localVersionIdsJson) {
        mapper.insertPublication(new PeopleRows.PublicationRow(
                publication.publicationId(),
                publication.batchId(),
                publication.companyId(),
                publication.templateType().name(),
                publication.templateVersion(),
                publication.fileSha256(),
                publication.snapshotDigest(),
                publication.snapshotJson(),
                localVersionIdsJson,
                publication.publishedBy(),
                publication.publishedAt()), idempotencyKey);
    }

    @Override
    public List<String> findDownstreamReferenceIds(String batchId) {
        return List.copyOf(mapper.findDownstreamReferenceIds(batchId));
    }

    @Override
    public boolean hasLaterVersions(String batchId, Instant publishedAt) {
        return mapper.hasLaterOrganizationVersions(batchId, publishedAt)
                || mapper.hasLaterEmployeeVersions(batchId, publishedAt)
                || mapper.hasLaterEmploymentVersions(batchId, publishedAt)
                || mapper.hasLaterPriorServiceRecords(batchId, publishedAt);
    }

    @Override
    public Optional<Rollback> findRollbackByPublication(String publicationId) {
        return Optional.ofNullable(mapper.findRollbackByPublication(publicationId))
                .map(row -> new Rollback(
                        row.rollbackId(),
                        row.batchId(),
                        row.sourcePublicationId(),
                        row.restoredSnapshotDigest(),
                        readStringList(row.createdVersionIdsJson()),
                        row.rolledBackBy(),
                        row.rolledBackAt()));
    }

    @Override
    public void saveRollback(
            Rollback rollback,
            String idempotencyKey,
            String reason,
            String createdVersionIdsJson) {
        mapper.insertRollback(new PeopleRows.RollbackRow(
                rollback.rollbackId(),
                rollback.batchId(),
                rollback.sourcePublicationId(),
                rollback.restoredSnapshotDigest(),
                createdVersionIdsJson,
                rollback.rolledBackBy(),
                rollback.rolledBackAt()), idempotencyKey, reason);
    }

    @Override
    public Optional<OrganizationVersion> findCurrentOrganization(String organizationId) {
        return Optional.ofNullable(mapper.findCurrentOrganization(organizationId))
                .map(MyBatisPeopleRepository::toOrganization);
    }

    @Override
    public Optional<OrganizationVersion> findOrganizationVersion(
            String organizationVersionId) {
        return Optional.ofNullable(mapper.findOrganizationVersion(organizationVersionId))
                .map(MyBatisPeopleRepository::toOrganization);
    }

    @Override
    public Optional<OrganizationVersion> findOrganizationAsOf(
            String organizationId, LocalDate asOf) {
        return Optional.ofNullable(mapper.findOrganizationAsOf(organizationId, asOf))
                .map(MyBatisPeopleRepository::toOrganization);
    }

    @Override
    public Optional<OrganizationVersion> findOrganizationByCode(
            String companyId, String code) {
        return Optional.ofNullable(mapper.findOrganizationByCode(companyId, code))
                .map(MyBatisPeopleRepository::toOrganization);
    }

    @Override
    public List<OrganizationVersion> listOrganizationVersions(
            String organizationId, int limit, int offset) {
        return mapper.listOrganizationVersions(organizationId, limit, offset).stream()
                .map(MyBatisPeopleRepository::toOrganization)
                .toList();
    }

    @Override
    public long countOrganizationVersions(String organizationId) {
        return mapper.countOrganizationVersions(organizationId);
    }

    @Override
    public boolean organizationCodeExists(
            String companyId, String code, String excludeOrganizationId) {
        return mapper.organizationCodeExists(companyId, code, excludeOrganizationId);
    }

    @Override
    public boolean organizationWouldCycle(
            String organizationId, String parentOrganizationId) {
        return mapper.organizationWouldCycle(organizationId, parentOrganizationId);
    }

    @Override
    public String createOrganizationIdentity(
            String organizationId, String companyId, String status, Instant at) {
        mapper.insertOrganizationIdentity(organizationId, companyId, status, at);
        return organizationId;
    }

    @Override
    public void saveOrganizationVersion(
            OrganizationVersion version, String projectionBatchId) {
        mapper.insertOrganizationVersion(toOrganizationRow(version));
        mapper.updateOrganizationIdentityStatus(version.organizationId(), version.status());
        mapper.upsertOrganizationProjection(
                version.organizationId(),
                version.organizationVersionId(),
                projectionBatchId,
                version.createdAt());
    }

    @Override
    public void closeCurrentOrganizationVersion(
            String organizationId, Instant effectiveTo, long expectedVersion) {
        if (mapper.closeCurrentOrganizationVersion(
                organizationId, effectiveTo, expectedVersion) != 1) {
            throw new OptimisticLockingFailureException("organization version conflict");
        }
    }

    @Override
    @Transactional
    public void rebuildOrganizationClosure(String projectionBatchId) {
        mapper.deleteOrganizationClosure();
        mapper.rebuildOrganizationClosure(projectionBatchId);
    }

    @Override
    public Optional<EmployeeVersion> findCurrentEmployee(String employeeId) {
        return Optional.ofNullable(mapper.findCurrentEmployee(employeeId))
                .map(MyBatisPeopleRepository::toEmployee);
    }

    @Override
    public long findEmployeeAggregateVersion(String employeeId) {
        Long version = mapper.findEmployeeAggregateVersion(employeeId);
        if (version == null) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        return version;
    }

    @Override
    public Optional<EmployeeVersion> findEmployeeVersion(String employeeVersionId) {
        return Optional.ofNullable(mapper.findEmployeeVersion(employeeVersionId))
                .map(MyBatisPeopleRepository::toEmployee);
    }

    @Override
    public Optional<EmployeeVersion> findEmployeeAsOf(String employeeId, LocalDate asOf) {
        return Optional.ofNullable(mapper.findEmployeeAsOf(employeeId, asOf))
                .map(MyBatisPeopleRepository::toEmployee);
    }

    @Override
    public Optional<EmployeeVersion> findEmployeeByNumber(
            String companyId, String employeeNumber) {
        return Optional.ofNullable(mapper.findEmployeeByNumber(companyId, employeeNumber))
                .map(MyBatisPeopleRepository::toEmployee);
    }

    @Override
    public List<EmployeeVersion> findEmployeesByExternalId(
            String companyId, String externalEmployeeId) {
        return mapper.findEmployeesByExternalId(companyId, externalEmployeeId).stream()
                .map(MyBatisPeopleRepository::toEmployee)
                .toList();
    }

    @Override
    public List<EmployeeVersion> listEmployeeVersions(
            String employeeId, int limit, int offset) {
        return mapper.listEmployeeVersions(employeeId, limit, offset).stream()
                .map(MyBatisPeopleRepository::toEmployee)
                .toList();
    }

    @Override
    public long countEmployeeVersions(String employeeId) {
        return mapper.countEmployeeVersions(employeeId);
    }

    @Override
    public boolean employeeNumberExists(
            String companyId, String employeeNumber, String excludeEmployeeId) {
        return mapper.employeeNumberExists(companyId, employeeNumber, excludeEmployeeId);
    }

    @Override
    public void createEmployeeIdentity(
            String employeeId, String companyId, String employeeNumber, String displayName,
            String status, LocalDate onboardDate, Instant at) {
        mapper.insertEmployeeIdentity(
                employeeId, companyId, employeeNumber, displayName,
                status, onboardDate, at);
    }

    @Override
    public void updateEmployeeIdentity(
            String employeeId, String employeeNumber, String displayName, String status,
            long expectedVersion, Instant at) {
        if (mapper.updateEmployeeIdentity(
                employeeId, employeeNumber, displayName, status, expectedVersion, at) != 1) {
            throw new OptimisticLockingFailureException("employee version conflict");
        }
    }

    @Override
    public void saveEmployeeVersion(EmployeeVersion version) {
        mapper.insertEmployeeVersion(toEmployeeRow(version));
        mapper.upsertEmployeeProjection(
                version.employeeId(), version.employeeVersionId(), version.createdAt());
    }

    @Override
    public void closeCurrentEmployeeVersion(
            String employeeId, LocalDate effectiveTo, long expectedVersion) {
        if (mapper.closeCurrentEmployeeVersion(employeeId, effectiveTo, expectedVersion) != 1) {
            throw new OptimisticLockingFailureException("employee version conflict");
        }
    }

    @Override
    public List<EmploymentPeriod> listEmploymentPeriods(
            String employeeId, LocalDate asOf, int limit, int offset) {
        return mapper.listEmploymentPeriods(employeeId, asOf, limit, offset).stream()
                .map(MyBatisPeopleRepository::toEmployment)
                .toList();
    }

    @Override
    public long countEmploymentPeriods(String employeeId, LocalDate asOf) {
        return mapper.countEmploymentPeriods(employeeId, asOf);
    }

    @Override
    public List<EmploymentPeriod> listAccessibleEmploymentPeriods(
            String employeeId,
            LocalDate asOf,
            String principalId,
            String capability,
            Instant at,
            int limit,
            int offset) {
        return mapper.listAccessibleEmploymentPeriods(
                        employeeId, asOf, principalId, capability, at, limit, offset)
                .stream()
                .map(MyBatisPeopleRepository::toEmployment)
                .toList();
    }

    @Override
    public long countAccessibleEmploymentPeriods(
            String employeeId,
            LocalDate asOf,
            String principalId,
            String capability,
            Instant at) {
        return mapper.countAccessibleEmploymentPeriods(
                employeeId, asOf, principalId, capability, at);
    }

    @Override
    public Optional<EmploymentPeriod> findCurrentEmploymentPeriodVersion(
            String employmentPeriodId) {
        return Optional.ofNullable(mapper.findCurrentEmploymentPeriodVersion(employmentPeriodId))
                .map(MyBatisPeopleRepository::toEmployment);
    }

    @Override
    public Optional<EmploymentPeriod> findEmploymentVersion(String assignmentVersionId) {
        return Optional.ofNullable(mapper.findEmploymentVersion(assignmentVersionId))
                .map(MyBatisPeopleRepository::toEmployment);
    }

    @Override
    public List<EmploymentPeriod> findEmploymentVersionsBySourceBatch(String batchId) {
        return mapper.findEmploymentVersionsBySourceBatch(batchId).stream()
                .map(MyBatisPeopleRepository::toEmployment)
                .toList();
    }

    @Override
    public boolean hasEmploymentOverlap(
            String employeeId, LocalDate start, LocalDate endExclusive,
            String excludeEmploymentPeriodId) {
        return mapper.hasEmploymentOverlap(
                employeeId, start, endExclusive, excludeEmploymentPeriodId);
    }

    @Override
    public void lockEmployee(String employeeId) {
        mapper.lockEmployee(employeeId);
    }

    @Override
    public void touchEmployee(String employeeId, long expectedVersion, Instant at) {
        if (mapper.touchEmployee(employeeId, expectedVersion, at) != 1) {
            throw new OptimisticLockingFailureException("employee aggregate version conflict");
        }
    }

    @Override
    public void saveEmploymentPeriodVersion(EmploymentPeriod period, boolean newIdentity) {
        mapper.insertEmploymentPeriodVersion(toEmploymentRow(period), newIdentity);
    }

    @Override
    public void closeEmploymentPeriodVersion(
            String employmentPeriodId, long expectedVersion) {
        if (mapper.closeEmploymentPeriodVersion(
                employmentPeriodId, expectedVersion) != 1) {
            throw new OptimisticLockingFailureException("employment period version conflict");
        }
    }

    @Override
    public List<PriorServiceRecord> listPriorServiceRecords(
            String employeeId, int limit, int offset) {
        return mapper.listPriorServiceRecords(employeeId, limit, offset).stream()
                .map(MyBatisPeopleRepository::toPriorService)
                .toList();
    }

    @Override
    public List<PriorServiceRecord> listAllPriorServiceRecords(String employeeId) {
        return mapper.listAllPriorServiceRecords(employeeId).stream()
                .map(MyBatisPeopleRepository::toPriorService)
                .toList();
    }

    @Override
    public Optional<PriorServiceRecord> findPriorServiceRecord(String priorServiceRecordId) {
        return Optional.ofNullable(mapper.findPriorServiceRecord(priorServiceRecordId))
                .map(MyBatisPeopleRepository::toPriorService);
    }

    @Override
    public List<PriorServiceRecord> findPriorServiceRecordsBySourceBatch(String batchId) {
        return mapper.findPriorServiceRecordsBySourceBatch(batchId).stream()
                .map(MyBatisPeopleRepository::toPriorService)
                .toList();
    }

    @Override
    public long countPriorServiceRecords(String employeeId) {
        return mapper.countPriorServiceRecords(employeeId);
    }

    @Override
    public void savePriorServiceRecord(PriorServiceRecord record) {
        mapper.insertPriorServiceRecord(toPriorServiceRow(record));
    }

    private PeopleRows.BatchRow toBatchRow(ImportBatch batch) {
        ImportFile file = batch.file();
        return new PeopleRows.BatchRow(
                batch.batchId(),
                batch.companyId(),
                batch.templateType().name(),
                batch.templateVersion(),
                batch.status().name(),
                batch.reason(),
                batch.fileSha256(),
                writeJson(batch.mapping()),
                batch.summary().added(),
                batch.summary().updated(),
                batch.summary().unchanged(),
                batch.summary().conflict(),
                batch.summary().error(),
                batch.summary().blockingIssueCount(),
                batch.precheckVersion(),
                batch.rowVersion(),
                batch.createdBy(),
                batch.createdAt(),
                batch.updatedBy(),
                batch.updatedAt(),
                batch.publishedAt(),
                batch.voidedAt(),
                batch.duplicateOfPublicationId(),
                file == null ? null : file.fileId(),
                file == null ? null : file.originalFileName(),
                file == null ? null : file.mediaType(),
                file == null ? null : file.sizeBytes(),
                file == null ? null : file.sha256(),
                file == null ? null : file.content(),
                file == null ? null : file.uploadedBy(),
                file == null ? null : file.uploadedAt());
    }

    private ImportBatch toBatch(PeopleRows.BatchRow row) {
        ImportFile file = row.fileId() == null ? null : new ImportFile(
                row.fileId(),
                row.batchId(),
                row.originalFileName(),
                row.mediaType(),
                row.sizeBytes(),
                row.storedFileSha256(),
                row.content(),
                row.uploadedBy(),
                row.uploadedAt());
        return new ImportBatch(
                row.batchId(),
                row.companyId(),
                TemplateType.valueOf(row.templateType()),
                row.templateVersion(),
                BatchStatus.valueOf(row.status()),
                row.reason(),
                row.fileSha256(),
                readMappings(row.mappingJson()),
                new PrecheckSummary(
                        row.addedCount(),
                        row.updatedCount(),
                        row.unchangedCount(),
                        row.conflictCount(),
                        row.errorCount(),
                        row.blockingIssueCount()),
                row.precheckVersion(),
                row.rowVersion(),
                row.createdBy(),
                row.createdAt(),
                row.updatedBy(),
                row.updatedAt(),
                row.publishedAt(),
                row.voidedAt(),
                row.duplicateOfPublicationId(),
                file);
    }

    private ImportDiff toDiff(PeopleRows.DiffRow row) {
        return new ImportDiff(
                row.diffId(),
                row.batchId(),
                row.rowNumber(),
                TemplateType.valueOf(row.entityType()),
                DiffCategory.valueOf(row.category()),
                row.matchedResourceId(),
                readMap(row.sourceValuesJson()),
                readNullableMap(row.currentValuesJson()),
                readNullableMap(row.proposedValuesJson()));
    }

    private ImportIssue toIssue(PeopleRows.IssueRow row) {
        return new ImportIssue(
                row.issueId(),
                row.batchId(),
                row.rowNumber(),
                row.fieldName(),
                row.issueCode(),
                row.message(),
                IssueSeverity.valueOf(row.severity()),
                readStringList(row.candidateEmployeeIdsJson()));
    }

    private Publication toPublication(
            PeopleRows.PublicationRow row, boolean deduplicated, String duplicateOf) {
        PeopleRows.BatchRow batch = row.templateType() == null
                ? mapper.findBatch(row.batchId())
                : null;
        return new Publication(
                row.publicationId(),
                row.batchId(),
                row.companyId() == null ? batch.companyId() : row.companyId(),
                TemplateType.valueOf(
                        row.templateType() == null ? batch.templateType() : row.templateType()),
                row.templateVersion() == null
                        ? batch.templateVersion()
                        : row.templateVersion(),
                row.fileSha256(),
                row.snapshotDigest(),
                readStringList(row.localVersionIdsJson()),
                deduplicated,
                duplicateOf,
                row.publishedBy(),
                row.publishedAt(),
                row.snapshotJson());
    }

    private static OrganizationVersion toOrganization(PeopleRows.OrganizationRow row) {
        return new OrganizationVersion(
                row.organizationVersionId(),
                row.organizationId(),
                row.companyId(),
                row.parentOrganizationId(),
                row.code(),
                row.name(),
                row.organizationType(),
                row.status(),
                row.effectiveFrom(),
                row.effectiveTo(),
                row.sourceAuthority(),
                row.sourceImportBatchId(),
                row.rowVersion(),
                row.changeReason(),
                row.createdBy(),
                row.createdAt(),
                row.childCount());
    }

    private static PeopleRows.OrganizationRow toOrganizationRow(OrganizationVersion version) {
        return new PeopleRows.OrganizationRow(
                version.organizationVersionId(),
                version.organizationId(),
                version.companyId(),
                version.parentOrganizationId(),
                version.code(),
                version.name(),
                version.organizationType(),
                version.status(),
                version.effectiveFrom(),
                version.effectiveTo(),
                version.sourceAuthority(),
                version.sourceBatchId(),
                version.rowVersion(),
                version.changeReason(),
                version.createdBy(),
                version.createdAt(),
                version.childCount());
    }

    private static EmployeeVersion toEmployee(PeopleRows.EmployeeRow row) {
        return new EmployeeVersion(
                row.employeeVersionId(),
                row.employeeId(),
                row.companyId(),
                row.employeeNumber(),
                row.displayName(),
                row.status(),
                row.externalEmployeeId(),
                row.effectiveFrom(),
                row.effectiveTo(),
                row.sourceAuthority(),
                row.sourceImportBatchId(),
                row.rowVersion(),
                row.changeReason(),
                row.createdBy(),
                row.createdAt());
    }

    private static PeopleRows.EmployeeRow toEmployeeRow(EmployeeVersion version) {
        return new PeopleRows.EmployeeRow(
                version.employeeVersionId(),
                version.employeeId(),
                version.companyId(),
                version.employeeNumber(),
                version.displayName(),
                version.status(),
                version.externalEmployeeId(),
                version.effectiveFrom(),
                version.effectiveTo(),
                version.sourceAuthority(),
                version.sourceBatchId(),
                version.rowVersion(),
                version.changeReason(),
                version.createdBy(),
                version.createdAt());
    }

    private static EmploymentPeriod toEmployment(PeopleRows.EmploymentRow row) {
        return new EmploymentPeriod(
                row.employmentPeriodId(),
                row.assignmentId(),
                row.employeeId(),
                row.organizationId(),
                row.positionId(),
                row.startDate(),
                row.terminationDate(),
                row.endExclusive(),
                row.recordStatus(),
                row.sourceImportBatchId(),
                row.rowVersion(),
                row.changeReason(),
                row.createdBy(),
                row.createdAt());
    }

    private static PeopleRows.EmploymentRow toEmploymentRow(EmploymentPeriod period) {
        return new PeopleRows.EmploymentRow(
                period.employmentPeriodId(),
                period.assignmentVersionId(),
                period.employeeId(),
                period.organizationId(),
                period.positionId(),
                period.startDate(),
                period.terminationDate(),
                period.endExclusive(),
                period.recordStatus(),
                period.sourceBatchId(),
                period.rowVersion(),
                period.changeReason(),
                period.createdBy(),
                period.createdAt());
    }

    private static PriorServiceRecord toPriorService(PeopleRows.PriorServiceRow row) {
        return new PriorServiceRecord(
                row.priorServiceRecordId(),
                row.employeeId(),
                row.recordType(),
                row.amountDays(),
                row.reason(),
                row.businessDate(),
                row.sourceImportBatchId(),
                row.reversalOfRecordId(),
                row.resultingTotalDays(),
                row.actorId(),
                row.occurredAt(),
                row.requestId(),
                row.rowVersion());
    }

    private static PeopleRows.PriorServiceRow toPriorServiceRow(PriorServiceRecord record) {
        return new PeopleRows.PriorServiceRow(
                record.priorServiceRecordId(),
                record.employeeId(),
                record.recordType(),
                record.amountDays(),
                record.reason(),
                record.businessDate(),
                record.sourceBatchId(),
                record.reversalOfRecordId(),
                record.resultingTotalDays(),
                record.actorId(),
                record.occurredAt(),
                record.requestId(),
                record.rowVersion());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw serializationFailure("write-people-json", exception);
        }
    }

    private String writeNullableJson(Object value) {
        return value == null ? null : writeJson(value);
    }

    private List<MappingEntry> readMappings(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, MAPPING_TYPE);
        } catch (Exception exception) {
            throw serializationFailure("read-import-mapping", exception);
        }
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (Exception exception) {
            throw serializationFailure("read-string-list", exception);
        }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception exception) {
            throw serializationFailure("read-people-json", exception);
        }
    }

    private Map<String, Object> readNullableMap(String json) {
        return json == null ? null : readMap(json);
    }

    private IllegalStateException serializationFailure(String operation, Exception exception) {
        LOGGER.error(
                "event=people_persistence_json_failure operation={} causeType={}",
                operation,
                exception.getClass().getName());
        return new IllegalStateException("Stored people data could not be processed", exception);
    }
}
