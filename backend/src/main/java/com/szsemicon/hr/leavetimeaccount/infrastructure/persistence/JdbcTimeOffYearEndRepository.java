package com.szsemicon.hr.leavetimeaccount.infrastructure.persistence;

import com.szsemicon.hr.leavetimeaccount.application.TimeOffYearEndModels.AccountCandidate;
import com.szsemicon.hr.leavetimeaccount.application.TimeOffYearEndModels.RunItemRecord;
import com.szsemicon.hr.leavetimeaccount.application.TimeOffYearEndModels.RunRecord;
import com.szsemicon.hr.leavetimeaccount.application.TimeOffYearEndRepository;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTimeOffYearEndRepository implements TimeOffYearEndRepository {

    private static final Logger log =
            LoggerFactory.getLogger(JdbcTimeOffYearEndRepository.class);

    private final JdbcTemplate jdbc;

    public JdbcTimeOffYearEndRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tryAcquireLock(
            int accountYear,
            String lockToken,
            String lockOwner,
            Duration lease) {
        long leaseMicros = leaseMicros(lease);
        jdbc.update(
                """
                INSERT IGNORE INTO time_off_year_end_lock (
                    account_year, lock_token, lock_owner, lock_expires_at,
                    acquired_at, updated_at
                ) VALUES (?, NULL, NULL, NULL, NULL, CURRENT_TIMESTAMP(6))
                """,
                accountYear);
        int updated = jdbc.update(
                """
                UPDATE time_off_year_end_lock
                SET lock_token = ?,
                    lock_owner = ?,
                    lock_expires_at = TIMESTAMPADD(
                        MICROSECOND, ?, CURRENT_TIMESTAMP(6)),
                    acquired_at = CURRENT_TIMESTAMP(6),
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE account_year = ?
                  AND (
                      lock_token IS NULL
                      OR lock_expires_at <= CURRENT_TIMESTAMP(6)
                  )
                """,
                lockToken,
                lockOwner,
                leaseMicros,
                accountYear);
        return updated == 1;
    }

    @Override
    public boolean renewLock(
            int accountYear,
            String lockToken,
            Duration lease) {
        int updated = jdbc.update(
                """
                UPDATE time_off_year_end_lock
                SET lock_expires_at = TIMESTAMPADD(
                        MICROSECOND, ?, CURRENT_TIMESTAMP(6)),
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE account_year = ?
                  AND lock_token = ?
                  AND lock_expires_at > CURRENT_TIMESTAMP(6)
                """,
                leaseMicros(lease),
                accountYear,
                lockToken);
        return updated == 1;
    }

    @Override
    public void releaseLock(int accountYear, String lockToken) {
        int updated = jdbc.update(
                """
                UPDATE time_off_year_end_lock
                SET lock_token = NULL,
                    lock_owner = NULL,
                    lock_expires_at = NULL,
                    acquired_at = NULL,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE account_year = ? AND lock_token = ?
                """,
                accountYear,
                lockToken);
        if (updated != 1) {
            log.error(
                    "TIME_OFF year-end lock release did not own year {} token",
                    accountYear);
        }
    }

    @Override
    public List<AccountCandidate> findCandidates(int accountYear) {
        return jdbc.query(
                """
                SELECT account.time_account_id, employee.employee_number
                FROM time_account account
                JOIN employee employee
                  ON employee.employee_id = account.employee_id
                 AND employee.company_id = account.company_id
                WHERE account.account_type = 'TIME_OFF'
                  AND account.account_year = ?
                ORDER BY employee.employee_number, account.time_account_id
                """,
                (resultSet, rowNumber) -> new AccountCandidate(
                        resultSet.getString("time_account_id"),
                        resultSet.getString("employee_number")),
                accountYear);
    }

    @Override
    public Optional<String> findExpiryEventDigest(
            String sourceRequestId,
            String sourceEventId) {
        List<String> values = jdbc.query(
                """
                SELECT payload_digest
                FROM oa_time_account_operation_event
                WHERE operation_kind = 'TIME_OFF_EXPIRY'
                  AND source_request_id = ?
                  AND source_event_id = ?
                  AND operation_cycle = 1
                """,
                (resultSet, rowNumber) -> resultSet.getString("payload_digest"),
                sourceRequestId,
                sourceEventId);
        if (values.size() > 1) {
            throw new IllegalStateException("SZSC_EXPIRY_EVENT_NOT_UNIQUE");
        }
        return values.stream().findFirst();
    }

    @Override
    public void insertRun(RunRecord run) {
        jdbc.update(
                """
                INSERT INTO time_off_year_end_run (
                    time_off_year_end_run_id, account_year, trigger_type,
                    run_status, lock_owner, total_count, success_count,
                    failure_count, skipped_count, failure_summary,
                    started_at, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                run.runId(),
                run.accountYear(),
                run.triggerType().name(),
                run.status().name(),
                run.lockOwner(),
                run.totalCount(),
                run.successCount(),
                run.failureCount(),
                run.skippedCount(),
                run.failureSummary(),
                Timestamp.from(run.startedAt()),
                timestampOrNull(run.completedAt()));
    }

    @Override
    public void insertRunItem(RunItemRecord item) {
        jdbc.update(
                """
                INSERT INTO time_off_year_end_run_item (
                    time_off_year_end_run_item_id, time_off_year_end_run_id,
                    time_account_id, employee_number, source_event_id,
                    payload_digest, item_status, affected_hours, result_code,
                    result_detail, attempted_at, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                item.itemId(),
                item.runId(),
                item.timeAccountId(),
                item.employeeNumber(),
                item.eventId(),
                item.payloadDigest(),
                item.status().name(),
                item.affectedHours(),
                item.resultCode(),
                item.resultDetail(),
                Timestamp.from(item.attemptedAt()),
                Timestamp.from(item.completedAt()));
    }

    @Override
    public void completeRun(RunRecord run) {
        int updated = jdbc.update(
                """
                UPDATE time_off_year_end_run
                SET run_status = ?,
                    total_count = ?,
                    success_count = ?,
                    failure_count = ?,
                    skipped_count = ?,
                    failure_summary = ?,
                    completed_at = ?
                WHERE time_off_year_end_run_id = ?
                  AND run_status = 'RUNNING'
                """,
                run.status().name(),
                run.totalCount(),
                run.successCount(),
                run.failureCount(),
                run.skippedCount(),
                run.failureSummary(),
                timestampOrNull(run.completedAt()),
                run.runId());
        if (updated != 1) {
            throw new IllegalStateException("SZSC_YEAR_END_RUN_NOT_RUNNING");
        }
    }

    private static Timestamp timestampOrNull(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static long leaseMicros(Duration lease) {
        try {
            return lease.toNanos() / 1_000L;
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("year-end lock lease is too large", exception);
        }
    }
}
