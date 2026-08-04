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

import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

class Wave1AccountAuthorizationAuditIntegrationTest extends Wave1IntegrationTestSupport {

    private static final String SYSTEM_ADMIN_ROLE =
            "10000000-0000-0000-0000-000000000002";
    private static final String EXECUTIVE_ROLE =
            "10000000-0000-0000-0000-000000000004";

    @Test
    void accountCreationHashesTheTemporaryPasswordAndNeverReturnsSecrets() throws Exception {
        String accountUsername = "wave1_created_" + UUID.randomUUID().toString().replace("-", "");
        String temporaryPassword = newTestSecret();
        long auditBefore = jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class);

        MvcResult result = mockMvc.perform(post("/api/v1/access/accounts")
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {
                                   "username":"%s",
                                   "displayName":"WAVE-1 合成新账号",
                                   "temporaryPassword":"%s",
                                   "roleAssignments":[{
                                     "roleId":"%s",
                                     "scopeType":"COMPANY",
                                     "scopeResourceId":"30000000-0000-0000-0000-000000000001",
                                     "validFrom":"2026-07-20T00:00:00Z",
                                     "validTo":null
                                   }]
                                 }
                                 """.formatted(accountUsername, temporaryPassword, SYSTEM_ADMIN_ROLE)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(accountUsername))
                .andExpect(jsonPath("$.firstPasswordChangeRequired").value(true))
                .andReturn();

        String body = result.getResponse().getContentAsString().toLowerCase();
        assertThat(body).doesNotContain(
                temporaryPassword.toLowerCase(),
                "temporarypassword",
                "passwordhash",
                "sessiontoken",
                "resettoken");

        String storedHash = jdbc.queryForObject(
                """
                SELECT credential.password_hash
                FROM password_credential credential
                JOIN local_account account ON account.account_id = credential.account_id
                WHERE account.normalized_username = ?
                """,
                String.class,
                accountUsername.toLowerCase());
        assertThat(storedHash)
                .isNotBlank()
                .isNotEqualTo(temporaryPassword);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class))
                .isGreaterThan(auditBefore);
    }

    @Test
    void ordinaryAccountCreationUsesServerDefaultAndPrivilegedCreationRequiresStrongPassword()
            throws Exception {
        jdbc.update(
                """
                INSERT INTO auth_role (
                    role_id, role_code, role_name, permission_domain
                ) VALUES (?, 'EXECUTIVE', '高管', 'HR')
                """,
                EXECUTIVE_ROLE);
        String ordinaryUsername =
                "wave1_ordinary_" + UUID.randomUUID().toString().replace("-", "");

        MvcResult ordinary = mockMvc.perform(post("/api/v1/access/accounts")
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {
                                   "username":"%s",
                                   "displayName":"WAVE-1 普通账号",
                                   "roleAssignments":[{
                                     "roleId":"%s",
                                     "scopeType":"COMPANY",
                                     "scopeResourceId":"30000000-0000-0000-0000-000000000001",
                                     "validFrom":"2026-07-20T00:00:00Z",
                                     "validTo":null
                                   }]
                                 }
                                 """.formatted(ordinaryUsername, EXECUTIVE_ROLE)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.firstPasswordChangeRequired").value(true))
                .andReturn();

        assertThat(ordinary.getResponse().getContentAsString().toLowerCase())
                .doesNotContain("123456", "temporarypassword", "passwordhash");
        String ordinaryHash = jdbc.queryForObject(
                """
                SELECT credential.password_hash
                FROM password_credential credential
                JOIN local_account account ON account.account_id = credential.account_id
                WHERE account.normalized_username = ?
                """,
                String.class,
                ordinaryUsername);
        assertThat(new BCryptPasswordEncoder().matches("123456", ordinaryHash))
                .isTrue();

        String privilegedBody = """
                {
                  "username":"%s",
                  "displayName":"WAVE-1 高权限账号",
                  %s
                  "roleAssignments":[{
                    "roleId":"%s",
                    "scopeType":"COMPANY",
                    "scopeResourceId":"30000000-0000-0000-0000-000000000001",
                    "validFrom":"2026-07-20T00:00:00Z",
                    "validTo":null
                  }]
                }
                """;
        mockMvc.perform(post("/api/v1/access/accounts")
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(privilegedBody.formatted(
                                "wave1_privileged_missing_" + UUID.randomUUID(),
                                "",
                                SYSTEM_ADMIN_ROLE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_POLICY_VIOLATION"));
        mockMvc.perform(post("/api/v1/access/accounts")
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(privilegedBody.formatted(
                                "wave1_privileged_weak_" + UUID.randomUUID(),
                                "\"temporaryPassword\":\"123456\",",
                                SYSTEM_ADMIN_ROLE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_POLICY_VIOLATION"));
    }

    @Test
    void writeCapabilityIsCheckedBeforeMutation() throws Exception {
        String originalStatus = jdbc.queryForObject(
                "SELECT status FROM local_account WHERE account_id = ?",
                String.class,
                STANDARD_ACCOUNT);

        mockMvc.perform(patch("/api/v1/access/accounts/{accountId}/status", STANDARD_ACCOUNT)
                        .with(user(LIMITED_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {
                                   "status":"DISABLED",
                                   "reason":"WAVE-1 合成越权尝试",
                                   "expectedVersion":0
                                 }
                                 """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        assertThat(jdbc.queryForObject(
                "SELECT status FROM local_account WHERE account_id = ?",
                String.class,
                STANDARD_ACCOUNT)).isEqualTo(originalStatus);
    }

    @Test
    void outOfScopeAndMissingAccountAreIndistinguishable() throws Exception {
        MvcResult outsideScope = mockMvc.perform(
                        get("/api/v1/access/accounts/{accountId}", OUTSIDE_SCOPE_ACCOUNT)
                                .with(user(LIMITED_PRINCIPAL)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_AVAILABLE"))
                .andReturn();
        MvcResult missing = mockMvc.perform(
                        get("/api/v1/access/accounts/{accountId}",
                                        "82000000-0000-0000-0000-999999999999")
                                .with(user(LIMITED_PRINCIPAL)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_AVAILABLE"))
                .andReturn();

        assertThat(JsonPath.<String>read(
                        outsideScope.getResponse().getContentAsString(),
                        "$.message"))
                .isEqualTo(JsonPath.read(
                        missing.getResponse().getContentAsString(),
                        "$.message"));
    }

    @Test
    void lockUnlockAndDisableRevokeSessionsAndAppendAudit() throws Exception {
        AuthenticatedSession session = login(STANDARD_USERNAME, standardPassword);
        long auditBefore = auditCountForResource(STANDARD_ACCOUNT);

        mockMvc.perform(post("/api/v1/access/accounts/{accountId}/lock", STANDARD_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"WAVE-1 合成安全锁定\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(withSessionWithoutCsrf(get("/api/v1/auth/session"), session))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject(
                "SELECT status FROM local_account WHERE account_id = ?",
                String.class,
                STANDARD_ACCOUNT)).isEqualTo("LOCKED");

        mockMvc.perform(post("/api/v1/access/accounts/{accountId}/unlock", STANDARD_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"WAVE-1 合成管理员解锁\"}"))
                .andExpect(status().isNoContent());
        AuthenticatedSession replacement = login(STANDARD_USERNAME, standardPassword);

        long currentVersion = jdbc.queryForObject(
                "SELECT row_version FROM local_account WHERE account_id = ?",
                Long.class,
                STANDARD_ACCOUNT);
        mockMvc.perform(patch("/api/v1/access/accounts/{accountId}/status", STANDARD_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {
                                   "status":"DISABLED",
                                   "reason":"WAVE-1 合成停用",
                                   "expectedVersion":%d
                                 }
                                 """.formatted(currentVersion)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
        mockMvc.perform(withSessionWithoutCsrf(get("/api/v1/auth/session"), replacement))
                .andExpect(status().isUnauthorized());

        assertThat(auditCountForResource(STANDARD_ACCOUNT)).isGreaterThan(auditBefore);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM session_revocation WHERE account_id = ?
                """,
                Long.class,
                STANDARD_ACCOUNT)).isGreaterThanOrEqualTo(2);
    }

    @Test
    void roleAssignmentHonorsValidityAndOptimisticVersionAndIsAudited() throws Exception {
        long auditBefore = auditCountForResource(STANDARD_ACCOUNT);

        mockMvc.perform(put(
                        "/api/v1/access/accounts/{accountId}/role-assignments",
                        STANDARD_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {
                                   "assignments":[{
                                     "roleId":"%s",
                                     "scopeType":"COMPANY",
                                     "scopeResourceId":"30000000-0000-0000-0000-000000000001",
                                     "validFrom":"2026-08-01T00:00:00Z",
                                     "validTo":"2026-12-31T16:00:00Z"
                                   }],
                                   "reason":"WAVE-1 合成限时授权",
                                   "expectedVersion":0
                                 }
                                 """.formatted(SYSTEM_ADMIN_ROLE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0].roleId").value(SYSTEM_ADMIN_ROLE));

        Timestamp validTo = jdbc.queryForObject(
                """
                SELECT assignment.valid_to
                FROM auth_principal_role_assignment assignment
                WHERE assignment.principal_id = ? AND assignment.role_id = ?
                """,
                Timestamp.class,
                STANDARD_PRINCIPAL,
                SYSTEM_ADMIN_ROLE);
        assertThat(validTo).isNotNull();
        assertThat(auditCountForResource(STANDARD_ACCOUNT)).isGreaterThan(auditBefore);
    }

    @Test
    void passwordResetGrantIsDeliveredOutOfBandAndOnlyItsDigestIsStored() throws Exception {
        MvcResult result = mockMvc.perform(post(
                        "/api/v1/access/accounts/{accountId}/password-reset-grants",
                        STANDARD_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"WAVE-1 合成密码恢复\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(true))
                .andReturn();

        assertThat(result.getResponse().getContentAsString().toLowerCase())
                .doesNotContain("grant", "token", "password");
        String digest = jdbc.queryForObject(
                """
                SELECT token_digest FROM password_reset_grant
                WHERE account_id = ? ORDER BY created_at DESC LIMIT 1
                """,
                String.class,
                STANDARD_ACCOUNT);
        assertThat(digest).hasSize(64);
    }

    @Test
    void administratorDirectResetUsesDefaultRevokesSessionsAndInvalidatesGrants()
            throws Exception {
        AuthenticatedSession oldSession = login(STANDARD_USERNAME, standardPassword);
        jdbc.update(
                """
                INSERT INTO password_reset_grant (
                    grant_id, account_id, token_digest, expires_at, used_at,
                    issued_by, request_id, created_at, row_version
                ) VALUES (?, ?, ?, ?, NULL, ?, ?, CURRENT_TIMESTAMP, 0)
                """,
                "86000000-0000-0000-0000-000000000011",
                STANDARD_ACCOUNT,
                sha256("unused-direct-reset-grant"),
                Timestamp.from(Instant.now().plusSeconds(600)),
                ADMIN_PRINCIPAL,
                "direct-reset-test");

        MvcResult result = mockMvc.perform(post(
                                "/api/v1/access/accounts/{accountId}/temporary-password-reset",
                                STANDARD_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"管理员直接重置\"}"))
                .andExpect(status().isNoContent())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEmpty();
        mockMvc.perform(post(
                                "/api/v1/access/accounts/{accountId}/temporary-password-reset",
                                STANDARD_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {
                                   "temporaryPassword":"123456",
                                   "reason":"普通账号显式携带默认密码"
                                 }
                                 """))
                .andExpect(status().isNoContent());
        String storedHash = jdbc.queryForObject(
                "SELECT password_hash FROM password_credential WHERE account_id = ?",
                String.class,
                STANDARD_ACCOUNT);
        assertThat(new BCryptPasswordEncoder().matches("123456", storedHash)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT first_password_change_required FROM local_account WHERE account_id = ?",
                Boolean.class,
                STANDARD_ACCOUNT)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT used_at FROM password_reset_grant WHERE grant_id = ?",
                Timestamp.class,
                "86000000-0000-0000-0000-000000000011")).isNotNull();
        mockMvc.perform(withSessionWithoutCsrf(get("/api/v1/auth/session"), oldSession))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject(
                """
                SELECT reason_code FROM audit_event
                WHERE resource_id_ref = ? AND action_code = 'TEMPORARY_PASSWORD_RESET'
                ORDER BY occurred_at DESC LIMIT 1
                """,
                String.class,
                STANDARD_ACCOUNT)).isEqualTo("普通账号显式携带默认密码");
    }

    @Test
    void defaultPasswordAccountCannotBeElevatedUntilAStrongTemporaryPasswordIsSet()
            throws Exception {
        jdbc.update(
                """
                UPDATE password_credential
                SET password_hash = ?, row_version = row_version + 1
                WHERE account_id = ?
                """,
                new BCryptPasswordEncoder(4).encode("123456"),
                STANDARD_ACCOUNT);
        jdbc.update(
                """
                UPDATE local_account
                SET first_password_change_required = TRUE
                WHERE account_id = ?
                """,
                STANDARD_ACCOUNT);

        mockMvc.perform(put(
                                "/api/v1/access/accounts/{accountId}/role-assignments",
                                STANDARD_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(roleAssignmentBody(0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(
                        "STRONG_TEMPORARY_PASSWORD_REQUIRED_FOR_PRIVILEGE_ELEVATION"));

        AuthenticatedSession firstChangeSession = login(STANDARD_USERNAME, "123456");
        String userPassword = newTestSecret();
        mockMvc.perform(withSession(
                        post("/api/v1/auth/password/first-change")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {
                                   "currentPassword":"123456",
                                   "newPassword":"%s"
                                 }
                                 """.formatted(userPassword)),
                        firstChangeSession))
                .andExpect(status().isNoContent());
        long rowVersion = jdbc.queryForObject(
                "SELECT row_version FROM local_account WHERE account_id = ?",
                Long.class,
                STANDARD_ACCOUNT);
        mockMvc.perform(put(
                                "/api/v1/access/accounts/{accountId}/role-assignments",
                                STANDARD_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(roleAssignmentBody(rowVersion)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0].roleId").value(SYSTEM_ADMIN_ROLE));

        mockMvc.perform(post(
                                "/api/v1/access/accounts/{accountId}/temporary-password-reset",
                                STANDARD_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"高权限账号禁止弱临时密码\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_POLICY_VIOLATION"));

        String strongTemporaryPassword = newTestSecret();
        mockMvc.perform(post(
                                "/api/v1/access/accounts/{accountId}/temporary-password-reset",
                                STANDARD_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {
                                   "temporaryPassword":"%s",
                                   "reason":"高权限账号强临时密码重置"
                                 }
                                 """.formatted(strongTemporaryPassword)))
                .andExpect(status().isNoContent());
        String privilegedHash = jdbc.queryForObject(
                "SELECT password_hash FROM password_credential WHERE account_id = ?",
                String.class,
                STANDARD_ACCOUNT);
        assertThat(new BCryptPasswordEncoder().matches(
                strongTemporaryPassword,
                privilegedHash)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT first_password_change_required FROM local_account WHERE account_id = ?",
                Boolean.class,
                STANDARD_ACCOUNT)).isTrue();
    }

    @Test
    void auditIsCapabilityProtectedReadOnlyAndKeepsCorrelationAndRequestIds() throws Exception {
        jdbc.update(
                """
                INSERT INTO audit_event (
                    event_id, occurred_at, actor_id_ref, actor_type, action_code,
                    resource_type, resource_id_ref, result_code, correlation_id,
                    request_id, event_hash
                ) VALUES (?, ?, ?, 'LOCAL_ACCOUNT', 'ACCOUNT_LOCKED',
                    'LOCAL_ACCOUNT', ?, 'SUCCESS', ?, ?, ?)
                """,
                "87000000-0000-0000-0000-000000000001",
                Timestamp.from(Instant.now()),
                ADMIN_PRINCIPAL,
                STANDARD_ACCOUNT,
                "wave1-correlation",
                "wave1-request",
                sha256("wave1-audit-event"));

        mockMvc.perform(get("/api/v1/access/audit-events")
                        .with(user(ADMIN_PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].correlationId").isNotEmpty());
        mockMvc.perform(get(
                        "/api/v1/access/audit-events/{auditEventId}",
                        "87000000-0000-0000-0000-000000000001")
                        .with(user(ADMIN_PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("wave1-request"));
        mockMvc.perform(get("/api/v1/access/audit-events")
                        .with(user(LIMITED_PRINCIPAL)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/access/audit-events")
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void payrollRemainsDefaultDeniedAndUndiscoverable() throws Exception {
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM auth_capability
                WHERE capability_code LIKE 'PAYROLL:%'
                """,
                Long.class)).isZero();

        mockMvc.perform(get("/api/v1/me/capabilities")
                        .with(user(ADMIN_PRINCIPAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capabilities").isArray())
                .andExpect(jsonPath("$.capabilities[?(@ =~ /PAYROLL:.*/)]").isEmpty());
        mockMvc.perform(get("/api/v1/payroll/runs")
                        .with(user(ADMIN_PRINCIPAL)))
                .andExpect(status().isNotFound());
    }

    private String roleAssignmentBody(long expectedVersion) {
        return """
                {
                  "assignments":[{
                    "roleId":"%s",
                    "scopeType":"COMPANY",
                    "scopeResourceId":"30000000-0000-0000-0000-000000000001",
                    "validFrom":"2026-07-20T00:00:00Z",
                    "validTo":null
                  }],
                  "reason":"高权限晋升验证",
                  "expectedVersion":%d
                }
                """.formatted(SYSTEM_ADMIN_ROLE, expectedVersion);
    }
}
