package com.szsemicon.hr.identityaccess.application;

import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.AccountRecord;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.ResolvedRoleAssignmentInput;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.RoleAssignmentInput;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.RoleAssignmentRecord;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.RoleRecord;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.SessionRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AccountPersistence {

    record GrantableCompanyRecord(
            String companyId,
            String code,
            String name) {
    }

    record GrantableOrganizationRecord(
            String organizationId,
            String companyId,
            String parentOrganizationId,
            String code,
            String name,
            boolean canIncludeDescendants) {
    }

    record EmployeeAccountCandidateRecord(
            String employeeId,
            String companyId,
            String employeeNumber,
            String displayName,
            String organizationName,
            String status,
            String accountId) {
    }

    /**
     * Minimal target snapshot used before account provisioning acquires its
     * company and employee row locks. Keeping this read model free of actor
     * authorization makes the target-before-actor lock order explicit.
     */
    record EmployeeProvisioningTarget(
            String employeeId,
            String companyId) {
    }

    record IdempotencyClaim(
            String recordId,
            String requestDigest,
            String resultJson,
            Instant createdAt,
            boolean firstClaim) {
    }

    record RecoverableRoleAssignment(
            String assignmentId,
            String roleCode,
            String scopeType,
            String companyId,
            String organizationId,
            Instant validFrom,
            Instant validTo,
            Instant scopeValidFrom,
            Instant scopeValidTo) {
    }

    boolean canAccessAccount(
            String principalId,
            String accountId,
            String requiredCapability,
            Instant at);

    /**
     * Requires one current capability-bearing actor scope to cover every
     * non-expired role scope on the target account. Account-wide mutations
     * must use this stronger check instead of relying on visibility through
     * only one intersecting company.
     */
    boolean canAccessAllAccountRoleScopes(
            String principalId,
            String accountId,
            String requiredCapability,
            Instant at);

    /**
     * Locks the target account as the serialization root for every
     * account-wide mutation and role-assignment replacement.
     */
    Optional<AccountRecord> lockAccountForScopeAuthorization(String accountId);

    /**
     * Locks only the local-account row for lock-order-sensitive account flows.
     * Unlike the scope-authorization read model, this must not join and
     * implicitly lock the principal or bound employee before target company
     * locks are acquired. Provisioning recovery and role replacement both rely
     * on this property.
     */
    Optional<AccountRecord> lockProvisionedAccount(String accountId);

    String createAccount(
            String username,
            String normalizedUsername,
            String displayName,
            String employeeId,
            String passwordHash,
            String actorId,
            Instant at);

    /**
     * Atomically claims an actor/action/key tuple. Implementations must insert
     * the generated record id with an INSERT ... ON DUPLICATE KEY no-op and
     * re-read the stored row while it remains locked. Comparing record ids is
     * what distinguishes the first claimant from a replay without relying on
     * vendor-specific update counts.
     */
    IdempotencyClaim claimIdempotency(
            String generatedRecordId,
            String actorId,
            String actionCode,
            String idempotencyKey,
            String requestDigest,
            Instant at);

    /**
     * Non-locking probe used only to choose the initial target-first or
     * account-first provisioning path. The selected transaction must re-read
     * the record under a lock before relying on it.
     */
    Optional<IdempotencyClaim> findIdempotency(
            String actorId,
            String actionCode,
            String idempotencyKey);

    /**
     * Locks an existing provisioning idempotency row without inserting a
     * child row that would implicitly lock the actor through its foreign key.
     */
    Optional<IdempotencyClaim> lockIdempotency(
            String actorId,
            String actionCode,
            String idempotencyKey);

    boolean completeIdempotency(
            String recordId,
            String requestDigest,
            String resultJson);

    List<AccountRecord> listAccounts(
            String principalId,
            String requiredCapability,
            String query,
            String status,
            int limit,
            int offset,
            Instant at);

    List<EmployeeAccountCandidateRecord> listEmployeeAccountCandidates(
            String principalId,
            String companyId,
            String query,
            int limit,
            int offset,
            Instant at);

    List<EmployeeAccountCandidateRecord> findEmployeeAccountCandidates(
            String principalId,
            List<String> employeeIds,
            Instant at);

    List<EmployeeProvisioningTarget> findEmployeeProvisioningTargets(
            List<String> employeeIds);

    /**
     * Locks every distinct target company first and every employee second,
     * both in deterministic order. People mutations use the same
     * company-before-employee order, so employment and organization changes
     * cannot race the later authorization recheck.
     */
    void lockEmployeeProvisioningTargets(
            List<EmployeeProvisioningTarget> targets);

    CandidateCounts countEmployeeAccountCandidates(
            String principalId,
            String companyId,
            String query,
            Instant at);

    record CandidateCounts(
            long total,
            long available,
            long alreadyProvisioned,
            long usernameConflicts) {
    }

    long countAccounts(
            String principalId,
            String requiredCapability,
            String query,
            String status,
            Instant at);

    /**
     * Lists active companies for which the actor's current capability scope
     * covers the requested scope type. Callers intersect results when an
     * operation requires more than one capability.
     */
    List<GrantableCompanyRecord> findGrantableCompanies(
            String actorPrincipalId,
            String requestedScopeType,
            String requiredCapability,
            Instant at);

    /**
     * Lists companies with at least one current organization target covered by
     * every required capability. Coverage by different organizations in the
     * same company must not be combined into a false-positive intersection.
     */
    List<GrantableCompanyRecord> findGrantableOrganizationCompanies(
            String actorPrincipalId,
            List<String> requiredCapabilities,
            Instant at);

    /**
     * Lists current active organizations in one company that are individually
     * covered by the actor's current capability scopes. Exact organization
     * authority returns only the exact node and marks it as unable to delegate
     * descendants; descendant authority returns its current closure.
     */
    List<GrantableOrganizationRecord> findGrantableOrganizations(
            String actorPrincipalId,
            String companyId,
            String requiredCapability,
            Instant at);

    void updateAccountStatus(
            String accountId,
            String status,
            long expectedVersion,
            String actorId,
            Instant at);

    void setAccountLock(String accountId, boolean locked, String actorId, Instant at);

    /**
     * Resolves every requested target to its active company and locks those
     * company rows in deterministic order. People and organization
     * publication use the same lock, so the authorization snapshot cannot race
     * with an employee move or organization hierarchy publication.
     */
    void lockRoleGrantTargetCompanies(
            String targetPrincipalId,
            String targetEmployeeId,
            List<RoleAssignmentInput> assignments,
            Instant at);

    /**
     * Locks the actor's currently effective assignments carrying the required
     * capability, so a scope decision and mutation share one authorization
     * snapshot.
     */
    boolean lockCurrentCapabilityAuthority(
            String actorPrincipalId,
            String requiredCapability,
            Instant at);

    /**
     * Locks every non-expired assignment that a replacement would close. The
     * caller must then verify {@link #canAccessAllAccountRoleScopes} before
     * replacing any assignment.
     */
    void lockTargetRoleAssignments(String targetPrincipalId, Instant at);

    /**
     * Returns every current or future assignment while locking the assignment,
     * role and scope rows. Credential recovery must reject any shape other than
     * one currently effective EMPLOYEE_SELF assignment with SELF scope.
     */
    List<RecoverableRoleAssignment> lockRecoverableRoleAssignments(
            String targetPrincipalId,
            Instant at);

    /**
     * Resolves a role id under a lock. An empty result is deliberately treated
     * as a non-delegable role by the application service.
     */
    Optional<String> findRoleCodeForUpdate(String roleId);

    Optional<String> findRoleIdByCodeForUpdate(String roleCode);

    /**
     * Verifies that each requested scope resource exists and is covered by a
     * current ROLE:ASSIGN-bearing actor scope, then returns server-resolved scope
     * identifiers. This method may only be used inside the surrounding account
     * transaction.
     */
    List<ResolvedRoleAssignmentInput> resolveAuthorizedRoleAssignmentScopes(
            String actorPrincipalId,
            String targetPrincipalId,
            String targetEmployeeId,
            List<RoleAssignmentInput> assignments,
            Instant at);

    /**
     * Re-evaluates a second capability against every requested target scope
     * without creating or reusing data-scope rows. Account creation uses this
     * to require the intersection of ACCOUNT:CREATE and ROLE:ASSIGN.
     */
    boolean coversEveryRoleAssignmentScope(
            String actorPrincipalId,
            String targetPrincipalId,
            String targetEmployeeId,
            List<RoleAssignmentInput> assignments,
            String requiredCapability,
            Instant at);

    void replaceRoleAssignments(
            String accountId,
            long expectedAccountVersion,
            List<ResolvedRoleAssignmentInput> assignments,
            String actorId,
            String reason,
            Instant at);

    List<RoleAssignmentRecord> findVisibleRoleAssignments(
            String actorPrincipalId,
            String targetPrincipalId,
            String requiredCapability,
            Instant at);

    List<SessionRecord> findSessions(String accountId);

    List<RoleRecord> findRoles();
}
