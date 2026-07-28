package com.szsemicon.hr.attendance.application;

import com.szsemicon.hr.attendance.domain.AttendancePolicyLifecycleModels.Page;
import com.szsemicon.hr.attendance.domain.AttendancePolicyLifecycleModels.ScopedPolicyVersion;
import com.szsemicon.hr.attendance.domain.AttendancePolicyLifecycleModels.ValidationResult;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AttendancePolicyLifecycleRepository {

    record Scope(
            String scopeId,
            String templateId,
            String legalEntityId,
            String policyKind,
            long rowVersion) {
    }

    Scope findScope(String templateId, String legalEntityId);

    Scope lockScope(String templateId, String legalEntityId);

    Page<ScopedPolicyVersion> list(
            String templateId, String legalEntityId, int page, int size);

    Optional<ScopedPolicyVersion> find(
            String templateId, String scopedVersionId, String legalEntityId);

    Optional<ScopedPolicyVersion> findByScopedVersionId(String scopedVersionId);

    int nextVersionNumber(String scopeId);

    List<String> findPublishedAt(String scopeId, LocalDate businessDate);

    Optional<String> findLatestPublishedVersionId(String scopeId);

    boolean hasPublicationAtOrAfter(
            String scopeId, LocalDate businessEffectiveFrom);

    void insert(ScopedPolicyVersion version);

    void appendLifecycle(
            String lifecycleEventId,
            String scopeId,
            String scopedVersionId,
            String action,
            LocalDate businessEffectiveFrom,
            String reason,
            String actorId,
            String requestId,
            Instant recordedAt);
}
