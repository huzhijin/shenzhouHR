package com.szsemicon.hr.wave1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class AccountTargetAuthorizationIntegrationTest
        extends Wave1IntegrationTestSupport {

    private static final String SYSTEM_ADMIN_ROLE =
            "10000000-0000-0000-0000-000000000002";
    private static final String AUDITOR_ROLE =
            "10000000-0000-0000-0000-000000000003";
    private static final String COMPANY_ONE =
            "30000000-0000-0000-0000-000000000001";
    private static final String COMPANY_TWO =
            "30000000-0000-0000-0000-000000000002";
    private static final String SPLIT_SCOPE =
            "99000000-0000-0000-0000-000000000001";
    private static final String EXPIRED_ACTOR_SCOPE =
            "99000000-0000-0000-0000-000000000002";
    private static final String EXPIRED_TARGET_SCOPE =
            "99000000-0000-0000-0000-000000000003";
    private static final String READ_ONLY_SPLIT_SCOPE =
            "99000000-0000-0000-0000-000000000004";
    private static final String MIXED_TARGET_COMPANY_TWO_SCOPE =
            "99000000-0000-0000-0000-000000000005";
    private static final String MIXED_TARGET_COMPANY_TWO_ASSIGNMENT =
            "aa000000-0000-0000-0000-000000000005";

    @Test
    void employeeBindingRemainsACompanyBoundaryWhenRolePointsElsewhere()
            throws Exception {
        Instant now = Instant.now();
        insertCompanyScope(
                MIXED_TARGET_COMPANY_TWO_SCOPE,
                COMPANY_TWO,
                now.minusSeconds(60),
                null);
        jdbc.update(
                """
                UPDATE auth_principal_role_assignment
                SET data_scope_id = ?
                WHERE principal_id = ?
                  AND role_id = ?
                """,
                MIXED_TARGET_COMPANY_TWO_SCOPE,
                ADMIN_PRINCIPAL,
                ADMIN_ROLE);
        insertRoleAssignment(
                MIXED_TARGET_COMPANY_TWO_ASSIGNMENT,
                STANDARD_PRINCIPAL,
                AUDITOR_ROLE,
                MIXED_TARGET_COMPANY_TWO_SCOPE,
                now.minusSeconds(60),
                null);

        mockMvc.perform(get("/api/v1/access/accounts")
                        .queryParam("query", STANDARD_USERNAME)
                        .with(user(ADMIN_PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.total").value(0));

        mockMvc.perform(get(
                        "/api/v1/access/accounts/{accountId}",
                        STANDARD_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("RESOURCE_NOT_AVAILABLE"));

        mockMvc.perform(patch(
                        "/api/v1/access/accounts/{accountId}/status",
                        STANDARD_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {
                                   "status":"DISABLED",
                                   "reason":"员工所属公司边界负例",
                                   "expectedVersion":0
                                 }
                                 """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("RESOURCE_NOT_AVAILABLE"));

        assertThat(jdbc.queryForObject(
                """
                SELECT status
                FROM local_account
                WHERE account_id = ?
                """,
                String.class,
                STANDARD_ACCOUNT)).isEqualTo("ACTIVE");
    }

    @Test
    void oneCompanyAuthorityCannotObserveOrMutateAMixedCompanyAccount()
            throws Exception {
        Instant now = Instant.now();
        insertCompanyScope(
                MIXED_TARGET_COMPANY_TWO_SCOPE,
                COMPANY_TWO,
                now.minusSeconds(60),
                null);
        jdbc.update(
                """
                UPDATE auth_principal_role_assignment
                SET data_scope_id = ?
                WHERE principal_id = ?
                  AND role_id = ?
                """,
                MIXED_TARGET_COMPANY_TWO_SCOPE,
                ADMIN_PRINCIPAL,
                ADMIN_ROLE);
        insertRoleAssignment(
                MIXED_TARGET_COMPANY_TWO_ASSIGNMENT,
                LIMITED_PRINCIPAL,
                AUDITOR_ROLE,
                MIXED_TARGET_COMPANY_TWO_SCOPE,
                now.minusSeconds(60),
                null);

        mockMvc.perform(get("/api/v1/access/accounts")
                        .queryParam("query", LIMITED_USERNAME)
                        .with(user(ADMIN_PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.total").value(0));

        mockMvc.perform(get(
                        "/api/v1/access/accounts/{accountId}",
                        LIMITED_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("RESOURCE_NOT_AVAILABLE"));

        mockMvc.perform(patch(
                        "/api/v1/access/accounts/{accountId}/status",
                        LIMITED_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {
                                   "status":"DISABLED",
                                   "reason":"混合公司账号状态修改负例",
                                   "expectedVersion":0
                                 }
                                 """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("RESOURCE_NOT_AVAILABLE"));

        assertReasonActionUnavailable(LIMITED_ACCOUNT, "lock");
        assertReasonActionUnavailable(LIMITED_ACCOUNT, "unlock");
        assertReasonActionUnavailable(
                LIMITED_ACCOUNT, "password-reset-grants");

        mockMvc.perform(put(
                        "/api/v1/access/accounts/{accountId}/role-assignments",
                        LIMITED_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {
                                   "assignments":[{
                                     "roleId":"%s",
                                     "scopeType":"COMPANY",
                                     "scopeResourceId":"%s",
                                     "validFrom":"%s",
                                     "validTo":null
                                   }],
                                   "reason":"混合公司角色替换负例",
                                   "expectedVersion":0
                                 }
                                 """.formatted(
                                SYSTEM_ADMIN_ROLE,
                                COMPANY_TWO,
                                now.plusSeconds(60))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("RESOURCE_NOT_AVAILABLE"));

        assertThat(jdbc.queryForObject(
                """
                SELECT status
                FROM local_account
                WHERE account_id = ?
                """,
                String.class,
                LIMITED_ACCOUNT)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                """
                SELECT row_version
                FROM local_account
                WHERE account_id = ?
                """,
                Long.class,
                LIMITED_ACCOUNT)).isZero();
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM password_reset_grant
                WHERE account_id = ?
                """,
                Long.class,
                LIMITED_ACCOUNT)).isZero();
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM auth_principal_role_assignment
                WHERE principal_id = ?
                  AND valid_to IS NULL
                """,
                Long.class,
                LIMITED_PRINCIPAL)).isEqualTo(2L);
    }

    @Test
    void capabilityAndCoveringScopeMustComeFromTheSameAssignmentForEveryEndpoint()
            throws Exception {
        Instant now = Instant.now();
        insertCompanyScope(
                SPLIT_SCOPE,
                COMPANY_TWO,
                now.minusSeconds(60),
                null);
        insertRoleAssignment(
                "aa000000-0000-0000-0000-000000000001",
                ADMIN_PRINCIPAL,
                AUDITOR_ROLE,
                SPLIT_SCOPE,
                now.minusSeconds(60),
                null);

        mockMvc.perform(get("/api/v1/access/accounts")
                        .queryParam("query", OUTSIDE_SCOPE_USERNAME)
                        .with(user(ADMIN_PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.total").value(0));

        mockMvc.perform(get(
                        "/api/v1/access/accounts/{accountId}",
                        OUTSIDE_SCOPE_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("RESOURCE_NOT_AVAILABLE"));

        mockMvc.perform(patch(
                        "/api/v1/access/accounts/{accountId}/status",
                        OUTSIDE_SCOPE_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {
                                   "status":"DISABLED",
                                   "reason":"跨公司状态修改负例",
                                   "expectedVersion":0
                                 }
                                 """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("RESOURCE_NOT_AVAILABLE"));

        assertReasonActionUnavailable("lock");
        assertReasonActionUnavailable("unlock");
        assertReasonActionUnavailable("password-reset-grants");

        mockMvc.perform(put(
                        "/api/v1/access/accounts/{accountId}/role-assignments",
                        OUTSIDE_SCOPE_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {
                                   "assignments":[{
                                     "roleId":"%s",
                                     "scopeType":"COMPANY",
                                     "scopeResourceId":"%s",
                                     "validFrom":"%s",
                                     "validTo":null
                                   }],
                                   "reason":"跨公司角色替换负例",
                                   "expectedVersion":0
                                 }
                                 """.formatted(
                                SYSTEM_ADMIN_ROLE,
                                COMPANY_ONE,
                                now.plusSeconds(60))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("RESOURCE_NOT_AVAILABLE"));

        assertThat(jdbc.queryForObject(
                """
                SELECT status
                FROM local_account
                WHERE account_id = ?
                """,
                String.class,
                OUTSIDE_SCOPE_ACCOUNT)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                """
                SELECT row_version
                FROM local_account
                WHERE account_id = ?
                """,
                Long.class,
                OUTSIDE_SCOPE_ACCOUNT)).isZero();
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM password_reset_grant
                WHERE account_id = ?
                """,
                Long.class,
                OUTSIDE_SCOPE_ACCOUNT)).isZero();
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM auth_principal_role_assignment
                WHERE principal_id = ?
                """,
                Long.class,
                OUTSIDE_SCOPE_PRINCIPAL)).isZero();
    }

    @Test
    void expiredActorScopeCannotCoverAnAccountEvenWhenItsAssignmentIsCurrent()
            throws Exception {
        Instant now = Instant.now();
        insertCompanyScope(
                EXPIRED_ACTOR_SCOPE,
                COMPANY_ONE,
                now.minusSeconds(120),
                now.minusSeconds(60));
        jdbc.update(
                """
                UPDATE auth_principal_role_assignment
                SET data_scope_id = ?
                WHERE principal_id = ?
                  AND role_id = ?
                """,
                EXPIRED_ACTOR_SCOPE,
                ADMIN_PRINCIPAL,
                ADMIN_ROLE);

        assertReadUnavailable(STANDARD_ACCOUNT);
    }

    @Test
    void readOnlyScopeCannotBorrowAdministrativeCapabilitiesFromAnotherEntity()
            throws Exception {
        Instant now = Instant.now();
        insertCompanyScope(
                READ_ONLY_SPLIT_SCOPE,
                COMPANY_TWO,
                now.minusSeconds(60),
                null);
        insertRoleAssignment(
                "aa000000-0000-0000-0000-000000000002",
                ADMIN_PRINCIPAL,
                READER_ROLE,
                READ_ONLY_SPLIT_SCOPE,
                now.minusSeconds(60),
                null);

        mockMvc.perform(get(
                        "/api/v1/access/accounts/{accountId}",
                        OUTSIDE_SCOPE_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId")
                        .value(OUTSIDE_SCOPE_ACCOUNT));

        mockMvc.perform(patch(
                        "/api/v1/access/accounts/{accountId}/status",
                        OUTSIDE_SCOPE_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {
                                   "status":"DISABLED",
                                   "reason":"只读范围借用写能力负例",
                                   "expectedVersion":0
                                 }
                                 """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("RESOURCE_NOT_AVAILABLE"));

        assertReasonActionUnavailable("lock");
        assertReasonActionUnavailable("unlock");
        assertReasonActionUnavailable("password-reset-grants");

        mockMvc.perform(put(
                        "/api/v1/access/accounts/{accountId}/role-assignments",
                        OUTSIDE_SCOPE_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {
                                   "assignments":[{
                                     "roleId":"%s",
                                     "scopeType":"COMPANY",
                                     "scopeResourceId":"%s",
                                     "validFrom":"%s",
                                     "validTo":null
                                   }],
                                   "reason":"只读范围借用角色能力负例",
                                   "expectedVersion":0
                                 }
                                 """.formatted(
                                SYSTEM_ADMIN_ROLE,
                                COMPANY_ONE,
                                now.plusSeconds(60))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("RESOURCE_NOT_AVAILABLE"));

        assertThat(jdbc.queryForObject(
                """
                SELECT status
                FROM local_account
                WHERE account_id = ?
                """,
                String.class,
                OUTSIDE_SCOPE_ACCOUNT)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                """
                SELECT row_version
                FROM local_account
                WHERE account_id = ?
                """,
                Long.class,
                OUTSIDE_SCOPE_ACCOUNT)).isZero();
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM password_reset_grant
                WHERE account_id = ?
                """,
                Long.class,
                OUTSIDE_SCOPE_ACCOUNT)).isZero();
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM auth_principal_role_assignment
                WHERE principal_id = ?
                """,
                Long.class,
                OUTSIDE_SCOPE_PRINCIPAL)).isZero();
    }

    @Test
    void expiredTargetRoleCannotKeepAnUnboundAccountVisible()
            throws Exception {
        jdbc.update(
                """
                UPDATE auth_principal_role_assignment
                SET valid_to = ?
                WHERE principal_id = ?
                """,
                Timestamp.from(Instant.now().minusSeconds(60)),
                LIMITED_PRINCIPAL);

        assertReadUnavailable(LIMITED_ACCOUNT);
    }

    @Test
    void expiredTargetScopeCannotKeepAnUnboundAccountVisible()
            throws Exception {
        Instant now = Instant.now();
        insertCompanyScope(
                EXPIRED_TARGET_SCOPE,
                COMPANY_ONE,
                now.minusSeconds(120),
                now.minusSeconds(60));
        jdbc.update(
                """
                UPDATE auth_principal_role_assignment
                SET data_scope_id = ?
                WHERE principal_id = ?
                """,
                EXPIRED_TARGET_SCOPE,
                LIMITED_PRINCIPAL);

        assertReadUnavailable(LIMITED_ACCOUNT);
    }

    private void assertReadUnavailable(String accountId) throws Exception {
        mockMvc.perform(get(
                        "/api/v1/access/accounts/{accountId}",
                        accountId)
                        .with(user(ADMIN_PRINCIPAL)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("RESOURCE_NOT_AVAILABLE"));
    }

    private void assertReasonActionUnavailable(String action) throws Exception {
        assertReasonActionUnavailable(OUTSIDE_SCOPE_ACCOUNT, action);
    }

    private void assertReasonActionUnavailable(
            String accountId,
            String action) throws Exception {
        mockMvc.perform(post(
                        "/api/v1/access/accounts/{accountId}/{action}",
                        accountId,
                        action)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"跨公司账号动作负例\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("RESOURCE_NOT_AVAILABLE"));
    }

    private void insertCompanyScope(
            String scopeId,
            String companyId,
            Instant validFrom,
            Instant validTo) {
        jdbc.update(
                """
                INSERT INTO auth_data_scope (
                    scope_id, scope_type, company_id, organization_id,
                    include_descendants, valid_from, valid_to
                ) VALUES (?, 'COMPANY', ?, NULL, TRUE, ?, ?)
                """,
                scopeId,
                companyId,
                Timestamp.from(validFrom),
                validTo == null ? null : Timestamp.from(validTo));
    }

    private void insertRoleAssignment(
            String assignmentId,
            String principalId,
            String roleId,
            String scopeId,
            Instant validFrom,
            Instant validTo) {
        jdbc.update(
                """
                INSERT INTO auth_principal_role_assignment (
                    assignment_id, principal_id, role_id, data_scope_id,
                    valid_from, valid_to, assigned_by, reason, row_version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, '账号目标授权负例', 0)
                """,
                assignmentId,
                principalId,
                roleId,
                scopeId,
                Timestamp.from(validFrom),
                validTo == null ? null : Timestamp.from(validTo),
                ADMIN_PRINCIPAL);
    }
}
