package com.szsemicon.hr.identityaccess.application;

import com.szsemicon.hr.audit.application.AuditService;
import com.szsemicon.hr.identityaccess.application.AccountPersistence.CandidateCounts;
import com.szsemicon.hr.identityaccess.application.AccountPersistence.EmployeeAccountCandidateRecord;
import com.szsemicon.hr.identityaccess.application.AccountPersistence.EmployeeProvisioningTarget;
import com.szsemicon.hr.identityaccess.application.AccountPersistence.IdempotencyClaim;
import com.szsemicon.hr.identityaccess.application.AccountPersistence.RecoverableRoleAssignment;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.AccountRecord;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.CredentialRecord;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.ResolvedRoleAssignmentInput;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.RoleAssignmentInput;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.RoleAssignmentRecord;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.RoleRecord;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.SessionRecord;
import com.szsemicon.hr.shared.security.PasswordCodec;
import com.szsemicon.hr.shared.security.SecurityTokenService;
import com.szsemicon.hr.shared.validation.IdempotencyKeyPolicy;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class AccountAccessService {

    private static final String ACCOUNT_CREATE = "ACCOUNT:CREATE";
    private static final String ACCOUNT_READ = "ACCOUNT:READ";
    private static final String ACCOUNT_EDIT = "ACCOUNT:EDIT";
    private static final String ACCOUNT_LOCK = "ACCOUNT:LOCK";
    private static final String ACCOUNT_UNLOCK = "ACCOUNT:UNLOCK";
    private static final String ACCOUNT_RESET_PASSWORD =
            "ACCOUNT:RESET_PASSWORD";
    private static final String ROLE_ASSIGN = "ROLE:ASSIGN";
    private static final String LEGACY_DEFAULT_TEMPORARY_PASSWORD = "123456";
    private static final String EMPLOYEE_SELF_ROLE = "EMPLOYEE_SELF";
    private static final String EMPLOYEE_ACCOUNTS_BULK_CREATE =
            "EMPLOYEE_ACCOUNTS_BULK_CREATE";
    private static final String EMPLOYEE_ACCOUNTS_BULK_CREATE_DIGEST_VERSION =
            "EMPLOYEE_ACCOUNTS_BULK_CREATE_V2";
    private static final String PROVISIONING_PASSWORD_DERIVATION_VERSION =
            "HMAC_SHA256_V1";
    private static final Pattern PROVISIONING_RECOVERY_KEY_PATTERN =
            Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final Pattern PROVISIONING_KEY_ID_PATTERN =
            Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    private static final int MAX_BULK_ACCOUNT_COUNT = 20;
    private static final Set<String> PRIVILEGED_ROLE_CODES =
            Set.of("SYSTEM_ADMIN", "HR_ADMIN", "AUDITOR");

    private final AccountPersistence accountPersistence;
    private final AuthenticationPersistence authenticationPersistence;
    private final AuditService auditService;
    private final SecurityTokenService tokenService;
    private final PasswordCodec passwordCodec;
    private final ObjectMapper objectMapper;
    private final byte[] provisioningPepper;
    private final String provisioningKeyId;
    private final Duration provisioningRecoveryWindow;
    private final TransactionTemplate provisioningTransactions;
    private final Clock clock;

    public AccountAccessService(
            AccountPersistence accountPersistence,
            AuthenticationPersistence authenticationPersistence,
            AuditService auditService,
            SecurityTokenService tokenService,
            PasswordCodec passwordCodec,
            ObjectMapper objectMapper,
            @Value("${shenzhouhr.security.account-provisioning.pepper:}")
                    String provisioningPepper,
            @Value("${shenzhouhr.security.account-provisioning.key-id:v1}")
                    String provisioningKeyId,
            @Value("${shenzhouhr.security.account-provisioning.recovery-window:PT1H}")
                    Duration provisioningRecoveryWindow,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.accountPersistence = accountPersistence;
        this.authenticationPersistence = authenticationPersistence;
        this.auditService = auditService;
        this.tokenService = tokenService;
        this.passwordCodec = passwordCodec;
        this.objectMapper = objectMapper;
        this.provisioningPepper = decodeProvisioningPepper(provisioningPepper);
        if (!PROVISIONING_KEY_ID_PATTERN.matcher(provisioningKeyId).matches()) {
            throw new IllegalStateException(
                    "account provisioning key id must be 1 to 64 safe characters");
        }
        if (provisioningRecoveryWindow.isNegative()
                || provisioningRecoveryWindow.isZero()
                || provisioningRecoveryWindow.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalStateException(
                    "account provisioning recovery window must be between 0 and 24 hours");
        }
        this.provisioningKeyId = provisioningKeyId;
        this.provisioningRecoveryWindow = provisioningRecoveryWindow;
        this.provisioningTransactions = new TransactionTemplate(transactionManager);
        this.provisioningTransactions.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.clock = clock;
    }

    @Transactional
    public AccountDetail createAccount(CreateAccountCommand command) {
        validateAssignments(command.roleAssignments());
        String employeeId = normalizeEmployeeId(command.employeeId());
        validateEmployeeBinding(employeeId, command.roleAssignments());
        String actorId = principalId();
        Instant now = clock.instant();
        AuthorizedRoleAssignments authorization =
                authorizeRoleAssignments(
                        actorId,
                        null,
                        employeeId,
                        command.roleAssignments(),
                        now);
        String temporaryPassword = creationTemporaryPassword(
                authorization.privileged(),
                command.temporaryPassword());
        String accountId;
        try {
            accountId = accountPersistence.createAccount(
                    command.username().trim(),
                    normalizeUsername(command.username()),
                    command.displayName().trim(),
                    employeeId,
                    passwordCodec.encode(temporaryPassword),
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
                authorization.assignments(),
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
        return detail(
                authenticationPersistence.findAccountById(accountId).orElseThrow(),
                actorId,
                ACCOUNT_CREATE,
                now);
    }

    @Transactional(readOnly = true)
    public AccountPage listAccounts(String query, String status, int page, int size) {
        int boundedSize = Math.min(Math.max(size, 1), 100);
        int boundedPage = Math.max(page, 0);
        String actorId = principalId();
        Instant now = clock.instant();
        List<AccountSummary> items = accountPersistence.listAccounts(
                        actorId,
                        ACCOUNT_READ,
                        query,
                        status,
                        boundedSize,
                        boundedPage * boundedSize,
                        now)
                .stream()
                .map(AccountAccessService::summary)
                .toList();
        return new AccountPage(
                items,
                accountPersistence.countAccounts(
                        actorId,
                        ACCOUNT_READ,
                        query,
                        status,
                        now),
                boundedPage,
                boundedSize);
    }

    @Transactional(readOnly = true)
    public EmployeeAccountCandidatePage listEmployeeAccountCandidates(
            String companyId,
            String query,
            int page,
            int size) {
        if (companyId == null || companyId.isBlank() || companyId.length() > 36) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "请选择公司后再查看待开通员工");
        }
        if (query != null && query.length() > 100) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "搜索内容不能超过 100 个字符");
        }
        int boundedSize = Math.min(Math.max(size, 1), 100);
        int boundedPage = Math.max(page, 0);
        String actorId = principalId();
        Instant now = clock.instant();
        List<EmployeeAccountCandidate> items = accountPersistence
                .listEmployeeAccountCandidates(
                        actorId,
                        companyId,
                        query,
                        boundedSize,
                        boundedPage * boundedSize,
                        now)
                .stream()
                .map(AccountAccessService::candidate)
                .toList();
        CandidateCounts counts = accountPersistence.countEmployeeAccountCandidates(
                actorId, companyId, query, now);
        return new EmployeeAccountCandidatePage(
                items,
                counts.total(),
                counts.available(),
                counts.alreadyProvisioned(),
                counts.usernameConflicts(),
                boundedPage,
                boundedSize);
    }

    public BulkAccountCreationResult createEmployeeAccounts(
            List<String> requestedEmployeeIds,
            String idempotencyKey,
            String recoveryKey) {
        if (!IdempotencyKeyPolicy.isValid(idempotencyKey)) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "Idempotency-Key 必须为 16 至 128 位字母、数字或 ._:-");
        }
        if (!validProvisioningRecoveryKey(recoveryKey)) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "Provisioning-Recovery-Key 必须是 32 字节随机值");
        }
        if (requestedEmployeeIds == null
                || requestedEmployeeIds.isEmpty()
                || requestedEmployeeIds.size() > MAX_BULK_ACCOUNT_COUNT
                || requestedEmployeeIds.stream().anyMatch(id ->
                        id == null || id.isBlank() || id.length() > 36)) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "每批请选择 1 至 " + MAX_BULK_ACCOUNT_COUNT + " 名员工");
        }
        List<String> employeeIds = requestedEmployeeIds.stream()
                .distinct()
                .sorted()
                .toList();
        if (employeeIds.size() != requestedEmployeeIds.size()) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "同一批次不能重复选择员工");
        }
        String actorId = principalId();
        Instant now = clock.instant();
        String employeeSetDigest = employeeAccountSetDigest(employeeIds);
        String requestDigest = employeeAccountProvisioningDigest(
                actorId,
                idempotencyKey,
                recoveryKey,
                employeeSetDigest);
        if (accountPersistence.findIdempotency(
                        actorId,
                        EMPLOYEE_ACCOUNTS_BULK_CREATE,
                        idempotencyKey)
                .isPresent()) {
            return runProvisioningTransaction(() -> recoverEmployeeAccountBatch(
                    employeeIds,
                    actorId,
                    idempotencyKey,
                    recoveryKey,
                    employeeSetDigest,
                    requestDigest,
                    now));
        }
        try {
            return runProvisioningTransaction(() -> createEmployeeAccountBatch(
                    employeeIds,
                    actorId,
                    idempotencyKey,
                    recoveryKey,
                    employeeSetDigest,
                    requestDigest,
                    now));
        } catch (ProvisioningReplayRaceException exception) {
            return runProvisioningTransaction(() -> recoverEmployeeAccountBatch(
                    employeeIds,
                    actorId,
                    idempotencyKey,
                    recoveryKey,
                    employeeSetDigest,
                    requestDigest,
                    now));
        }
    }

    private BulkAccountCreationResult createEmployeeAccountBatch(
            List<String> employeeIds,
            String actorId,
            String idempotencyKey,
            String recoveryKey,
            String employeeSetDigest,
            String requestDigest,
            Instant now) {
        lockEmployeeProvisioningTargets(employeeIds);
        lockBulkProvisioningAuthority(actorId, now);
        if (accountPersistence.findIdempotency(
                        actorId,
                        EMPLOYEE_ACCOUNTS_BULK_CREATE,
                        idempotencyKey)
                .isPresent()) {
            throw new ProvisioningReplayRaceException();
        }
        IdempotencyClaim claim = accountPersistence.claimIdempotency(
                UUID.randomUUID().toString(),
                actorId,
                EMPLOYEE_ACCOUNTS_BULK_CREATE,
                idempotencyKey,
                requestDigest,
                now);
        requireMatchingProvisioningDigest(claim, requestDigest);
        if (!claim.firstClaim()) {
            throw new ProvisioningReplayRaceException();
        }
        List<EmployeeAccountCandidateRecord> candidates = requireProvisioningCandidates(
                actorId, employeeIds, now);
        EmployeeAccountCandidateRecord blocked = candidates.stream()
                .filter(item -> !"AVAILABLE".equals(item.status()))
                .findFirst()
                .orElse(null);
        if (blocked != null) {
            String message = "ALREADY_PROVISIONED".equals(blocked.status())
                    ? "员工 “" + blocked.displayName() + "” 已有登录账号"
                    : "工号 “" + blocked.employeeNumber() + "” 已被其他账号使用";
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "ACCOUNT_PROVISIONING_CONFLICT",
                    message + "，请刷新预检结果后处理");
        }
        String employeeRoleId = accountPersistence
                .findRoleIdByCodeForUpdate(EMPLOYEE_SELF_ROLE)
                .orElseThrow(() -> new ApiProblemException(
                        HttpStatus.CONFLICT,
                        "EMPLOYEE_ROLE_NOT_CONFIGURED",
                        "系统尚未配置“员工本人”角色，暂时无法批量开通账号"));
        List<TemporaryCredential> credentials = new ArrayList<>();
        for (EmployeeAccountCandidateRecord employee : candidates) {
            String temporaryPassword = provisioningPassword(
                    claim.recordId(),
                    actorId,
                    idempotencyKey,
                    recoveryKey,
                    employeeSetDigest,
                    employee.employeeId());
            AccountDetail created = createAccount(new CreateAccountCommand(
                    employee.employeeNumber(),
                    employee.displayName(),
                    temporaryPassword,
                    employee.employeeId(),
                    List.of(new RoleAssignmentInput(
                            employeeRoleId,
                            "SELF",
                            null,
                            now,
                            null))));
            credentials.add(new TemporaryCredential(
                    created.accountId(),
                    employee.employeeId(),
                    employee.employeeNumber(),
                    employee.displayName(),
                    employee.organizationName(),
                    created.username(),
                    temporaryPassword));
        }
        if (!accountPersistence.completeIdempotency(
                claim.recordId(),
                requestDigest,
                accountBindingJson(credentials))) {
            throw new IllegalStateException(
                    "account provisioning idempotency completion failed");
        }
        auditService.record(
                actorId,
                "EMPLOYEE_ACCOUNTS_BULK_CREATED",
                "LOCAL_ACCOUNT_BATCH",
                claim.recordId(),
                "SUCCESS",
                "created=" + credentials.size());
        return new BulkAccountCreationResult(
                List.copyOf(credentials),
                credentials.size(),
                false);
    }

    private BulkAccountCreationResult recoverEmployeeAccountBatch(
            List<String> employeeIds,
            String actorId,
            String idempotencyKey,
            String recoveryKey,
            String employeeSetDigest,
            String requestDigest,
            Instant now) {
        IdempotencyClaim claim = accountPersistence.lockIdempotency(
                        actorId,
                        EMPLOYEE_ACCOUNTS_BULK_CREATE,
                        idempotencyKey)
                .orElseThrow(AccountAccessService::accountProvisioningRecoveryUnavailable);
        requireMatchingProvisioningDigest(claim, requestDigest);
        requireProvisioningRecoveryWindow(claim.createdAt(), now);
        Map<String, String> expectedAccountIds = storedAccountBindings(
                claim.resultJson());
        if (!expectedAccountIds.keySet().equals(Set.copyOf(employeeIds))) {
            throw accountProvisioningRecoveryUnavailable();
        }
        Map<String, AccountRecord> lockedAccounts = lockProvisionedAccounts(
                expectedAccountIds);
        lockEmployeeProvisioningTargets(employeeIds);
        lockBulkProvisioningAuthority(actorId, now);
        List<EmployeeAccountCandidateRecord> candidates = requireProvisioningCandidates(
                actorId, employeeIds, now);
        return recoverEmployeeAccountCredentials(
                candidates,
                claim.recordId(),
                actorId,
                idempotencyKey,
                recoveryKey,
                employeeSetDigest,
                expectedAccountIds,
                lockedAccounts,
                now);
    }

    private BulkAccountCreationResult runProvisioningTransaction(
            Supplier<BulkAccountCreationResult> operation) {
        return Objects.requireNonNull(provisioningTransactions.execute(
                status -> operation.get()));
    }

    private void requireMatchingProvisioningDigest(
            IdempotencyClaim claim,
            String requestDigest) {
        if (!claim.requestDigest().equals(requestDigest)) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST",
                    "幂等键已用于不同请求");
        }
    }

    private List<EmployeeAccountCandidateRecord> requireProvisioningCandidates(
            String actorId,
            List<String> employeeIds,
            Instant now) {
        List<EmployeeAccountCandidateRecord> candidates =
                accountPersistence.findEmployeeAccountCandidates(
                                actorId, employeeIds, now)
                        .stream()
                        .sorted(Comparator.comparing(
                                EmployeeAccountCandidateRecord::employeeId))
                        .toList();
        if (candidates.size() != employeeIds.size()) {
            throw accountProvisioningCandidateChanged();
        }
        return candidates;
    }

    @Transactional(readOnly = true)
    public AccountDetail getAccount(String accountId) {
        String actorId = principalId();
        Instant now = clock.instant();
        return detail(
                requireVisible(accountId, ACCOUNT_READ, actorId, now),
                actorId,
                ACCOUNT_READ,
                now);
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
        String actorId = principalId();
        Instant now = clock.instant();
        AccountRecord account = requireLockedVisible(
                accountId, ACCOUNT_EDIT, actorId, now);
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
        return detail(
                authenticationPersistence.findAccountById(accountId).orElseThrow(),
                actorId,
                ACCOUNT_EDIT,
                now);
    }

    @Transactional
    public void lock(String accountId, boolean locked, String reason) {
        String actorId = principalId();
        Instant now = clock.instant();
        AccountRecord account = requireLockedVisible(
                accountId,
                locked ? ACCOUNT_LOCK : ACCOUNT_UNLOCK,
                actorId,
                now);
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
        String actorId = principalId();
        Instant now = clock.instant();
        AccountRecord account = requireLockedVisible(
                accountId,
                ACCOUNT_RESET_PASSWORD,
                actorId,
                now);
        String rawGrant = tokenService.newOpaqueToken();
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
    public void resetTemporaryPassword(
            String accountId,
            String suppliedTemporaryPassword,
            String reason) {
        String actorId = principalId();
        Instant now = clock.instant();
        AccountRecord account = requireLockedVisible(
                accountId,
                ACCOUNT_RESET_PASSWORD,
                actorId,
                now);
        boolean privileged = accountPersistence
                .findVisibleRoleAssignments(
                        actorId,
                        account.principalId(),
                        ACCOUNT_RESET_PASSWORD,
                        now)
                .stream()
                .map(RoleAssignmentRecord::roleCode)
                .anyMatch(PRIVILEGED_ROLE_CODES::contains);
        String temporaryPassword = resetTemporaryPassword(
                privileged,
                suppliedTemporaryPassword);
        authenticationPersistence.replaceCredential(
                account.accountId(),
                passwordCodec.encode(temporaryPassword),
                false,
                actorId,
                now);
        authenticationPersistence.invalidateUnusedResetGrants(
                account.accountId(),
                now);
        authenticationPersistence.revokeAllSessions(
                account.accountId(),
                actorId,
                "ADMIN_TEMPORARY_PASSWORD_RESET",
                auditService.currentCorrelationId(),
                now);
        auditService.record(
                actorId,
                "TEMPORARY_PASSWORD_RESET",
                "LOCAL_ACCOUNT",
                account.accountId(),
                "SUCCESS",
                reason);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AccountDetail replaceRoleAssignments(
            String accountId,
            List<RoleAssignmentInput> assignments,
            String reason,
            long expectedVersion) {
        validateAssignments(assignments);
        String actorId = principalId();
        Instant now = clock.instant();
        AccountRecord account = requireLockedLocalAccount(accountId);
        if (!accountPersistence.canAccessAllAccountRoleScopes(
                actorId,
                account.accountId(),
                ROLE_ASSIGN,
                now)) {
            throw unavailable();
        }
        denySelfRoleAssignment(actorId, account.principalId());
        lockRoleAssignmentAuthorization(
                actorId,
                account.principalId(),
                null,
                assignments,
                now);
        accountPersistence.lockTargetRoleAssignments(
                account.principalId(), now);
        if (!accountPersistence.canAccessAllAccountRoleScopes(
                actorId,
                account.accountId(),
                ROLE_ASSIGN,
                now)) {
            throw unavailable();
        }
        AuthorizedRoleAssignments authorization =
                resolveAuthorizedRoleAssignments(
                        actorId,
                        account.principalId(),
                        null,
                        assignments,
                        now);
        denyWeakDefaultPasswordPrivilegeEscalation(
                account,
                authorization.privileged());
        accountPersistence.replaceRoleAssignments(
                account.accountId(),
                expectedVersion,
                authorization.assignments(),
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
        return detail(
                authenticationPersistence.findAccountById(accountId).orElseThrow(),
                actorId,
                ROLE_ASSIGN,
                now);
    }

    @Transactional(readOnly = true)
    public List<RoleRecord> listRoles() {
        return accountPersistence.findRoles();
    }

    private AccountRecord requireVisible(
            String accountId,
            String requiredCapability,
            String actorId,
            Instant at) {
        if (!accountPersistence.canAccessAccount(
                actorId,
                accountId,
                requiredCapability,
                at)) {
            throw unavailable();
        }
        return authenticationPersistence.findAccountById(accountId)
                .orElseThrow(AccountAccessService::unavailable);
    }

    private AccountRecord requireLockedVisible(
            String accountId,
            String requiredCapability,
            String actorId,
            Instant at) {
        AccountRecord account = requireLockedAccount(accountId);
        if (!accountPersistence.lockCurrentCapabilityAuthority(
                actorId, requiredCapability, at)) {
            throw unavailable();
        }
        if (!accountPersistence.canAccessAllAccountRoleScopes(
                actorId,
                accountId,
                requiredCapability,
                at)) {
            throw unavailable();
        }
        return account;
    }

    private AccountRecord requireLockedAccount(String accountId) {
        return accountPersistence
                .lockAccountForScopeAuthorization(accountId)
                .orElseThrow(AccountAccessService::unavailable);
    }

    private AccountRecord requireLockedLocalAccount(String accountId) {
        return accountPersistence
                .lockProvisionedAccount(accountId)
                .orElseThrow(AccountAccessService::unavailable);
    }

    private AccountDetail detail(
            AccountRecord account,
            String actorId,
            String requiredCapability,
            Instant at) {
        List<RoleAssignmentRecord> roles =
                accountPersistence.findVisibleRoleAssignments(
                        actorId,
                        account.principalId(),
                        requiredCapability,
                        at);
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

    private static EmployeeAccountCandidate candidate(
            EmployeeAccountCandidateRecord candidate) {
        return new EmployeeAccountCandidate(
                candidate.employeeId(),
                candidate.companyId(),
                candidate.employeeNumber(),
                candidate.displayName(),
                candidate.organizationName(),
                candidate.status());
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
                    || !Set.of("COMPANY", "ORGANIZATION", "SELF")
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

    private AuthorizedRoleAssignments authorizeRoleAssignments(
            String actorPrincipalId,
            String targetPrincipalId,
            String targetEmployeeId,
            List<RoleAssignmentInput> assignments,
            Instant now) {
        denySelfRoleAssignment(actorPrincipalId, targetPrincipalId);
        lockRoleAssignmentAuthorization(
                actorPrincipalId,
                targetPrincipalId,
                targetEmployeeId,
                assignments,
                now);
        return resolveAuthorizedRoleAssignments(
                actorPrincipalId,
                targetPrincipalId,
                targetEmployeeId,
                assignments,
                now);
    }

    private void lockRoleAssignmentAuthorization(
            String actorPrincipalId,
            String targetPrincipalId,
            String targetEmployeeId,
            List<RoleAssignmentInput> assignments,
            Instant now) {
        accountPersistence.lockRoleGrantTargetCompanies(
                targetPrincipalId,
                targetEmployeeId,
                assignments,
                now);
        if (!accountPersistence.lockCurrentCapabilityAuthority(
                actorPrincipalId, ROLE_ASSIGN, now)) {
            throw roleAssignmentScopeDenied();
        }
    }

    private AuthorizedRoleAssignments resolveAuthorizedRoleAssignments(
            String actorPrincipalId,
            String targetPrincipalId,
            String targetEmployeeId,
            List<RoleAssignmentInput> assignments,
            Instant now) {
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
        return new AuthorizedRoleAssignments(
                accountPersistence.resolveAuthorizedRoleAssignmentScopes(
                        actorPrincipalId,
                        targetPrincipalId,
                        targetEmployeeId,
                        assignments,
                        now),
                roleCodes.values().stream()
                        .anyMatch(PRIVILEGED_ROLE_CODES::contains));
    }

    private void denyWeakDefaultPasswordPrivilegeEscalation(
            AccountRecord account,
            boolean privilegedAssignmentRequested) {
        if (!privilegedAssignmentRequested
                || !account.firstPasswordChangeRequired()) {
            return;
        }
        boolean stillUsesDefaultTemporaryPassword = authenticationPersistence
                .findCredential(account.accountId())
                .map(credential -> passwordCodec.matches(
                        LEGACY_DEFAULT_TEMPORARY_PASSWORD,
                        credential.passwordHash()))
                .orElse(true);
        if (stillUsesDefaultTemporaryPassword) {
            throw new ApiProblemException(
                    HttpStatus.CONFLICT,
                    "STRONG_TEMPORARY_PASSWORD_REQUIRED_FOR_PRIVILEGE_ELEVATION",
                    "该账号仍使用普通临时密码，请先由账号本人完成首次改密再授予高权限");
        }
    }

    private static void denySelfRoleAssignment(
            String actorPrincipalId,
            String targetPrincipalId) {
        if (actorPrincipalId.equals(targetPrincipalId)) {
            throw new ApiProblemException(
                    HttpStatus.FORBIDDEN,
                    "ROLE_ASSIGNMENT_SELF_SERVICE_DENIED",
                    "禁止修改本账号的角色授权");
        }
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

    private String creationTemporaryPassword(
            boolean privileged,
            String suppliedSecret) {
        return requireStrongTemporaryPassword(suppliedSecret);
    }

    private String resetTemporaryPassword(
            boolean privileged,
            String suppliedSecret) {
        return requireStrongTemporaryPassword(suppliedSecret);
    }

    private String requireStrongTemporaryPassword(String suppliedSecret) {
        if (!passwordCodec.meetsPolicy(suppliedSecret)) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "PASSWORD_POLICY_VIOLATION",
                    "临时密码必须为 12 至 256 位，并包含大小写字母、数字和符号");
        }
        return suppliedSecret;
    }

    private String employeeAccountSetDigest(List<String> employeeIds) {
        StringBuilder canonical = new StringBuilder(
                "EMPLOYEE_ACCOUNT_SET_V1")
                .append('\n')
                .append(employeeIds.size())
                .append('\n');
        employeeIds.stream().sorted().forEach(employeeId -> canonical
                .append(employeeId.length())
                .append(':')
                .append(employeeId)
                .append('\n'));
        return tokenService.digest(canonical.toString());
    }

    private String employeeAccountProvisioningDigest(
            String actorId,
            String idempotencyKey,
            String recoveryKey,
            String employeeSetDigest) {
        return tokenService.digest(lengthPrefixedCanonical(
                EMPLOYEE_ACCOUNTS_BULK_CREATE_DIGEST_VERSION,
                actorId,
                EMPLOYEE_ACCOUNTS_BULK_CREATE,
                idempotencyKey,
                employeeSetDigest,
                recoveryKey));
    }

    private String provisioningPassword(
            String idempotencyRecordId,
            String actorId,
            String idempotencyKey,
            String recoveryKey,
            String employeeSetDigest,
            String employeeId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(provisioningPepper, "HmacSHA256"));
            updateLengthPrefixed(
                    mac,
                    PROVISIONING_PASSWORD_DERIVATION_VERSION,
                    provisioningKeyId,
                    actorId,
                    EMPLOYEE_ACCOUNTS_BULK_CREATE,
                    idempotencyKey,
                    idempotencyRecordId,
                    employeeSetDigest,
                    employeeId,
                    recoveryKey);
            String material = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(mac.doFinal());
            return "Hr1!" + material.substring(0, 24);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "HmacSHA256 must be available for account provisioning",
                    exception);
        }
    }

    private void lockBulkProvisioningAuthority(String actorId, Instant now) {
        boolean canCreate = accountPersistence.lockCurrentCapabilityAuthority(
                actorId, ACCOUNT_CREATE, now);
        boolean canAssign = accountPersistence.lockCurrentCapabilityAuthority(
                actorId, ROLE_ASSIGN, now);
        if (!canCreate || !canAssign) {
            throw new ApiProblemException(
                    HttpStatus.FORBIDDEN,
                    "ACCOUNT_PROVISIONING_AUTHORITY_CHANGED",
                    "当前账号已无权批量开通员工账号，请重新登录后确认权限");
        }
    }

    private String accountBindingJson(List<TemporaryCredential> credentials) {
        List<ProvisionedAccountBinding> bindings = credentials.stream()
                .map(credential -> new ProvisionedAccountBinding(
                        credential.employeeId(), credential.accountId()))
                .sorted(Comparator.comparing(
                        ProvisionedAccountBinding::employeeId))
                .toList();
        try {
            return objectMapper.writeValueAsString(new ProvisioningBatchReceipt(
                    PROVISIONING_PASSWORD_DERIVATION_VERSION,
                    provisioningKeyId,
                    bindings));
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "account provisioning bindings must be JSON serializable",
                    exception);
        }
    }

    private Map<String, String> storedAccountBindings(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            throw accountProvisioningRecoveryUnavailable();
        }
        try {
            ProvisioningBatchReceipt receipt = objectMapper.readValue(
                    resultJson, ProvisioningBatchReceipt.class);
            if (receipt == null
                    || !PROVISIONING_PASSWORD_DERIVATION_VERSION.equals(
                            receipt.derivationVersion())
                    || !provisioningKeyId.equals(receipt.keyId())
                    || receipt.bindings() == null
                    || receipt.bindings().isEmpty()) {
                throw accountProvisioningRecoveryUnavailable();
            }
            Map<String, String> result = new HashMap<>();
            for (ProvisionedAccountBinding binding : receipt.bindings()) {
                if (binding == null
                        || binding.employeeId() == null
                        || binding.employeeId().isBlank()
                        || binding.accountId() == null
                        || binding.accountId().isBlank()
                        || result.putIfAbsent(
                                        binding.employeeId(), binding.accountId())
                                != null) {
                    throw accountProvisioningRecoveryUnavailable();
                }
            }
            return Map.copyOf(result);
        } catch (ApiProblemException exception) {
            throw exception;
        } catch (Exception exception) {
            throw accountProvisioningRecoveryUnavailable();
        }
    }

    private Map<String, AccountRecord> lockProvisionedAccounts(
            Map<String, String> expectedAccountIds) {
        List<String> accountIds = expectedAccountIds.values().stream()
                .distinct()
                .sorted()
                .toList();
        if (accountIds.size() != expectedAccountIds.size()) {
            throw accountProvisioningRecoveryUnavailable();
        }
        Map<String, AccountRecord> locked = new HashMap<>();
        for (String accountId : accountIds) {
            AccountRecord account = accountPersistence
                    .lockProvisionedAccount(accountId)
                    .orElseThrow(AccountAccessService::accountProvisioningRecoveryUnavailable);
            locked.put(accountId, account);
        }
        return Map.copyOf(locked);
    }

    private void lockEmployeeProvisioningTargets(List<String> employeeIds) {
        List<EmployeeProvisioningTarget> expected =
                accountPersistence.findEmployeeProvisioningTargets(employeeIds);
        if (expected.size() != employeeIds.size()) {
            throw accountProvisioningCandidateChanged();
        }
        accountPersistence.lockEmployeeProvisioningTargets(expected);
        List<EmployeeProvisioningTarget> current =
                accountPersistence.findEmployeeProvisioningTargets(employeeIds);
        if (!current.equals(expected)) {
            throw accountProvisioningCandidateChanged();
        }
    }

    private void requireProvisioningRecoveryWindow(
            Instant idempotencyCreatedAt,
            Instant now) {
        if (idempotencyCreatedAt == null
                || !now.isBefore(idempotencyCreatedAt.plus(
                        provisioningRecoveryWindow))) {
            throw accountProvisioningRecoveryUnavailable();
        }
    }

    private BulkAccountCreationResult recoverEmployeeAccountCredentials(
            List<EmployeeAccountCandidateRecord> candidates,
            String idempotencyRecordId,
            String actorId,
            String idempotencyKey,
            String recoveryKey,
            String employeeSetDigest,
            Map<String, String> expectedAccountIds,
            Map<String, AccountRecord> lockedAccounts,
            Instant now) {
        Map<String, String> currentAccountIds = new HashMap<>();
        for (EmployeeAccountCandidateRecord candidate : candidates) {
            if (candidate.accountId() != null) {
                currentAccountIds.put(
                        candidate.employeeId(), candidate.accountId());
            }
        }
        boolean invalidBinding = candidates.stream().anyMatch(candidate ->
                !"ALREADY_PROVISIONED".equals(candidate.status())
                        || candidate.accountId() == null
                        || candidate.accountId().isBlank());
        List<String> accountIds = candidates.stream()
                .map(EmployeeAccountCandidateRecord::accountId)
                .filter(accountId -> accountId != null && !accountId.isBlank())
                .distinct()
                .sorted()
                .toList();
        if (invalidBinding
                || accountIds.size() != candidates.size()
                || !currentAccountIds.equals(expectedAccountIds)) {
            throw accountProvisioningRecoveryUnavailable();
        }

        for (EmployeeAccountCandidateRecord employee : candidates) {
            String accountId = employee.accountId();
            AccountRecord account = lockedAccounts.get(accountId);
            List<RecoverableRoleAssignment> assignments = account == null
                    ? List.of()
                    : accountPersistence.lockRecoverableRoleAssignments(
                            account.principalId(), now);
            CredentialRecord credential = account == null
                    ? null
                    : authenticationPersistence.lockCredential(accountId)
                            .orElse(null);
            String expectedPassword = provisioningPassword(
                    idempotencyRecordId,
                    actorId,
                    idempotencyKey,
                    recoveryKey,
                    employeeSetDigest,
                    employee.employeeId());
            if (account == null
                    || !"ACTIVE".equals(account.status())
                    || !account.firstPasswordChangeRequired()
                    || account.lastLoginAt() != null
                    || !account.username().equals(employee.employeeNumber())
                    || !recoverableEmployeeSelfRole(assignments, now)
                    || credential == null
                    || !passwordCodec.matches(
                            expectedPassword, credential.passwordHash())) {
                throw accountProvisioningRecoveryUnavailable();
            }
        }

        List<TemporaryCredential> credentials = new ArrayList<>();
        for (EmployeeAccountCandidateRecord employee : candidates) {
            AccountRecord account = lockedAccounts.get(employee.accountId());
            String temporaryPassword = provisioningPassword(
                    idempotencyRecordId,
                    actorId,
                    idempotencyKey,
                    recoveryKey,
                    employeeSetDigest,
                    employee.employeeId());
            credentials.add(new TemporaryCredential(
                    account.accountId(),
                    employee.employeeId(),
                    employee.employeeNumber(),
                    employee.displayName(),
                    employee.organizationName(),
                    account.username(),
                    temporaryPassword));
        }
        auditService.record(
                actorId,
                "EMPLOYEE_ACCOUNTS_BULK_REPLAY_CONFIRMED",
                "LOCAL_ACCOUNT_BATCH",
                idempotencyRecordId,
                "SUCCESS",
                "confirmed=" + credentials.size());
        return new BulkAccountCreationResult(
                List.copyOf(credentials),
                credentials.size(),
                true);
    }

    private static boolean recoverableEmployeeSelfRole(
            List<RecoverableRoleAssignment> assignments,
            Instant now) {
        if (assignments.size() != 1) {
            return false;
        }
        RecoverableRoleAssignment assignment = assignments.getFirst();
        return EMPLOYEE_SELF_ROLE.equals(assignment.roleCode())
                && "SELF".equals(assignment.scopeType())
                && assignment.companyId() == null
                && assignment.organizationId() == null
                && !assignment.validFrom().isAfter(now)
                && assignment.validTo() == null
                && assignment.scopeValidFrom() != null
                && !assignment.scopeValidFrom().isAfter(now)
                && assignment.scopeValidTo() == null;
    }

    private static boolean validProvisioningRecoveryKey(String recoveryKey) {
        if (recoveryKey == null
                || !PROVISIONING_RECOVERY_KEY_PATTERN.matcher(recoveryKey).matches()) {
            return false;
        }
        try {
            return Base64.getUrlDecoder().decode(recoveryKey).length == 32;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static byte[] decodeProvisioningPepper(String encodedPepper) {
        if (!validProvisioningRecoveryKey(encodedPepper)) {
            throw new IllegalStateException(
                    "SHENZHOUHR_PROVISIONING_PEPPER must be 32 random bytes encoded as base64url");
        }
        return Base64.getUrlDecoder().decode(encodedPepper);
    }

    private static String lengthPrefixedCanonical(String... values) {
        StringBuilder canonical = new StringBuilder();
        for (String value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            canonical.append(bytes.length).append(':').append(value).append('\n');
        }
        return canonical.toString();
    }

    private static void updateLengthPrefixed(Mac mac, String... values) {
        for (String value : values) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            mac.update((byte) (bytes.length >>> 24));
            mac.update((byte) (bytes.length >>> 16));
            mac.update((byte) (bytes.length >>> 8));
            mac.update((byte) bytes.length);
            mac.update(bytes);
        }
    }

    private static ApiProblemException accountProvisioningRecoveryUnavailable() {
        return new ApiProblemException(
                HttpStatus.CONFLICT,
                "ACCOUNT_PROVISIONING_RECOVERY_UNAVAILABLE",
                "此前开通的账号已被使用或状态已变化，不能恢复临时凭据，请按密码重置流程处理");
    }

    private static ApiProblemException accountProvisioningCandidateChanged() {
        return new ApiProblemException(
                HttpStatus.CONFLICT,
                "ACCOUNT_PROVISIONING_CANDIDATE_CHANGED",
                "部分员工已离职、没有有效任职或不在当前授权范围，请刷新后重试");
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

    public record EmployeeAccountCandidate(
            String employeeId,
            String companyId,
            String employeeNumber,
            String displayName,
            String organizationName,
            String status) {
    }

    public record EmployeeAccountCandidatePage(
            List<EmployeeAccountCandidate> items,
            long total,
            long available,
            long alreadyProvisioned,
            long usernameConflicts,
            int page,
            int size) {
    }

    public record TemporaryCredential(
            String accountId,
            String employeeId,
            String employeeNumber,
            String displayName,
            String organizationName,
            String username,
            String temporaryPassword) {
    }

    public record BulkAccountCreationResult(
            List<TemporaryCredential> credentials,
            int created,
            boolean replayed) {
    }

    private record AuthorizedRoleAssignments(
            List<ResolvedRoleAssignmentInput> assignments,
            boolean privileged) {
    }

    private record ProvisionedAccountBinding(
            String employeeId,
            String accountId) {
    }

    private record ProvisioningBatchReceipt(
            String derivationVersion,
            String keyId,
            List<ProvisionedAccountBinding> bindings) {
    }

    private static final class ProvisioningReplayRaceException
            extends RuntimeException {
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
            Instant createdAt,
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
