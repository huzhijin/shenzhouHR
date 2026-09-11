package com.szsemicon.hr.identityaccess.infrastructure.persistence;

import com.szsemicon.hr.authorization.application.CapabilityRepository;
import com.szsemicon.hr.identityaccess.application.AuthenticationPersistence;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.AccountRecord;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.CredentialRecord;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.FailureRecord;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.ResetGrantRecord;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.SessionRecord;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuthenticationPersistenceAdapter implements AuthenticationPersistence {

    private final JdbcTemplate jdbc;
    private final CapabilityRepository capabilityRepository;
    private final AuthenticationMapper mapper;

    public AuthenticationPersistenceAdapter(
            JdbcTemplate jdbc,
            CapabilityRepository capabilityRepository,
            AuthenticationMapper mapper) {
        this.jdbc = jdbc;
        this.capabilityRepository = capabilityRepository;
        this.mapper = mapper;
    }

    @Override
    public Optional<AccountRecord> findAccountByNormalizedUsername(String normalizedUsername) {
        return Optional.ofNullable(mapper.findAccountByNormalizedUsername(normalizedUsername))
                .map(AuthenticationPersistenceAdapter::toAccount);
    }

    @Override
    public Optional<AccountRecord> lockAccountByNormalizedUsername(String normalizedUsername) {
        return Optional.ofNullable(mapper.lockAccountByNormalizedUsername(normalizedUsername))
                .map(AuthenticationPersistenceAdapter::toAccount);
    }

    @Override
    public Optional<AccountRecord> findAccountById(String accountId) {
        return Optional.ofNullable(mapper.findAccountById(accountId))
                .map(AuthenticationPersistenceAdapter::toAccount);
    }

    @Override
    public Optional<AccountRecord> lockAccountById(String accountId) {
        return Optional.ofNullable(mapper.lockAccountById(accountId))
                .map(AuthenticationPersistenceAdapter::toAccount);
    }

    @Override
    public Optional<AccountRecord> findAccountByPrincipalId(String principalId) {
        return Optional.ofNullable(mapper.findAccountByPrincipalId(principalId))
                .map(AuthenticationPersistenceAdapter::toAccount);
    }

    @Override
    public Optional<CredentialRecord> findCredential(String accountId) {
        return Optional.ofNullable(mapper.findCredential(accountId))
                .map(row -> new CredentialRecord(
                        row.accountId(),
                        row.passwordHash(),
                        row.rowVersion()));
    }

    @Override
    public Optional<CredentialRecord> lockCredential(String accountId) {
        return Optional.ofNullable(mapper.lockCredential(accountId))
                .map(row -> new CredentialRecord(
                        row.accountId(),
                        row.passwordHash(),
                        row.rowVersion()));
    }

    @Override
    public FailureRecord findFailure(String accountId) {
        FailureRow row = mapper.findFailure(accountId);
        return row == null
                ? FailureRecord.empty()
                : new FailureRecord(
                        row.failureCount(),
                        row.windowStartedAt(),
                        row.lastFailedAt(),
                        row.lockedUntil(),
                        row.rowVersion());
    }

    @Override
    public Optional<SessionRecord> findSessionByDigest(String tokenDigest, Instant at) {
        return Optional.ofNullable(mapper.findActiveSessionByDigest(tokenDigest, at))
                .map(AuthenticationPersistenceAdapter::toSession);
    }

    @Override
    public Optional<SessionRecord> findSessionById(String sessionId) {
        return Optional.ofNullable(mapper.findSessionById(sessionId))
                .map(AuthenticationPersistenceAdapter::toSession);
    }

    @Override
    public Optional<ResetGrantRecord> findResetGrantByDigest(String digest, Instant at) {
        return Optional.ofNullable(mapper.findActiveResetGrant(digest, at))
                .map(row -> new ResetGrantRecord(
                        row.grantId(),
                        row.accountId(),
                        row.expiresAt(),
                        row.usedAt(),
                        row.rowVersion()));
    }

    @Override
    public Optional<ResetGrantRecord> lockResetGrantByDigest(String digest, Instant at) {
        return Optional.ofNullable(mapper.lockActiveResetGrant(digest, at))
                .map(row -> new ResetGrantRecord(
                        row.grantId(),
                        row.accountId(),
                        row.expiresAt(),
                        row.usedAt(),
                        row.rowVersion()));
    }

    @Override
    public List<String> findCapabilities(String principalId, Instant at) {
        return new ArrayList<>(capabilityRepository.findActiveCodes(principalId, at));
    }

    @Override
    public void recordFailedLogin(
            String accountId,
            int failureCount,
            Instant windowStartedAt,
            Instant failedAt,
            Instant lockedUntil) {
        int updated = jdbc.update(
                """
                UPDATE login_failure_window
                SET failure_count = ?, window_started_at = ?, last_failed_at = ?,
                    locked_until = ?, row_version = row_version + 1
                WHERE account_id = ?
                """,
                failureCount,
                timestamp(windowStartedAt),
                timestamp(failedAt),
                timestamp(lockedUntil),
                accountId);
        if (updated == 0) {
            jdbc.update(
                    """
                    INSERT INTO login_failure_window (
                        account_id, failure_count, window_started_at,
                        last_failed_at, locked_until, row_version
                    ) VALUES (?, ?, ?, ?, ?, 0)
                    """,
                    accountId,
                    failureCount,
                    timestamp(windowStartedAt),
                    timestamp(failedAt),
                    timestamp(lockedUntil));
        }
        if (lockedUntil != null) {
            jdbc.update(
                    """
                    UPDATE local_account
                    SET status = 'LOCKED', locked_until = ?,
                        row_version = row_version + 1, updated_at = ?
                    WHERE account_id = ?
                    """,
                    Timestamp.from(lockedUntil),
                    Timestamp.from(failedAt),
                    accountId);
        }
    }

    @Override
    public void clearLoginFailures(String accountId) {
        jdbc.update(
                """
                UPDATE login_failure_window
                SET failure_count = 0, window_started_at = NULL, last_failed_at = NULL,
                    locked_until = NULL, row_version = row_version + 1
                WHERE account_id = ?
                """,
                accountId);
    }

    @Override
    public void unlockExpiredAccount(String accountId, String actorId, Instant now) {
        jdbc.update(
                """
                UPDATE local_account
                SET status = 'ACTIVE', locked_until = NULL, updated_by = ?,
                    updated_at = ?, row_version = row_version + 1
                WHERE account_id = ? AND status = 'LOCKED'
                  AND locked_until IS NOT NULL AND locked_until <= ?
                """,
                actorId,
                Timestamp.from(now),
                accountId,
                Timestamp.from(now));
        clearLoginFailures(accountId);
    }

    @Override
    public void recordSuccessfulLogin(String accountId, Instant at) {
        jdbc.update(
                """
                UPDATE local_account SET last_login_at = ?, updated_at = ?
                WHERE account_id = ?
                """,
                Timestamp.from(at),
                Timestamp.from(at),
                accountId);
        clearLoginFailures(accountId);
    }

    @Override
    public void insertSession(SessionRecord session) {
        jdbc.update(
                """
                INSERT INTO user_session (
                    session_id, account_id, token_digest, status, created_at,
                    last_seen_at, idle_expires_at, absolute_expires_at,
                    request_id, row_version
                ) VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, 0)
                """,
                session.sessionId(),
                session.accountId(),
                session.tokenDigest(),
                Timestamp.from(session.createdAt()),
                Timestamp.from(session.lastSeenAt()),
                Timestamp.from(session.idleExpiresAt()),
                Timestamp.from(session.absoluteExpiresAt()),
                session.requestId());
    }

    @Override
    public void touchSession(String sessionId, Instant lastSeenAt, Instant idleExpiresAt) {
        jdbc.update(
                """
                UPDATE user_session
                SET last_seen_at = ?, idle_expires_at = ?, row_version = row_version + 1
                WHERE session_id = ? AND status = 'ACTIVE'
                """,
                Timestamp.from(lastSeenAt),
                Timestamp.from(idleExpiresAt),
                sessionId);
    }

    @Override
    public void replaceCredential(
            String accountId,
            String passwordHash,
            boolean clearFirstChange,
            String actorId,
            Instant at) {
        jdbc.update(
                """
                UPDATE password_credential
                SET password_hash = ?, algorithm = 'BCRYPT', parameter_version = '2B_COST_12',
                    changed_at = ?, row_version = row_version + 1
                WHERE account_id = ?
                """,
                passwordHash,
                Timestamp.from(at),
                accountId);
        jdbc.update(
                """
                UPDATE local_account
                SET first_password_change_required = ?, session_epoch = session_epoch + 1,
                    updated_by = ?, updated_at = ?, row_version = row_version + 1
                WHERE account_id = ?
                """,
                !clearFirstChange,
                actorId,
                Timestamp.from(at),
                accountId);
    }

    @Override
    public void markResetGrantUsed(String grantId, long expectedVersion, Instant at) {
        int updated = jdbc.update(
                """
                UPDATE password_reset_grant
                SET used_at = ?, row_version = row_version + 1
                WHERE grant_id = ? AND used_at IS NULL AND row_version = ?
                """,
                Timestamp.from(at),
                grantId,
                expectedVersion);
        if (updated != 1) {
            throw new OptimisticLockingFailureException("reset grant was already consumed");
        }
    }

    @Override
    public void revokeSession(
            String sessionId,
            String accountId,
            String actorId,
            String reason,
            String requestId,
            Instant at) {
        int updated = jdbc.update(
                """
                UPDATE user_session
                SET status = 'REVOKED', revoked_at = ?, revocation_reason = ?,
                    row_version = row_version + 1
                WHERE session_id = ? AND account_id = ? AND status = 'ACTIVE'
                """,
                Timestamp.from(at),
                reason,
                sessionId,
                accountId);
        if (updated == 1) {
            jdbc.update(
                    """
                    INSERT INTO session_revocation (
                        revocation_id, session_id, account_id, reason,
                        revoked_by, request_id, revoked_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(),
                    sessionId,
                    accountId,
                    reason,
                    actorId,
                    requestId,
                    Timestamp.from(at));
        }
    }

    @Override
    public void revokeAllSessions(
            String accountId,
            String actorId,
            String reason,
            String requestId,
            Instant at) {
        List<String> activeSessionIds = jdbc.queryForList(
                """
                SELECT session_id FROM user_session
                WHERE account_id = ? AND status = 'ACTIVE'
                """,
                String.class,
                accountId);
        for (String sessionId : activeSessionIds) {
            revokeSession(sessionId, accountId, actorId, reason, requestId, at);
        }
    }

    @Override
    public void issueResetGrant(
            String accountId,
            String digest,
            String actorId,
            String requestId,
            Instant expiresAt,
            Instant now) {
        jdbc.update(
                """
                INSERT INTO password_reset_grant (
                    grant_id, account_id, token_digest, expires_at, used_at,
                    issued_by, request_id, created_at, row_version
                ) VALUES (?, ?, ?, ?, NULL, ?, ?, ?, 0)
                """,
                UUID.randomUUID().toString(),
                accountId,
                digest,
                Timestamp.from(expiresAt),
                actorId,
                requestId,
                Timestamp.from(now));
    }

    @Override
    public void invalidateUnusedResetGrants(String accountId, Instant at) {
        jdbc.update(
                """
                UPDATE password_reset_grant
                SET used_at = ?, row_version = row_version + 1
                WHERE account_id = ? AND used_at IS NULL
                """,
                Timestamp.from(at),
                accountId);
    }

    private static AccountRecord toAccount(AccountRow row) {
        return new AccountRecord(
                row.accountId(),
                row.principalId(),
                row.username(),
                row.normalizedUsername(),
                row.displayName(),
                row.status(),
                row.firstPasswordChangeRequired(),
                row.lockedUntil(),
                row.lastLoginAt(),
                row.sessionEpoch(),
                row.rowVersion(),
                row.companyId());
    }

    private static SessionRecord toSession(SessionRow row) {
        return new SessionRecord(
                row.sessionId(),
                row.accountId(),
                row.tokenDigest(),
                row.status(),
                row.createdAt(),
                row.lastSeenAt(),
                row.idleExpiresAt(),
                row.absoluteExpiresAt(),
                row.requestId(),
                row.rowVersion());
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
