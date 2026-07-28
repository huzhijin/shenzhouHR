package com.szsemicon.hr.identityaccess.application;

import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.AccountRecord;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.CredentialRecord;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.FailureRecord;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.ResetGrantRecord;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.SessionRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AuthenticationPersistence {

    Optional<AccountRecord> findAccountByNormalizedUsername(String normalizedUsername);

    Optional<AccountRecord> findAccountById(String accountId);

    Optional<AccountRecord> findAccountByPrincipalId(String principalId);

    Optional<CredentialRecord> findCredential(String accountId);

    FailureRecord findFailure(String accountId);

    Optional<SessionRecord> findSessionByDigest(String tokenDigest, Instant at);

    Optional<SessionRecord> findSessionById(String sessionId);

    Optional<ResetGrantRecord> findResetGrantByDigest(String digest, Instant at);

    List<String> findCapabilities(String principalId, Instant at);

    void recordFailedLogin(
            String accountId,
            int failureCount,
            Instant windowStartedAt,
            Instant failedAt,
            Instant lockedUntil);

    void clearLoginFailures(String accountId);

    void unlockExpiredAccount(String accountId, String actorId, Instant now);

    void recordSuccessfulLogin(String accountId, Instant at);

    void insertSession(SessionRecord session);

    void touchSession(String sessionId, Instant lastSeenAt, Instant idleExpiresAt);

    void replaceCredential(
            String accountId,
            String passwordHash,
            boolean clearFirstChange,
            String actorId,
            Instant at);

    void markResetGrantUsed(String grantId, long expectedVersion, Instant at);

    void revokeSession(
            String sessionId,
            String accountId,
            String actorId,
            String reason,
            String requestId,
            Instant at);

    void revokeAllSessions(
            String accountId,
            String actorId,
            String reason,
            String requestId,
            Instant at);

    void issueResetGrant(
            String accountId,
            String digest,
            String actorId,
            String requestId,
            Instant expiresAt,
            Instant now);
}
