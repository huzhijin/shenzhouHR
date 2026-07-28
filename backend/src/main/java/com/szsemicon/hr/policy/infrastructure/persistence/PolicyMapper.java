package com.szsemicon.hr.policy.infrastructure.persistence;

import com.szsemicon.hr.policy.application.PolicyRepository.AuditRecord;
import com.szsemicon.hr.policy.application.PolicyRepository.PublicationRecord;
import com.szsemicon.hr.policy.application.PolicyRepository.RollbackRecord;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface PolicyMapper {

    long countTemplates(
            @Param("query") String query,
            @Param("status") String status);

    List<PolicyRows.TemplateRow> findTemplates(
            @Param("query") String query,
            @Param("status") String status,
            @Param("sort") String sort,
            @Param("limit") int limit,
            @Param("offset") long offset);

    PolicyRows.TemplateRow findTemplate(@Param("templateId") String templateId);

    long countTemplateCode(@Param("code") String code);

    void insertTemplate(PolicyRows.TemplateRow template);

    long countVersions(@Param("templateId") String templateId);

    List<PolicyRows.VersionRow> findVersions(
            @Param("templateId") String templateId,
            @Param("limit") int limit,
            @Param("offset") long offset);

    PolicyRows.VersionRow findVersion(
            @Param("templateId") String templateId,
            @Param("versionId") String versionId);

    int maxVersionNumber(@Param("templateId") String templateId);

    void insertVersion(PolicyRows.VersionRow version);

    int updateDraft(
            @Param("version") PolicyRows.VersionRow version,
            @Param("expectedVersion") long expectedVersion,
            @Param("newVersion") long newVersion,
            @Param("actorId") String actorId,
            @Param("now") Instant now);

    int saveValidation(
            @Param("templateId") String templateId,
            @Param("versionId") String versionId,
            @Param("validationJson") String validationJson,
            @Param("status") String status,
            @Param("expectedVersion") long expectedVersion,
            @Param("newVersion") long newVersion,
            @Param("actorId") String actorId,
            @Param("now") Instant now);

    int transitionVersion(
            @Param("templateId") String templateId,
            @Param("versionId") String versionId,
            @Param("expectedStatus") String expectedStatus,
            @Param("status") String status,
            @Param("changeReason") String changeReason,
            @Param("snapshotJson") String snapshotJson,
            @Param("snapshotDigest") String snapshotDigest,
            @Param("expectedVersion") long expectedVersion,
            @Param("newVersion") long newVersion,
            @Param("actorId") String actorId,
            @Param("now") Instant now,
            @Param("publishedAt") Instant publishedAt);

    int touchDraft(
            @Param("templateId") String templateId,
            @Param("versionId") String versionId,
            @Param("expectedVersion") long expectedVersion,
            @Param("newVersion") long newVersion,
            @Param("actorId") String actorId,
            @Param("now") Instant now);

    int claimVersion(
            @Param("templateId") String templateId,
            @Param("versionId") String versionId,
            @Param("expectedStatus") String expectedStatus,
            @Param("expectedVersion") long expectedVersion,
            @Param("newVersion") long newVersion,
            @Param("actorId") String actorId,
            @Param("now") Instant now);

    List<PolicyRows.ScopeRow> findScopes(@Param("versionId") String versionId);

    void deleteScopes(@Param("versionId") String versionId);

    void insertScope(PolicyRows.ScopeRow scope);

    List<PolicyRows.ConflictRow> findPublicationConflicts(
            @Param("templateId") String templateId,
            @Param("versionId") String versionId);

    void insertPublication(PublicationRecord record);

    void insertRollback(RollbackRecord record);

    void insertAudit(AuditRecord record);
}
