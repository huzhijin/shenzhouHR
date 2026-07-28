package com.szsemicon.hr.attendance.application;

import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.PolicyBinding;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.PolicyKind;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AttendancePolicyRepository {

    Optional<PolicyBinding> findBinding(String bindingId);

    Optional<PolicyBinding> findBindingRevision(String bindingRevisionId);

    List<String> findPublishedVersionIdsByKind(
            String legalEntityId,
            PolicyKind policyKind,
            LocalDate effectiveFrom,
            LocalDate effectiveTo);

    List<PolicyBinding> listBindings(
            String principalId,
            String capability,
            String groupId,
            LocalDate asOf,
            int limit,
            int offset,
            Instant at);

    long countBindings(
            String principalId,
            String capability,
            String groupId,
            LocalDate asOf,
            Instant at);

    List<PolicyBinding> findBindingFamilyHeads(
            String groupId, PolicyKind policyKind);

    void lockBindingFamily(String bindingId);

    void insertBinding(PolicyBinding binding, String idempotencyKey);

    boolean updateBinding(PolicyBinding binding, long expectedVersion);

    boolean publishedVersionMatchesKind(
            String legalEntityId,
            String policyVersionId,
            PolicyKind policyKind,
            LocalDate effectiveFrom,
            LocalDate effectiveTo);

    String publishedVersionDigest(String policyVersionId);

    String publishedVersionParameters(String policyVersionId);

    boolean hasOtherFamily(
            PolicyKind policyKind,
            String groupId,
            String excludeBindingId);

    List<PolicyBinding> resolveBindings(
            String groupId,
            String groupRevisionId,
            LocalDate businessDate,
            Instant knowledgeAsOf);
}
