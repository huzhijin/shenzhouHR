package com.szsemicon.hr.wave1;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "SHENZHOUHR_DEV_PRINCIPAL_ID=",
        "shenzhouhr.development-principal.enabled=false",
        "shenzhouhr.security.login.max-failures=5",
        "shenzhouhr.security.login.failure-window=PT15M",
        "shenzhouhr.security.login.lock-duration=PT30M",
        "shenzhouhr.security.session.cookie-secure=true",
        "shenzhouhr.security.session.idle-timeout=PT30M",
        "shenzhouhr.security.session.absolute-timeout=PT8H"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE, printOnlyOnFailure = false)
@ActiveProfiles("test")
@Transactional
abstract class Wave1IntegrationTestSupport {

    static final String ADMIN_PRINCIPAL = "81000000-0000-0000-0000-000000000001";
    static final String FIRST_CHANGE_PRINCIPAL = "81000000-0000-0000-0000-000000000002";
    static final String STANDARD_PRINCIPAL = "81000000-0000-0000-0000-000000000003";
    static final String OUTSIDE_SCOPE_PRINCIPAL = "81000000-0000-0000-0000-000000000004";
    static final String LIMITED_PRINCIPAL = "81000000-0000-0000-0000-000000000005";

    static final String ADMIN_ACCOUNT = "82000000-0000-0000-0000-000000000001";
    static final String FIRST_CHANGE_ACCOUNT = "82000000-0000-0000-0000-000000000002";
    static final String STANDARD_ACCOUNT = "82000000-0000-0000-0000-000000000003";
    static final String OUTSIDE_SCOPE_ACCOUNT = "82000000-0000-0000-0000-000000000004";
    static final String LIMITED_ACCOUNT = "82000000-0000-0000-0000-000000000005";

    static final String ADMIN_USERNAME = "wave1_admin_synthetic";
    static final String FIRST_CHANGE_USERNAME = "wave1_first_change_synthetic";
    static final String STANDARD_USERNAME = "wave1_standard_synthetic";
    static final String OUTSIDE_SCOPE_USERNAME = "wave1_outside_scope_synthetic";
    static final String LIMITED_USERNAME = "wave1_limited_synthetic";

    static final String ADMIN_ROLE = "11000000-0000-0000-0000-000000000001";
    static final String READER_ROLE = "11000000-0000-0000-0000-000000000002";
    static final String LEGAL_ENTITY_SCOPE = "90000000-0000-0000-0000-000000000001";

    static final String POLICY_TEMPLATE = "83000000-0000-0000-0000-000000000001";
    static final String POLICY_PUBLISHED_VERSION = "84000000-0000-0000-0000-000000000001";
    static final String POLICY_DRAFT_VERSION = "84000000-0000-0000-0000-000000000002";
    static final String POLICY_CONFLICT_VERSION = "84000000-0000-0000-0000-000000000003";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    protected String adminPassword;
    protected String firstChangePassword;
    protected String standardPassword;
    protected String outsideScopePassword;
    protected String limitedPassword;

    @BeforeEach
    void resetWave1SyntheticState() {
        adminPassword = newTestSecret();
        firstChangePassword = newTestSecret();
        standardPassword = newTestSecret();
        outsideScopePassword = newTestSecret();
        limitedPassword = newTestSecret();

        insertPrincipal(ADMIN_PRINCIPAL, "b0000000-0000-0000-0000-000000000001");
        insertPrincipal(FIRST_CHANGE_PRINCIPAL, "b0000000-0000-0000-0000-000000000002");
        insertPrincipal(STANDARD_PRINCIPAL, "b0000000-0000-0000-0000-000000000003");
        insertPrincipal(OUTSIDE_SCOPE_PRINCIPAL, "b0000000-0000-0000-0000-000000000004");
        insertPrincipal(LIMITED_PRINCIPAL, null);

        insertRoleAssignment(
                "a1000000-0000-0000-0000-000000000001",
                ADMIN_PRINCIPAL,
                ADMIN_ROLE);
        insertRoleAssignment(
                "a1000000-0000-0000-0000-000000000002",
                LIMITED_PRINCIPAL,
                READER_ROLE);

        insertAccount(
                ADMIN_ACCOUNT, ADMIN_PRINCIPAL, ADMIN_USERNAME, "WAVE-1 合成管理员",
                false, adminPassword);
        insertAccount(
                FIRST_CHANGE_ACCOUNT, FIRST_CHANGE_PRINCIPAL, FIRST_CHANGE_USERNAME,
                "WAVE-1 合成首次改密账号", true, firstChangePassword);
        insertAccount(
                STANDARD_ACCOUNT, STANDARD_PRINCIPAL, STANDARD_USERNAME,
                "WAVE-1 合成普通账号", false, standardPassword);
        insertAccount(
                OUTSIDE_SCOPE_ACCOUNT, OUTSIDE_SCOPE_PRINCIPAL, OUTSIDE_SCOPE_USERNAME,
                "WAVE-1 合成范围外账号", false, outsideScopePassword);
        insertAccount(
                LIMITED_ACCOUNT, LIMITED_PRINCIPAL, LIMITED_USERNAME,
                "WAVE-1 合成只读账号", false, limitedPassword);

        seedPolicies();
    }

    protected AuthenticatedSession login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(username, password)))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                .andExpect(header().exists("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.sessionId").isString())
                .andReturn();

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        String csrfToken = result.getResponse().getHeader("X-CSRF-TOKEN");
        String responseBody = result.getResponse().getContentAsString();
        String sessionId = JsonPath.read(responseBody, "$.sessionId");
        return new AuthenticatedSession(
                setCookie.substring(0, setCookie.indexOf(';')),
                csrfToken,
                sessionId);
    }

    protected MockHttpServletRequestBuilder withSession(
            MockHttpServletRequestBuilder request,
            AuthenticatedSession session) {
        return request
                .header(HttpHeaders.COOKIE, session.cookie())
                .header("X-CSRF-TOKEN", session.csrfToken());
    }

    protected MockHttpServletRequestBuilder withSessionWithoutCsrf(
            MockHttpServletRequestBuilder request,
            AuthenticatedSession session) {
        return request.header(HttpHeaders.COOKIE, session.cookie());
    }

    protected String newTestSecret() {
        return "Qa-" + UUID.randomUUID() + "-A9!";
    }

    protected String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    protected long auditCountForResource(String resourceId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE resource_id_ref = ?",
                Long.class,
                resourceId);
        return count == null ? 0 : count;
    }

    private void insertPrincipal(String principalId, String employeeId) {
        jdbc.update(
                """
                INSERT INTO auth_principal (
                    principal_id, employee_id, status, created_at, row_version
                ) VALUES (?, ?, 'ACTIVE', CURRENT_TIMESTAMP, 0)
                """,
                principalId,
                employeeId);
    }

    private void insertRoleAssignment(String assignmentId, String principalId, String roleId) {
        jdbc.update(
                """
                INSERT INTO auth_principal_role_assignment (
                    assignment_id, principal_id, role_id, data_scope_id,
                    valid_from, valid_to, assigned_by, reason, row_version
                ) VALUES (?, ?, ?, ?, ?, NULL, ?, 'WAVE-1 SYNTHETIC TEST', 0)
                """,
                assignmentId,
                principalId,
                roleId,
                LEGAL_ENTITY_SCOPE,
                Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
                ADMIN_PRINCIPAL);
    }

    private void insertAccount(
            String accountId,
            String principalId,
            String username,
            String displayName,
            boolean firstChangeRequired,
            String password) {
        Timestamp now = Timestamp.from(Instant.parse("2026-07-20T00:00:00Z"));
        jdbc.update(
                """
                INSERT INTO local_account (
                    account_id, principal_id, username, normalized_username, display_name,
                    status, first_password_change_required, session_epoch, row_version,
                    created_by, created_at, updated_by, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, 0, 0, ?, ?, ?, ?)
                """,
                accountId,
                principalId,
                username,
                username.toLowerCase(),
                displayName,
                firstChangeRequired,
                ADMIN_PRINCIPAL,
                now,
                ADMIN_PRINCIPAL,
                now);
        jdbc.update(
                """
                INSERT INTO password_credential (
                    credential_id, account_id, password_hash, algorithm,
                    parameter_version, changed_at, row_version
                ) VALUES (?, ?, ?, 'BCRYPT', '2A_COST_4', ?, 0)
                """,
                accountId.replace("82000000", "82500000"),
                accountId,
                new BCryptPasswordEncoder(4).encode(password),
                now);
        jdbc.update(
                """
                INSERT INTO login_failure_window (
                    account_id, failure_count, row_version
                ) VALUES (?, 0, 0)
                """,
                accountId);
    }

    private void seedPolicies() {
        Timestamp now = Timestamp.from(Instant.parse("2026-07-20T00:00:00Z"));
        jdbc.update(
                """
                INSERT INTO policy_template (
                    template_id, template_code, name, description, field_definitions_json,
                    status, row_version, created_by, created_at, updated_by, updated_at
                ) VALUES (?, 'WAVE1_APPROVAL_MODE', '合成审批模式',
                    '仅用于 WAVE-1 自动化测试的受控枚举模板', ?,
                    'ACTIVE', 0, ?, ?, ?, ?)
                """,
                POLICY_TEMPLATE,
                """
                [{"key":"approvalMode","label":"审批模式","valueType":"ENUM",
                  "required":true,"enumValues":["MANUAL","AUTOMATIC"]}]
                """,
                ADMIN_PRINCIPAL,
                now,
                ADMIN_PRINCIPAL,
                now);
        insertPolicyVersion(
                POLICY_PUBLISHED_VERSION, 1, "PUBLISHED",
                Date.valueOf("2026-01-01"), Date.valueOf("2026-05-31"),
                0, now);
        insertPolicyVersion(
                POLICY_DRAFT_VERSION, 2, "DRAFT",
                Date.valueOf("2027-01-01"), null,
                0, now);
        insertPolicyVersion(
                POLICY_CONFLICT_VERSION, 3, "DRAFT",
                Date.valueOf("2026-03-01"), Date.valueOf("2026-04-30"),
                0, now);
        insertScope(
                "85000000-0000-0000-0000-000000000001",
                POLICY_PUBLISHED_VERSION,
                "2026-01-01",
                "2026-05-31");
        insertScope(
                "85000000-0000-0000-0000-000000000002",
                POLICY_DRAFT_VERSION,
                "2027-01-01",
                null);
        insertScope(
                "85000000-0000-0000-0000-000000000003",
                POLICY_CONFLICT_VERSION,
                "2026-03-01",
                "2026-04-30");
    }

    private void insertPolicyVersion(
            String versionId,
            int number,
            String status,
            Date effectiveFrom,
            Date effectiveTo,
            long rowVersion,
            Timestamp now) {
        jdbc.update(
                """
                INSERT INTO policy_version (
                    version_id, template_id, version_number, status, parameters_json,
                    effective_from, effective_to, change_reason, validation_json,
                    snapshot_json, snapshot_digest, rollback_of_version_id, row_version,
                    created_by, created_at, published_at, updated_by, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'WAVE-1 SYNTHETIC CHANGE',
                    ?, NULL, NULL, NULL, ?, ?, ?, ?, ?, ?)
                """,
                versionId,
                POLICY_TEMPLATE,
                number,
                status,
                "[{\"key\":\"approvalMode\",\"value\":\"MANUAL\"}]",
                effectiveFrom,
                effectiveTo,
                "{\"valid\":true,\"issues\":[]}",
                rowVersion,
                ADMIN_PRINCIPAL,
                now,
                "PUBLISHED".equals(status) ? now : null,
                ADMIN_PRINCIPAL,
                now);
    }

    private void insertScope(
            String bindingId,
            String versionId,
            String effectiveFrom,
            String effectiveTo) {
        jdbc.update(
                """
                INSERT INTO policy_scope_binding (
                    binding_id, version_id, scope_type, scope_resource_id,
                    priority, effective_from, effective_to, row_version
                ) VALUES (?, ?, 'COMPANY', '30000000-0000-0000-0000-000000000001',
                    100, ?, ?, 0)
                """,
                bindingId,
                versionId,
                Date.valueOf(effectiveFrom),
                effectiveTo == null ? null : Date.valueOf(effectiveTo));
    }

    private String loginBody(String username, String password) {
        return """
               {"username":"%s","password":"%s"}
               """.formatted(username, password);
    }

    protected record AuthenticatedSession(String cookie, String csrfToken, String sessionId) {
    }
}
