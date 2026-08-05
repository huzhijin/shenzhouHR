package com.szsemicon.hr.attendance.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface ShiftMapper {

    ShiftRows.TemplateRow findTemplate(@Param("shiftId") String shiftId);

    ShiftRows.TemplateRow findTemplateByIdempotency(
            @Param("actorId") String actorId,
            @Param("idempotencyKey") String idempotencyKey);

    List<ShiftRows.TemplateRow> listTemplates(
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("companyId") String companyId,
            @Param("limit") int limit,
            @Param("offset") int offset,
            @Param("at") Instant at);

    long countTemplates(
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("companyId") String companyId,
            @Param("at") Instant at);

    void insertTemplate(
            @Param("row") ShiftRows.TemplateRow row,
            @Param("idempotencyKey") String idempotencyKey);

    void insertTemplateMetadata(
            @Param("eventId") String eventId,
            @Param("occurredAt") Instant occurredAt,
            @Param("actorId") String actorId,
            @Param("shiftId") String shiftId,
            @Param("templateName") String templateName,
            @Param("reason") String reason,
            @Param("resourceVersion") long resourceVersion,
            @Param("nameDigest") String nameDigest,
            @Param("correlationId") String correlationId,
            @Param("requestId") String requestId,
            @Param("eventHash") String eventHash);

    String lockTemplate(@Param("shiftId") String shiftId);

    int updateTemplate(
            @Param("row") ShiftRows.TemplateRow row,
            @Param("expectedVersion") long expectedVersion);

    ShiftRows.VersionRow findVersion(@Param("versionId") String versionId);

    ShiftRows.VersionRow findVersionAsOf(
            @Param("versionId") String versionId,
            @Param("knowledgeAsOf") Instant knowledgeAsOf);

    ShiftRows.VersionRow findVersionByIdempotency(
            @Param("actorId") String actorId,
            @Param("idempotencyKey") String idempotencyKey);

    List<ShiftRows.VersionRow> listVersions(
            @Param("shiftId") String shiftId,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countVersions(@Param("shiftId") String shiftId);

    int nextVersionNumber(@Param("shiftId") String shiftId);

    void insertVersion(
            @Param("row") ShiftRows.VersionRow row,
            @Param("idempotencyKey") String idempotencyKey);

    int updateDraftVersion(
            @Param("row") ShiftRows.VersionRow row,
            @Param("predecessorVersionId") String predecessorVersionId,
            @Param("expectedVersion") long expectedVersion);

    int publishVersion(
            @Param("versionId") String versionId,
            @Param("expectedVersion") long expectedVersion,
            @Param("snapshotDigest") String snapshotDigest,
            @Param("actorId") String actorId,
            @Param("reason") String reason,
            @Param("at") Instant at);

    int transitionVersionStatus(
            @Param("versionId") String versionId,
            @Param("expectedVersion") long expectedVersion,
            @Param("expectedStatus") String expectedStatus,
            @Param("targetStatus") String targetStatus,
            @Param("actorId") String actorId,
            @Param("reason") String reason,
            @Param("at") Instant at);

    int nextPublicationSequence(@Param("shiftId") String shiftId);

    String latestPublicationTimelineId(@Param("shiftId") String shiftId);

    void insertPublicationTimeline(
            @Param("row") AttendanceGroupRows.TimelineFactRow row);

    boolean hasPublishedOverlap(
            @Param("shiftId") String shiftId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("excludeVersionId") String excludeVersionId);

    List<ShiftRows.VersionRow> resolvePublishedAt(
            @Param("shiftId") String shiftId,
            @Param("date") LocalDate date,
            @Param("knowledgeAsOf") Instant knowledgeAsOf);

    boolean hasOverrideReferencesAtOrAfter(
            @Param("versionId") String versionId,
            @Param("businessEffectiveFrom") LocalDate businessEffectiveFrom);

    boolean hasActiveGroupReferencesAtOrAfter(
            @Param("shiftId") String shiftId,
            @Param("businessEffectiveFrom") LocalDate businessEffectiveFrom);
}
