package com.szsemicon.hr.identityaccess.infrastructure.persistence;

import com.szsemicon.hr.identityaccess.application.AccountPersistence;
import com.szsemicon.hr.identityaccess.application.AccountPersistence.CandidateCounts;
import com.szsemicon.hr.identityaccess.application.AccountPersistence.EmployeeAccountCandidateRecord;
import com.szsemicon.hr.identityaccess.application.EmployeeAccountConflictException;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.AccountRecord;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.ResolvedRoleAssignmentInput;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.RoleAssignmentInput;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.RoleAssignmentRecord;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.RoleRecord;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.SessionRecord;
import com.szsemicon.hr.shared.security.ResourceNotAvailableAccessDeniedException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountPersistenceAdapter implements AccountPersistence {

    private static final String ACCOUNT_FROM = """
            FROM local_account account
            JOIN auth_principal principal
              ON principal.principal_id = account.principal_id
            LEFT JOIN employee
              ON employee.employee_id = principal.employee_id
            """;

    private static final String ACCOUNT_SELECT = """
            SELECT account.account_id, account.principal_id, account.username,
                   account.normalized_username, account.display_name, account.status,
                   account.first_password_change_required, account.locked_until,
                   account.last_login_at, account.session_epoch, account.row_version,
                   employee.company_id
            """ + ACCOUNT_FROM;

    private static final String EMPLOYEE_ACCOUNT_CANDIDATE_FROM = """
            FROM employee employee
            JOIN company company
              ON company.company_id = employee.company_id
             AND company.status = 'ACTIVE'
            JOIN employee_current_projection employee_projection
              ON employee_projection.employee_id = employee.employee_id
            JOIN employee_version employee_version
              ON employee_version.employee_version_id =
                 employee_projection.current_version_id
             AND employee_version.employee_id = employee.employee_id
            LEFT JOIN employment_assignment current_assignment
              ON current_assignment.assignment_id = (
                SELECT assignment.assignment_id
                FROM employment_assignment assignment
                WHERE assignment.employee_id = employee.employee_id
                  AND assignment.record_status = 'ACTIVE'
                  AND assignment.version_valid_to IS NULL
                  AND assignment.effective_from <= :authorizationTime
                  AND (
                    assignment.effective_to IS NULL
                    OR assignment.effective_to > :authorizationTime
                  )
                ORDER BY assignment.effective_from DESC,
                         assignment.assignment_id
                LIMIT 1
              )
            LEFT JOIN organization_current_projection organization_projection
              ON organization_projection.organization_id =
                 current_assignment.organization_id
            LEFT JOIN organization_version organization_version
              ON organization_version.organization_version_id =
                 organization_projection.current_version_id
             AND organization_version.organization_id =
                 current_assignment.organization_id
            LEFT JOIN auth_principal bound_principal
              ON bound_principal.employee_id = employee.employee_id
            LEFT JOIN local_account username_account
              ON username_account.normalized_username =
                 LOWER(TRIM(employee_version.employee_number))
            """;

    private static final String EMPLOYEE_ACCOUNT_CANDIDATE_STATUS = """
            CASE
              WHEN bound_principal.principal_id IS NOT NULL
                THEN 'ALREADY_PROVISIONED'
              WHEN username_account.account_id IS NOT NULL
                THEN 'USERNAME_CONFLICT'
              ELSE 'AVAILABLE'
            END
            """;

    private static final String EMPLOYEE_ACCOUNT_CANDIDATE_AUTHORIZATION = """
            (
              SELECT COUNT(DISTINCT capability.capability_code)
              FROM auth_principal actor
              JOIN auth_principal_role_assignment assignment
                ON assignment.principal_id = actor.principal_id
              JOIN auth_role_capability role_capability
                ON role_capability.role_id = assignment.role_id
              JOIN auth_capability capability
                ON capability.capability_id = role_capability.capability_id
               AND capability.capability_code IN ('ACCOUNT:CREATE', 'ROLE:ASSIGN')
              JOIN auth_data_scope scope
                ON scope.scope_id = assignment.data_scope_id
              WHERE actor.principal_id = :principalId
                AND actor.status = 'ACTIVE'
                AND assignment.valid_from <= :authorizationTime
                AND (
                  assignment.valid_to IS NULL
                  OR assignment.valid_to > :authorizationTime
                )
                AND scope.valid_from <= :authorizationTime
                AND (
                  scope.valid_to IS NULL
                  OR scope.valid_to > :authorizationTime
                )
                AND (
                  (
                    scope.scope_type = 'COMPANY'
                    AND scope.company_id = employee.company_id
                  )
                  OR (
                    scope.scope_type = 'ORGANIZATION'
                    AND current_assignment.organization_id IS NOT NULL
                    AND EXISTS (
                      SELECT 1
                      FROM organization_identity scoped_organization
                      WHERE scoped_organization.organization_id =
                            scope.organization_id
                        AND scoped_organization.identity_status = 'ACTIVE'
                        AND scoped_organization.company_id = employee.company_id
                    )
                    AND (
                      scope.organization_id =
                          current_assignment.organization_id
                      OR (
                        scope.include_descendants = TRUE
                        AND EXISTS (
                          SELECT 1
                          FROM organization_current_closure closure
                          WHERE closure.ancestor_organization_id =
                                scope.organization_id
                            AND closure.descendant_organization_id =
                                current_assignment.organization_id
                        )
                      )
                    )
                  )
                )
            ) = 2
            """;

    private static final String EMPLOYEE_ACCOUNT_CANDIDATE_BASE_FILTER = """
            employee.company_id = :companyId
              AND employee.employment_status = 'ACTIVE'
              AND employee_version.status = 'ACTIVE'
              AND current_assignment.assignment_id IS NOT NULL
              AND (
                :normalizedQuery = ''
                OR LOWER(employee_version.employee_number) LIKE :queryPattern
                OR LOWER(employee_version.display_name) LIKE :queryPattern
                OR LOWER(COALESCE(organization_version.name, '')) LIKE :queryPattern
              )
              AND
            """ + EMPLOYEE_ACCOUNT_CANDIDATE_AUTHORIZATION;

    private static final String ACCOUNT_VISIBILITY_PREDICATE = """
            EXISTS (
              SELECT 1
              FROM auth_principal actor
              JOIN auth_principal_role_assignment assignment
                ON assignment.principal_id = actor.principal_id
              JOIN auth_role_capability role_capability
                ON role_capability.role_id = assignment.role_id
              JOIN auth_capability capability
                ON capability.capability_id =
                   role_capability.capability_id
               AND capability.capability_code = :requiredCapability
              JOIN auth_data_scope scope
                ON scope.scope_id = assignment.data_scope_id
              WHERE actor.principal_id = :principalId
                AND actor.status = 'ACTIVE'
                AND assignment.valid_from <= :authorizationTime
                AND (
                  assignment.valid_to IS NULL
                  OR assignment.valid_to > :authorizationTime
                )
                AND scope.valid_from <= :authorizationTime
                AND (
                  scope.valid_to IS NULL
                  OR scope.valid_to > :authorizationTime
                )
                AND (
                  (
                    scope.scope_type = 'SELF'
                    AND account.principal_id = :principalId
                  )
                  OR (
                    scope.scope_type = 'COMPANY'
                    AND EXISTS (
                      SELECT 1
                      FROM company scoped_company
                      WHERE scoped_company.company_id = scope.company_id
                        AND scoped_company.status = 'ACTIVE'
                    )
                    AND (
                      scope.company_id = employee.company_id
                      OR EXISTS (
                        SELECT 1
                        FROM auth_principal_role_assignment target_role
                        JOIN auth_data_scope target_scope
                          ON target_scope.scope_id =
                             target_role.data_scope_id
                        WHERE target_role.principal_id =
                              account.principal_id
                          AND target_role.valid_from <=
                              :authorizationTime
                          AND (
                            target_role.valid_to IS NULL
                            OR target_role.valid_to >
                               :authorizationTime
                          )
                          AND target_scope.valid_from <=
                              :authorizationTime
                          AND (
                            target_scope.valid_to IS NULL
                            OR target_scope.valid_to >
                               :authorizationTime
                          )
                          AND target_scope.scope_type = 'COMPANY'
                          AND target_scope.company_id =
                              scope.company_id
                      )
                    )
                  )
                  OR (
                    scope.scope_type = 'ORGANIZATION'
                    AND EXISTS (
                      SELECT 1
                      FROM organization_identity scoped_organization
                      JOIN company scoped_company
                        ON scoped_company.company_id =
                           scoped_organization.company_id
                       AND scoped_company.status = 'ACTIVE'
                      JOIN organization_current_projection
                          scoped_projection
                        ON scoped_projection.organization_id =
                           scoped_organization.organization_id
                      JOIN organization_version scoped_version
                        ON scoped_version.organization_version_id =
                           scoped_projection.current_version_id
                       AND scoped_version.organization_id =
                           scoped_organization.organization_id
                       AND scoped_version.status = 'ACTIVE'
                      JOIN employment_assignment target_assignment
                        ON target_assignment.employee_id =
                           employee.employee_id
                       AND target_assignment.record_status = 'ACTIVE'
                       AND target_assignment.version_valid_to IS NULL
                      JOIN organization_identity target_organization
                        ON target_organization.organization_id =
                           target_assignment.organization_id
                       AND target_organization.identity_status = 'ACTIVE'
                       AND target_organization.company_id =
                           scoped_organization.company_id
                       AND target_organization.company_id =
                           employee.company_id
                      JOIN organization_current_projection
                          target_projection
                        ON target_projection.organization_id =
                           target_organization.organization_id
                      JOIN organization_version target_version
                        ON target_version.organization_version_id =
                           target_projection.current_version_id
                       AND target_version.organization_id =
                           target_organization.organization_id
                       AND target_version.status = 'ACTIVE'
                      WHERE scoped_organization.organization_id =
                            scope.organization_id
                        AND scoped_organization.identity_status = 'ACTIVE'
                        AND scoped_version.effective_from <=
                            :authorizationTime
                        AND (
                          scoped_version.effective_to IS NULL
                          OR scoped_version.effective_to >
                             :authorizationTime
                        )
                        AND target_assignment.effective_from <=
                            :authorizationTime
                        AND (
                          target_assignment.effective_to IS NULL
                          OR target_assignment.effective_to >
                             :authorizationTime
                        )
                        AND target_version.effective_from <=
                            :authorizationTime
                        AND (
                          target_version.effective_to IS NULL
                          OR target_version.effective_to >
                             :authorizationTime
                        )
                        AND (
                          (
                            scope.include_descendants = FALSE
                            AND scope.organization_id =
                                target_assignment.organization_id
                          )
                          OR (
                            scope.include_descendants = TRUE
                            AND EXISTS (
                              SELECT 1
                              FROM organization_current_closure closure
                              WHERE closure.ancestor_organization_id =
                                    scope.organization_id
                                AND closure.descendant_organization_id =
                                    target_assignment.organization_id
                            )
                          )
                        )
                    )
                  )
                )
            )
            """;

    private static final String ACCOUNT_EMPLOYEE_BOUNDARY_PREDICATE = """
            EXISTS (
              SELECT 1
              FROM auth_principal employee_scope_actor
              JOIN auth_principal_role_assignment employee_scope_assignment
                ON employee_scope_assignment.principal_id =
                   employee_scope_actor.principal_id
              JOIN auth_role_capability employee_scope_role_capability
                ON employee_scope_role_capability.role_id =
                   employee_scope_assignment.role_id
              JOIN auth_capability employee_scope_capability
                ON employee_scope_capability.capability_id =
                   employee_scope_role_capability.capability_id
               AND employee_scope_capability.capability_code =
                   :requiredCapability
              JOIN auth_data_scope employee_actor_scope
                ON employee_actor_scope.scope_id =
                   employee_scope_assignment.data_scope_id
              WHERE employee_scope_actor.principal_id = :principalId
                AND employee_scope_actor.status = 'ACTIVE'
                AND employee_scope_assignment.valid_from <=
                    :authorizationTime
                AND (
                  employee_scope_assignment.valid_to IS NULL
                  OR employee_scope_assignment.valid_to >
                     :authorizationTime
                )
                AND employee_actor_scope.valid_from <=
                    :authorizationTime
                AND (
                  employee_actor_scope.valid_to IS NULL
                  OR employee_actor_scope.valid_to >
                     :authorizationTime
                )
                AND (
                  (
                    employee_actor_scope.scope_type = 'SELF'
                    AND account.principal_id = :principalId
                  )
                  OR (
                    employee_actor_scope.scope_type = 'COMPANY'
                    AND employee_actor_scope.company_id =
                        employee.company_id
                    AND EXISTS (
                      SELECT 1
                      FROM company employee_company
                      WHERE employee_company.company_id =
                            employee.company_id
                        AND employee_company.status = 'ACTIVE'
                    )
                  )
                  OR (
                    employee_actor_scope.scope_type = 'ORGANIZATION'
                    AND EXISTS (
                      SELECT 1
                      FROM organization_identity employee_scoped_organization
                      JOIN company employee_company
                        ON employee_company.company_id =
                           employee_scoped_organization.company_id
                       AND employee_company.status = 'ACTIVE'
                      JOIN organization_current_projection
                          employee_scoped_projection
                        ON employee_scoped_projection.organization_id =
                           employee_scoped_organization.organization_id
                      JOIN organization_version employee_scoped_version
                        ON employee_scoped_version.organization_version_id =
                           employee_scoped_projection.current_version_id
                       AND employee_scoped_version.organization_id =
                           employee_scoped_organization.organization_id
                       AND employee_scoped_version.status = 'ACTIVE'
                      JOIN employment_assignment employee_target_assignment
                        ON employee_target_assignment.employee_id =
                           employee.employee_id
                       AND employee_target_assignment.record_status = 'ACTIVE'
                       AND employee_target_assignment.version_valid_to IS NULL
                      JOIN organization_identity employee_target_organization
                        ON employee_target_organization.organization_id =
                           employee_target_assignment.organization_id
                       AND employee_target_organization.identity_status =
                           'ACTIVE'
                       AND employee_target_organization.company_id =
                           employee_scoped_organization.company_id
                       AND employee_target_organization.company_id =
                           employee.company_id
                      JOIN organization_current_projection
                          employee_target_projection
                        ON employee_target_projection.organization_id =
                           employee_target_organization.organization_id
                      JOIN organization_version employee_target_version
                        ON employee_target_version.organization_version_id =
                           employee_target_projection.current_version_id
                       AND employee_target_version.organization_id =
                           employee_target_organization.organization_id
                       AND employee_target_version.status = 'ACTIVE'
                      WHERE employee_scoped_organization.organization_id =
                            employee_actor_scope.organization_id
                        AND employee_scoped_organization.identity_status =
                            'ACTIVE'
                        AND employee_scoped_version.effective_from <=
                            :authorizationTime
                        AND (
                          employee_scoped_version.effective_to IS NULL
                          OR employee_scoped_version.effective_to >
                             :authorizationTime
                        )
                        AND employee_target_assignment.effective_from <=
                            :authorizationTime
                        AND (
                          employee_target_assignment.effective_to IS NULL
                          OR employee_target_assignment.effective_to >
                             :authorizationTime
                        )
                        AND employee_target_version.effective_from <=
                            :authorizationTime
                        AND (
                          employee_target_version.effective_to IS NULL
                          OR employee_target_version.effective_to >
                             :authorizationTime
                        )
                        AND (
                          employee_actor_scope.organization_id =
                              employee_target_assignment.organization_id
                          OR (
                            employee_actor_scope.include_descendants = TRUE
                            AND EXISTS (
                              SELECT 1
                              FROM organization_current_closure closure
                              WHERE closure.ancestor_organization_id =
                                    employee_actor_scope.organization_id
                                AND closure.descendant_organization_id =
                                    employee_target_assignment.organization_id
                            )
                          )
                        )
                    )
                  )
                )
            )
            """;

    private static final String TARGET_ROLE_RESOURCE_PREDICATE = """
            (
              (
                target_scope.scope_type = 'COMPANY'
                AND EXISTS (
                  SELECT 1
                  FROM company target_company
                  WHERE target_company.company_id =
                        target_scope.company_id
                    AND target_company.status = 'ACTIVE'
                )
              )
              OR (
                target_scope.scope_type = 'ORGANIZATION'
                AND target_scope_organization.identity_status = 'ACTIVE'
                AND EXISTS (
                  SELECT 1
                  FROM company target_company
                  JOIN organization_current_projection target_projection
                    ON target_projection.organization_id =
                       target_scope_organization.organization_id
                  JOIN organization_version target_version
                    ON target_version.organization_version_id =
                       target_projection.current_version_id
                   AND target_version.organization_id =
                       target_scope_organization.organization_id
                  WHERE target_company.company_id =
                        target_scope_organization.company_id
                    AND target_company.status = 'ACTIVE'
                    AND target_version.status = 'ACTIVE'
                    AND target_version.effective_from <=
                        :authorizationTime
                    AND (
                      target_version.effective_to IS NULL
                      OR target_version.effective_to >
                         :authorizationTime
                    )
                )
              )
              OR (
                target_scope.scope_type = 'SELF'
                AND target_employee.employment_status = 'ACTIVE'
                AND EXISTS (
                  SELECT 1
                  FROM company target_company
                  WHERE target_company.company_id =
                        target_employee.company_id
                    AND target_company.status = 'ACTIVE'
                )
              )
            )
            """;

    private static final String TARGET_ROLE_SCOPE_COVERAGE_PREDICATE = """
            (
            """ + TARGET_ROLE_RESOURCE_PREDICATE + """
              AND EXISTS (
                SELECT 1
                FROM auth_principal scope_actor
                JOIN auth_principal_role_assignment scope_assignment
                  ON scope_assignment.principal_id =
                     scope_actor.principal_id
                JOIN auth_role_capability scope_role_capability
                  ON scope_role_capability.role_id =
                     scope_assignment.role_id
                JOIN auth_capability scope_capability
                  ON scope_capability.capability_id =
                     scope_role_capability.capability_id
                 AND scope_capability.capability_code =
                     :requiredCapability
                JOIN auth_data_scope actor_scope
                  ON actor_scope.scope_id =
                     scope_assignment.data_scope_id
                WHERE scope_actor.principal_id = :principalId
                  AND scope_actor.status = 'ACTIVE'
                  AND scope_assignment.valid_from <=
                      :authorizationTime
                  AND (
                    scope_assignment.valid_to IS NULL
                    OR scope_assignment.valid_to >
                       :authorizationTime
                  )
                  AND actor_scope.valid_from <=
                      :authorizationTime
                  AND (
                    actor_scope.valid_to IS NULL
                    OR actor_scope.valid_to >
                       :authorizationTime
                  )
                  AND (
                    (
                      actor_scope.scope_type = 'COMPANY'
                      AND actor_scope.company_id =
                          CASE target_scope.scope_type
                            WHEN 'COMPANY' THEN target_scope.company_id
                            WHEN 'ORGANIZATION'
                              THEN target_scope_organization.company_id
                            WHEN 'SELF' THEN target_employee.company_id
                            ELSE NULL
                          END
                    )
                    OR (
                      actor_scope.scope_type = 'ORGANIZATION'
                      AND target_scope.scope_type = 'ORGANIZATION'
                      AND EXISTS (
                        SELECT 1
                        FROM organization_identity actor_organization
                        JOIN organization_current_projection actor_projection
                          ON actor_projection.organization_id =
                             actor_organization.organization_id
                        JOIN organization_version actor_version
                          ON actor_version.organization_version_id =
                             actor_projection.current_version_id
                         AND actor_version.organization_id =
                             actor_organization.organization_id
                        WHERE actor_organization.organization_id =
                              actor_scope.organization_id
                          AND actor_organization.identity_status = 'ACTIVE'
                          AND actor_organization.company_id =
                              target_scope_organization.company_id
                          AND actor_version.status = 'ACTIVE'
                          AND actor_version.effective_from <=
                              :authorizationTime
                          AND (
                            actor_version.effective_to IS NULL
                            OR actor_version.effective_to >
                               :authorizationTime
                          )
                          AND (
                            actor_scope.organization_id =
                                target_scope.organization_id
                            OR (
                              actor_scope.include_descendants = TRUE
                              AND EXISTS (
                                SELECT 1
                                FROM organization_current_closure closure
                                WHERE closure.ancestor_organization_id =
                                      actor_scope.organization_id
                                  AND closure.descendant_organization_id =
                                      target_scope.organization_id
                              )
                            )
                          )
                      )
                    )
                    OR (
                      actor_scope.scope_type = 'ORGANIZATION'
                      AND target_scope.scope_type = 'SELF'
                      AND EXISTS (
                        SELECT 1
                        FROM organization_identity actor_organization
                        JOIN organization_current_projection actor_projection
                          ON actor_projection.organization_id =
                             actor_organization.organization_id
                        JOIN organization_version actor_version
                          ON actor_version.organization_version_id =
                             actor_projection.current_version_id
                         AND actor_version.organization_id =
                             actor_organization.organization_id
                        JOIN employment_assignment target_employment
                          ON target_employment.employee_id =
                             target_employee.employee_id
                         AND target_employment.record_status = 'ACTIVE'
                         AND target_employment.version_valid_to IS NULL
                        JOIN organization_identity
                            target_employment_organization
                          ON target_employment_organization.organization_id =
                             target_employment.organization_id
                         AND target_employment_organization.identity_status =
                             'ACTIVE'
                         AND target_employment_organization.company_id =
                             actor_organization.company_id
                         AND target_employment_organization.company_id =
                             target_employee.company_id
                        JOIN organization_current_projection
                            target_employment_projection
                          ON target_employment_projection.organization_id =
                             target_employment_organization.organization_id
                        JOIN organization_version target_employment_version
                          ON target_employment_version.organization_version_id =
                             target_employment_projection.current_version_id
                         AND target_employment_version.organization_id =
                             target_employment_organization.organization_id
                        WHERE actor_organization.organization_id =
                              actor_scope.organization_id
                          AND actor_organization.identity_status = 'ACTIVE'
                          AND actor_version.status = 'ACTIVE'
                          AND actor_version.effective_from <=
                              :authorizationTime
                          AND (
                            actor_version.effective_to IS NULL
                            OR actor_version.effective_to >
                               :authorizationTime
                          )
                          AND target_employment.effective_from <=
                              :authorizationTime
                          AND (
                            target_employment.effective_to IS NULL
                            OR target_employment.effective_to >
                               :authorizationTime
                          )
                          AND target_employment_version.status = 'ACTIVE'
                          AND target_employment_version.effective_from <=
                              :authorizationTime
                          AND (
                            target_employment_version.effective_to IS NULL
                            OR target_employment_version.effective_to >
                               :authorizationTime
                          )
                          AND (
                            actor_scope.organization_id =
                                target_employment.organization_id
                            OR (
                              actor_scope.include_descendants = TRUE
                              AND EXISTS (
                                SELECT 1
                                FROM organization_current_closure closure
                                WHERE closure.ancestor_organization_id =
                                      actor_scope.organization_id
                                  AND closure.descendant_organization_id =
                                      target_employment.organization_id
                              )
                            )
                          )
                      )
                    )
                    OR (
                      actor_scope.scope_type = 'SELF'
                      AND target_scope.scope_type = 'SELF'
                      AND scope_actor.principal_id =
                          target_principal.principal_id
                    )
                  )
              )
            )
            """;

    private static final String ACCOUNT_FULL_VISIBILITY_PREDICATE = """
            (
            """ + ACCOUNT_VISIBILITY_PREDICATE + """
              AND (
                employee.employee_id IS NULL
                OR
            """ + ACCOUNT_EMPLOYEE_BOUNDARY_PREDICATE + """
              )
              AND NOT EXISTS (
                SELECT 1
                FROM auth_principal_role_assignment target_assignment
                JOIN auth_principal target_principal
                  ON target_principal.principal_id =
                     target_assignment.principal_id
                LEFT JOIN auth_data_scope target_scope
                  ON target_scope.scope_id =
                     target_assignment.data_scope_id
                LEFT JOIN organization_identity target_scope_organization
                  ON target_scope_organization.organization_id =
                     target_scope.organization_id
                LEFT JOIN employee target_employee
                  ON target_employee.employee_id =
                     target_principal.employee_id
                WHERE target_principal.principal_id =
                      account.principal_id
                  AND (
                    target_assignment.valid_to IS NULL
                    OR target_assignment.valid_to >
                       :authorizationTime
                  )
                  AND NOT (
                    target_scope.scope_id IS NOT NULL
                    AND
            """ + TARGET_ROLE_SCOPE_COVERAGE_PREDICATE + """
                  )
              )
            )
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public AccountPersistenceAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public boolean canAccessAccount(
            String principalId,
            String accountId,
            String requiredCapability,
            Instant at) {
        return canAccessAllAccountRoleScopes(
                principalId, accountId, requiredCapability, at);
    }

    @Override
    public boolean canAccessAllAccountRoleScopes(
            String principalId,
            String accountId,
            String requiredCapability,
            Instant at) {
        var parameters = visibilityParameters(
                principalId, requiredCapability, at)
                .addValue("accountId", accountId);
        Long count = namedJdbc.queryForObject(
                "SELECT COUNT(*) "
                        + ACCOUNT_FROM
                        + " WHERE account.account_id = :accountId AND "
                        + ACCOUNT_FULL_VISIBILITY_PREDICATE,
                parameters,
                Long.class);
        return count != null && count > 0;
    }

    @Override
    public Optional<AccountRecord> lockAccountForScopeAuthorization(
            String accountId) {
        List<AccountRecord> accounts = jdbc.query(
                ACCOUNT_SELECT
                        + """
                         WHERE account.account_id = ?
                         FOR UPDATE
                         """,
                AccountPersistenceAdapter::mapAccount,
                accountId);
        return accounts.size() == 1
                ? Optional.of(accounts.getFirst())
                : Optional.empty();
    }

    @Override
    public String createAccount(
            String username,
            String normalizedUsername,
            String displayName,
            String employeeId,
            String passwordHash,
            String actorId,
            Instant at) {
        String principalId = UUID.randomUUID().toString();
        String accountId = UUID.randomUUID().toString();
        try {
            jdbc.update(
                    """
                    INSERT INTO auth_principal (
                        principal_id, employee_id, status, created_at, row_version
                    ) VALUES (?, ?, 'ACTIVE', ?, 0)
                    """,
                    principalId,
                    employeeId,
                    Timestamp.from(at));
        } catch (DataIntegrityViolationException exception) {
            if (employeeId != null) {
                throw new EmployeeAccountConflictException(exception);
            }
            throw exception;
        }
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
            String requiredCapability,
            String query,
            String status,
            int limit,
            int offset,
            Instant at) {
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase();
        String normalizedStatus = status == null ? "" : status.trim().toUpperCase();
        var parameters = visibilityParameters(
                principalId, requiredCapability, at)
                .addValue("normalizedQuery", normalizedQuery)
                .addValue("queryPattern", "%" + normalizedQuery + "%")
                .addValue("normalizedStatus", normalizedStatus)
                .addValue("limit", limit)
                .addValue("offset", offset);
        return namedJdbc.query(
                ACCOUNT_SELECT
                        + """
                         WHERE (
                           :normalizedQuery = ''
                           OR LOWER(account.username) LIKE :queryPattern
                           OR LOWER(account.display_name) LIKE :queryPattern
                         )
                           AND (
                             :normalizedStatus = ''
                             OR account.status = :normalizedStatus
                           )
                           AND
                         """
                        + ACCOUNT_FULL_VISIBILITY_PREDICATE
                        + """
                         ORDER BY account.normalized_username
                         LIMIT :limit OFFSET :offset
                         """,
                parameters,
                AccountPersistenceAdapter::mapAccount);
    }

    @Override
    public List<EmployeeAccountCandidateRecord> listEmployeeAccountCandidates(
            String principalId,
            String companyId,
            String query,
            int limit,
            int offset,
            Instant at) {
        MapSqlParameterSource parameters = candidateParameters(
                        principalId, companyId, query, at)
                .addValue("limit", limit)
                .addValue("offset", offset);
        return namedJdbc.query(
                """
                SELECT employee.employee_id,
                       employee.company_id,
                       employee_version.employee_number,
                       employee_version.display_name,
                       organization_version.name AS organization_name,
                """
                        + EMPLOYEE_ACCOUNT_CANDIDATE_STATUS
                        + " AS candidate_status "
                        + EMPLOYEE_ACCOUNT_CANDIDATE_FROM
                        + " WHERE "
                        + EMPLOYEE_ACCOUNT_CANDIDATE_BASE_FILTER
                        + """
                         ORDER BY employee_version.employee_number,
                                  employee.employee_id
                         LIMIT :limit OFFSET :offset
                         """,
                parameters,
                AccountPersistenceAdapter::mapEmployeeAccountCandidate);
    }

    @Override
    public List<EmployeeAccountCandidateRecord> findEmployeeAccountCandidates(
            String principalId,
            List<String> employeeIds,
            Instant at) {
        if (employeeIds.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource parameters = candidateParameters(
                        principalId, "", "", at)
                .addValue("employeeIds", employeeIds);
        return namedJdbc.query(
                """
                SELECT employee.employee_id,
                       employee.company_id,
                       employee_version.employee_number,
                       employee_version.display_name,
                       organization_version.name AS organization_name,
                """
                        + EMPLOYEE_ACCOUNT_CANDIDATE_STATUS
                        + " AS candidate_status "
                        + EMPLOYEE_ACCOUNT_CANDIDATE_FROM
                        + " WHERE employee.employee_id IN (:employeeIds)"
                        + """
                          AND employee.employment_status = 'ACTIVE'
                          AND employee_version.status = 'ACTIVE'
                          AND current_assignment.assignment_id IS NOT NULL
                          AND
                         """
                        + EMPLOYEE_ACCOUNT_CANDIDATE_AUTHORIZATION
                        + """
                         ORDER BY employee.company_id,
                                  employee_version.employee_number,
                                  employee.employee_id
                         """,
                parameters,
                AccountPersistenceAdapter::mapEmployeeAccountCandidate);
    }

    @Override
    public CandidateCounts countEmployeeAccountCandidates(
            String principalId,
            String companyId,
            String query,
            Instant at) {
        MapSqlParameterSource parameters = candidateParameters(
                principalId, companyId, query, at);
        return namedJdbc.queryForObject(
                "SELECT COUNT(*) AS total, "
                        + "SUM(candidate_status = 'AVAILABLE') AS available, "
                        + "SUM(candidate_status = 'ALREADY_PROVISIONED') AS already_provisioned, "
                        + "SUM(candidate_status = 'USERNAME_CONFLICT') AS username_conflicts "
                        + "FROM (SELECT "
                        + EMPLOYEE_ACCOUNT_CANDIDATE_STATUS
                        + " AS candidate_status "
                        + EMPLOYEE_ACCOUNT_CANDIDATE_FROM
                        + " WHERE "
                        + EMPLOYEE_ACCOUNT_CANDIDATE_BASE_FILTER
                        + ") candidate_counts",
                parameters,
                (result, row) -> new CandidateCounts(
                        result.getLong("total"),
                        result.getLong("available"),
                        result.getLong("already_provisioned"),
                        result.getLong("username_conflicts")));
    }

    @Override
    public long countAccounts(
            String principalId,
            String requiredCapability,
            String query,
            String status,
            Instant at) {
        String normalizedQuery = query == null
                ? ""
                : query.trim().toLowerCase();
        String normalizedStatus = status == null
                ? ""
                : status.trim().toUpperCase();
        var parameters = visibilityParameters(
                principalId, requiredCapability, at)
                .addValue("normalizedQuery", normalizedQuery)
                .addValue("queryPattern", "%" + normalizedQuery + "%")
                .addValue("normalizedStatus", normalizedStatus);
        Long count = namedJdbc.queryForObject(
                "SELECT COUNT(*) "
                        + ACCOUNT_FROM
                        + """
                         WHERE (
                           :normalizedQuery = ''
                           OR LOWER(account.username) LIKE :queryPattern
                           OR LOWER(account.display_name) LIKE :queryPattern
                         )
                           AND (
                             :normalizedStatus = ''
                             OR account.status = :normalizedStatus
                           )
                           AND
                         """
                        + ACCOUNT_FULL_VISIBILITY_PREDICATE,
                parameters,
                Long.class);
        return count == null ? 0 : count;
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
    public void lockRoleGrantTargetCompanies(
            String targetPrincipalId,
            String targetEmployeeId,
            List<RoleAssignmentInput> assignments,
            Instant at) {
        SortedSet<String> targetCompanyIds = new TreeSet<>();
        if (targetPrincipalId != null) {
            targetCompanyIds.addAll(findExistingRoleTargetCompanyIds(
                    targetPrincipalId, at));
        }
        for (RoleAssignmentInput assignment : assignments) {
            targetCompanyIds.add(resolveGrantTargetCompanyId(
                    targetPrincipalId,
                    targetEmployeeId,
                    assignment,
                    at));
        }
        for (String companyId : targetCompanyIds) {
            List<String> locked = jdbc.queryForList(
                    """
                    SELECT company_id
                    FROM company
                    WHERE company_id = ?
                      AND status = 'ACTIVE'
                    FOR UPDATE
                    """,
                    String.class,
                    companyId);
            if (locked.size() != 1) {
                throw new ResourceNotAvailableAccessDeniedException();
            }
        }
    }

    @Override
    public void lockTargetRoleAssignments(
            String targetPrincipalId,
            Instant at) {
        jdbc.queryForList(
                """
                SELECT assignment_id
                FROM auth_principal_role_assignment
                WHERE principal_id = ?
                  AND (valid_to IS NULL OR valid_to > ?)
                ORDER BY assignment_id
                FOR UPDATE
                """,
                String.class,
                targetPrincipalId,
                Timestamp.from(at));
    }

    @Override
    public boolean lockCurrentCapabilityAuthority(
            String actorPrincipalId,
            String requiredCapability,
            Instant at) {
        List<String> assignmentIds = jdbc.queryForList(
                """
                SELECT assignment.assignment_id
                FROM auth_principal principal
                JOIN auth_principal_role_assignment assignment
                  ON assignment.principal_id = principal.principal_id
                JOIN auth_role_capability role_capability
                  ON role_capability.role_id = assignment.role_id
                JOIN auth_capability capability
                  ON capability.capability_id = role_capability.capability_id
                 AND capability.capability_code = ?
                JOIN auth_data_scope scope
                  ON scope.scope_id = assignment.data_scope_id
                WHERE principal.principal_id = ?
                  AND principal.status = 'ACTIVE'
                  AND assignment.valid_from <= ?
                  AND (assignment.valid_to IS NULL OR assignment.valid_to > ?)
                  AND scope.valid_from <= ?
                  AND (scope.valid_to IS NULL OR scope.valid_to > ?)
                ORDER BY assignment.assignment_id
                FOR UPDATE
                """,
                String.class,
                requiredCapability,
                actorPrincipalId,
                Timestamp.from(at),
                Timestamp.from(at),
                Timestamp.from(at),
                Timestamp.from(at));
        return !assignmentIds.isEmpty();
    }

    @Override
    public Optional<String> findRoleCodeForUpdate(String roleId) {
        List<String> roleCodes = jdbc.queryForList(
                """
                SELECT role_code
                FROM auth_role
                WHERE role_id = ?
                FOR UPDATE
                """,
                String.class,
                roleId);
        return roleCodes.size() == 1
                ? Optional.of(roleCodes.getFirst())
                : Optional.empty();
    }

    @Override
    public Optional<String> findRoleIdByCodeForUpdate(String roleCode) {
        List<String> roleIds = jdbc.queryForList(
                """
                SELECT role_id
                FROM auth_role
                WHERE role_code = ?
                FOR UPDATE
                """,
                String.class,
                roleCode);
        return roleIds.size() == 1
                ? Optional.of(roleIds.getFirst())
                : Optional.empty();
    }

    @Override
    public List<ResolvedRoleAssignmentInput> resolveAuthorizedRoleAssignmentScopes(
            String actorPrincipalId,
            String targetPrincipalId,
            String targetEmployeeId,
            List<RoleAssignmentInput> assignments,
            Instant at) {
        List<ResolvedRoleAssignmentInput> resolved = new ArrayList<>();
        for (RoleAssignmentInput assignment : assignments) {
            boolean covered = switch (assignment.scopeType()) {
                case "COMPANY" -> canGrantCompany(
                        actorPrincipalId, assignment, at);
                case "ORGANIZATION" -> canGrantOrganization(
                        actorPrincipalId, assignment, at);
                case "SELF" -> canGrantSelf(
                        actorPrincipalId,
                        targetPrincipalId,
                        targetEmployeeId,
                        assignment,
                        at);
                default -> false;
            };
            if (!covered) {
                throw new ResourceNotAvailableAccessDeniedException();
            }
            resolved.add(new ResolvedRoleAssignmentInput(
                    assignment.roleId(),
                    resolveAuthorizedScope(assignment),
                    assignment.validFrom(),
                    assignment.validTo()));
        }
        return List.copyOf(resolved);
    }

    @Override
    public void replaceRoleAssignments(
            String accountId,
            long expectedAccountVersion,
            List<ResolvedRoleAssignmentInput> assignments,
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
        for (ResolvedRoleAssignmentInput assignment : assignments) {
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
                    assignment.dataScopeId(),
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
    public List<RoleAssignmentRecord> findVisibleRoleAssignments(
            String actorPrincipalId,
            String targetPrincipalId,
            String requiredCapability,
            Instant at) {
        var parameters = visibilityParameters(
                actorPrincipalId, requiredCapability, at)
                .addValue("targetPrincipalId", targetPrincipalId);
        return namedJdbc.query(
                """
                SELECT assignment.assignment_id, role.role_id, role.role_code, role.role_name,
                       target_scope.scope_type,
                       CASE
                         WHEN target_scope.scope_type = 'COMPANY'
                           THEN target_scope.company_id
                         WHEN target_scope.scope_type = 'ORGANIZATION'
                           THEN target_scope.organization_id
                         ELSE NULL
                       END AS scope_resource_id,
                       assignment.valid_from, assignment.valid_to
                FROM auth_principal_role_assignment assignment
                JOIN auth_principal target_principal
                  ON target_principal.principal_id =
                     assignment.principal_id
                JOIN auth_role role ON role.role_id = assignment.role_id
                JOIN auth_data_scope target_scope
                  ON target_scope.scope_id = assignment.data_scope_id
                LEFT JOIN organization_identity target_scope_organization
                  ON target_scope_organization.organization_id =
                     target_scope.organization_id
                LEFT JOIN employee target_employee
                  ON target_employee.employee_id =
                     target_principal.employee_id
                WHERE assignment.principal_id = :targetPrincipalId
                  AND (
                    assignment.valid_to IS NULL
                    OR assignment.valid_to > :authorizationTime
                  )
                  AND
                """ + TARGET_ROLE_SCOPE_COVERAGE_PREDICATE + """
                ORDER BY role.role_code, assignment.assignment_id
                """,
                parameters,
                (result, row) -> new RoleAssignmentRecord(
                        result.getString("assignment_id"),
                        result.getString("role_id"),
                        result.getString("role_code"),
                        result.getString("role_name"),
                        result.getString("scope_type"),
                        result.getString("scope_resource_id"),
                        instant(result, "valid_from"),
                        instant(result, "valid_to")));
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
                WHERE (
                  capability.capability_code IS NULL
                  OR capability.capability_code NOT LIKE 'PAYROLL:%'
                )
                  AND role.role_code NOT IN (
                    'MANUFACTURING_SUPERVISOR',
                    'MANUFACTURING_CENTER_SUPERVISOR'
                  )
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

    private List<String> findExistingRoleTargetCompanyIds(
            String targetPrincipalId,
            Instant at) {
        return jdbc.queryForList(
                """
                SELECT DISTINCT target_company_id
                FROM (
                  SELECT target_scope.company_id AS target_company_id
                  FROM auth_principal_role_assignment target_assignment
                  JOIN auth_data_scope target_scope
                    ON target_scope.scope_id =
                       target_assignment.data_scope_id
                  WHERE target_assignment.principal_id = ?
                    AND (
                      target_assignment.valid_to IS NULL
                      OR target_assignment.valid_to > ?
                    )
                    AND target_scope.scope_type = 'COMPANY'
                    AND target_scope.company_id IS NOT NULL
                  UNION
                  SELECT target_organization.company_id AS target_company_id
                  FROM auth_principal_role_assignment target_assignment
                  JOIN auth_data_scope target_scope
                    ON target_scope.scope_id =
                       target_assignment.data_scope_id
                  JOIN organization_identity target_organization
                    ON target_organization.organization_id =
                       target_scope.organization_id
                  WHERE target_assignment.principal_id = ?
                    AND (
                      target_assignment.valid_to IS NULL
                      OR target_assignment.valid_to > ?
                    )
                    AND target_scope.scope_type = 'ORGANIZATION'
                  UNION
                  SELECT target_employee.company_id AS target_company_id
                  FROM auth_principal_role_assignment target_assignment
                  JOIN auth_data_scope target_scope
                    ON target_scope.scope_id =
                       target_assignment.data_scope_id
                  JOIN auth_principal target_principal
                    ON target_principal.principal_id =
                       target_assignment.principal_id
                  JOIN employee target_employee
                    ON target_employee.employee_id =
                       target_principal.employee_id
                  WHERE target_assignment.principal_id = ?
                    AND (
                      target_assignment.valid_to IS NULL
                      OR target_assignment.valid_to > ?
                    )
                    AND target_scope.scope_type = 'SELF'
                ) existing_target_companies
                ORDER BY target_company_id
                """,
                String.class,
                targetPrincipalId,
                Timestamp.from(at),
                targetPrincipalId,
                Timestamp.from(at),
                targetPrincipalId,
                Timestamp.from(at));
    }

    private String resolveGrantTargetCompanyId(
            String targetPrincipalId,
            String targetEmployeeId,
            RoleAssignmentInput requested,
            Instant at) {
        List<String> companyIds = switch (requested.scopeType()) {
            case "COMPANY" -> jdbc.queryForList(
                    """
                    SELECT company_id
                    FROM company
                    WHERE company_id = ?
                      AND status = 'ACTIVE'
                    """,
                    String.class,
                    requested.scopeResourceId());
            case "ORGANIZATION" -> jdbc.queryForList(
                    """
                    SELECT organization.company_id
                    FROM organization_identity organization
                    JOIN company company
                      ON company.company_id =
                         organization.company_id
                     AND company.status = 'ACTIVE'
                    JOIN organization_current_projection projection
                      ON projection.organization_id =
                         organization.organization_id
                    JOIN organization_version version
                      ON version.organization_version_id =
                         projection.current_version_id
                     AND version.organization_id =
                         organization.organization_id
                    WHERE organization.organization_id = ?
                      AND organization.identity_status = 'ACTIVE'
                      AND version.status = 'ACTIVE'
                      AND version.effective_from <= ?
                      AND (
                        version.effective_to IS NULL
                        OR version.effective_to > ?
                      )
                    """,
                    String.class,
                    requested.scopeResourceId(),
                    Timestamp.from(at),
                    Timestamp.from(at));
            case "SELF" -> resolveSelfTargetCompanyId(
                    targetPrincipalId, targetEmployeeId);
            default -> List.of();
        };
        if (companyIds.size() != 1) {
            throw new ResourceNotAvailableAccessDeniedException();
        }
        return companyIds.getFirst();
    }

    private List<String> resolveSelfTargetCompanyId(
            String targetPrincipalId,
            String targetEmployeeId) {
        if (targetPrincipalId != null) {
            return jdbc.queryForList(
                    """
                    SELECT employee.company_id
                    FROM auth_principal principal
                    JOIN employee employee
                      ON employee.employee_id = principal.employee_id
                     AND employee.employment_status = 'ACTIVE'
                    JOIN company company
                      ON company.company_id =
                         employee.company_id
                     AND company.status = 'ACTIVE'
                    WHERE principal.principal_id = ?
                      AND principal.status = 'ACTIVE'
                    """,
                    String.class,
                    targetPrincipalId);
        }
        if (targetEmployeeId != null) {
            return jdbc.queryForList(
                    """
                    SELECT employee.company_id
                    FROM employee employee
                    JOIN company company
                      ON company.company_id =
                         employee.company_id
                     AND company.status = 'ACTIVE'
                    WHERE employee.employee_id = ?
                      AND employee.employment_status = 'ACTIVE'
                    """,
                    String.class,
                    targetEmployeeId);
        }
        return List.of();
    }

    private boolean canGrantCompany(
            String actorPrincipalId,
            RoleAssignmentInput requested,
            Instant at) {
        Timestamp requestedFrom = Timestamp.from(requested.validFrom());
        Timestamp requestedTo = timestamp(requested.validTo());
        Long count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM company target_company
                WHERE target_company.company_id = ?
                  AND target_company.status = 'ACTIVE'
                  AND EXISTS (
                    SELECT 1
                    FROM auth_principal actor
                    JOIN auth_principal_role_assignment assignment
                      ON assignment.principal_id = actor.principal_id
                    JOIN auth_role_capability role_capability
                      ON role_capability.role_id = assignment.role_id
                    JOIN auth_capability capability
                      ON capability.capability_id = role_capability.capability_id
                     AND capability.capability_code = 'ROLE:ASSIGN'
                    JOIN auth_data_scope scope
                      ON scope.scope_id = assignment.data_scope_id
                    WHERE actor.principal_id = ?
                      AND actor.status = 'ACTIVE'
                      AND assignment.valid_from <= ?
                      AND (assignment.valid_to IS NULL OR assignment.valid_to > ?)
                      AND scope.valid_from <= ?
                      AND (scope.valid_to IS NULL OR scope.valid_to > ?)
                      AND assignment.valid_from <= ?
                      AND (
                        assignment.valid_to IS NULL
                        OR (? IS NOT NULL AND assignment.valid_to >= ?)
                      )
                      AND scope.valid_from <= ?
                      AND (
                        scope.valid_to IS NULL
                        OR (? IS NOT NULL AND scope.valid_to >= ?)
                      )
                      AND scope.scope_type = 'COMPANY'
                      AND scope.company_id =
                          target_company.company_id
                  )
                """,
                Long.class,
                requested.scopeResourceId(),
                actorPrincipalId,
                Timestamp.from(at),
                Timestamp.from(at),
                Timestamp.from(at),
                Timestamp.from(at),
                requestedFrom,
                requestedTo,
                requestedTo,
                requestedFrom,
                requestedTo,
                requestedTo);
        return count != null && count == 1;
    }

    private boolean canGrantOrganization(
            String actorPrincipalId,
            RoleAssignmentInput requested,
            Instant at) {
        Timestamp timestamp = Timestamp.from(at);
        Timestamp requestedFrom = Timestamp.from(requested.validFrom());
        Timestamp requestedTo = timestamp(requested.validTo());
        Long count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM organization_identity target_organization
                JOIN company target_company
                  ON target_company.company_id =
                     target_organization.company_id
                 AND target_company.status = 'ACTIVE'
                JOIN organization_current_projection target_projection
                  ON target_projection.organization_id =
                     target_organization.organization_id
                JOIN organization_version target_version
                  ON target_version.organization_version_id =
                     target_projection.current_version_id
                 AND target_version.organization_id =
                     target_organization.organization_id
                WHERE target_organization.organization_id = ?
                  AND target_organization.identity_status = 'ACTIVE'
                  AND target_version.status = 'ACTIVE'
                  AND target_version.effective_from <= ?
                  AND (
                    target_version.effective_to IS NULL
                    OR target_version.effective_to > ?
                  )
                  AND EXISTS (
                    SELECT 1
                    FROM auth_principal actor
                    JOIN auth_principal_role_assignment assignment
                      ON assignment.principal_id = actor.principal_id
                    JOIN auth_role_capability role_capability
                      ON role_capability.role_id = assignment.role_id
                    JOIN auth_capability capability
                      ON capability.capability_id = role_capability.capability_id
                     AND capability.capability_code = 'ROLE:ASSIGN'
                    JOIN auth_data_scope scope
                      ON scope.scope_id = assignment.data_scope_id
                    LEFT JOIN organization_identity actor_organization
                      ON actor_organization.organization_id =
                         scope.organization_id
                    LEFT JOIN organization_current_projection actor_projection
                      ON actor_projection.organization_id =
                         actor_organization.organization_id
                    LEFT JOIN organization_version actor_version
                      ON actor_version.organization_version_id =
                         actor_projection.current_version_id
                     AND actor_version.organization_id =
                         actor_organization.organization_id
                    WHERE actor.principal_id = ?
                      AND actor.status = 'ACTIVE'
                      AND assignment.valid_from <= ?
                      AND (assignment.valid_to IS NULL OR assignment.valid_to > ?)
                      AND scope.valid_from <= ?
                      AND (scope.valid_to IS NULL OR scope.valid_to > ?)
                      AND assignment.valid_from <= ?
                      AND (
                        assignment.valid_to IS NULL
                        OR (? IS NOT NULL AND assignment.valid_to >= ?)
                      )
                      AND scope.valid_from <= ?
                      AND (
                        scope.valid_to IS NULL
                        OR (? IS NOT NULL AND scope.valid_to >= ?)
                      )
                      AND (
                        (
                          scope.scope_type = 'COMPANY'
                          AND scope.company_id =
                              target_organization.company_id
                        )
                        OR (
                          scope.scope_type = 'ORGANIZATION'
                          AND actor_organization.identity_status = 'ACTIVE'
                          AND actor_organization.company_id =
                              target_organization.company_id
                          AND actor_version.status = 'ACTIVE'
                          AND actor_version.effective_from <= ?
                          AND (
                            actor_version.effective_to IS NULL
                            OR actor_version.effective_to > ?
                          )
                          AND (
                            scope.organization_id =
                                target_organization.organization_id
                            OR (
                              scope.include_descendants = TRUE
                              AND EXISTS (
                                SELECT 1
                                FROM organization_current_closure closure
                                WHERE closure.ancestor_organization_id =
                                      scope.organization_id
                                  AND closure.descendant_organization_id =
                                      target_organization.organization_id
                              )
                            )
                          )
                        )
                      )
                  )
                """,
                Long.class,
                requested.scopeResourceId(),
                timestamp,
                timestamp,
                actorPrincipalId,
                timestamp,
                timestamp,
                timestamp,
                timestamp,
                requestedFrom,
                requestedTo,
                requestedTo,
                requestedFrom,
                requestedTo,
                requestedTo,
                timestamp,
                timestamp);
        return count != null && count == 1;
    }

    private boolean canGrantSelf(
            String actorPrincipalId,
            String targetPrincipalId,
            String targetEmployeeId,
            RoleAssignmentInput requested,
            Instant at) {
        Timestamp timestamp = Timestamp.from(at);
        Timestamp requestedFrom = Timestamp.from(requested.validFrom());
        Timestamp requestedTo = timestamp(requested.validTo());
        Long count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM employee target_employee
                LEFT JOIN auth_principal target_principal
                  ON target_principal.employee_id = target_employee.employee_id
                JOIN company target_company
                  ON target_company.company_id =
                     target_employee.company_id
                 AND target_company.status = 'ACTIVE'
                WHERE target_employee.employment_status = 'ACTIVE'
                  AND (
                    (
                      ? IS NOT NULL
                      AND target_principal.principal_id = ?
                      AND target_principal.status = 'ACTIVE'
                    )
                    OR (
                      ? IS NOT NULL
                      AND target_employee.employee_id = ?
                    )
                  )
                  AND EXISTS (
                    SELECT 1
                    FROM auth_principal actor
                    JOIN auth_principal_role_assignment assignment
                      ON assignment.principal_id = actor.principal_id
                    JOIN auth_role_capability role_capability
                      ON role_capability.role_id = assignment.role_id
                    JOIN auth_capability capability
                      ON capability.capability_id = role_capability.capability_id
                     AND capability.capability_code = 'ROLE:ASSIGN'
                    JOIN auth_data_scope scope
                      ON scope.scope_id = assignment.data_scope_id
                    LEFT JOIN organization_identity actor_organization
                      ON actor_organization.organization_id =
                         scope.organization_id
                    LEFT JOIN organization_current_projection actor_projection
                      ON actor_projection.organization_id =
                         actor_organization.organization_id
                    LEFT JOIN organization_version actor_version
                      ON actor_version.organization_version_id =
                         actor_projection.current_version_id
                     AND actor_version.organization_id =
                         actor_organization.organization_id
                    WHERE actor.principal_id = ?
                      AND actor.status = 'ACTIVE'
                      AND assignment.valid_from <= ?
                      AND (assignment.valid_to IS NULL OR assignment.valid_to > ?)
                      AND scope.valid_from <= ?
                      AND (scope.valid_to IS NULL OR scope.valid_to > ?)
                      AND assignment.valid_from <= ?
                      AND (
                        assignment.valid_to IS NULL
                        OR (? IS NOT NULL AND assignment.valid_to >= ?)
                      )
                      AND scope.valid_from <= ?
                      AND (
                        scope.valid_to IS NULL
                        OR (? IS NOT NULL AND scope.valid_to >= ?)
                      )
                      AND (
                        (
                          scope.scope_type = 'COMPANY'
                          AND scope.company_id =
                              target_employee.company_id
                        )
                        OR (
                          scope.scope_type = 'ORGANIZATION'
                          AND actor_organization.identity_status = 'ACTIVE'
                          AND actor_organization.company_id =
                              target_employee.company_id
                          AND actor_version.status = 'ACTIVE'
                          AND actor_version.effective_from <= ?
                          AND (
                            actor_version.effective_to IS NULL
                            OR actor_version.effective_to > ?
                          )
                          AND EXISTS (
                            SELECT 1
                            FROM employment_assignment target_assignment
                            JOIN organization_identity target_organization
                              ON target_organization.organization_id =
                                 target_assignment.organization_id
                             AND target_organization.identity_status = 'ACTIVE'
                            JOIN organization_current_projection target_projection
                              ON target_projection.organization_id =
                                 target_organization.organization_id
                            JOIN organization_version target_version
                              ON target_version.organization_version_id =
                                 target_projection.current_version_id
                             AND target_version.organization_id =
                                 target_organization.organization_id
                            WHERE target_assignment.employee_id =
                                  target_employee.employee_id
                              AND target_assignment.record_status = 'ACTIVE'
                              AND target_assignment.version_valid_to IS NULL
                              AND target_assignment.effective_from <= ?
                              AND (
                                target_assignment.effective_to IS NULL
                                OR target_assignment.effective_to > ?
                              )
                              AND target_organization.company_id =
                                  actor_organization.company_id
                              AND target_version.status = 'ACTIVE'
                              AND target_version.effective_from <= ?
                              AND (
                                target_version.effective_to IS NULL
                                OR target_version.effective_to > ?
                              )
                              AND (
                                scope.organization_id =
                                    target_assignment.organization_id
                                OR (
                                  scope.include_descendants = TRUE
                                  AND EXISTS (
                                    SELECT 1
                                    FROM organization_current_closure closure
                                    WHERE closure.ancestor_organization_id =
                                          scope.organization_id
                                      AND closure.descendant_organization_id =
                                          target_assignment.organization_id
                                  )
                                )
                              )
                          )
                        )
                      )
                  )
                """,
                Long.class,
                targetPrincipalId,
                targetPrincipalId,
                targetEmployeeId,
                targetEmployeeId,
                actorPrincipalId,
                timestamp,
                timestamp,
                timestamp,
                timestamp,
                requestedFrom,
                requestedTo,
                requestedTo,
                requestedFrom,
                requestedTo,
                requestedTo,
                timestamp,
                timestamp,
                timestamp,
                timestamp,
                timestamp,
                timestamp);
        return count != null && count == 1;
    }

    private String resolveAuthorizedScope(RoleAssignmentInput assignment) {
        String targetColumn = switch (assignment.scopeType()) {
            case "COMPANY" -> "company_id";
            case "ORGANIZATION" -> "organization_id";
            case "SELF" -> null;
            default -> throw new IllegalArgumentException("unsupported scope type");
        };
        List<String> existing;
        Timestamp requestedFrom = Timestamp.from(assignment.validFrom());
        Timestamp requestedTo = timestamp(assignment.validTo());
        if (targetColumn == null) {
            existing = jdbc.queryForList(
                    """
                    SELECT scope_id
                    FROM auth_data_scope
                    WHERE scope_type = 'SELF'
                      AND valid_from <= ?
                      AND (
                        valid_to IS NULL
                        OR (? IS NOT NULL AND valid_to >= ?)
                      )
                    LIMIT 1
                    FOR UPDATE
                    """,
                    String.class,
                    requestedFrom,
                    requestedTo,
                    requestedTo);
        } else {
            existing = jdbc.queryForList(
                    "SELECT scope_id FROM auth_data_scope WHERE scope_type = ? AND "
                            + targetColumn
                            + " = ? AND include_descendants = TRUE "
                            + "AND valid_from <= ? "
                            + "AND (valid_to IS NULL "
                            + "OR (? IS NOT NULL AND valid_to >= ?)) "
                            + "LIMIT 1 FOR UPDATE",
                    String.class,
                    assignment.scopeType(),
                    assignment.scopeResourceId(),
                    requestedFrom,
                    requestedTo,
                    requestedTo);
        }
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }

        String scopeId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO auth_data_scope (
                    scope_id, scope_type, company_id, organization_id,
                    include_descendants, valid_from, valid_to
                ) VALUES (?, ?, ?, ?, TRUE, ?, ?)
                """,
                scopeId,
                assignment.scopeType(),
                "COMPANY".equals(assignment.scopeType())
                        ? assignment.scopeResourceId()
                        : null,
                "ORGANIZATION".equals(assignment.scopeType())
                        ? assignment.scopeResourceId()
                        : null,
                requestedFrom,
                requestedTo);
        return scopeId;
    }

    private static MapSqlParameterSource visibilityParameters(
            String principalId,
            String requiredCapability,
            Instant at) {
        return new MapSqlParameterSource()
                .addValue("principalId", principalId)
                .addValue("requiredCapability", requiredCapability)
                .addValue("authorizationTime", Timestamp.from(at));
    }

    private static MapSqlParameterSource candidateParameters(
            String principalId,
            String companyId,
            String query,
            Instant at) {
        String normalizedQuery = query == null
                ? ""
                : query.trim().toLowerCase();
        return new MapSqlParameterSource()
                .addValue("principalId", principalId)
                .addValue("companyId", companyId)
                .addValue("normalizedQuery", normalizedQuery)
                .addValue("queryPattern", "%" + normalizedQuery + "%")
                .addValue("authorizationTime", Timestamp.from(at));
    }

    private static EmployeeAccountCandidateRecord mapEmployeeAccountCandidate(
            ResultSet result,
            int row) throws SQLException {
        return new EmployeeAccountCandidateRecord(
                result.getString("employee_id"),
                result.getString("company_id"),
                result.getString("employee_number"),
                result.getString("display_name"),
                result.getString("organization_name"),
                result.getString("candidate_status"));
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
                result.getString("company_id"));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
