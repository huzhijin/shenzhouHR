package com.szsemicon.hr.identityaccess.application;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.AccountRecord;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.CredentialRecord;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.FailureRecord;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.ResetGrantRecord;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.SessionRecord;
import com.szsemicon.hr.shared.security.PasswordCodec;
import com.szsemicon.hr.shared.security.SecurityTokenService;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthenticationService {

    private static final String INVALID_CREDENTIALS_MESSAGE = "用户名或密码错误";
    private final AuthenticationPersistence repository;
    private final AuditService auditService;
    private final SecurityTokenService tokenService;
    private final PasswordCodec passwordCodec;
    private final Clock clock;
    private final int maxFailures;
    private final Duration failureWindow;
    private final Duration lockDuration;
    private final Duration idleTimeout;
    private final Duration absoluteTimeout;

    public AuthenticationService(
            AuthenticationPersistence repository,
            AuditService auditService,
            SecurityTokenService tokenService,
            PasswordCodec passwordCodec,
            Clock clock,
            @Value("${shenzhouhr.security.login.max-failures:5}") int maxFailures,
            @Value("${shenzhouhr.security.login.failure-window:PT15M}") Duration failureWindow,
            @Value("${shenzhouhr.security.login.lock-duration:PT30M}") Duration lockDuration,
            @Value("${shenzhouhr.security.session.idle-timeout:PT30M}") Duration idleTimeout,
            @Value("${shenzhouhr.security.session.absolute-timeout:PT8H}") Duration absoluteTimeout) {
        this.repository = repository;
        this.auditService = auditService;
        this.tokenService = tokenService;
        this.passwordCodec = passwordCodec;
        this.clock = clock;
        this.maxFailures = maxFailures;
        this.failureWindow = failureWindow;
        this.lockDuration = lockDuration;
        this.idleTimeout = idleTimeout;
        this.absoluteTimeout = absoluteTimeout;
    }

    @Transactional(noRollbackFor = ApiProblemException.class)
    public LoginResult login(String username, String password) {
        Instant now = clock.instant();
        String normalizedUsername = normalizeUsername(username);
        AccountRecord account = repository.findAccountByNormalizedUsername(normalizedUsername)
                .orElse(null);
        CredentialRecord credential = account == null
                ? null
                : repository.findCredential(account.accountId()).orElse(null);
        boolean passwordMatches = passwordCodec.matches(
                password,
                credential == null ? passwordCodec.nonMatchingHash() : credential.passwordHash());
        if (account == null || credential == null || !passwordMatches) {
            if (account != null) {
                registerFailure(account, now);
                auditService.record(
                        account.principalId(),
                        "LOGIN_FAILED",
                        "LOCAL_ACCOUNT",
                        account.accountId(),
                        "FAILURE",
                        "INVALID_CREDENTIALS");
            } else {
                auditService.record(
                        null,
                        "LOGIN_FAILED",
                        "LOCAL_ACCOUNT",
                        null,
                        "FAILURE",
                        "INVALID_CREDENTIALS");
            }
            throw new ApiProblemException(
                    HttpStatus.UNAUTHORIZED,
                    "INVALID_CREDENTIALS",
                    INVALID_CREDENTIALS_MESSAGE);
        }

        if ("LOCKED".equals(account.status())) {
            if (account.lockedUntil() != null && !account.lockedUntil().isAfter(now)) {
                repository.unlockExpiredAccount(account.accountId(), account.principalId(), now);
                account = repository.findAccountById(account.accountId()).orElseThrow();
            } else {
                auditService.record(
                        account.principalId(),
                        "LOGIN_REJECTED_LOCKED",
                        "LOCAL_ACCOUNT",
                        account.accountId(),
                        "DENIED",
                        "ACCOUNT_LOCKED");
                throw new ApiProblemException(
                        HttpStatus.LOCKED,
                        "ACCOUNT_LOCKED",
                        "账号已锁定，请稍后重试或联系管理员");
            }
        }
        if (!"ACTIVE".equals(account.status())) {
            throw new ApiProblemException(
                    HttpStatus.UNAUTHORIZED,
                    "INVALID_CREDENTIALS",
                    INVALID_CREDENTIALS_MESSAGE);
        }

        repository.recordSuccessfulLogin(account.accountId(), now);
        String rawToken = tokenService.newOpaqueToken();
        String sessionId = UUID.randomUUID().toString();
        SessionRecord session = new SessionRecord(
                sessionId,
                account.accountId(),
                tokenService.digest(rawToken),
                "ACTIVE",
                now,
                now,
                now.plus(idleTimeout),
                now.plus(absoluteTimeout),
                auditService.currentCorrelationId(),
                0);
        repository.insertSession(session);
        auditService.record(
                account.principalId(),
                "LOGIN_SUCCEEDED",
                "LOCAL_ACCOUNT",
                account.accountId(),
                "SUCCESS",
                null);
        AccountRecord refreshed = repository.findAccountById(account.accountId()).orElseThrow();
        return new LoginResult(rawToken, session, refreshed, capabilities(refreshed.principalId()));
    }

    @Transactional
    public SessionIdentity authenticateSession(String rawToken) {
        Instant now = clock.instant();
        SessionRecord session = repository.findSessionByDigest(tokenService.digest(rawToken), now)
                .orElse(null);
        if (session == null) {
            return null;
        }
        AccountRecord account = repository.findAccountById(session.accountId()).orElse(null);
        if (account == null || !"ACTIVE".equals(account.status())) {
            return null;
        }
        Instant extendedIdle = now.plus(idleTimeout);
        if (extendedIdle.isAfter(session.absoluteExpiresAt())) {
            extendedIdle = session.absoluteExpiresAt();
        }
        repository.touchSession(session.sessionId(), now, extendedIdle);
        SessionRecord refreshed = new SessionRecord(
                session.sessionId(),
                session.accountId(),
                session.tokenDigest(),
                session.status(),
                session.createdAt(),
                now,
                extendedIdle,
                session.absoluteExpiresAt(),
                session.requestId(),
                session.rowVersion() + 1);
        return new SessionIdentity(refreshed, account, capabilities(account.principalId()));
    }

    @Transactional(readOnly = true)
    public SessionIdentity currentSession(String rawToken) {
        SessionIdentity identity = authenticateSession(rawToken);
        if (identity == null) {
            throw new AuthenticationCredentialsNotFoundException("session is not active");
        }
        return identity;
    }

    @Transactional
    public void changePassword(
            String currentPassword,
            String newPassword,
            boolean firstChangeOnly,
            String currentSessionId) {
        validateNewPassword(newPassword);
        AccountRecord account = currentAccount();
        if (firstChangeOnly && !account.firstPasswordChangeRequired()) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "FIRST_PASSWORD_CHANGE_NOT_REQUIRED",
                    "当前账号不需要首次改密");
        }
        if (!firstChangeOnly && account.firstPasswordChangeRequired()) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "FIRST_PASSWORD_CHANGE_REQUIRED",
                    "必须先完成首次密码修改");
        }
        CredentialRecord credential = repository.findCredential(account.accountId())
                .orElseThrow(() -> new ApiProblemException(
                        HttpStatus.UNAUTHORIZED,
                        "INVALID_CREDENTIALS",
                        INVALID_CREDENTIALS_MESSAGE));
        if (!passwordCodec.matches(currentPassword, credential.passwordHash())) {
            throw new ApiProblemException(
                    HttpStatus.UNAUTHORIZED,
                    "INVALID_CREDENTIALS",
                    INVALID_CREDENTIALS_MESSAGE);
        }
        Instant now = clock.instant();
        repository.replaceCredential(
                account.accountId(),
                passwordCodec.encode(newPassword),
                true,
                account.principalId(),
                now);
        repository.revokeAllSessions(
                account.accountId(),
                account.principalId(),
                firstChangeOnly ? "FIRST_PASSWORD_CHANGED" : "PASSWORD_CHANGED",
                auditService.currentCorrelationId(),
                now);
        auditService.record(
                account.principalId(),
                firstChangeOnly ? "FIRST_PASSWORD_CHANGED" : "PASSWORD_CHANGED",
                "LOCAL_ACCOUNT",
                account.accountId(),
                "SUCCESS",
                null);
    }

    @Transactional
    public void resetPassword(String grant, String newPassword) {
        validateNewPassword(newPassword);
        Instant now = clock.instant();
        ResetGrantRecord resetGrant = repository.findResetGrantByDigest(
                        tokenService.digest(grant),
                        now)
                .orElseThrow(() -> new ApiProblemException(
                        HttpStatus.UNAUTHORIZED,
                        "INVALID_RESET_GRANT",
                        "密码重置授权无效或已过期"));
        AccountRecord account = repository.findAccountById(resetGrant.accountId())
                .orElseThrow(() -> new ApiProblemException(
                        HttpStatus.UNAUTHORIZED,
                        "INVALID_RESET_GRANT",
                        "密码重置授权无效或已过期"));
        repository.markResetGrantUsed(resetGrant.grantId(), resetGrant.rowVersion(), now);
        repository.replaceCredential(
                account.accountId(),
                passwordCodec.encode(newPassword),
                true,
                account.principalId(),
                now);
        repository.revokeAllSessions(
                account.accountId(),
                account.principalId(),
                "PASSWORD_RESET",
                auditService.currentCorrelationId(),
                now);
        auditService.record(
                account.principalId(),
                "PASSWORD_RESET",
                "LOCAL_ACCOUNT",
                account.accountId(),
                "SUCCESS",
                null);
    }

    @Transactional
    public void logout(String sessionId) {
        SessionRecord session = repository.findSessionById(sessionId)
                .orElseThrow(() -> new ApiProblemException(
                        HttpStatus.UNAUTHORIZED,
                        "AUTHENTICATION_REQUIRED",
                        "需要有效身份"));
        AccountRecord account = currentAccount();
        if (!account.accountId().equals(session.accountId())) {
            throw unavailable();
        }
        Instant now = clock.instant();
        repository.revokeSession(
                sessionId,
                account.accountId(),
                account.principalId(),
                "LOGOUT",
                auditService.currentCorrelationId(),
                now);
        auditService.record(
                account.principalId(),
                "LOGOUT",
                "USER_SESSION",
                sessionId,
                "SUCCESS",
                null);
    }

    @Transactional
    public void revokeCurrentAccountSession(String sessionId, String reason) {
        SessionRecord session = repository.findSessionById(sessionId)
                .orElseThrow(AuthenticationService::unavailable);
        AccountRecord account = currentAccount();
        if (!account.accountId().equals(session.accountId())) {
            throw unavailable();
        }
        Instant now = clock.instant();
        repository.revokeSession(
                sessionId,
                account.accountId(),
                account.principalId(),
                reason,
                auditService.currentCorrelationId(),
                now);
        auditService.record(
                account.principalId(),
                "SESSION_REVOKED",
                "USER_SESSION",
                sessionId,
                "SUCCESS",
                reason);
    }

    @Transactional
    public void requestPasswordReset(String username) {
        AccountRecord account = repository.findAccountByNormalizedUsername(normalizeUsername(username))
                .orElse(null);
        if (account != null) {
            String rawGrant = tokenService.newOpaqueToken();
            repository.issueResetGrant(
                    account.accountId(),
                    tokenService.digest(rawGrant),
                    account.principalId(),
                    auditService.currentCorrelationId(),
                    clock.instant().plus(Duration.ofMinutes(15)),
                    clock.instant());
            auditService.record(
                    account.principalId(),
                    "PASSWORD_RESET_REQUESTED",
                    "LOCAL_ACCOUNT",
                    account.accountId(),
                    "SUCCESS",
                    "OUT_OF_BAND_DELIVERY");
        }
    }

    public List<String> capabilities(String principalId) {
        return repository.findCapabilities(principalId, clock.instant()).stream()
                .filter(capability -> !capability.startsWith("PAYROLL:"))
                .sorted()
                .toList();
    }

    public AccountRecord currentAccount() {
        String principalId = SecurityContextHolder.getContext().getAuthentication().getName();
        return repository.findAccountByPrincipalId(principalId)
                .orElseThrow(AuthenticationService::unavailable);
    }

    private void registerFailure(AccountRecord account, Instant now) {
        FailureRecord current = repository.findFailure(account.accountId());
        Instant windowStarted = current.windowStartedAt();
        int failures = current.failureCount();
        if (windowStarted == null || windowStarted.plus(failureWindow).isBefore(now)) {
            windowStarted = now;
            failures = 0;
        }
        failures++;
        Instant lockedUntil = failures >= maxFailures ? now.plus(lockDuration) : null;
        repository.recordFailedLogin(
                account.accountId(),
                failures,
                windowStarted,
                now,
                lockedUntil);
        if (lockedUntil != null) {
            auditService.record(
                    account.principalId(),
                    "ACCOUNT_AUTO_LOCKED",
                    "LOCAL_ACCOUNT",
                    account.accountId(),
                    "SUCCESS",
                    "LOGIN_FAILURE_THRESHOLD");
        }
    }

    private static String normalizeUsername(String username) {
        String value = username == null ? "" : username.trim();
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private void validateNewPassword(String suppliedSecret) {
        if (!passwordCodec.meetsPolicy(suppliedSecret)) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "PASSWORD_POLICY_VIOLATION",
                    "新密码不符合安全策略");
        }
    }

    private static ApiProblemException unavailable() {
        return new ApiProblemException(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_AVAILABLE",
                "请求的资源不可用");
    }

    public record LoginResult(
            String rawToken,
            SessionRecord session,
            AccountRecord account,
            List<String> capabilities) {
    }

    public record SessionIdentity(
            SessionRecord session,
            AccountRecord account,
            List<String> capabilities) {
    }
}
