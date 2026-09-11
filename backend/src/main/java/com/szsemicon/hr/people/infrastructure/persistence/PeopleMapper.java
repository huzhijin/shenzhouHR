package com.szsemicon.hr.people.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface PeopleMapper {

    boolean canAccessCompany(
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("companyId") String companyId,
            @Param("at") Instant at);

    boolean canAccessOrganization(
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("organizationId") String organizationId,
            @Param("at") Instant at);

    boolean canAccessEmployee(
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("employeeId") String employeeId,
            @Param("asOf") LocalDate asOf,
            @Param("at") Instant at);

    boolean companyExists(@Param("companyId") String companyId);

    String lockCompany(@Param("companyId") String companyId);

    PeopleRows.IdempotencyRow findIdempotency(
            @Param("actorId") String actorId,
            @Param("actionCode") String actionCode,
            @Param("idempotencyKey") String idempotencyKey);

    void insertIdempotency(
            @Param("id") String id,
            @Param("actorId") String actorId,
            @Param("actionCode") String actionCode,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("requestDigest") String requestDigest,
            @Param("resourceId") String resourceId,
            @Param("resultJson") String resultJson,
            @Param("at") Instant at);

    void insertBatch(PeopleRows.BatchRow row);

    PeopleRows.BatchRow findBatch(@Param("batchId") String batchId);

    List<PeopleRows.BatchRow> listBatches(
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("status") String status,
            @Param("limit") int limit,
            @Param("offset") int offset,
            @Param("at") Instant at);

    long countBatches(
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("status") String status,
            @Param("at") Instant at);

    int updateBatchState(
            @Param("batchId") String batchId,
            @Param("expectedVersion") long expectedVersion,
            @Param("status") String status,
            @Param("actorId") String actorId,
            @Param("at") Instant at,
            @Param("precheckVersion") Long precheckVersion,
            @Param("added") int added,
            @Param("updated") int updated,
            @Param("unchanged") int unchanged,
            @Param("conflict") int conflict,
            @Param("error") int error,
            @Param("blocking") int blocking,
            @Param("publishedAt") Instant publishedAt,
            @Param("voidedAt") Instant voidedAt,
            @Param("duplicateOfPublicationId") String duplicateOfPublicationId);

    int updateBatchMapping(
            @Param("batchId") String batchId,
            @Param("expectedVersion") long expectedVersion,
            @Param("mappingJson") String mappingJson,
            @Param("actorId") String actorId,
            @Param("at") Instant at);

    void insertFile(PeopleRows.BatchRow row);

    int attachFile(
            @Param("batchId") String batchId,
            @Param("expectedVersion") long expectedVersion,
            @Param("fileSha256") String fileSha256,
            @Param("actorId") String actorId,
            @Param("at") Instant at);

    void deletePrecheckDiffs(@Param("batchId") String batchId);

    void deletePrecheckIssues(@Param("batchId") String batchId);

    void insertDiff(PeopleRows.DiffRow row);

    void insertIssue(PeopleRows.IssueRow row);

    List<PeopleRows.DiffRow> listDiffs(
            @Param("batchId") String batchId,
            @Param("category") String category,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countDiffs(
            @Param("batchId") String batchId,
            @Param("category") String category);

    List<PeopleRows.IssueRow> listIssues(
            @Param("batchId") String batchId,
            @Param("severity") String severity,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countIssues(
            @Param("batchId") String batchId,
            @Param("severity") String severity);

    PeopleRows.PublicationRow findPublicationByBatch(@Param("batchId") String batchId);

    PeopleRows.PublicationRow findPublicationById(@Param("publicationId") String publicationId);

    PeopleRows.PublicationRow findPublicationByFileHash(
            @Param("companyId") String companyId,
            @Param("templateType") String templateType,
            @Param("templateVersion") String templateVersion,
            @Param("fileSha256") String fileSha256);

    void insertPublication(
            @Param("row") PeopleRows.PublicationRow row,
            @Param("idempotencyKey") String idempotencyKey);

    List<String> findDownstreamReferenceIds(@Param("batchId") String batchId);

    boolean hasLaterOrganizationVersions(
            @Param("batchId") String batchId, @Param("publishedAt") Instant publishedAt);

    boolean hasLaterEmployeeVersions(
            @Param("batchId") String batchId, @Param("publishedAt") Instant publishedAt);

    boolean hasLaterEmploymentVersions(
            @Param("batchId") String batchId, @Param("publishedAt") Instant publishedAt);

    boolean hasLaterPriorServiceRecords(
            @Param("batchId") String batchId, @Param("publishedAt") Instant publishedAt);

    PeopleRows.RollbackRow findRollbackByPublication(
            @Param("publicationId") String publicationId);

    void insertRollback(
            @Param("row") PeopleRows.RollbackRow row,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("reason") String reason);

    PeopleRows.OrganizationRow findCurrentOrganization(
            @Param("organizationId") String organizationId);

    PeopleRows.OrganizationRow findOrganizationVersion(
            @Param("organizationVersionId") String organizationVersionId);

    PeopleRows.OrganizationRow findOrganizationAsOf(
            @Param("organizationId") String organizationId,
            @Param("asOf") LocalDate asOf);

    PeopleRows.OrganizationRow findOrganizationByCode(
            @Param("companyId") String companyId,
            @Param("code") String code);

    List<PeopleRows.OrganizationRow> listOrganizationVersions(
            @Param("organizationId") String organizationId,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countOrganizationVersions(@Param("organizationId") String organizationId);

    boolean organizationCodeExists(
            @Param("companyId") String companyId,
            @Param("code") String code,
            @Param("excludeOrganizationId") String excludeOrganizationId);

    boolean organizationWouldCycle(
            @Param("organizationId") String organizationId,
            @Param("parentOrganizationId") String parentOrganizationId);

    void insertOrganizationIdentity(
            @Param("organizationId") String organizationId,
            @Param("companyId") String companyId,
            @Param("status") String status,
            @Param("at") Instant at);

    void insertOrganizationVersion(PeopleRows.OrganizationRow row);

    void updateOrganizationIdentityStatus(
            @Param("organizationId") String organizationId,
            @Param("status") String status);

    int closeCurrentOrganizationVersion(
            @Param("organizationId") String organizationId,
            @Param("effectiveTo") Instant effectiveTo,
            @Param("expectedVersion") long expectedVersion);

    void upsertOrganizationProjection(
            @Param("organizationId") String organizationId,
            @Param("versionId") String versionId,
            @Param("projectionBatchId") String projectionBatchId,
            @Param("at") Instant at);

    void deleteOrganizationClosure();

    void rebuildOrganizationClosure(@Param("projectionBatchId") String projectionBatchId);

    PeopleRows.EmployeeRow findCurrentEmployee(@Param("employeeId") String employeeId);

    Long findEmployeeAggregateVersion(@Param("employeeId") String employeeId);

    PeopleRows.EmployeeRow findEmployeeVersion(
            @Param("employeeVersionId") String employeeVersionId);

    PeopleRows.EmployeeRow findEmployeeAsOf(
            @Param("employeeId") String employeeId,
            @Param("asOf") LocalDate asOf);

    PeopleRows.EmployeeRow findEmployeeByNumber(
            @Param("companyId") String companyId,
            @Param("employeeNumber") String employeeNumber);

    List<PeopleRows.EmployeeRow> findEmployeesByExternalId(
            @Param("companyId") String companyId,
            @Param("externalEmployeeId") String externalEmployeeId);

    List<PeopleRows.EmployeeRow> listEmployeeVersions(
            @Param("employeeId") String employeeId,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countEmployeeVersions(@Param("employeeId") String employeeId);

    boolean employeeNumberExists(
            @Param("companyId") String companyId,
            @Param("employeeNumber") String employeeNumber,
            @Param("excludeEmployeeId") String excludeEmployeeId);

    void insertEmployeeIdentity(
            @Param("employeeId") String employeeId,
            @Param("companyId") String companyId,
            @Param("employeeNumber") String employeeNumber,
            @Param("displayName") String displayName,
            @Param("status") String status,
            @Param("onboardDate") LocalDate onboardDate,
            @Param("at") Instant at);

    int updateEmployeeIdentity(
            @Param("employeeId") String employeeId,
            @Param("employeeNumber") String employeeNumber,
            @Param("displayName") String displayName,
            @Param("status") String status,
            @Param("expectedVersion") long expectedVersion,
            @Param("at") Instant at);

    int correctCurrentEmployeeVersion(
            @Param("employeeId") String employeeId,
            @Param("employeeNumber") String employeeNumber,
            @Param("displayName") String displayName,
            @Param("status") String status,
            @Param("effectiveTo") LocalDate effectiveTo,
            @Param("changeReason") String changeReason,
            @Param("expectedVersion") long expectedVersion);

    void insertEmployeeVersion(PeopleRows.EmployeeRow row);

    int closeCurrentEmployeeVersion(
            @Param("employeeId") String employeeId,
            @Param("effectiveTo") LocalDate effectiveTo,
            @Param("expectedVersion") long expectedVersion);

    void upsertEmployeeProjection(
            @Param("employeeId") String employeeId,
            @Param("versionId") String versionId,
            @Param("at") Instant at);

    List<PeopleRows.EmploymentRow> listEmploymentPeriods(
            @Param("employeeId") String employeeId,
            @Param("asOf") LocalDate asOf,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countEmploymentPeriods(
            @Param("employeeId") String employeeId,
            @Param("asOf") LocalDate asOf);

    List<PeopleRows.EmploymentRow> listAccessibleEmploymentPeriods(
            @Param("employeeId") String employeeId,
            @Param("asOf") LocalDate asOf,
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("at") Instant at,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countAccessibleEmploymentPeriods(
            @Param("employeeId") String employeeId,
            @Param("asOf") LocalDate asOf,
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("at") Instant at);

    PeopleRows.EmploymentRow findCurrentEmploymentPeriodVersion(
            @Param("employmentPeriodId") String employmentPeriodId);

    PeopleRows.EmploymentRow findEmploymentVersion(
            @Param("assignmentVersionId") String assignmentVersionId);

    List<PeopleRows.EmploymentRow> findEmploymentVersionsBySourceBatch(
            @Param("batchId") String batchId);

    boolean hasEmploymentOverlap(
            @Param("employeeId") String employeeId,
            @Param("start") LocalDate start,
            @Param("endExclusive") LocalDate endExclusive,
            @Param("excludeEmploymentPeriodId") String excludeEmploymentPeriodId);

    void lockEmployee(@Param("employeeId") String employeeId);

    int touchEmployee(
            @Param("employeeId") String employeeId,
            @Param("expectedVersion") long expectedVersion,
            @Param("at") Instant at);

    int insertEmploymentPeriodIdentity(
            @Param("row") PeopleRows.EmploymentRow row);

    void insertEmploymentPeriodVersion(
            @Param("row") PeopleRows.EmploymentRow row);

    int closeEmploymentPeriodVersion(
            @Param("employmentPeriodId") String employmentPeriodId,
            @Param("expectedVersion") long expectedVersion);

    List<PeopleRows.PriorServiceRow> listPriorServiceRecords(
            @Param("employeeId") String employeeId,
            @Param("limit") int limit,
            @Param("offset") int offset);

    List<PeopleRows.PriorServiceRow> listAllPriorServiceRecords(
            @Param("employeeId") String employeeId);

    PeopleRows.PriorServiceRow findPriorServiceRecord(
            @Param("priorServiceRecordId") String priorServiceRecordId);

    List<PeopleRows.PriorServiceRow> findPriorServiceRecordsBySourceBatch(
            @Param("batchId") String batchId);

    long countPriorServiceRecords(@Param("employeeId") String employeeId);

    void insertPriorServiceRecord(PeopleRows.PriorServiceRow row);
}
