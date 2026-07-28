package com.szsemicon.hr.attendance.application;

import com.szsemicon.hr.attendance.domain.ShiftModels.ShiftTemplate;
import com.szsemicon.hr.attendance.domain.ShiftModels.ShiftVersion;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ShiftRepository {

    Optional<ShiftTemplate> findTemplate(String shiftId);

    Optional<ShiftTemplate> findTemplateByIdempotency(String actorId, String idempotencyKey);

    List<ShiftTemplate> listTemplates(
            String principalId,
            String capability,
            int limit,
            int offset,
            Instant at);

    long countTemplates(String principalId, String capability, Instant at);

    void insertTemplate(ShiftTemplate template, String idempotencyKey);

    void lockTemplate(String shiftId);

    boolean updateTemplate(ShiftTemplate template, long expectedVersion);

    Optional<ShiftVersion> findVersion(String versionId);

    Optional<ShiftVersion> findVersion(String versionId, Instant knowledgeAsOf);

    Optional<ShiftVersion> findVersionByIdempotency(String actorId, String idempotencyKey);

    List<ShiftVersion> listVersions(String shiftId, int limit, int offset);

    long countVersions(String shiftId);

    int nextVersionNumber(String shiftId);

    void insertVersion(ShiftVersion version, String segmentsJson, String idempotencyKey);

    boolean updateDraftVersion(
            ShiftVersion version, String segmentsJson, long expectedVersion);

    boolean publishVersion(
            String versionId,
            long expectedVersion,
            String snapshotDigest,
            String actorId,
            String reason,
            java.time.Instant at);

    boolean scheduleVersionDeactivation(
            String versionId,
            long expectedVersion,
            LocalDate businessEffectiveFrom,
            String successorVersionId,
            String actorId,
            String reason,
            java.time.Instant at);

    boolean hasOverrideReferencesAtOrAfter(
            String versionId, LocalDate businessEffectiveFrom);

    boolean hasActiveGroupReferencesAtOrAfter(
            String shiftId, LocalDate businessEffectiveFrom);

    boolean hasPublishedOverlap(
            String shiftId, LocalDate from, LocalDate to, String excludeVersionId);

    List<ShiftVersion> resolvePublishedAt(
            String shiftId, LocalDate date, Instant knowledgeAsOf);
}
