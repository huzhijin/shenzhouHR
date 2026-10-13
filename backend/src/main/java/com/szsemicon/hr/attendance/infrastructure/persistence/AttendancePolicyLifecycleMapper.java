package com.szsemicon.hr.attendance.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface AttendancePolicyLifecycleMapper {

    AttendancePolicyLifecycleRows.ScopeRow findScope(
            @Param("templateId") String templateId,
            @Param("companyId") String companyId);

    AttendancePolicyLifecycleRows.ScopeRow lockScope(
            @Param("templateId") String templateId,
            @Param("companyId") String companyId);

    long countVersions(
            @Param("templateId") String templateId,
            @Param("companyId") String companyId);

    List<AttendancePolicyLifecycleRows.VersionRow> listVersions(
            @Param("templateId") String templateId,
            @Param("companyId") String companyId,
            @Param("limit") int limit,
            @Param("offset") long offset);

    AttendancePolicyLifecycleRows.VersionRow findVersion(
            @Param("templateId") String templateId,
            @Param("scopedVersionId") String scopedVersionId,
            @Param("companyId") String companyId);

    AttendancePolicyLifecycleRows.VersionRow findVersionByScopedVersionId(
            @Param("scopedVersionId") String scopedVersionId);

    int maxVersionNumber(@Param("scopeId") String scopeId);

    List<String> findPublishedAt(
            @Param("scopeId") String scopeId,
            @Param("businessDate") LocalDate businessDate);

    String findLatestPublishedVersionId(@Param("scopeId") String scopeId);

    boolean hasPublicationAtOrAfter(
            @Param("scopeId") String scopeId,
            @Param("businessEffectiveFrom") LocalDate businessEffectiveFrom);

    void insertVersion(@Param("row") AttendancePolicyLifecycleRows.VersionRow row);

    AttendancePolicyLifecycleRows.LifecycleHeadRow lifecycleHead(
            @Param("scopeId") String scopeId);

    void insertLifecycle(
            @Param("lifecycleEventId") String lifecycleEventId,
            @Param("scopeId") String scopeId,
            @Param("scopedVersionId") String scopedVersionId,
            @Param("eventSequence") int eventSequence,
            @Param("predecessorEventId") String predecessorEventId,
            @Param("action") String action,
            @Param("businessEffectiveFrom") LocalDate businessEffectiveFrom,
            @Param("reason") String reason,
            @Param("actorId") String actorId,
            @Param("requestId") String requestId,
            @Param("recordedAt") Instant recordedAt);
}
