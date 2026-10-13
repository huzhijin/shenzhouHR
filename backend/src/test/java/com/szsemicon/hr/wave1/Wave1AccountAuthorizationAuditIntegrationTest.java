package com.szsemicon.hr.wave1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MvcResult;

class Wave1AccountAuthorizationAuditIntegrationTest extends Wave1IntegrationTestSupport {

    private static final String SYSTEM_ADMIN_ROLE =
            "10000000-0000-0000-0000-000000000002";
    private static final String EXECUTIVE_ROLE =
            "10000000-0000-0000-0000-000000000004";
    private static final String EMPLOYEE_SELF_ROLE =
            "10000000-0000-0000-0000-000000000006";
    private static final String PROVISIONING_RECOVERY_KEY = "A".repeat(43);
    private static final String DIFFERENT_PROVISIONING_RECOVERY_KEY = "B".repeat(43);

    @Test
    void employeeAccountProvisioningRequiresBothCapabilitiesAndCreatesSelfAccount()
            throws Exception {
        insertEmployeeSelfRole();
        ProvisioningCandidate candidate = insertProvisioningCandidate(
                "W1-BULK-" + UUID.randomUUID().toString().substring(0, 8));
        String idempotencyKey = provisioningIdempotencyKey();

        mockMvc.perform(get("/api/v1/access/account-provisioning/candidates")
                        .with(user(LIMITED_PRINCIPAL))
                        .param("companyId", candidate.companyId())
                        .param("query", candidate.employeeNumber()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/access/account-provisioning/accounts")
                        .with(user(LIMITED_PRINCIPAL))
                        .with(csrf())
                        .header("Idempotency-Key", idempotencyKey)
                        .header("Provisioning-Recovery-Key", PROVISIONING_RECOVERY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(employeeIdsBody(candidate.employeeId())))
                .andExpect(status().isForbidden());
        assertThat(accountCountForEmployee(candidate.employeeId())).isZero();

        mockMvc.perform(get("/api/v1/access/account-provisioning/candidates")
                        .with(user(ADMIN_PRINCIPAL))
                        .param("companyId", candidate.companyId())
                        .param("query", candidate.employeeNumber()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.available").value(1))
                .andExpect(jsonPath("$.alreadyProvisioned").value(0))
                .andExpect(jsonPath("$.usernameConflicts").value(0))
                .andExpect(jsonPath("$.items[0].employeeId")
                        .value(candidate.employeeId()))
                .andExpect(jsonPath("$.items[0].employeeNumber")
                        .value(candidate.employeeNumber()))
                .andExpect(jsonPath("$.items[0].status").value("AVAILABLE"));

        MvcResult result = mockMvc.perform(post(
                                "/api/v1/access/account-provisioning/accounts")
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header("Idempotency-Key", idempotencyKey)
                        .header("Provisioning-Recovery-Key", PROVISIONING_RECOVERY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(employeeIdsBody(candidate.employeeId())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist("Idempotency-Replayed"))
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.credentials[0].employeeId")
                        .value(candidate.employeeId()))
                .andExpect(jsonPath("$.credentials[0].username")
                        .value(candidate.employeeNumber()))
                .andExpect(jsonPath("$.credentials[0].temporaryPassword").isNotEmpty())
                .andReturn();

        String temporaryPassword = JsonPath.read(
                result.getResponse().getContentAsString(),
                "$.credentials[0].temporaryPassword");
        Map<String, Object> account = jdbc.queryForMap(
                """
                SELECT account.account_id,
                       account.username,
                       account.first_password_change_required,
                       credential.password_hash
                FROM auth_principal principal
                JOIN local_account account
                  ON account.principal_id = principal.principal_id
                JOIN password_credential credential
                  ON credential.account_id = account.account_id
                WHERE principal.employee_id = ?
                """,
                candidate.employeeId());
        assertThat(account.get("USERNAME")).isEqualTo(candidate.employeeNumber());
        assertThat(account.get("FIRST_PASSWORD_CHANGE_REQUIRED")).isEqualTo(true);
        assertThat(new BCryptPasswordEncoder().matches(
                temporaryPassword,
                (String) account.get("PASSWORD_HASH"))).isTrue();

        Map<String, Object> assignment = jdbc.queryForMap(
                """
                SELECT role.role_code,
                       scope.scope_type,
                       scope.company_id,
                       scope.organization_id
                FROM auth_principal principal
                JOIN auth_principal_role_assignment assignment
                  ON assignment.principal_id = principal.principal_id
                JOIN auth_role role ON role.role_id = assignment.role_id
                JOIN auth_data_scope scope ON scope.scope_id = assignment.data_scope_id
                WHERE principal.employee_id = ?
                """,
                candidate.employeeId());
        assertThat(assignment.get("ROLE_CODE")).isEqualTo("EMPLOYEE_SELF");
        assertThat(assignment.get("SCOPE_TYPE")).isEqualTo("SELF");
        assertThat(assignment.get("COMPANY_ID")).isNull();
        assertThat(assignment.get("ORGANIZATION_ID")).isNull();
        assertThat(jdbc.queryForObject(
                """
                SELECT reason_code
                FROM audit_event
                WHERE action_code = 'EMPLOYEE_ACCOUNTS_BULK_CREATED'
                ORDER BY occurred_at DESC
                LIMIT 1
                """,
                String.class)).isEqualTo("created=1");
        Map<String, Object> idempotency = jdbc.queryForMap(
                """
                SELECT action_code, request_digest, resource_id, result_json
                FROM people_idempotency_record
                WHERE actor_id = ? AND idempotency_key = ?
                """,
                ADMIN_PRINCIPAL,
                idempotencyKey);
        assertThat(idempotency.get("ACTION_CODE"))
                .isEqualTo("EMPLOYEE_ACCOUNTS_BULK_CREATE");
        assertThat(idempotency.get("REQUEST_DIGEST").toString()).hasSize(64);
        assertThat(idempotency.get("RESOURCE_ID")).isNull();
        String storedBindings = idempotency.get("RESULT_JSON").toString();
        assertThat(storedBindings)
                .contains(candidate.employeeId(), account.get("ACCOUNT_ID").toString())
                .doesNotContain(temporaryPassword);
    }

    @Test
    void employeeAccountProvisioningRejectsOversizedAndConflictingRequests()
            throws Exception {
        insertEmployeeSelfRole();
        ProvisioningCandidate candidate = insertProvisioningCandidate(
                "W1-CONFLICT-" + UUID.randomUUID().toString().substring(0, 8));
        String conflictKey = provisioningIdempotencyKey();
        jdbc.update(
                """
                UPDATE local_account
                SET username = ?, normalized_username = ?
                WHERE account_id = ?
                """,
                candidate.employeeNumber(),
                candidate.employeeNumber().toLowerCase(),
                STANDARD_ACCOUNT);

        mockMvc.perform(get("/api/v1/access/account-provisioning/candidates")
                        .with(user(ADMIN_PRINCIPAL))
                        .param("companyId", candidate.companyId())
                        .param("query", candidate.employeeNumber()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(0))
                .andExpect(jsonPath("$.usernameConflicts").value(1))
                .andExpect(jsonPath("$.items[0].status")
                        .value("USERNAME_CONFLICT"));
        mockMvc.perform(post("/api/v1/access/account-provisioning/accounts")
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header("Idempotency-Key", conflictKey)
                        .header("Provisioning-Recovery-Key", PROVISIONING_RECOVERY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(employeeIdsBody(candidate.employeeId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_PROVISIONING_CONFLICT"));
        assertThat(accountCountForEmployee(candidate.employeeId())).isZero();

        String repeatedId = "\"" + candidate.employeeId() + "\"";
        String oversizedBody = "{\"employeeIds\":["
                + String.join(",", Collections.nCopies(21, repeatedId))
                + "]}";
        mockMvc.perform(post("/api/v1/access/account-provisioning/accounts")
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header("Idempotency-Key", provisioningIdempotencyKey())
                        .header("Provisioning-Recovery-Key", PROVISIONING_RECOVERY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversizedBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(accountCountForEmployee(candidate.employeeId())).isZero();
    }

    @Test
    void employeeAccountProvisioningReplayUsesCanonicalIdsAndConfirmsSameCredentials()
            throws Exception {
        insertEmployeeSelfRole();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ProvisioningCandidate first = insertProvisioningCandidate(
                "W1-REPLAY-A-" + suffix);
        ProvisioningCandidate second = insertProvisioningCandidate(
                "W1-REPLAY-B-" + suffix);
        String idempotencyKey = provisioningIdempotencyKey();

        MvcResult initial = mockMvc.perform(post(
                                "/api/v1/access/account-provisioning/accounts")
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header("Idempotency-Key", idempotencyKey)
                        .header("Provisioning-Recovery-Key", PROVISIONING_RECOVERY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(employeeIdsBody(
                                second.employeeId(), first.employeeId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn();

        List<String> initialAccountIds = JsonPath.read(
                initial.getResponse().getContentAsString(),
                "$.credentials[*].accountId");
        List<String> initialPasswords = JsonPath.read(
                initial.getResponse().getContentAsString(),
                "$.credentials[*].temporaryPassword");

        MvcResult replay = mockMvc.perform(post(
                                "/api/v1/access/account-provisioning/accounts")
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header("Idempotency-Key", idempotencyKey)
                        .header("Provisioning-Recovery-Key", PROVISIONING_RECOVERY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(employeeIdsBody(
                                first.employeeId(), second.employeeId())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().doesNotExist("Idempotency-Replayed"))
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.replayed").value(true))
                .andReturn();

        List<String> replayAccountIds = JsonPath.read(
                replay.getResponse().getContentAsString(),
                "$.credentials[*].accountId");
        List<String> replayPasswords = JsonPath.read(
                replay.getResponse().getContentAsString(),
                "$.credentials[*].temporaryPassword");
        assertThat(replayAccountIds).containsExactlyElementsOf(initialAccountIds);
        assertThat(replayPasswords).containsExactlyElementsOf(initialPasswords);
        for (int index = 0; index < replayAccountIds.size(); index++) {
            String storedHash = jdbc.queryForObject(
                    "SELECT password_hash FROM password_credential WHERE account_id = ?",
                    String.class,
                    replayAccountIds.get(index));
            assertThat(new BCryptPasswordEncoder().matches(
                    replayPasswords.get(index), storedHash)).isTrue();
            assertThat(new BCryptPasswordEncoder().matches(
                    initialPasswords.get(index), storedHash)).isTrue();
        }
        assertThat(accountCountForEmployee(first.employeeId())).isOne();
        assertThat(accountCountForEmployee(second.employeeId())).isOne();
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM people_idempotency_record
                WHERE actor_id = ? AND action_code = ? AND idempotency_key = ?
                  AND result_json IS NOT NULL
                """,
                Long.class,
                ADMIN_PRINCIPAL,
                "EMPLOYEE_ACCOUNTS_BULK_CREATE",
                idempotencyKey)).isOne();
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM audit_event
                WHERE action_code = 'EMPLOYEE_ACCOUNTS_BULK_CREATED'
                """,
                Long.class)).isOne();
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM audit_event
                WHERE action_code = 'EMPLOYEE_ACCOUNT_CREDENTIAL_RECOVERED'
                """,
                Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                """
                SELECT reason_code FROM audit_event
                WHERE action_code = 'EMPLOYEE_ACCOUNTS_BULK_REPLAY_CONFIRMED'
                ORDER BY occurred_at DESC LIMIT 1
                """,
                String.class)).isEqualTo("confirmed=2");
    }

    @Test
    void employeeAccountProvisioningRejectsIdempotencyKeyReuseWithDifferentIds()
            throws Exception {
        insertEmployeeSelfRole();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ProvisioningCandidate original = insertProvisioningCandidate(
                "W1-IDEM-A-" + suffix);
        ProvisioningCandidate different = insertProvisioningCandidate(
                "W1-IDEM-B-" + suffix);
        String idempotencyKey = provisioningIdempotencyKey();

        MvcResult initial = mockMvc.perform(post(
                                "/api/v1/access/account-provisioning/accounts")
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header("Idempotency-Key", idempotencyKey)
                        .header("Provisioning-Recovery-Key", PROVISIONING_RECOVERY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(employeeIdsBody(original.employeeId())))
                .andExpect(status().isOk())
                .andReturn();
        String accountId = JsonPath.read(
                initial.getResponse().getContentAsString(),
                "$.credentials[0].accountId");
        String passwordHash = jdbc.queryForObject(
                "SELECT password_hash FROM password_credential WHERE account_id = ?",
                String.class,
                accountId);

        mockMvc.perform(post("/api/v1/access/account-provisioning/accounts")
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header("Idempotency-Key", idempotencyKey)
                        .header("Provisioning-Recovery-Key", PROVISIONING_RECOVERY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(employeeIdsBody(different.employeeId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(
                        "IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST"));

        mockMvc.perform(post("/api/v1/access/account-provisioning/accounts")
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header("Idempotency-Key", idempotencyKey)
                        .header(
                                "Provisioning-Recovery-Key",
                                DIFFERENT_PROVISIONING_RECOVERY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(employeeIdsBody(original.employeeId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(
                        "IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST"));

        assertThat(accountCountForEmployee(different.employeeId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT password_hash FROM password_credential WHERE account_id = ?",
                String.class,
                accountId)).isEqualTo(passwordHash);
    }

    @Test
    void employeeAccountProvisioningRefusesRecoveryAfterAnyAccountWasUsedOrChanged()
            throws Exception {
        insertEmployeeSelfRole();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ProvisioningCandidate first = insertProvisioningCandidate(
                "W1-USED-A-" + suffix);
        ProvisioningCandidate second = insertProvisioningCandidate(
                "W1-USED-B-" + suffix);
        String idempotencyKey = provisioningIdempotencyKey();

        MvcResult initial = mockMvc.perform(post(
                                "/api/v1/access/account-provisioning/accounts")
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header("Idempotency-Key", idempotencyKey)
                        .header("Provisioning-Recovery-Key", PROVISIONING_RECOVERY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(employeeIdsBody(first.employeeId(), second.employeeId())))
                .andExpect(status().isOk())
                .andReturn();
        List<String> accountIds = JsonPath.read(
                initial.getResponse().getContentAsString(),
                "$.credentials[*].accountId");
        List<String> originalHashes = accountIds.stream()
                .map(accountId -> jdbc.queryForObject(
                        "SELECT password_hash FROM password_credential WHERE account_id = ?",
                        String.class,
                        accountId))
                .toList();
        jdbc.update(
                "UPDATE local_account SET last_login_at = CURRENT_TIMESTAMP WHERE account_id = ?",
                accountIds.getLast());

        mockMvc.perform(post("/api/v1/access/account-provisioning/accounts")
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header("Idempotency-Key", idempotencyKey)
                        .header("Provisioning-Recovery-Key", PROVISIONING_RECOVERY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(employeeIdsBody(second.employeeId(), first.employeeId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(
                        "ACCOUNT_PROVISIONING_RECOVERY_UNAVAILABLE"));

        jdbc.update(
                """
                UPDATE local_account
                SET last_login_at = NULL, status = 'DISABLED'
                WHERE account_id = ?
                """,
                accountIds.getLast());
        assertRecoveryUnavailable(
                idempotencyKey, second.employeeId(), first.employeeId());

        jdbc.update(
                """
                UPDATE local_account
                SET status = 'ACTIVE', first_password_change_required = FALSE
                WHERE account_id = ?
                """,
                accountIds.getLast());
        assertRecoveryUnavailable(
                idempotencyKey, first.employeeId(), second.employeeId());

        List<String> hashesAfterRejectedRecovery = accountIds.stream()
                .map(accountId -> jdbc.queryForObject(
                        "SELECT password_hash FROM password_credential WHERE account_id = ?",
                        String.class,
                        accountId))
                .toList();
        assertThat(hashesAfterRejectedRecovery)
                .containsExactlyElementsOf(originalHashes);
    }

    @Test
    void employeeAccountProvisioningRefusesRecoveryAfterRoleEscalationOrFutureGrant()
            throws Exception {
        insertEmployeeSelfRole();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ProvisioningCandidate escalated = insertProvisioningCandidate(
                "W1-ROLE-A-" + suffix);
        ProvisioningCandidate futureGrant = insertProvisioningCandidate(
                "W1-ROLE-B-" + suffix);
        String escalatedKey = provisioningIdempotencyKey();
        String futureKey = provisioningIdempotencyKey();

        MvcResult escalatedInitial = provisionEmployeeAccount(
                escalatedKey, escalated.employeeId());
        MvcResult futureInitial = provisionEmployeeAccount(
                futureKey, futureGrant.employeeId());
        String escalatedAccountId = JsonPath.read(
                escalatedInitial.getResponse().getContentAsString(),
                "$.credentials[0].accountId");
        String futureAccountId = JsonPath.read(
                futureInitial.getResponse().getContentAsString(),
                "$.credentials[0].accountId");
        String escalatedPrincipalId = jdbc.queryForObject(
                "SELECT principal_id FROM local_account WHERE account_id = ?",
                String.class,
                escalatedAccountId);
        String futurePrincipalId = jdbc.queryForObject(
                "SELECT principal_id FROM local_account WHERE account_id = ?",
                String.class,
                futureAccountId);
        String escalatedHash = passwordHash(escalatedAccountId);
        String futureHash = passwordHash(futureAccountId);

        jdbc.update(
                """
                UPDATE auth_principal_role_assignment
                SET role_id = ?, data_scope_id = ?, row_version = row_version + 1
                WHERE principal_id = ? AND valid_to IS NULL
                """,
                SYSTEM_ADMIN_ROLE,
                COMPANY_SCOPE,
                escalatedPrincipalId);
        jdbc.update(
                """
                INSERT INTO auth_principal_role_assignment (
                    assignment_id, principal_id, role_id, data_scope_id,
                    valid_from, valid_to, assigned_by, reason, row_version
                ) VALUES (?, ?, ?, ?, ?, NULL, ?, ?, 0)
                """,
                UUID.randomUUID().toString(),
                futurePrincipalId,
                SYSTEM_ADMIN_ROLE,
                COMPANY_SCOPE,
                Timestamp.from(Instant.now().plusSeconds(86_400)),
                ADMIN_PRINCIPAL,
                "FUTURE PRIVILEGE TEST");

        assertRecoveryUnavailable(escalatedKey, escalated.employeeId());
        assertRecoveryUnavailable(futureKey, futureGrant.employeeId());

        assertThat(passwordHash(escalatedAccountId)).isEqualTo(escalatedHash);
        assertThat(passwordHash(futureAccountId)).isEqualTo(futureHash);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM audit_event
                WHERE action_code = 'EMPLOYEE_ACCOUNTS_BULK_REPLAY_CONFIRMED'
                """,
                Long.class)).isZero();
    }

    @Test
    void employeeAccountProvisioningReplayRechecksCurrentDatabaseAuthority()
            throws Exception {
        insertEmployeeSelfRole();
        ProvisioningCandidate candidate = insertProvisioningCandidate(
                "W1-REVOKE-" + UUID.randomUUID().toString().substring(0, 8));
        String idempotencyKey = provisioningIdempotencyKey();
        MvcResult initial = provisionEmployeeAccount(
                idempotencyKey, candidate.employeeId());
        String accountId = JsonPath.read(
                initial.getResponse().getContentAsString(),
                "$.credentials[0].accountId");
        String originalHash = passwordHash(accountId);

        jdbc.update(
                """
                UPDATE auth_principal_role_assignment
                SET valid_to = ?, row_version = row_version + 1
                WHERE principal_id = ? AND valid_to IS NULL
                """,
                Timestamp.from(Instant.now().minusSeconds(1)),
                ADMIN_PRINCIPAL);

        mockMvc.perform(post("/api/v1/access/account-provisioning/accounts")
                        .with(user(ADMIN_PRINCIPAL).authorities(
                                new SimpleGrantedAuthority("ACCOUNT:CREATE"),
                                new SimpleGrantedAuthority("ROLE:ASSIGN")))
                        .with(csrf())
                        .header("Idempotency-Key", idempotencyKey)
                        .header("Provisioning-Recovery-Key", PROVISIONING_RECOVERY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(employeeIdsBody(candidate.employeeId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(
                        "ACCESS_DENIED"));

        assertThat(passwordHash(accountId)).isEqualTo(originalHash);
    }

    @Test
    void employeeAccountProvisioningRecoveryExpiresWithoutChangingPassword()
            throws Exception {
        insertEmployeeSelfRole();
        ProvisioningCandidate candidate = insertProvisioningCandidate(
                "W1-EXPIRE-" + UUID.randomUUID().toString().substring(0, 8));
        String idempotencyKey = provisioningIdempotencyKey();
        MvcResult initial = provisionEmployeeAccount(
                idempotencyKey, candidate.employeeId());
        String accountId = JsonPath.read(
                initial.getResponse().getContentAsString(),
                "$.credentials[0].accountId");
        String originalHash = passwordHash(accountId);
        jdbc.update(
                """
                UPDATE people_idempotency_record
                SET created_at = ?
                WHERE actor_id = ? AND action_code = ? AND idempotency_key = ?
                """,
                Timestamp.from(Instant.now().minusSeconds(7_200)),
                ADMIN_PRINCIPAL,
                "EMPLOYEE_ACCOUNTS_BULK_CREATE",
                idempotencyKey);

        assertRecoveryUnavailable(idempotencyKey, candidate.employeeId());
        assertThat(passwordHash(accountId)).isEqualTo(originalHash);
    }

    @Test
    void employeeAccountProvisioningReplayOnlyRotatesOriginallyBoundAccounts()
            throws Exception {
        insertEmployeeSelfRole();
        ProvisioningCandidate candidate = insertProvisioningCandidate(
                "W1-REBIND-" + UUID.randomUUID().toString().substring(0, 8));
        String idempotencyKey = provisioningIdempotencyKey();

        MvcResult initial = mockMvc.perform(post(
                                "/api/v1/access/account-provisioning/accounts")
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header("Idempotency-Key", idempotencyKey)
                        .header("Provisioning-Recovery-Key", PROVISIONING_RECOVERY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(employeeIdsBody(candidate.employeeId())))
                .andExpect(status().isOk())
                .andReturn();
        String originalAccountId = JsonPath.read(
                initial.getResponse().getContentAsString(),
                "$.credentials[0].accountId");
        String originalPrincipalId = jdbc.queryForObject(
                "SELECT principal_id FROM local_account WHERE account_id = ?",
                String.class,
                originalAccountId);
        String originalHash = jdbc.queryForObject(
                "SELECT password_hash FROM password_credential WHERE account_id = ?",
                String.class,
                originalAccountId);
        String replacementHash = jdbc.queryForObject(
                "SELECT password_hash FROM password_credential WHERE account_id = ?",
                String.class,
                STANDARD_ACCOUNT);

        jdbc.update(
                "UPDATE auth_principal SET employee_id = NULL WHERE principal_id = ?",
                originalPrincipalId);
        jdbc.update(
                "UPDATE auth_principal SET employee_id = ? WHERE principal_id = ?",
                candidate.employeeId(),
                STANDARD_PRINCIPAL);

        mockMvc.perform(post("/api/v1/access/account-provisioning/accounts")
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header("Idempotency-Key", idempotencyKey)
                        .header("Provisioning-Recovery-Key", PROVISIONING_RECOVERY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(employeeIdsBody(candidate.employeeId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(
                        "ACCOUNT_PROVISIONING_RECOVERY_UNAVAILABLE"));

        assertThat(jdbc.queryForObject(
                "SELECT password_hash FROM password_credential WHERE account_id = ?",
                String.class,
                originalAccountId)).isEqualTo(originalHash);
        assertThat(jdbc.queryForObject(
                "SELECT password_hash FROM password_credential WHERE account_id = ?",
                String.class,
                STANDARD_ACCOUNT)).isEqualTo(replacementHash);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM audit_event
                WHERE action_code = 'EMPLOYEE_ACCOUNT_CREDENTIAL_RECOVERED'
                """,
                Long.class)).isZero();
    }

    @Test
    void employeeAccountProvisioningRequiresAValidIdempotencyKey()
            throws Exception {
        ProvisioningCandidate candidate = insertProvisioningCandidate(
                "W1-IDEM-VALIDATE-"
                        + UUID.randomUUID().toString().substring(0, 8));

        mockMvc.perform(post("/api/v1/access/account-provisioning/accounts")
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(employeeIdsBody(candidate.employeeId())))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/access/account-provisioning/accounts")
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header("Idempotency-Key", "too-short")
                        .header("Provisioning-Recovery-Key", PROVISIONING_RECOVERY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(employeeIdsBody(candidate.employeeId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(post("/api/v1/access/account-provisioning/accounts")
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header("Idempotency-Key", provisioningIdempotencyKey())
                        .header("Provisioning-Recovery-Key", "predictable")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(employeeIdsBody(candidate.employeeId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

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
    void everyAccountCreationRequiresAnExplicitStrongTemporaryPassword()
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

        mockMvc.perform(post("/api/v1/access/accounts")
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
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        String ordinaryPassword = newTestSecret();
        mockMvc.perform(post("/api/v1/access/accounts")
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {
                                   "username":"%s",
                                   "displayName":"WAVE-1 普通账号",
                                   "temporaryPassword":"%s",
                                   "roleAssignments":[{
                                     "roleId":"%s",
                                     "scopeType":"COMPANY",
                                     "scopeResourceId":"30000000-0000-0000-0000-000000000001",
                                     "validFrom":"2026-07-20T00:00:00Z",
                                     "validTo":null
                                   }]
                                 }
                                 """.formatted(
                                ordinaryUsername,
                                ordinaryPassword,
                                EXECUTIVE_ROLE)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.firstPasswordChangeRequired").value(true));

        String ordinaryHash = jdbc.queryForObject(
                """
                SELECT credential.password_hash
                FROM password_credential credential
                JOIN local_account account ON account.account_id = credential.account_id
                WHERE account.normalized_username = ?
                """,
                String.class,
                ordinaryUsername);
        assertThat(new BCryptPasswordEncoder().matches(ordinaryPassword, ordinaryHash))
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
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(post("/api/v1/access/accounts")
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(privilegedBody.formatted(
                                "wave1_privileged_weak_" + UUID.randomUUID(),
                                "\"temporaryPassword\":\"123456\",",
                                SYSTEM_ADMIN_ROLE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
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
    void administratorDirectResetRequiresStrongPasswordRevokesSessionsAndInvalidatesGrants()
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

        mockMvc.perform(post(
                                "/api/v1/access/accounts/{accountId}/temporary-password-reset",
                                STANDARD_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"管理员直接重置\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        String resetPassword = newTestSecret();
        mockMvc.perform(post(
                                "/api/v1/access/accounts/{accountId}/temporary-password-reset",
                                STANDARD_ACCOUNT)
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                 {
                                   "temporaryPassword":"%s",
                                   "reason":"管理员设置独立临时密码"
                                 }
                                 """.formatted(resetPassword)))
                .andExpect(status().isNoContent());
        String storedHash = jdbc.queryForObject(
                "SELECT password_hash FROM password_credential WHERE account_id = ?",
                String.class,
                STANDARD_ACCOUNT);
        assertThat(new BCryptPasswordEncoder().matches(resetPassword, storedHash)).isTrue();
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
                STANDARD_ACCOUNT)).isEqualTo("管理员设置独立临时密码");
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
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

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

    private void insertEmployeeSelfRole() {
        jdbc.update(
                """
                INSERT INTO auth_role (
                    role_id, role_code, role_name, permission_domain
                ) VALUES (?, 'EMPLOYEE_SELF', '员工本人', 'IDENTITY')
                """,
                EMPLOYEE_SELF_ROLE);
    }

    private ProvisioningCandidate insertProvisioningCandidate(String employeeNumber) {
        String employeeId = UUID.randomUUID().toString();
        String employeeVersionId = UUID.randomUUID().toString();
        String assignmentId = UUID.randomUUID().toString();
        String companyId = "30000000-0000-0000-0000-000000000001";
        jdbc.update(
                """
                INSERT INTO employee (
                    employee_id, company_id, display_name, employment_status,
                    employee_number
                ) VALUES (?, ?, 'WAVE-1 批量账号候选人', 'ACTIVE', ?)
                """,
                employeeId,
                companyId,
                employeeNumber);
        jdbc.update(
                """
                INSERT INTO employee_version (
                    employee_version_id, employee_id, employee_number, display_name,
                    status, effective_from, effective_to, source_authority,
                    row_version, change_reason, created_at
                ) VALUES (?, ?, ?, 'WAVE-1 批量账号候选人', 'ACTIVE',
                    DATE '2020-01-01', NULL, 'LOCAL', 0,
                    'WAVE-1 ACCOUNT PROVISIONING TEST', CURRENT_TIMESTAMP)
                """,
                employeeVersionId,
                employeeId,
                employeeNumber);
        jdbc.update(
                """
                INSERT INTO employee_current_projection (
                    employee_id, current_version_id, projected_at
                ) VALUES (?, ?, CURRENT_TIMESTAMP)
                """,
                employeeId,
                employeeVersionId);
        jdbc.update(
                """
                INSERT INTO employment_assignment (
                    assignment_id, employee_id, organization_id,
                    effective_from, effective_to, employment_period_id,
                    record_status, version_valid_to
                ) VALUES (?, ?, '40000000-0000-0000-0000-000000000002',
                    TIMESTAMP '2020-01-01 00:00:00', NULL, ?, 'ACTIVE', NULL)
                """,
                assignmentId,
                employeeId,
                assignmentId);
        return new ProvisioningCandidate(employeeId, companyId, employeeNumber);
    }

    private long accountCountForEmployee(String employeeId) {
        Long count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM auth_principal principal
                JOIN local_account account
                  ON account.principal_id = principal.principal_id
                WHERE principal.employee_id = ?
                """,
                Long.class,
                employeeId);
        return count == null ? 0 : count;
    }

    private MvcResult provisionEmployeeAccount(
            String idempotencyKey,
            String employeeId) throws Exception {
        return mockMvc.perform(post("/api/v1/access/account-provisioning/accounts")
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header("Idempotency-Key", idempotencyKey)
                        .header("Provisioning-Recovery-Key", PROVISIONING_RECOVERY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(employeeIdsBody(employeeId)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private String passwordHash(String accountId) {
        return jdbc.queryForObject(
                "SELECT password_hash FROM password_credential WHERE account_id = ?",
                String.class,
                accountId);
    }

    private void assertRecoveryUnavailable(
            String idempotencyKey,
            String... employeeIds) throws Exception {
        mockMvc.perform(post("/api/v1/access/account-provisioning/accounts")
                        .with(user(ADMIN_PRINCIPAL))
                        .with(csrf())
                        .header("Idempotency-Key", idempotencyKey)
                        .header("Provisioning-Recovery-Key", PROVISIONING_RECOVERY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(employeeIdsBody(employeeIds)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(
                        "ACCOUNT_PROVISIONING_RECOVERY_UNAVAILABLE"));
    }

    private static String employeeIdsBody(String... employeeIds) {
        return "{\"employeeIds\":["
                + String.join(",", java.util.Arrays.stream(employeeIds)
                        .map(employeeId -> "\"" + employeeId + "\"")
                        .toList())
                + "]}";
    }

    private static String provisioningIdempotencyKey() {
        return "wave1-account-provision-" + UUID.randomUUID();
    }

    private record ProvisioningCandidate(
            String employeeId,
            String companyId,
            String employeeNumber) {
    }
}
