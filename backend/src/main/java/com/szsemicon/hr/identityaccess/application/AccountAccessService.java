package com.szsemicon.hr.identityaccess.application;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.AccountRecord;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.RoleAssignmentInput;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.RoleAssignmentRecord;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.RoleRecord;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountAccessService {

    private final AccountPersistence accountPersistence;
    private final AuthenticationPersistence authenticationPersistence;
    private final AuditService auditService;
    private final SecurityTokenService tokenService;
    private final PasswordCodec passwordCodec;
    private final Clock clock;

    public AccountAccessService(
            AccountPersistence accountPersistence,
            AuthenticationPersistence authenticationPersistence,
            AuditService auditService,
            SecurityTokenService tokenService,
            PasswordCodec passwordCodec,
            Clock clock) {
        this.accountPersistence = accountPersistence;
        this.authenticationPersistence = authenticationPersistence;
        this.auditService = auditService;
        this.tokenService = tokenService;
        this.passwordCodec = passwordCodec;
        this.clock = clock;
    }

    @Transactional
    public AccountDetail createAccount(CreateAccountCommand command) {
        validatePassword(command.temporaryPassword());
        validateAssignments(command.roleAssignments());
        String actorId = principalId();
        Instant now = clock.instant();
        String accountId;
        try {
            accountId = accountPersistence.createAccount(
                    command.username().trim(),
                    normalizeUsername(command.username()),
                    command.displayName().trim(),
                    passwordCodec.encode(command.temporaryPassword()),
                    actorId,
                    now);
        } catch (DataIntegrityViolationException exception) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "ACCOUNT_USERNAME_CONFLICT",
                    "账号用户名已存在");
        }
        accountPersistence.replaceRoleAssignments(
                accountId,
                0,
                command.roleAssignments(),
                actorId,
                "CREATE_ACCOUNT",
                now);
        auditService.record(
                actorId,
                "ACCOUNT_CREATED",
                "LOCAL_ACCOUNT",
                accountId,
                "SUCCESS",
                null);
        return detail(authenticationPersistence.findAccountById(accountId).orElseThrow());
    }

    @Transactional(readOnly = true)
    public AccountPage listAccounts(String query, String status, int page, int size) {
        int boundedSize = Math.min(Math.max(size, 1), 100);
        int boundedPage = Math.max(page, 0);
        String actorId = principalId();
        List<AccountSummary> items = accountPersistence.listAccounts(
                        actorId,
                        query,
                        status,
                        boundedSize,
                        boundedPage * boundedSize,
                        clock.instant())
                .stream()
                .map(AccountAccessService::summary)
                .toList();
        return new AccountPage(
                items,
                accountPersistence.countAccounts(actorId, query, status, clock.instant()),
                boundedPage,
                boundedSize);
    }

    @Transactional(readOnly = true)
    public AccountDetail getAccount(String accountId) {
        return detail(requireVisible(accountId));
    }

    @Transactional
    public AccountDetail updateStatus(
            String accountId,
            String status,
            String reason,
            long expectedVersion) {
        if (!List.of("ACTIVE", "DISABLED").contains(status)) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "账号状态不受支持");
        }
        AccountRecord account = requireVisible(accountId);
        String actorId = principalId();
        Instant now = clock.instant();
        accountPersistence.updateAccountStatus(
                account.accountId(),
                status,
                expectedVersion,
                actorId,
                now);
        if ("DISABLED".equals(status)) {
            authenticationPersistence.revokeAllSessions(
                    account.accountId(),
                    actorId,
                    "ACCOUNT_DISABLED",
                    auditService.currentCorrelationId(),
                    now);
        }
        auditService.record(
                actorId,
                "ACCOUNT_STATUS_CHANGED",
                "LOCAL_ACCOUNT",
                accountId,
                "SUCCESS",
                reason);
        return detail(authenticationPersistence.findAccountById(accountId).orElseThrow());
    }

    @Transactional
    public void lock(String accountId, boolean locked, String reason) {
        AccountRecord account = requireVisible(accountId);
        String actorId = principalId();
        Instant now = clock.instant();
        accountPersistence.setAccountLock(account.accountId(), locked, actorId, now);
        if (locked) {
            authenticationPersistence.revokeAllSessions(
                    account.accountId(),
                    actorId,
                    "ACCOUNT_LOCKED",
                    auditService.currentCorrelationId(),
                    now);
        }
        auditService.record(
                actorId,
                locked ? "ACCOUNT_LOCKED" : "ACCOUNT_UNLOCKED",
                "LOCAL_ACCOUNT",
                accountId,
                "SUCCESS",
                reason);
    }

    @Transactional
    public void issueResetGrant(String accountId, String reason) {
        AccountRecord account = requireVisible(accountId);
        String actorId = principalId();
        String rawGrant = tokenService.newOpaqueToken();
        Instant now = clock.instant();
        authenticationPersistence.issueResetGrant(
                account.accountId(),
                tokenService.digest(rawGrant),
                actorId,
                auditService.currentCorrelationId(),
                now.plus(Duration.ofMinutes(15)),
                now);
        auditService.record(
                actorId,
                "PASSWORD_RESET_GRANT_ISSUED",
                "LOCAL_ACCOUNT",
                accountId,
                "SUCCESS",
                reason);
    }

    @Transactional
    public AccountDetail replaceRoleAssignments(
            String accountId,
            List<RoleAssignmentInput> assignments,
            String reason,
            long expectedVersion) {
        validateAssignments(assignments);
        AccountRecord account = requireVisible(accountId);
        String actorId = principalId();
        accountPersistence.replaceRoleAssignments(
                account.accountId(),
                expectedVersion,
                assignments,
                actorId,
                reason,
                clock.instant());
        auditService.record(
                actorId,
                "ROLE_ASSIGNMENTS_REPLACED",
                "LOCAL_ACCOUNT",
                accountId,
                "SUCCESS",
                reason);
        return detail(authenticationPersistence.findAccountById(accountId).orElseThrow());
    }

    @Transactional(readOnly = true)
    public List<RoleRecord> listRoles() {
        return accountPersistence.findRoles();
    }

    private AccountRecord requireVisible(String accountId) {
        String actorId = principalId();
        if (!accountPersistence.canAccessAccount(actorId, accountId, clock.instant())) {
            throw unavailable();
        }
        return authenticationPersistence.findAccountById(accountId)
                .orElseThrow(AccountAccessService::unavailable);
    }

    private AccountDetail detail(AccountRecord account) {
        List<RoleAssignmentRecord> roles = accountPersistence.findRoleAssignments(
                account.principalId(),
                clock.instant());
        List<SessionRecord> sessions = accountPersistence.findSessions(account.accountId());
        boolean resetPending = false;
        return new AccountDetail(
                account.accountId(),
                account.username(),
                account.displayName(),
                account.status(),
                account.firstPasswordChangeRequired(),
                resetPending,
                account.lockedUntil(),
                account.lastLoginAt(),
                account.rowVersion(),
                roles,
                sessions.stream()
                        .map(session -> new SessionSummary(
                                session.sessionId(),
                                session.status(),
                                session.createdAt(),
                                session.lastSeenAt(),
                                session.idleExpiresAt(),
                                session.absoluteExpiresAt()))
                        .toList());
    }

    private static AccountSummary summary(AccountRecord account) {
        return new AccountSummary(
                account.accountId(),
                account.username(),
                account.displayName(),
                account.status(),
                account.firstPasswordChangeRequired(),
                false,
                account.lockedUntil(),
                account.lastLoginAt(),
                account.rowVersion());
    }

    private static void validateAssignments(List<RoleAssignmentInput> assignments) {
        if (assignments == null || assignments.isEmpty()) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "至少需要一个有效角色授权");
        }
        for (RoleAssignmentInput assignment : assignments) {
            if (assignment.validFrom() == null
                    || assignment.validTo() != null
                    && !assignment.validTo().isAfter(assignment.validFrom())) {
                throw new ApiProblemException(
                        HttpStatus.BAD_REQUEST,
                        "VALIDATION_ERROR",
                        "授权有效期无效");
            }
        }
    }

    private void validatePassword(String suppliedSecret) {
        if (!passwordCodec.meetsPolicy(suppliedSecret)) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "PASSWORD_POLICY_VIOLATION",
                    "临时密码不符合安全策略");
        }
    }

    private static String normalizeUsername(String username) {
        return Normalizer.normalize(username.trim(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
    }

    private static String principalId() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    private static ApiProblemException unavailable() {
        return new ApiProblemException(
                HttpStatus.NOT_FOUND,
                "RESOURCE_NOT_AVAILABLE",
                "请求的资源不可用");
    }

    public record CreateAccountCommand(
            String username,
            String displayName,
            String temporaryPassword,
            List<RoleAssignmentInput> roleAssignments) {
    }

    public record AccountSummary(
            String accountId,
            String username,
            String displayName,
            String status,
            boolean firstPasswordChangeRequired,
            boolean resetPending,
            Instant lockedUntil,
            Instant lastLoginAt,
            long rowVersion) {
    }

    public record AccountDetail(
            String accountId,
            String username,
            String displayName,
            String status,
            boolean firstPasswordChangeRequired,
            boolean resetPending,
            Instant lockedUntil,
            Instant lastLoginAt,
            long rowVersion,
            List<RoleAssignmentRecord> roles,
            List<SessionSummary> sessions) {
    }

    public record SessionSummary(
            String sessionId,
            String status,
            Instant issuedAt,
            Instant lastSeenAt,
            Instant idleExpiresAt,
            Instant absoluteExpiresAt) {
    }

    public record AccountPage(
            List<AccountSummary> items,
            long total,
            int page,
            int size) {
    }
}
