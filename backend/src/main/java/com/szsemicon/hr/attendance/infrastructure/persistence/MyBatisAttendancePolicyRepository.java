package com.szsemicon.hr.attendance.infrastructure.persistence;

import com.szsemicon.hr.attendance.application.AttendancePolicyRepository;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.LifecycleStatus;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.PolicyBinding;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.PolicyKind;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

@Repository
class MyBatisAttendancePolicyRepository implements AttendancePolicyRepository {

    private static final String EFFECTIVE_TO_PREFIX = "@@effectiveTo=";

    private final AttendancePolicyMapper mapper;

    MyBatisAttendancePolicyRepository(AttendancePolicyMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<PolicyBinding> findBinding(String bindingId) {
        return Optional.ofNullable(mapper.findBinding(bindingId)).map(this::binding);
    }

    @Override
    public Optional<PolicyBinding> findBindingRevision(String bindingRevisionId) {
        return Optional.ofNullable(mapper.findBindingRevision(bindingRevisionId))
                .map(this::binding);
    }

    @Override
    public List<String> findPublishedVersionIdsByKind(
            String companyId,
            PolicyKind policyKind,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
        return mapper.findPublishedVersionIdsByKind(
                companyId, policyKind.name(), effectiveFrom, effectiveTo);
    }

    @Override
    public List<PolicyBinding> listBindings(
            String principalId,
            String capability,
            String companyId,
            String groupId,
            LocalDate asOf,
            int limit,
            int offset,
            Instant at) {
        return mapper.listBindings(
                        principalId, capability, companyId,
                        groupId, asOf, limit, offset, at)
                .stream()
                .map(this::binding)
                .toList();
    }

    @Override
    public long countBindings(
            String principalId,
            String capability,
            String companyId,
            String groupId,
            LocalDate asOf,
            Instant at) {
        return mapper.countBindings(
                principalId, capability, companyId, groupId, asOf, at);
    }

    @Override
    public List<PolicyBinding> findBindingFamilyHeads(
            String groupId, PolicyKind policyKind) {
        return mapper.findBindingFamilyHeads(groupId, policyKind.name())
                .stream()
                .map(this::binding)
                .toList();
    }

    @Override
    public void lockBindingFamily(String bindingId) {
        if (mapper.lockBindingFamily(bindingId) == null) {
            throw new OptimisticLockingFailureException(
                    "attendance policy binding family no longer exists");
        }
    }

    @Override
    public void insertBinding(PolicyBinding binding, String idempotencyKey) {
        AttendancePolicyRows.BindingRow row = row(binding);
        mapper.insertBindingFamily(row);
        mapper.insertBindingRevision(row);
    }

    @Override
    public boolean updateBinding(PolicyBinding binding, long expectedVersion) {
        return mapper.updateBinding(row(binding), expectedVersion) == 1;
    }

    @Override
    public boolean publishedVersionMatchesKind(
            String companyId,
            String policyVersionId,
            PolicyKind policyKind,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {
        return mapper.publishedVersionMatchesKind(
                companyId, policyVersionId, policyKind.name(),
                effectiveFrom, effectiveTo);
    }

    @Override
    public String publishedVersionDigest(String policyVersionId) {
        return mapper.publishedVersionDigest(policyVersionId);
    }

    @Override
    public String publishedVersionParameters(String policyVersionId) {
        return mapper.publishedVersionParameters(policyVersionId);
    }

    @Override
    public boolean hasOtherFamily(
            PolicyKind policyKind,
            String groupId,
            String excludeBindingId) {
        return mapper.hasOtherFamily(
                policyKind.name(), groupId, excludeBindingId);
    }

    @Override
    public List<PolicyBinding> resolveBindings(
            String groupId,
            String groupRevisionId,
            LocalDate businessDate,
            Instant knowledgeAsOf) {
        return mapper.resolveBindings(
                        groupId, groupRevisionId, businessDate, knowledgeAsOf)
                .stream()
                .map(this::binding).toList();
    }

    private PolicyBinding binding(AttendancePolicyRows.BindingRow row) {
        LocalDate effectiveTo = earliest(
                row.effectiveTo(), storedEffectiveTo(row.changeReason()));
        return new PolicyBinding(
                row.attendancePolicyBindingId(),
                row.attendancePolicyBindingRevisionId(), row.revisionNumber(),
                row.companyId(), PolicyKind.valueOf(row.policyKind()),
                row.policyVersionId(), row.attendanceGroupId(),
                row.attendanceGroupRevisionId(),
                row.effectiveFrom(), effectiveTo,
                LifecycleStatus.valueOf(row.status()), row.snapshotDigest(),
                row.rowVersion(), userReason(row.changeReason()),
                row.createdBy(), row.createdAt(),
                row.updatedBy(), row.updatedAt());
    }

    private AttendancePolicyRows.BindingRow row(PolicyBinding value) {
        return new AttendancePolicyRows.BindingRow(
                value.bindingId(), value.bindingRevisionId(),
                value.revisionNumber(), value.companyId(),
                value.policyKind().name(), value.policyVersionId(),
                value.groupId(), value.groupRevisionId(),
                value.effectiveFrom(),
                value.effectiveTo(), value.status().name(), value.snapshotDigest(),
                value.rowVersion(),
                storedReason(value.changeReason(), value.effectiveTo()),
                value.updatedBy(),
                value.updatedAt(), value.updatedBy(), value.updatedAt());
    }

    private String storedReason(String reason, LocalDate effectiveTo) {
        return EFFECTIVE_TO_PREFIX
                + (effectiveTo == null ? "NULL" : effectiveTo)
                + "\n"
                + reason;
    }

    private LocalDate storedEffectiveTo(String reason) {
        if (reason == null || !reason.startsWith(EFFECTIVE_TO_PREFIX)) {
            return null;
        }
        int start = EFFECTIVE_TO_PREFIX.length();
        int separator = reason.indexOf('\n', start);
        String value = separator < 0
                ? reason.substring(start) : reason.substring(start, separator);
        return "NULL".equals(value) ? null : LocalDate.parse(value);
    }

    private String userReason(String reason) {
        if (reason == null || !reason.startsWith(EFFECTIVE_TO_PREFIX)) {
            return reason;
        }
        int separator = reason.indexOf('\n', EFFECTIVE_TO_PREFIX.length());
        return separator < 0 ? "" : reason.substring(separator + 1);
    }

    private LocalDate earliest(LocalDate first, LocalDate second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.isBefore(second) ? first : second;
    }
}
