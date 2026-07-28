package com.szsemicon.hr.identityaccess.application;

import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.AccountRecord;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.RoleAssignmentInput;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.RoleAssignmentRecord;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.RoleRecord;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.SessionRecord;
import java.time.Instant;
import java.util.List;

public interface AccountPersistence {

    boolean canAccessAccount(String principalId, String accountId, Instant at);

    String createAccount(
            String username,
            String normalizedUsername,
            String displayName,
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

    void replaceRoleAssignments(
            String accountId,
            long expectedAccountVersion,
            List<RoleAssignmentInput> assignments,
            String actorId,
            String reason,
            Instant at);

    List<RoleAssignmentRecord> findRoleAssignments(String principalId, Instant at);

    List<SessionRecord> findSessions(String accountId);

    List<RoleRecord> findRoles();
}
