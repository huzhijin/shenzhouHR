package com.szsemicon.hr.identityaccess.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.identityaccess.application.AccountPersistence.IdempotencyClaim;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class AccountProvisioningIdempotencyConcurrencyTest {

    private ExecutorService executor;
    private JdbcTemplate jdbc;
    private AccountPersistenceAdapter persistence;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(
                "jdbc:h2:mem:account-provision-idempotency-"
                        + UUID.randomUUID()
                        + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE people_idempotency_record (
                    idempotency_record_id VARCHAR(36) PRIMARY KEY,
                    actor_id VARCHAR(36) NOT NULL,
                    action_code VARCHAR(96) NOT NULL,
                    idempotency_key VARCHAR(128) NOT NULL,
                    request_digest CHAR(64) NOT NULL,
                    resource_id VARCHAR(36),
                    result_json CLOB,
                    created_at TIMESTAMP NOT NULL,
                    UNIQUE (actor_id, action_code, idempotency_key)
                )
                """);
        persistence = new AccountPersistenceAdapter(jdbc);
        transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() throws Exception {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void concurrentSameKeyClaimsHaveExactlyOneFirstClaimant() throws Exception {
        String firstRecordId = UUID.randomUUID().toString();
        String secondRecordId = UUID.randomUUID().toString();
        String actorId = UUID.randomUUID().toString();
        String idempotencyKey = "concurrent-account-provision-key";
        String requestDigest = "a".repeat(64);
        CyclicBarrier start = new CyclicBarrier(2);

        Future<IdempotencyClaim> first = executor.submit(() -> claimAfterBarrier(
                start,
                firstRecordId,
                actorId,
                idempotencyKey,
                requestDigest));
        Future<IdempotencyClaim> second = executor.submit(() -> claimAfterBarrier(
                start,
                secondRecordId,
                actorId,
                idempotencyKey,
                requestDigest));

        IdempotencyClaim firstResult = first.get(10, TimeUnit.SECONDS);
        IdempotencyClaim secondResult = second.get(10, TimeUnit.SECONDS);
        assertThat(List.of(firstResult.firstClaim(), secondResult.firstClaim()))
                .containsExactlyInAnyOrder(true, false);
        assertThat(firstResult.recordId()).isEqualTo(secondResult.recordId());
        assertThat(firstResult.requestDigest()).isEqualTo(requestDigest);
        assertThat(secondResult.requestDigest()).isEqualTo(requestDigest);
        assertThat(firstResult.createdAt())
                .isEqualTo(Instant.parse("2026-08-05T00:00:00Z"));
        assertThat(secondResult.createdAt()).isEqualTo(firstResult.createdAt());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM people_idempotency_record",
                Long.class)).isOne();
    }

    private IdempotencyClaim claimAfterBarrier(
            CyclicBarrier start,
            String recordId,
            String actorId,
            String idempotencyKey,
            String requestDigest) {
        return transactions.execute(status -> {
            await(start);
            return persistence.claimIdempotency(
                    recordId,
                    actorId,
                    "EMPLOYEE_ACCOUNTS_BULK_CREATE",
                    idempotencyKey,
                    requestDigest,
                    Instant.parse("2026-08-05T00:00:00Z"));
        });
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "concurrent idempotency claim did not start", exception);
        }
    }
}
