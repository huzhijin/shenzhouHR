package com.szsemicon.hr.identityaccess.application;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.AccountRecord;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.ResolvedRoleAssignmentInput;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
        String employeeId = normalizeEmployeeId(command.employeeId());
        validateEmployeeBinding(employeeId, command.roleAssignments());
        String actorId = principalId();
        Instant now = clock.instant();
        List<ResolvedRoleAssignmentInput> authorizedAssignments =
                authorizeRoleAssignments(
                        actorId,
                        null,
                        employeeId,
                        command.roleAssignments(),
                        now);
        String accountId;
        try {
            accountId = accountPersistence.createAccount(
                    command.username().trim(),
                    normalizeUsername(command.username()),
                    command.displayName().trim(),
                    employeeId,
                    passwordCodec.encode(command.temporaryPassword()),
                    actorId,
                    now);
        } catch (EmployeeAccountConflictException exception) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "EMPLOYEE_ACCOUNT_CONFLICT",
                    "该员工已绑定本地账号");
        } catch (DataIntegrityViolationException exception) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "ACCOUNT_USERNAME_CONFLICT",
                    "账号用户名已存在");
        }
        accountPersistence.replaceRoleAssignments(
                accountId,
                0,
                authorizedAssignments,
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
        Instant now = clock.instant();
        List<ResolvedRoleAssignmentInput> authorizedAssignments =
                authorizeRoleAssignments(
                        actorId,
                        account.principalId(),
                        null,
                        assignments,
                        now);
        accountPersistence.replaceRoleAssignments(
                account.accountId(),
                expectedVersion,
                authorizedAssignments,
                actorId,
                reason,
                now);
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
        if (assignments == null || assignments.isEmpty() || assignments.size() > 100) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "角色授权数量必须为 1 至 100 条");
        }
        Set<String> assignmentKeys = new HashSet<>();
        for (RoleAssignmentInput assignment : assignments) {
            if (assignment == null
                    || assignment.roleId() == null
                    || assignment.roleId().isBlank()
                    || assignment.roleId().length() > 36
                    || assignment.scopeType() == null
                    || !Set.of("LEGAL_ENTITY", "ORGANIZATION", "SELF")
                            .contains(assignment.scopeType())
                    || assignment.validFrom() == null
                    || assignment.validTo() != null
                    && !assignment.validTo().isAfter(assignment.validFrom())) {
                throw new ApiProblemException(
                        HttpStatus.BAD_REQUEST,
                        "VALIDATION_ERROR",
                        "授权有效期无效");
            }
            if ("SELF".equals(assignment.scopeType())) {
                if (assignment.scopeResourceId() != null
                        && !assignment.scopeResourceId().isBlank()) {
                    throw invalidScopeTarget();
                }
            } else if (assignment.scopeResourceId() == null
                    || assignment.scopeResourceId().isBlank()
                    || assignment.scopeResourceId().length() > 36) {
                throw invalidScopeTarget();
            }
            String assignmentKey = assignment.roleId()
                    + '\u0000'
                    + assignment.scopeType()
                    + '\u0000'
                    + String.valueOf(assignment.scopeResourceId())
                    + '\u0000'
                    + assignment.validFrom()
                    + '\u0000'
                    + assignment.validTo();
            if (!assignmentKeys.add(assignmentKey)) {
                throw new ApiProblemException(
                        HttpStatus.BAD_REQUEST,
                        "VALIDATION_ERROR",
                        "角色授权不能包含完全重复的记录");
            }
        }
    }

    private static void validateEmployeeBinding(
            String employeeId,
            List<RoleAssignmentInput> assignments) {
        boolean includesSelfScope = assignments.stream()
                .anyMatch(assignment -> "SELF".equals(assignment.scopeType()));
        if (includesSelfScope) {
            if (employeeId == null
                    || employeeId.isBlank()
                    || employeeId.length() > 36) {
                throw new ApiProblemException(
                        HttpStatus.BAD_REQUEST,
                        "EMPLOYEE_BINDING_REQUIRED",
                        "本人角色必须绑定有效员工");
            }
            return;
        }
        if (employeeId != null && !employeeId.isBlank()) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "EMPLOYEE_BINDING_NOT_ALLOWED",
                    "未授予本人角色时不能绑定员工");
        }
    }

    private static String normalizeEmployeeId(String employeeId) {
        return employeeId == null || employeeId.isBlank() ? null : employeeId;
    }

    private List<ResolvedRoleAssignmentInput> authorizeRoleAssignments(
            String actorPrincipalId,
            String targetPrincipalId,
            String targetEmployeeId,
            List<RoleAssignmentInput> assignments,
            Instant now) {
        if (actorPrincipalId.equals(targetPrincipalId)) {
            throw new ApiProblemException(
                    HttpStatus.FORBIDDEN,
                    "ROLE_ASSIGNMENT_SELF_SERVICE_DENIED",
                    "禁止修改本账号的角色授权");
        }
        accountPersistence.lockRoleGrantTargetLegalEntities(
                targetPrincipalId,
                targetEmployeeId,
                assignments,
                now);
        if (!accountPersistence.lockCurrentRoleGrantAuthority(
                actorPrincipalId, now)) {
            throw roleAssignmentScopeDenied();
        }
        Map<String, String> roleCodes = new HashMap<>();
        for (String roleId : assignments.stream()
                .map(RoleAssignmentInput::roleId)
                .distinct()
                .sorted()
                .toList()) {
            roleCodes.put(
                    roleId,
                    accountPersistence
                            .findRoleCodeForUpdate(roleId)
                            .orElseThrow(AccountAccessService::roleScopeNotAllowed));
        }
        for (RoleAssignmentInput assignment : assignments) {
            String roleCode = roleCodes.get(assignment.roleId());
            if (!RoleScopeMatrix.permits(roleCode, assignment.scopeType())) {
                throw roleScopeNotAllowed();
            }
        }
        return accountPersistence.resolveAuthorizedRoleAssignmentScopes(
                actorPrincipalId,
                targetPrincipalId,
                targetEmployeeId,
                assignments,
                now);
    }

    private static ApiProblemException invalidScopeTarget() {
        return new ApiProblemException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "授权范围资源无效");
    }

    private static ApiProblemException roleScopeNotAllowed() {
        return new ApiProblemException(
                HttpStatus.BAD_REQUEST,
                "ROLE_SCOPE_NOT_ALLOWED",
                "该角色不允许使用请求的授权范围");
    }

    private static ApiProblemException roleAssignmentScopeDenied() {
        return new ApiProblemException(
                HttpStatus.FORBIDDEN,
                "ROLE_ASSIGNMENT_SCOPE_DENIED",
                "当前授权范围不能授予请求的数据范围");
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
            String employeeId,
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
