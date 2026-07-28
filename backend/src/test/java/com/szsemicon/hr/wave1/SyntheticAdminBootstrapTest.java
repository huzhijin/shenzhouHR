package com.szsemicon.hr.wave1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.szsemicon.hr.identityaccess.application.AccountPersistence;
import com.szsemicon.hr.identityaccess.application.AuthenticationPersistence;
import com.szsemicon.hr.identityaccess.infrastructure.bootstrap.SyntheticAdminBootstrap;
import com.szsemicon.hr.shared.security.PasswordCodec;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class SyntheticAdminBootstrapTest extends Wave1IntegrationTestSupport {

    private static final String SYSTEM_ADMIN_ROLE =
            "10000000-0000-0000-0000-000000000002";

    @Autowired
    private AccountPersistence accountPersistence;

    @Autowired
    private AuthenticationPersistence authenticationPersistence;

    @Autowired
    private Clock clock;

    @Autowired
    private PasswordCodec passwordCodec;

    @Test
    void bootstrapConditionRequiresDevAndTheExplicitSwitch() {
        bootstrapContext()
                .withPropertyValues("shenzhouhr.bootstrap.enabled=true")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(SyntheticAdminBootstrap.class));

        bootstrapContext()
                .withPropertyValues(
                        "spring.profiles.active=dev",
                        "shenzhouhr.bootstrap.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(SyntheticAdminBootstrap.class));

        bootstrapContext()
                .withPropertyValues(
                        "spring.profiles.active=dev",
                        "shenzhouhr.bootstrap.enabled=true",
                        "SHENZHOUHR_BOOTSTRAP_ADMIN_USERNAME=" + syntheticUsername(),
                        "SHENZHOUHR_BOOTSTRAP_ADMIN_PASSWORD=" + newTestSecret())
                .run(context -> assertThat(context)
                        .hasSingleBean(SyntheticAdminBootstrap.class));
    }

    @Test
    void enabledDevBootstrapCreatesSyntheticSystemAdminWithOnlyBcryptHash() throws Exception {
        String username = syntheticUsername();
        String password = newTestSecret();

        runBootstrap(username, password);

        BootstrapAccount account = findBootstrapAccount(username);
        assertThat(account.status()).isEqualTo("ACTIVE");
        assertThat(account.firstChangeRequired()).isTrue();
        assertThat(account.passwordHash())
                .isNotBlank()
                .isNotEqualTo(password)
                .startsWith("$2");
        assertThat(new BCryptPasswordEncoder().matches(password, account.passwordHash())).isTrue();
        assertThat(account.algorithm()).isEqualTo("BCRYPT");
        assertThat(account.roleCode()).isEqualTo("SYSTEM_ADMIN");
        assertThat(account.roleId()).isEqualTo(SYSTEM_ADMIN_ROLE);
        assertSyntheticPolicyData();
    }

    @Test
    void repeatedBootstrapReplacesHashAndRevokesEveryOldSession() throws Exception {
        String username = syntheticUsername();
        String firstPassword = newTestSecret();
        runBootstrap(username, firstPassword);
        BootstrapAccount initial = findBootstrapAccount(username);

        String sessionId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO user_session (
                    session_id, account_id, token_digest, status, created_at, last_seen_at,
                    idle_expires_at, absolute_expires_at, request_id, row_version
                ) VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?, ?, 'bootstrap-test', 0)
                """,
                sessionId,
                initial.accountId(),
                sha256(UUID.randomUUID().toString()),
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now().plus(30, ChronoUnit.MINUTES)),
                Timestamp.from(Instant.now().plus(8, ChronoUnit.HOURS)));

        String replacementPassword = newTestSecret();
        runBootstrap(username, replacementPassword);
        BootstrapAccount replaced = findBootstrapAccount(username);

        assertThat(replaced.accountId()).isEqualTo(initial.accountId());
        assertThat(replaced.passwordHash()).isNotEqualTo(initial.passwordHash());
        assertThat(new BCryptPasswordEncoder()
                .matches(replacementPassword, replaced.passwordHash())).isTrue();
        assertThat(new BCryptPasswordEncoder()
                .matches(firstPassword, replaced.passwordHash())).isFalse();
        assertThat(replaced.firstChangeRequired()).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM user_session WHERE session_id = ?",
                String.class,
                sessionId)).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM session_revocation WHERE session_id = ?",
                Long.class,
                sessionId)).isEqualTo(1);
        assertSyntheticPolicyData();
    }

    @Test
    void missingOrNonSyntheticUsernameAndWeakPasswordFailWithoutEchoingPassword() {
        String compliantPassword = newTestSecret();
        assertRejectedWithoutPassword("", compliantPassword);
        assertRejectedWithoutPassword(
                "wave1_admin_" + UUID.randomUUID().toString().replace("-", ""),
                compliantPassword);

        String weakPassword = "weak-" + UUID.randomUUID();
        assertRejectedWithoutPassword(syntheticUsername(), weakPassword);
    }

    private void assertRejectedWithoutPassword(String username, String password) {
        assertThatThrownBy(() -> runBootstrap(username, password))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(error -> assertThat(error.getMessage())
                        .doesNotContain(password));
    }

    private void runBootstrap(String username, String password) throws Exception {
        new SyntheticAdminBootstrap(
                        accountPersistence,
                        authenticationPersistence,
                        jdbc,
                        clock,
                        passwordCodec,
                        username,
                        password)
                .run(new DefaultApplicationArguments(new String[0]));
    }

    private BootstrapAccount findBootstrapAccount(String username) {
        return jdbc.queryForObject(
                """
                SELECT account.account_id, account.status,
                       account.first_password_change_required,
                       credential.password_hash, credential.algorithm,
                       role.role_id, role.role_code
                FROM local_account account
                JOIN password_credential credential
                  ON credential.account_id = account.account_id
                JOIN auth_principal_role_assignment assignment
                  ON assignment.principal_id = account.principal_id
                JOIN auth_role role ON role.role_id = assignment.role_id
                WHERE account.normalized_username = ?
                """,
                (result, rowNumber) -> new BootstrapAccount(
                        result.getString("account_id"),
                        result.getString("status"),
                        result.getBoolean("first_password_change_required"),
                        result.getString("password_hash"),
                        result.getString("algorithm"),
                        result.getString("role_id"),
                        result.getString("role_code")),
                username.toLowerCase());
    }

    private void assertSyntheticPolicyData() {
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM policy_template
                WHERE template_code = 'WAVE1_ATTENDANCE_APPROVAL'
                """,
                Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM policy_version
                WHERE template_id = '31000000-0000-0000-0000-000000000001'
                """,
                Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM policy_scope_binding
                WHERE version_id IN (
                    '32000000-0000-0000-0000-000000000001',
                    '32000000-0000-0000-0000-000000000002',
                    '32000000-0000-0000-0000-000000000003'
                )
                """,
                Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM policy_publication_record
                WHERE publication_id = '34000000-0000-0000-0000-000000000001'
                """,
                Long.class)).isEqualTo(1);
    }

    private String syntheticUsername() {
        return "wave1_bootstrap_synthetic_"
                + UUID.randomUUID().toString().replace("-", "");
    }

    private ApplicationContextRunner bootstrapContext() {
        return new ApplicationContextRunner()
                .withUserConfiguration(BootstrapOnlyConfiguration.class)
                .withBean(AccountPersistence.class, () -> mock(AccountPersistence.class))
                .withBean(
                        AuthenticationPersistence.class,
                        () -> mock(AuthenticationPersistence.class))
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(Clock.class, Clock::systemUTC)
                .withBean(PasswordCodec.class, PasswordCodec::new);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(SyntheticAdminBootstrap.class)
    static class BootstrapOnlyConfiguration {
    }

    private record BootstrapAccount(
            String accountId,
            String status,
            boolean firstChangeRequired,
            String passwordHash,
            String algorithm,
            String roleId,
            String roleCode) {
    }
}
