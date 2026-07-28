package com.szsemicon.hr.identityaccess.infrastructure.bootstrap;

import com.szsemicon.hr.identityaccess.application.AccountPersistence;
import com.szsemicon.hr.identityaccess.application.AuthenticationPersistence;
import com.szsemicon.hr.identityaccess.application.IdentityAccessRepository.ResolvedRoleAssignmentInput;
import com.szsemicon.hr.shared.security.PasswordCodec;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("dev")
@ConditionalOnProperty(
        name = "shenzhouhr.bootstrap.enabled",
        havingValue = "true")
public class SyntheticAdminBootstrap implements ApplicationRunner {

    static final String SYNTHETIC_LEGAL_ENTITY_ID =
            "30000000-0000-0000-0000-000000000001";
    static final String SYSTEM_ADMIN_ROLE_ID =
            "10000000-0000-0000-0000-000000000002";
    static final String SYNTHETIC_LEGAL_ENTITY_SCOPE_ID =
            "39000000-0000-0000-0000-000000000001";
    static final String SYNTHETIC_POLICY_TEMPLATE_ID =
            "31000000-0000-0000-0000-000000000001";
    static final String SYNTHETIC_PUBLISHED_VERSION_ID =
            "32000000-0000-0000-0000-000000000001";
    static final String SYNTHETIC_DRAFT_VERSION_ID =
            "32000000-0000-0000-0000-000000000002";
    static final String SYNTHETIC_CONFLICT_VERSION_ID =
            "32000000-0000-0000-0000-000000000003";

    private final AccountPersistence accountPersistence;
    private final AuthenticationPersistence authenticationPersistence;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final String username;
    private final String credentialHash;

    public SyntheticAdminBootstrap(
            AccountPersistence accountPersistence,
            AuthenticationPersistence authenticationPersistence,
            JdbcTemplate jdbc,
            Clock clock,
            PasswordCodec passwordCodec,
            @Value("${SHENZHOUHR_BOOTSTRAP_ADMIN_USERNAME:}") String username,
            @Value("${SHENZHOUHR_BOOTSTRAP_ADMIN_PASSWORD:}") String suppliedSecret) {
        this.accountPersistence = accountPersistence;
        this.authenticationPersistence = authenticationPersistence;
        this.jdbc = jdbc;
        this.clock = clock;
        this.username = username == null ? "" : username.trim();
        validateInput(passwordCodec, suppliedSecret);
        this.credentialHash = passwordCodec.encode(suppliedSecret);
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        Instant now = clock.instant();
        String normalizedUsername = username.toLowerCase(Locale.ROOT);
        var existing = authenticationPersistence.findAccountByNormalizedUsername(
                normalizedUsername);
        if (existing.isPresent()) {
            var account = existing.orElseThrow();
            authenticationPersistence.replaceCredential(
                    account.accountId(),
                    credentialHash,
                    false,
                    account.principalId(),
                    now);
            authenticationPersistence.revokeAllSessions(
                    account.accountId(),
                    account.principalId(),
                    "DEV_SYNTHETIC_BOOTSTRAP_RESET",
                    "dev-bootstrap",
                    now);
            ensureSyntheticPolicyData(account.principalId(), now);
            return;
        }

        ensureSyntheticLegalEntity();
        String accountId = accountPersistence.createAccount(
                username,
                normalizedUsername,
                "WAVE-1 本地合成管理员",
                null,
                credentialHash,
                "DEV_SYNTHETIC_BOOTSTRAP",
                now);
        ensureSyntheticLegalEntityScope(now);
        accountPersistence.replaceRoleAssignments(
                accountId,
                0,
                List.of(new ResolvedRoleAssignmentInput(
                        SYSTEM_ADMIN_ROLE_ID,
                        SYNTHETIC_LEGAL_ENTITY_SCOPE_ID,
                        now,
                        null)),
                "DEV_SYNTHETIC_BOOTSTRAP",
                "WAVE-1 synthetic local administrator",
                now);
        String principalId = authenticationPersistence
                .findAccountByNormalizedUsername(normalizedUsername)
                .orElseThrow()
                .principalId();
        ensureSyntheticPolicyData(principalId, now);
    }

    private void ensureSyntheticLegalEntity() {
        jdbc.update(
                """
                INSERT INTO legal_entity (
                    legal_entity_id, code, name, status
                )
                SELECT ?, 'WAVE1_SYNTHETIC', 'WAVE-1 合成开发法人', 'ACTIVE'
                WHERE NOT EXISTS (
                    SELECT 1 FROM legal_entity WHERE legal_entity_id = ?
                )
                """,
                SYNTHETIC_LEGAL_ENTITY_ID,
                SYNTHETIC_LEGAL_ENTITY_ID);
    }

    private void ensureSyntheticLegalEntityScope(Instant now) {
        jdbc.update(
                """
                INSERT INTO auth_data_scope (
                    scope_id, scope_type, legal_entity_id, organization_id,
                    include_descendants, valid_from, valid_to
                )
                SELECT ?, 'LEGAL_ENTITY', ?, NULL, TRUE, ?, NULL
                WHERE NOT EXISTS (
                    SELECT 1 FROM auth_data_scope WHERE scope_id = ?
                )
                """,
                SYNTHETIC_LEGAL_ENTITY_SCOPE_ID,
                SYNTHETIC_LEGAL_ENTITY_ID,
                Timestamp.from(now),
                SYNTHETIC_LEGAL_ENTITY_SCOPE_ID);
    }

    private void ensureSyntheticPolicyData(String principalId, Instant now) {
        Timestamp timestamp = Timestamp.from(now);
        jdbc.update(
                """
                INSERT INTO policy_template (
                    template_id, template_code, name, description, field_definitions_json,
                    status, row_version, created_by, created_at, updated_by, updated_at
                )
                SELECT ?, 'WAVE1_ATTENDANCE_APPROVAL', '考勤异常审批规则',
                    'WAVE-1 本机真实联调使用的合成审批规则',
                    ?, 'ACTIVE', 0, ?, ?, ?, ?
                WHERE NOT EXISTS (
                    SELECT 1 FROM policy_template WHERE template_id = ?
                )
                """,
                SYNTHETIC_POLICY_TEMPLATE_ID,
                """
                [{"key":"approvalMode","label":"审批模式","valueType":"ENUM",
                  "required":true,"enumValues":["MANUAL","AUTOMATIC"]}]
                """,
                principalId,
                timestamp,
                principalId,
                timestamp,
                SYNTHETIC_POLICY_TEMPLATE_ID);
        insertSyntheticPolicyVersion(
                SYNTHETIC_PUBLISHED_VERSION_ID,
                1,
                "PUBLISHED",
                "2026-01-01",
                "2026-12-31",
                "WAVE-1 合成已发布基线",
                principalId,
                timestamp);
        insertSyntheticPolicyVersion(
                SYNTHETIC_DRAFT_VERSION_ID,
                2,
                "DRAFT",
                "2027-01-01",
                null,
                "WAVE-1 合成待发布草稿",
                principalId,
                timestamp);
        insertSyntheticPolicyVersion(
                SYNTHETIC_CONFLICT_VERSION_ID,
                3,
                "DRAFT",
                "2026-07-01",
                "2026-09-30",
                "WAVE-1 合成冲突草稿",
                principalId,
                timestamp);
        insertSyntheticScope(
                "33000000-0000-0000-0000-000000000001",
                SYNTHETIC_PUBLISHED_VERSION_ID,
                "2026-01-01",
                "2026-12-31");
        insertSyntheticScope(
                "33000000-0000-0000-0000-000000000002",
                SYNTHETIC_DRAFT_VERSION_ID,
                "2027-01-01",
                null);
        insertSyntheticScope(
                "33000000-0000-0000-0000-000000000003",
                SYNTHETIC_CONFLICT_VERSION_ID,
                "2026-07-01",
                "2026-09-30");
        jdbc.update(
                """
                INSERT INTO policy_publication_record (
                    publication_id, template_id, version_id, action, reason,
                    actor_id, request_id, result, occurred_at, snapshot_digest
                )
                SELECT '34000000-0000-0000-0000-000000000001', ?, ?, 'PUBLISH',
                    'WAVE-1 合成已发布基线', ?, 'wave1-synthetic-bootstrap',
                    'SUCCESS', ?, ?
                WHERE NOT EXISTS (
                    SELECT 1 FROM policy_publication_record
                    WHERE publication_id = '34000000-0000-0000-0000-000000000001'
                )
                """,
                SYNTHETIC_POLICY_TEMPLATE_ID,
                SYNTHETIC_PUBLISHED_VERSION_ID,
                principalId,
                timestamp,
                "b77e951e21b442f7e69d6970515881a333c3ff71c84f62a887047215584339b0");
    }

    private void insertSyntheticPolicyVersion(
            String versionId,
            int versionNumber,
            String status,
            String effectiveFrom,
            String effectiveTo,
            String changeReason,
            String principalId,
            Timestamp timestamp) {
        jdbc.update(
                """
                INSERT INTO policy_version (
                    version_id, template_id, version_number, status, parameters_json,
                    effective_from, effective_to, change_reason, validation_json,
                    snapshot_json, snapshot_digest, rollback_of_version_id, row_version,
                    created_by, created_at, published_at, updated_by, updated_at
                )
                SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, NULL, 0, ?, ?, ?, ?, ?
                WHERE NOT EXISTS (
                    SELECT 1 FROM policy_version WHERE version_id = ?
                )
                """,
                versionId,
                SYNTHETIC_POLICY_TEMPLATE_ID,
                versionNumber,
                status,
                "[{\"key\":\"approvalMode\",\"value\":\"MANUAL\"}]",
                Date.valueOf(effectiveFrom),
                effectiveTo == null ? null : Date.valueOf(effectiveTo),
                changeReason,
                "{\"valid\":true,\"issues\":[]}",
                "PUBLISHED".equals(status)
                        ? "{\"parameters\":[{\"key\":\"approvalMode\",\"value\":\"MANUAL\"}]}"
                        : null,
                "PUBLISHED".equals(status)
                        ? "b77e951e21b442f7e69d6970515881a333c3ff71c84f62a887047215584339b0"
                        : null,
                principalId,
                timestamp,
                "PUBLISHED".equals(status) ? timestamp : null,
                principalId,
                timestamp,
                versionId);
    }

    private void insertSyntheticScope(
            String bindingId,
            String versionId,
            String effectiveFrom,
            String effectiveTo) {
        jdbc.update(
                """
                INSERT INTO policy_scope_binding (
                    binding_id, version_id, scope_type, scope_resource_id,
                    priority, effective_from, effective_to, row_version
                )
                SELECT ?, ?, 'COMPANY', ?, 100, ?, ?, 0
                WHERE NOT EXISTS (
                    SELECT 1 FROM policy_scope_binding WHERE binding_id = ?
                )
                """,
                bindingId,
                versionId,
                SYNTHETIC_LEGAL_ENTITY_ID,
                Date.valueOf(effectiveFrom),
                effectiveTo == null ? null : Date.valueOf(effectiveTo),
                bindingId);
    }

    private void validateInput(
            PasswordCodec passwordCodec,
            String suppliedSecret) {
        if (username.isBlank()
                || !username.toLowerCase(Locale.ROOT).contains("synthetic")) {
            throw new IllegalStateException(
                    "SHENZHOUHR_BOOTSTRAP_ADMIN_USERNAME must identify a synthetic account");
        }
        if (!passwordCodec.meetsPolicy(suppliedSecret)) {
            throw new IllegalStateException(
                    "SHENZHOUHR_BOOTSTRAP_ADMIN_PASSWORD does not meet the password policy");
        }
    }
}
