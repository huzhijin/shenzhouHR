package com.szsemicon.hr.identityaccess.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AccountListSqlAuthorizationContractTest {

    private static final Path ADAPTER = Path.of(
            "src/main/java/com/szsemicon/hr/identityaccess/"
                    + "infrastructure/persistence/"
                    + "AccountPersistenceAdapter.java");
    private static final Path SERVICE = Path.of(
            "src/main/java/com/szsemicon/hr/identityaccess/"
                    + "application/AccountAccessService.java");

    @Test
    void listAndCountRequireFullRoleScopeCoverageBeforeObservation()
            throws Exception {
        String source = Files.readString(ADAPTER);
        String coverage = between(
                source,
                "private static final String TARGET_ROLE_SCOPE_COVERAGE_PREDICATE",
                "private static final String ACCOUNT_FULL_VISIBILITY_PREDICATE");
        String fullVisibility = between(
                source,
                "private static final String ACCOUNT_FULL_VISIBILITY_PREDICATE",
                "private final JdbcTemplate jdbc;");
        String list = between(
                source,
                "public List<AccountRecord> listAccounts(",
                "public long countAccounts(");
        String count = between(
                source,
                "public long countAccounts(",
                "public void updateAccountStatus(");

        assertThat(coverage).contains(
                "scope_capability.capability_code =",
                ":requiredCapability",
                "scope_actor.principal_id = :principalId",
                "scope_actor.status = 'ACTIVE'",
                "actor_scope.scope_type = 'COMPANY'",
                "actor_scope.scope_type = 'ORGANIZATION'",
                "actor_scope.scope_type = 'SELF'");
        assertThat(fullVisibility).contains(
                "ACCOUNT_VISIBILITY_PREDICATE",
                "ACCOUNT_EMPLOYEE_BOUNDARY_PREDICATE",
                "employee.employee_id IS NULL",
                "NOT EXISTS",
                "target_assignment.valid_to",
                "TARGET_ROLE_SCOPE_COVERAGE_PREDICATE");
        assertThat(list)
                .contains("ACCOUNT_FULL_VISIBILITY_PREDICATE")
                .contains("ORDER BY account.normalized_username")
                .contains("LIMIT :limit OFFSET :offset")
                .doesNotContain(
                        ".stream()",
                        "canAccessAccount(",
                        "Integer.MAX_VALUE");
        assertThat(count)
                .contains("SELECT COUNT(*)")
                .contains("ACCOUNT_FULL_VISIBILITY_PREDICATE")
                .doesNotContain(
                        "listAccounts(",
                        "Integer.MAX_VALUE");
    }

    @Test
    void accountWideMutationsLockTheTargetBeforeFullCoverageRecheck()
            throws Exception {
        String adapter = Files.readString(ADAPTER);
        String service = Files.readString(SERVICE);
        String lock = between(
                adapter,
                "public Optional<AccountRecord> lockAccountForScopeAuthorization(",
                "public String createAccount(");
        String assignmentLock = between(
                adapter,
                "public void lockTargetRoleAssignments(",
                "public boolean lockCurrentCapabilityAuthority(");
        String lockedGuard = between(
                service,
                "private AccountRecord requireLockedVisible(",
                "private AccountRecord requireLockedAccount(");
        String replacement = between(
                service,
                "public AccountDetail replaceRoleAssignments(",
                "public List<RoleRecord> listRoles()");

        assertThat(lock).contains(
                "WHERE account.account_id = ?",
                "FOR UPDATE");
        assertThat(assignmentLock).contains(
                "targetPrincipalId",
                "valid_to IS NULL OR valid_to > ?",
                "ORDER BY assignment_id",
                "FOR UPDATE");
        assertThat(lockedGuard).containsSubsequence(
                "requireLockedAccount(accountId)",
                "lockCurrentCapabilityAuthority(",
                "canAccessAllAccountRoleScopes(");
        assertThat(replacement).containsSubsequence(
                "requireLockedLocalAccount(accountId)",
                "canAccessAllAccountRoleScopes(",
                "lockRoleAssignmentAuthorization(",
                "lockTargetRoleAssignments(",
                "canAccessAllAccountRoleScopes(",
                "replaceRoleAssignments(");
        assertThat(replacement)
                .doesNotContain("requireLockedAccount(accountId)");
        assertThat(service).contains(
                "@Transactional(isolation = Isolation.READ_COMMITTED)\n"
                        + "    public AccountDetail replaceRoleAssignments(");
        assertThat(service).contains(
                "AccountRecord account = requireLockedVisible(\n"
                        + "                accountId, ACCOUNT_EDIT",
                "AccountRecord account = requireLockedVisible(\n"
                        + "                accountId,\n"
                        + "                locked ? ACCOUNT_LOCK : ACCOUNT_UNLOCK",
                "AccountRecord account = requireLockedVisible(\n"
                        + "                accountId,\n"
                        + "                ACCOUNT_RESET_PASSWORD");
    }

    private static String between(
            String value, String start, String end) {
        int from = value.indexOf(start);
        int to = value.indexOf(end, from + start.length());
        assertThat(from).isGreaterThanOrEqualTo(0);
        assertThat(to).isGreaterThan(from);
        return value.substring(from, to);
    }
}
