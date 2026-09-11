package com.szsemicon.hr.identityaccess.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "SHENZHOUHR_DEV_PRINCIPAL_ID=",
        "shenzhouhr.development-principal.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthenticationLoginCredentialLockConcurrencyTest {

    private static final String OLD_PASSWORD = "Old#Password12345";
    private static final String NEW_PASSWORD = "New#Password12345";
    private static final long LOGIN_COMPLETION_TIMEOUT_SECONDS = 30;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    private ExecutorService executor;
    private TransactionTemplate transactions;
    private String principalId;
    private String accountId;
    private String username;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
        transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        principalId = UUID.randomUUID().toString();
        accountId = UUID.randomUUID().toString();
        username = "lock-race-" + UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update(
                """
                INSERT INTO auth_principal (
                    principal_id, employee_id, status, created_at, row_version
                ) VALUES (?, NULL, 'ACTIVE', ?, 0)
                """,
                principalId,
                Timestamp.from(now));
        jdbc.update(
                """
                INSERT INTO local_account (
                    account_id, principal_id, username, normalized_username,
                    display_name, status, first_password_change_required,
                    session_epoch, row_version, created_by, created_at,
                    updated_by, updated_at
                ) VALUES (?, ?, ?, ?, '并发登录锁测试', 'ACTIVE', TRUE,
                    0, 0, ?, ?, ?, ?)
                """,
                accountId,
                principalId,
                username,
                username,
                principalId,
                Timestamp.from(now),
                principalId,
                Timestamp.from(now));
        jdbc.update(
                """
                INSERT INTO password_credential (
                    credential_id, account_id, password_hash, algorithm,
                    parameter_version, changed_at, row_version
                ) VALUES (?, ?, ?, 'BCRYPT', '2A_COST_4', ?, 0)
                """,
                UUID.randomUUID().toString(),
                accountId,
                new BCryptPasswordEncoder(4).encode(OLD_PASSWORD),
                Timestamp.from(now));
        jdbc.update(
                """
                INSERT INTO login_failure_window (
                    account_id, failure_count, row_version
                ) VALUES (?, 0, 0)
                """,
                accountId);
    }

    @AfterEach
    void tearDown() throws Exception {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        jdbc.update("DELETE FROM audit_event WHERE actor_id_ref = ?", principalId);
        jdbc.update("DELETE FROM session_revocation WHERE account_id = ?", accountId);
        jdbc.update("DELETE FROM user_session WHERE account_id = ?", accountId);
        jdbc.update("DELETE FROM login_failure_window WHERE account_id = ?", accountId);
        jdbc.update("DELETE FROM password_reset_grant WHERE account_id = ?", accountId);
        jdbc.update("DELETE FROM password_credential WHERE account_id = ?", accountId);
        jdbc.update("DELETE FROM local_account WHERE account_id = ?", accountId);
        jdbc.update("DELETE FROM auth_principal WHERE principal_id = ?", principalId);
    }

    @Test
    void loginReadsPasswordOnlyAfterAnAccountLockedRotationCommits()
            throws Exception {
        CountDownLatch accountLocked = new CountDownLatch(1);
        CountDownLatch allowRotation = new CountDownLatch(1);
        Future<?> rotation = executor.submit(() -> transactions.executeWithoutResult(status -> {
            jdbc.queryForObject(
                    "SELECT account_id FROM local_account WHERE account_id = ? FOR UPDATE",
                    String.class,
                    accountId);
            accountLocked.countDown();
            await(allowRotation);
            jdbc.update(
                    """
                    UPDATE password_credential
                    SET password_hash = ?, changed_at = ?, row_version = row_version + 1
                    WHERE account_id = ?
                    """,
                    new BCryptPasswordEncoder(4).encode(NEW_PASSWORD),
                    Timestamp.from(Instant.now()),
                    accountId);
        }));

        assertThat(accountLocked.await(5, TimeUnit.SECONDS)).isTrue();
        Future<Integer> login = executor.submit(() -> mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"username":"%s","password":"%s"}
                                        """.formatted(username, OLD_PASSWORD)))
                .andReturn()
                .getResponse()
                .getStatus());

        assertThatThrownBy(() -> login.get(250, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
        allowRotation.countDown();
        rotation.get(5, TimeUnit.SECONDS);

        assertThat(login.get(
                LOGIN_COMPLETION_TIMEOUT_SECONDS,
                TimeUnit.SECONDS)).isEqualTo(401);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_session WHERE account_id = ?",
                Long.class,
                accountId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT last_login_at FROM local_account WHERE account_id = ?",
                Timestamp.class,
                accountId)).isNull();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrency test latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("concurrency test was interrupted", exception);
        }
    }
}
