package com.szsemicon.hr.people.application;

import com.szsemicon.hr.people.domain.PeopleModels.EmployeeVersion;
import com.szsemicon.hr.people.domain.PeopleModels.EmploymentPeriod;
import com.szsemicon.hr.people.domain.PeopleModels.IdempotencyRecord;
import com.szsemicon.hr.people.domain.PeopleModels.ImportBatch;
import com.szsemicon.hr.people.domain.PeopleModels.ImportDiff;
import com.szsemicon.hr.people.domain.PeopleModels.ImportFile;
import com.szsemicon.hr.people.domain.PeopleModels.ImportIssue;
import com.szsemicon.hr.people.domain.PeopleModels.OrganizationVersion;
import com.szsemicon.hr.people.domain.PeopleModels.PriorServiceRecord;
import com.szsemicon.hr.people.domain.PeopleModels.Publication;
import com.szsemicon.hr.people.domain.PeopleModels.Rollback;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PeopleRepository {

    boolean canAccessLegalEntity(
            String principalId, String capability, String legalEntityId, Instant at);

    boolean canAccessOrganization(
            String principalId, String capability, String organizationId, Instant at);

    boolean canAccessEmployee(
            String principalId, String capability, String employeeId, LocalDate asOf, Instant at);

    boolean legalEntityExists(String legalEntityId);

    void lockLegalEntity(String legalEntityId);

    Optional<IdempotencyRecord> findIdempotency(
            String actorId, String actionCode, String idempotencyKey);

    void saveIdempotency(
            String id, String actorId, String actionCode, String idempotencyKey,
            String requestDigest, String resourceId, String resultJson, Instant at);

    void createBatch(ImportBatch batch);

    Optional<ImportBatch> findBatch(String batchId);

    List<ImportBatch> listBatches(
            String principalId, String capability, String status, int limit, int offset, Instant at);

    long countBatches(String principalId, String capability, String status, Instant at);

    boolean updateBatchState(
            String batchId, long expectedVersion, String status, String actorId, Instant at,
            Long precheckVersion, int added, int updated, int unchanged, int conflict, int error,
            int blocking, Instant publishedAt, Instant voidedAt, String duplicateOfPublicationId);

    boolean saveBatchMapping(
            String batchId, long expectedVersion, String mappingJson, String actorId, Instant at);

    boolean saveFile(ImportFile file, long expectedVersion, String actorId, Instant at);

    List<ImportDiff> listDiffs(String batchId, String category, int limit, int offset);

    long countDiffs(String batchId, String category);

    List<ImportIssue> listIssues(String batchId, String severity, int limit, int offset);

    long countIssues(String batchId, String severity);

    void replacePrecheck(String batchId, List<ImportDiff> diffs, List<ImportIssue> issues);

    Optional<Publication> findPublicationByBatch(String batchId);

    Optional<Publication> findPublicationById(String publicationId);

    Optional<Publication> findPublicationByFileHash(
            String legalEntityId,
            String templateType,
            String templateVersion,
            String fileSha256);

    void savePublication(
            Publication publication, String idempotencyKey, String localVersionIdsJson);

    List<String> findDownstreamReferenceIds(String batchId);

    boolean hasLaterVersions(String batchId, Instant publishedAt);

    Optional<Rollback> findRollbackByPublication(String publicationId);

    void saveRollback(
            Rollback rollback,
            String idempotencyKey,
            String reason,
            String createdVersionIdsJson);

    Optional<OrganizationVersion> findCurrentOrganization(String organizationId);

    Optional<OrganizationVersion> findOrganizationVersion(String organizationVersionId);

    Optional<OrganizationVersion> findOrganizationAsOf(String organizationId, LocalDate asOf);

    Optional<OrganizationVersion> findOrganizationByCode(String legalEntityId, String code);

    List<OrganizationVersion> listOrganizationVersions(String organizationId, int limit, int offset);

    long countOrganizationVersions(String organizationId);

    boolean organizationCodeExists(String legalEntityId, String code, String excludeOrganizationId);

    boolean organizationWouldCycle(String organizationId, String parentOrganizationId);

    String createOrganizationIdentity(
            String organizationId, String legalEntityId, String status, Instant at);

    void saveOrganizationVersion(OrganizationVersion version, String projectionBatchId);

    void closeCurrentOrganizationVersion(
            String organizationId, Instant effectiveTo, long expectedVersion);

    void rebuildOrganizationClosure(String projectionBatchId);

    Optional<EmployeeVersion> findCurrentEmployee(String employeeId);

    long findEmployeeAggregateVersion(String employeeId);

    Optional<EmployeeVersion> findEmployeeVersion(String employeeVersionId);

    Optional<EmployeeVersion> findEmployeeAsOf(String employeeId, LocalDate asOf);

    Optional<EmployeeVersion> findEmployeeByNumber(String legalEntityId, String employeeNumber);

    List<EmployeeVersion> findEmployeesByExternalId(String legalEntityId, String externalEmployeeId);

    List<EmployeeVersion> listEmployeeVersions(String employeeId, int limit, int offset);

    long countEmployeeVersions(String employeeId);

    boolean employeeNumberExists(String legalEntityId, String employeeNumber, String excludeEmployeeId);

    void createEmployeeIdentity(
            String employeeId, String legalEntityId, String employeeNumber, String displayName,
            String status, LocalDate onboardDate, Instant at);

    void updateEmployeeIdentity(
            String employeeId, String employeeNumber, String displayName, String status,
            long expectedVersion, Instant at);

    void saveEmployeeVersion(EmployeeVersion version);

    void closeCurrentEmployeeVersion(String employeeId, LocalDate effectiveTo, long expectedVersion);

    List<EmploymentPeriod> listEmploymentPeriods(
            String employeeId, LocalDate asOf, int limit, int offset);

    long countEmploymentPeriods(String employeeId, LocalDate asOf);

    List<EmploymentPeriod> listAccessibleEmploymentPeriods(
            String employeeId,
            LocalDate asOf,
            String principalId,
            String capability,
            Instant at,
            int limit,
            int offset);

    long countAccessibleEmploymentPeriods(
            String employeeId,
            LocalDate asOf,
            String principalId,
            String capability,
            Instant at);

    Optional<EmploymentPeriod> findCurrentEmploymentPeriodVersion(String employmentPeriodId);

    Optional<EmploymentPeriod> findEmploymentVersion(String assignmentVersionId);

    List<EmploymentPeriod> findEmploymentVersionsBySourceBatch(String batchId);

    boolean hasEmploymentOverlap(
            String employeeId, LocalDate start, LocalDate endExclusive,
            String excludeEmploymentPeriodId);

    void lockEmployee(String employeeId);

    void touchEmployee(String employeeId, long expectedVersion, Instant at);

    void saveEmploymentPeriodVersion(EmploymentPeriod period, boolean newIdentity);

    void closeEmploymentPeriodVersion(String employmentPeriodId, long expectedVersion);

    List<PriorServiceRecord> listPriorServiceRecords(String employeeId, int limit, int offset);

    List<PriorServiceRecord> listAllPriorServiceRecords(String employeeId);

    Optional<PriorServiceRecord> findPriorServiceRecord(String priorServiceRecordId);

    List<PriorServiceRecord> findPriorServiceRecordsBySourceBatch(String batchId);

    long countPriorServiceRecords(String employeeId);

    void savePriorServiceRecord(PriorServiceRecord record);
}
