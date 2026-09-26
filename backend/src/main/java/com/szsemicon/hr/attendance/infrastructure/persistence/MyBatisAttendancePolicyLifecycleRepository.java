package com.szsemicon.hr.attendance.infrastructure.persistence;

import com.szsemicon.hr.attendance.application.AttendancePolicyLifecycleRepository;
import com.szsemicon.hr.attendance.domain.AttendancePolicyLifecycleModels.Page;
import com.szsemicon.hr.attendance.domain.AttendancePolicyLifecycleModels.ParameterValue;
import com.szsemicon.hr.attendance.domain.AttendancePolicyLifecycleModels.ScopedPolicyVersion;
import com.szsemicon.hr.attendance.domain.AttendancePolicyLifecycleModels.ScopedVersionStatus;
import com.szsemicon.hr.attendance.domain.AttendancePolicyParameterValidator;
import com.szsemicon.hr.attendance.domain.AttendancePolicyLifecycleModels.ValidationResult;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.PolicyKind;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
class MyBatisAttendancePolicyLifecycleRepository
        implements AttendancePolicyLifecycleRepository {

    private final AttendancePolicyLifecycleMapper mapper;
    private final ObjectMapper objectMapper;

    MyBatisAttendancePolicyLifecycleRepository(
            AttendancePolicyLifecycleMapper mapper,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public Scope findScope(String templateId, String companyId) {
        return scope(mapper.findScope(templateId, companyId));
    }

    @Override
    public Scope lockScope(String templateId, String companyId) {
        return scope(mapper.lockScope(templateId, companyId));
    }

    private Scope scope(AttendancePolicyLifecycleRows.ScopeRow row) {
        return row == null
                ? null
                : new Scope(
                        row.scopeId(),
                        row.templateId(),
                        row.companyId(),
                        row.policyKind(),
                        row.rowVersion());
    }

    @Override
    public Page<ScopedPolicyVersion> list(
            String templateId, String companyId, int page, int size) {
        long offset = Math.multiplyExact((long) page, size);
        List<ScopedPolicyVersion> items = mapper.listVersions(
                        templateId, companyId, size, offset)
                .stream()
                .map(this::version)
                .toList();
        return new Page<>(
                items,
                mapper.countVersions(templateId, companyId),
                page,
                size);
    }

    @Override
    public Optional<ScopedPolicyVersion> find(
            String templateId, String scopedVersionId, String companyId) {
        return Optional.ofNullable(
                        mapper.findVersion(templateId, scopedVersionId, companyId))
                .map(this::version);
    }

    @Override
    public Optional<ScopedPolicyVersion> findByScopedVersionId(
            String scopedVersionId) {
        return Optional.ofNullable(
                        mapper.findVersionByScopedVersionId(scopedVersionId))
                .map(this::version);
    }

    @Override
    public int nextVersionNumber(String scopeId) {
        return Math.addExact(mapper.maxVersionNumber(scopeId), 1);
    }

    @Override
    public List<String> findPublishedAt(
            String scopeId, LocalDate businessDate) {
        return List.copyOf(mapper.findPublishedAt(scopeId, businessDate));
    }

    @Override
    public Optional<String> findLatestPublishedVersionId(String scopeId) {
        return Optional.ofNullable(
                mapper.findLatestPublishedVersionId(scopeId));
    }

    @Override
    public boolean hasPublicationAtOrAfter(
            String scopeId, LocalDate businessEffectiveFrom) {
        return mapper.hasPublicationAtOrAfter(
                scopeId, businessEffectiveFrom);
    }

    @Override
    public void insert(ScopedPolicyVersion version) {
        mapper.insertVersion(row(version));
    }

    @Override
    public void appendLifecycle(
            String lifecycleEventId,
            String scopeId,
            String scopedVersionId,
            String action,
            LocalDate businessEffectiveFrom,
            String reason,
            String actorId,
            String requestId,
            Instant recordedAt) {
        var head = mapper.lifecycleHead(scopeId);
        mapper.insertLifecycle(
                lifecycleEventId,
                scopeId,
                scopedVersionId,
                head == null ? 1 : Math.addExact(head.eventSequence(), 1),
                head == null ? null : head.lifecycleEventId(),
                action,
                businessEffectiveFrom,
                reason,
                actorId,
                requestId,
                recordedAt);
    }

    private ScopedPolicyVersion version(AttendancePolicyLifecycleRows.VersionRow row) {
        List<ParameterValue> parameters = readParameters(row.parametersJson());
        ValidationResult validation = row.validationJson() == null
                ? new ValidationResult(false, List.of(), null)
                : read(row.validationJson(), ValidationResult.class);
        ScopedVersionStatus status = ScopedVersionStatus.valueOf(row.status());
        var issues = AttendancePolicyParameterValidator.validate(
                PolicyKind.valueOf(row.policyKind()), parameters);
        if (status == ScopedVersionStatus.DRAFT) {
            validation = new ValidationResult(false, issues, null);
        } else {
            validation = new ValidationResult(
                    issues.isEmpty(),
                    issues,
                    row.validatedAt() == null
                            ? validation.validatedAt()
                            : row.validatedAt());
        }
        return new ScopedPolicyVersion(
                row.scopedVersionId(),
                row.scopeId(),
                row.templateId(),
                row.companyId(),
                PolicyKind.valueOf(row.policyKind()),
                row.versionNumber(),
                status,
                parameters,
                row.effectiveFrom(),
                row.effectiveTo(),
                row.changeReason(),
                validation,
                row.snapshotJson(),
                row.snapshotDigest(),
                row.rollbackOfScopedVersionId(),
                row.rowVersion(),
                row.createdBy(),
                row.createdAt(),
                row.publishedAt(),
                row.createdBy(),
                row.createdAt(),
                row.deactivationEffectiveFrom());
    }

    private AttendancePolicyLifecycleRows.VersionRow row(ScopedPolicyVersion value) {
        return new AttendancePolicyLifecycleRows.VersionRow(
                value.scopedVersionId(),
                value.scopeId(),
                value.templateId(),
                value.companyId(),
                value.policyKind().name(),
                value.versionNumber(),
                value.status().name(),
                write(value.parameters()),
                value.effectiveFrom(),
                value.effectiveTo(),
                value.changeReason(),
                value.validation().validatedAt() == null
                        ? null
                        : write(value.validation()),
                value.snapshotJson(),
                value.snapshotDigest(),
                value.rollbackOfScopedVersionId(),
                value.rowVersion(),
                value.createdBy(),
                value.createdAt(),
                value.validation().validatedAt(),
                value.publishedAt(),
                value.deactivationEffectiveFrom());
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "attendance policy JSON serialization failed", exception);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "stored attendance policy JSON is invalid", exception);
        }
    }

    private <T> List<T> readArray(String json, Class<T[]> type) {
        return Arrays.asList(read(json, type));
    }

    @SuppressWarnings("unchecked")
    private List<ParameterValue> readParameters(String json) {
        try {
            return readArray(json, ParameterValue[].class);
        } catch (IllegalStateException arrayFailure) {
            Map<String, Object> values = read(json, Map.class);
            return values.entrySet().stream()
                    .map(entry -> new ParameterValue(entry.getKey(), entry.getValue()))
                    .toList();
        }
    }
}
