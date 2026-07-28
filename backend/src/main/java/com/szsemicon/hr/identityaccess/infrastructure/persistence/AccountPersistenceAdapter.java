package com.szsemicon.hr.identityaccess.infrastructure.persistence;

import com.szsemicon.hr.identityaccess.application.AccountPersistence;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.AccountRecord;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.RoleAssignmentInput;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.RoleAssignmentRecord;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.RoleRecord;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.SessionRecord;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountPersistenceAdapter implements AccountPersistence {

    private static final String ACCOUNT_SELECT = """
            SELECT account.account_id, account.principal_id, account.username,
                   account.normalized_username, account.display_name, account.status,
                   account.first_password_change_required, account.locked_until,
                   account.last_login_at, account.session_epoch, account.row_version,
                   employee.legal_entity_id
            FROM local_account account
            JOIN auth_principal principal ON principal.principal_id = account.principal_id
            LEFT JOIN employee ON employee.employee_id = principal.employee_id
            """;

    private final JdbcTemplate jdbc;

    public AccountPersistenceAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean canAccessAccount(String principalId, String accountId, Instant at) {
        Long count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM local_account target_account
                JOIN auth_principal target_principal
                  ON target_principal.principal_id = target_account.principal_id
                LEFT JOIN employee target_employee
                  ON target_employee.employee_id = target_principal.employee_id
                WHERE target_account.account_id = ?
                  AND EXISTS (
                    SELECT 1
                    FROM auth_principal_role_assignment assignment
                    LEFT JOIN auth_data_scope scope
                      ON scope.scope_id = assignment.data_scope_id
                    LEFT JOIN employment_assignment target_assignment
                      ON target_assignment.employee_id = target_employee.employee_id
                     AND target_assignment.effective_from <= ?
                     AND (target_assignment.effective_to IS NULL OR target_assignment.effective_to > ?)
                    LEFT JOIN organization_current_closure closure
                      ON closure.ancestor_organization_id = scope.organization_id
                     AND closure.descendant_organization_id = target_assignment.organization_id
                    WHERE assignment.principal_id = ?
                      AND assignment.valid_from <= ?
                      AND (assignment.valid_to IS NULL OR assignment.valid_to > ?)
                      AND (
                        scope.scope_type = 'SELF' AND target_account.principal_id = ?
                        OR scope.scope_type = 'LEGAL_ENTITY'
                           AND (
                             scope.legal_entity_id = target_employee.legal_entity_id
                             OR EXISTS (
                               SELECT 1
                               FROM auth_principal_role_assignment target_role
                               JOIN auth_data_scope target_scope
                                 ON target_scope.scope_id = target_role.data_scope_id
                               WHERE target_role.principal_id = target_account.principal_id
                                 AND target_scope.scope_type = 'LEGAL_ENTITY'
                                 AND target_scope.legal_entity_id = scope.legal_entity_id
                             )
                           )
                        OR scope.scope_type = 'ORGANIZATION'
                           AND (
                             scope.organization_id = target_assignment.organization_id
                             OR scope.include_descendants = TRUE
                                AND closure.descendant_organization_id IS NOT NULL
                           )
                      )
                  )
                """,
                Long.class,
                accountId,
                Timestamp.from(at),
                Timestamp.from(at),
                principalId,
                Timestamp.from(at),
                Timestamp.from(at),
                principalId);
        return count != null && count > 0;
    }

    @Override
    public String createAccount(
            String username,
            String normalizedUsername,
            String displayName,
            String passwordHash,
            String actorId,
            Instant at) {
        String principalId = UUID.randomUUID().toString();
        String accountId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO auth_principal (principal_id, status, created_at, row_version)
                VALUES (?, 'ACTIVE', ?, 0)
                """,
                principalId,
                Timestamp.from(at));
        jdbc.update(
                """
                INSERT INTO local_account (
                    account_id, principal_id, username, normalized_username, display_name,
                    status, first_password_change_required, session_epoch, row_version,
                    created_by, created_at, updated_by, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'ACTIVE', TRUE, 0, 0, ?, ?, ?, ?)
                """,
                accountId,
                principalId,
                username,
                normalizedUsername,
                displayName,
                actorId,
                Timestamp.from(at),
                actorId,
                Timestamp.from(at));
        jdbc.update(
                """
                INSERT INTO password_credential (
                    credential_id, account_id, password_hash, algorithm,
                    parameter_version, changed_at, row_version
                ) VALUES (?, ?, ?, 'BCRYPT', '2B_COST_12', ?, 0)
                """,
                UUID.randomUUID().toString(),
                accountId,
                passwordHash,
                Timestamp.from(at));
        jdbc.update(
                """
                INSERT INTO login_failure_window (account_id, failure_count, row_version)
                VALUES (?, 0, 0)
                """,
                accountId);
        return accountId;
    }

    @Override
    public List<AccountRecord> listAccounts(
            String principalId,
            String query,
            String status,
            int limit,
            int offset,
            Instant at) {
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase();
        String normalizedStatus = status == null ? "" : status.trim().toUpperCase();
        List<AccountRecord> all = jdbc.query(
                ACCOUNT_SELECT
                        + """
                         WHERE (? = '' OR LOWER(account.username) LIKE ? OR LOWER(account.display_name) LIKE ?)
                           AND (? = '' OR account.status = ?)
                         ORDER BY account.normalized_username
                         """,
                AccountPersistenceAdapter::mapAccount,
                normalizedQuery,
                "%" + normalizedQuery + "%",
                "%" + normalizedQuery + "%",
                normalizedStatus,
                normalizedStatus);
        return all.stream()
                .filter(account -> canAccessAccount(principalId, account.accountId(), at))
                .skip(offset)
                .limit(limit)
                .toList();
    }

    @Override
    public long countAccounts(String principalId, String query, String status, Instant at) {
        return listAccounts(principalId, query, status, Integer.MAX_VALUE, 0, at).size();
    }

    @Override
    public void updateAccountStatus(
            String accountId,
            String status,
            long expectedVersion,
            String actorId,
            Instant at) {
        int updated = jdbc.update(
                """
                UPDATE local_account
                SET status = ?, locked_until = NULL, session_epoch = session_epoch + 1,
                    updated_by = ?, updated_at = ?, row_version = row_version + 1
                WHERE account_id = ? AND row_version = ?
                """,
                status,
                actorId,
                Timestamp.from(at),
                accountId,
                expectedVersion);
        if (updated != 1) {
            throw new OptimisticLockingFailureException("account version mismatch");
        }
    }

    @Override
    public void setAccountLock(String accountId, boolean locked, String actorId, Instant at) {
        jdbc.update(
                """
                UPDATE local_account
                SET status = ?, locked_until = ?, session_epoch = session_epoch + 1,
                    updated_by = ?, updated_at = ?, row_version = row_version + 1
                WHERE account_id = ?
                """,
                locked ? "LOCKED" : "ACTIVE",
                locked ? Timestamp.from(at.plusSeconds(30 * 60)) : null,
                actorId,
                Timestamp.from(at),
                accountId);
        if (!locked) {
            jdbc.update(
                    """
                    UPDATE login_failure_window
                    SET failure_count = 0, window_started_at = NULL, last_failed_at = NULL,
                        locked_until = NULL, row_version = row_version + 1
                    WHERE account_id = ?
                    """,
                    accountId);
        }
    }

    @Override
    public void replaceRoleAssignments(
            String accountId,
            long expectedAccountVersion,
            List<RoleAssignmentInput> assignments,
            String actorId,
            String reason,
            Instant at) {
        AccountRecord account = findAccount(accountId);
        if (account.rowVersion() != expectedAccountVersion) {
            throw new OptimisticLockingFailureException("account version mismatch");
        }
        jdbc.update(
                """
                UPDATE auth_principal_role_assignment
                SET valid_to = CASE
                      WHEN valid_from < ? THEN ?
                      ELSE TIMESTAMPADD(MICROSECOND, 1, valid_from)
                    END,
                    row_version = row_version + 1
                WHERE principal_id = ?
                  AND (valid_to IS NULL OR valid_to > ?)
                """,
                Timestamp.from(at),
                Timestamp.from(at),
                account.principalId(),
                Timestamp.from(at));
        for (RoleAssignmentInput assignment : assignments) {
            jdbc.update(
                    """
                    INSERT INTO auth_principal_role_assignment (
                        assignment_id, principal_id, role_id, data_scope_id,
                        valid_from, valid_to, assigned_by, reason, row_version
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)
                    """,
                    UUID.randomUUID().toString(),
                    account.principalId(),
                    assignment.roleId(),
                    resolveScope(assignment, at),
                    Timestamp.from(assignment.validFrom()),
                    timestamp(assignment.validTo()),
                    actorId,
                    reason);
        }
        int updated = jdbc.update(
                """
                UPDATE local_account
                SET row_version = row_version + 1, updated_by = ?, updated_at = ?
                WHERE account_id = ? AND row_version = ?
                """,
                actorId,
                Timestamp.from(at),
                accountId,
                expectedAccountVersion);
        if (updated != 1) {
            throw new OptimisticLockingFailureException("account version mismatch");
        }
    }

    @Override
    public List<RoleAssignmentRecord> findRoleAssignments(String principalId, Instant at) {
        return jdbc.query(
                """
                SELECT assignment.assignment_id, role.role_id, role.role_code, role.role_name,
                       scope.scope_type,
                       CASE
                         WHEN scope.scope_type = 'LEGAL_ENTITY' THEN scope.legal_entity_id
                         WHEN scope.scope_type = 'ORGANIZATION' THEN scope.organization_id
                         ELSE NULL
                       END AS scope_resource_id,
                       assignment.valid_from, assignment.valid_to
                FROM auth_principal_role_assignment assignment
                JOIN auth_role role ON role.role_id = assignment.role_id
                LEFT JOIN auth_data_scope scope ON scope.scope_id = assignment.data_scope_id
                WHERE assignment.principal_id = ?
                  AND (assignment.valid_to IS NULL OR assignment.valid_to > ?)
                ORDER BY role.role_code
                """,
                (result, row) -> new RoleAssignmentRecord(
                        result.getString("assignment_id"),
                        result.getString("role_id"),
                        result.getString("role_code"),
                        result.getString("role_name"),
                        result.getString("scope_type"),
                        result.getString("scope_resource_id"),
                        instant(result, "valid_from"),
                        instant(result, "valid_to")),
                principalId,
                Timestamp.from(at));
    }

    @Override
    public List<SessionRecord> findSessions(String accountId) {
        return jdbc.query(
                """
                SELECT session_id, account_id, token_digest, status, created_at,
                       last_seen_at, idle_expires_at, absolute_expires_at,
                       request_id, row_version
                FROM user_session WHERE account_id = ? ORDER BY created_at DESC
                """,
                (result, row) -> new SessionRecord(
                        result.getString("session_id"),
                        result.getString("account_id"),
                        result.getString("token_digest"),
                        result.getString("status"),
                        instant(result, "created_at"),
                        instant(result, "last_seen_at"),
                        instant(result, "idle_expires_at"),
                        instant(result, "absolute_expires_at"),
                        result.getString("request_id"),
                        result.getLong("row_version")),
                accountId);
    }

    @Override
    public List<RoleRecord> findRoles() {
        List<RoleRecord> roles = new ArrayList<>();
        jdbc.query(
                """
                SELECT role.role_id, role.role_code, role.role_name,
                       capability.capability_code
                FROM auth_role role
                LEFT JOIN auth_role_capability role_capability ON role_capability.role_id = role.role_id
                LEFT JOIN auth_capability capability ON capability.capability_id = role_capability.capability_id
                WHERE capability.capability_code IS NULL
                   OR capability.capability_code NOT LIKE 'PAYROLL:%'
                ORDER BY role.role_code, capability.capability_code
                """,
                result -> {
                    String currentId = null;
                    String code = null;
                    String name = null;
                    List<String> capabilities = new ArrayList<>();
                    while (result.next()) {
                        String roleId = result.getString("role_id");
                        if (currentId != null && !currentId.equals(roleId)) {
                            roles.add(new RoleRecord(
                                    currentId,
                                    code,
                                    name,
                                    List.copyOf(capabilities)));
                            capabilities.clear();
                        }
                        currentId = roleId;
                        code = result.getString("role_code");
                        name = result.getString("role_name");
                        String capability = result.getString("capability_code");
                        if (capability != null) {
                            capabilities.add(capability);
                        }
                    }
                    if (currentId != null) {
                        roles.add(new RoleRecord(
                                currentId,
                                code,
                                name,
                                List.copyOf(capabilities)));
                    }
                    return null;
                });
        return List.copyOf(roles);
    }

    private AccountRecord findAccount(String accountId) {
        try {
            return jdbc.queryForObject(
                    ACCOUNT_SELECT + " WHERE account.account_id = ?",
                    AccountPersistenceAdapter::mapAccount,
                    accountId);
        } catch (EmptyResultDataAccessException exception) {
            throw new IllegalArgumentException("account unavailable");
        }
    }

    private String resolveScope(RoleAssignmentInput assignment, Instant at) {
        String targetColumn = switch (assignment.scopeType()) {
            case "LEGAL_ENTITY" -> "legal_entity_id";
            case "ORGANIZATION" -> "organization_id";
            case "SELF" -> null;
            default -> throw new IllegalArgumentException("unsupported scope type");
        };
        List<String> existing;
        if (targetColumn == null) {
            existing = jdbc.queryForList(
                    "SELECT scope_id FROM auth_data_scope WHERE scope_type = 'SELF' LIMIT 1",
                    String.class);
        } else {
            existing = jdbc.queryForList(
                    "SELECT scope_id FROM auth_data_scope WHERE scope_type = ? AND "
                            + targetColumn + " = ? LIMIT 1",
                    String.class,
                    assignment.scopeType(),
                    assignment.scopeResourceId());
        }
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }

        String scopeId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO auth_data_scope (
                    scope_id, scope_type, legal_entity_id, organization_id,
                    include_descendants, valid_from, valid_to
                ) VALUES (?, ?, ?, ?, TRUE, ?, NULL)
                """,
                scopeId,
                assignment.scopeType(),
                "LEGAL_ENTITY".equals(assignment.scopeType())
                        ? assignment.scopeResourceId()
                        : null,
                "ORGANIZATION".equals(assignment.scopeType())
                        ? assignment.scopeResourceId()
                        : null,
                Timestamp.from(at));
        return scopeId;
    }

    private static AccountRecord mapAccount(ResultSet result, int row) throws SQLException {
        return new AccountRecord(
                result.getString("account_id"),
                result.getString("principal_id"),
                result.getString("username"),
                result.getString("normalized_username"),
                result.getString("display_name"),
                result.getString("status"),
                result.getBoolean("first_password_change_required"),
                instant(result, "locked_until"),
                instant(result, "last_login_at"),
                result.getLong("session_epoch"),
                result.getLong("row_version"),
                result.getString("legal_entity_id"));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
