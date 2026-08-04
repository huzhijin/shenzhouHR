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

    record EmployeeAccountCandidateRecord(
            String employeeId,
            String companyId,
            String employeeNumber,
            String displayName,
            String organizationName,
            String status) {
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

    String createAccount(
            String username,
            String normalizedUsername,
            String displayName,
            String employeeId,
            String passwordHash,
            String actorId,
            Instant at);

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
