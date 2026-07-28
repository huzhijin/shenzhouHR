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

    boolean canAccessAccount(String principalId, String accountId, Instant at);

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
            String query,
            String status,
            int limit,
            int offset,
            Instant at);

    long countAccounts(String principalId, String query, String status, Instant at);

    void updateAccountStatus(
            String accountId,
            String status,
            long expectedVersion,
            String actorId,
            Instant at);

    void setAccountLock(String accountId, boolean locked, String actorId, Instant at);

    /**
     * Resolves every requested target to its active legal entity and locks those
     * legal-entity rows in deterministic order. People and organization
     * publication use the same lock, so the authorization snapshot cannot race
     * with an employee move or organization hierarchy publication.
     */
    void lockRoleGrantTargetLegalEntities(
            String targetPrincipalId,
            String targetEmployeeId,
            List<RoleAssignmentInput> assignments,
            Instant at);

    /**
     * Locks the actor's currently effective ROLE:ASSIGN-bearing assignments so
     * the subsequent scope checks and assignment replacement are one atomic
     * authorization decision.
     */
    boolean lockCurrentRoleGrantAuthority(String actorPrincipalId, Instant at);

    /**
     * Resolves a role id under a lock. An empty result is deliberately treated
     * as a non-delegable role by the application service.
     */
    Optional<String> findRoleCodeForUpdate(String roleId);

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

    List<RoleAssignmentRecord> findRoleAssignments(String principalId, Instant at);

    List<SessionRecord> findSessions(String accountId);

    List<RoleRecord> findRoles();
}
