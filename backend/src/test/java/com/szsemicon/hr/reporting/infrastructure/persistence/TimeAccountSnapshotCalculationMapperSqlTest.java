package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TimeAccountSnapshotCalculationMapperSqlTest {

    private static final Pattern SNAPSHOT_QUERY = Pattern.compile(
            "<select id=\"findTimeAccountSnapshots\"[^>]*>(.*?)</select>",
            Pattern.DOTALL);
    private static final Path CALCULATION_MAPPER = Path.of(
            "src/main/resources/mappers/AttendanceReportCalculationMapper.xml");

    private Connection connection;

    @BeforeEach
    void createSchema() throws Exception {
        connection = DriverManager.getConnection(
                "jdbc:h2:mem:time-account-snapshot-" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        execute("""
                CREATE TABLE employment_assignment (
                    assignment_id VARCHAR(36) PRIMARY KEY,
                    employment_period_id VARCHAR(36) NOT NULL,
                    employee_id VARCHAR(36) NOT NULL,
                    effective_from TIMESTAMP NOT NULL,
                    effective_to TIMESTAMP NULL,
                    record_status VARCHAR(32) NOT NULL,
                    created_at TIMESTAMP NOT NULL,
                    version_valid_to TIMESTAMP NULL
                )
                """);
        execute("""
                CREATE TABLE time_account (
                    time_account_id VARCHAR(36) PRIMARY KEY,
                    employee_id VARCHAR(36) NOT NULL,
                    employment_period_id VARCHAR(36) NOT NULL,
                    company_id VARCHAR(36) NOT NULL,
                    account_type VARCHAR(32) NOT NULL,
                    account_year SMALLINT NOT NULL,
                    row_version BIGINT NOT NULL,
                    created_at TIMESTAMP NOT NULL
                )
                """);
        execute("""
                CREATE TABLE time_account_ledger_entry (
                    time_account_ledger_entry_id VARCHAR(36) PRIMARY KEY,
                    time_account_id VARCHAR(36) NOT NULL,
                    sequence_no BIGINT NOT NULL,
                    entry_type VARCHAR(24) NOT NULL,
                    amount_hours DECIMAL(12, 2) NOT NULL,
                    reversal_of_entry_id VARCHAR(36) NULL,
                    occurred_at TIMESTAMP NOT NULL
                )
                """);
        execute("""
                INSERT INTO employment_assignment (
                    assignment_id, employment_period_id, employee_id,
                    effective_from, effective_to, record_status, created_at,
                    version_valid_to
                ) VALUES (
                    'assignment-v2', 'period-1', 'employee-1',
                    TIMESTAMP '2026-01-01 00:00:00', NULL, 'ACTIVE',
                    TIMESTAMP '2026-01-01 00:00:00', NULL
                )
                """);
        execute("""
                INSERT INTO time_account (
                    time_account_id, employee_id, employment_period_id,
                    company_id, account_type, account_year, row_version,
                    created_at
                ) VALUES (
                    'account-1', 'employee-1', 'period-1',
                    'company-1', 'ANNUAL_LEAVE', 2026, 99,
                    TIMESTAMP '2026-01-01 00:00:00'
                )
                """);
    }

    @AfterEach
    void closeConnection() throws Exception {
        connection.close();
    }

    @Test
    void reversalNetsItsOriginalComponentAndCutoffControlsLedgerVersion()
            throws Exception {
        insertEntry(1, "opening", "OPENING", "40.00", null, "2026-08-01 00:00:00");
        insertEntry(2, "grant", "GRANT", "8.00", null, "2026-08-02 00:00:00");
        insertEntry(3, "grant-reversal", "REVERSAL", "-8.00", "grant", "2026-08-03 00:00:00");
        insertEntry(4, "overtime", "OVERTIME_CREDIT", "5.00", null, "2026-08-04 00:00:00");
        insertEntry(5, "overtime-reversal", "REVERSAL", "-5.00", "overtime", "2026-08-05 00:00:00");
        insertEntry(6, "use", "USE", "-4.00", null, "2026-08-06 00:00:00");
        insertEntry(7, "use-reversal", "REVERSAL", "4.00", "use", "2026-08-07 00:00:00");
        insertEntry(8, "expiry", "EXPIRY", "-2.00", null, "2026-08-08 00:00:00");
        insertEntry(9, "expiry-reversal", "REVERSAL", "2.00", "expiry", "2026-08-09 00:00:00");
        insertEntry(10, "return", "RETURN", "3.00", null, "2026-08-10 00:00:00");
        insertEntry(11, "return-reversal", "REVERSAL", "-3.00", "return", "2026-08-11 00:00:00");
        insertEntry(12, "increase-reversed", "ADJUSTMENT", "10.00", null, "2026-08-12 00:00:00");
        insertEntry(13, "increase-reversal", "REVERSAL", "-10.00", "increase-reversed", "2026-08-13 00:00:00");
        insertEntry(14, "increase-active", "ADJUSTMENT", "6.00", null, "2026-08-14 00:00:00");
        insertEntry(15, "deduction-active", "ADJUSTMENT", "-4.00", null, "2026-08-15 00:00:00");
        insertEntry(16, "future-grant", "GRANT", "100.00", null, "2026-08-18 00:00:00");

        Timestamp dataAsOf = Timestamp.valueOf("2026-08-17 10:00:00");
        try (PreparedStatement statement = connection.prepareStatement(
                snapshotSql())) {
            statement.setTimestamp(1, dataAsOf);
            statement.setTimestamp(2, dataAsOf);
            statement.setTimestamp(3, Timestamp.valueOf("2026-09-01 00:00:00"));
            statement.setTimestamp(4, Timestamp.valueOf("2026-08-01 00:00:00"));
            statement.setString(5, "company-1");
            statement.setDate(6, Date.valueOf("2026-08-01"));
            statement.setTimestamp(7, dataAsOf);

            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString("employmentAssignmentId"))
                        .isEqualTo("assignment-v2");
                assertHours(result, "openingHours", "40.00");
                assertHours(result, "grantedHours", "0.00");
                assertHours(result, "overtimeCreditHours", "0.00");
                assertHours(result, "manualIncreaseHours", "6.00");
                assertHours(result, "usedHours", "0.00");
                assertHours(result, "expiredHours", "0.00");
                assertHours(result, "returnedHours", "0.00");
                assertHours(result, "manualDeductionHours", "4.00");
                assertThat(result.getString("ledgerVersion"))
                        .isEqualTo("TIME_ACCOUNT:account-1:ASOF:S15");
                assertThat(result.next()).isFalse();
            }
        }
    }

    @Test
    void snapshotSqlDoesNotDependOnCurrentAccountRowVersion() throws Exception {
        String sql = snapshotSql();

        assertThat(sql)
                .contains(
                        "entry.occurred_at <= ?",
                        "COALESCE(original.amount_hours, entry.amount_hours)",
                        "semantic_amount_hours > 0",
                        "semantic_amount_hours < 0",
                        "assignment.employment_period_id = account.employment_period_id",
                        "assignment.assignment_id AS employmentAssignmentId",
                        "':ASOF:S'")
                .doesNotContain("account.row_version");
    }

    private void insertEntry(
            long sequence,
            String id,
            String type,
            String amount,
            String reversalOf,
            String occurredAt) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO time_account_ledger_entry (
                    time_account_ledger_entry_id, time_account_id,
                    sequence_no, entry_type, amount_hours,
                    reversal_of_entry_id, occurred_at
                ) VALUES (?, 'account-1', ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, id);
            statement.setLong(2, sequence);
            statement.setString(3, type);
            statement.setBigDecimal(4, new BigDecimal(amount));
            statement.setString(5, reversalOf);
            statement.setTimestamp(6, Timestamp.valueOf(occurredAt));
            statement.executeUpdate();
        }
    }

    private static void assertHours(
            ResultSet result, String column, String expected) throws Exception {
        assertThat(result.getBigDecimal(column))
                .isEqualByComparingTo(expected);
    }

    private static String snapshotSql() throws Exception {
        String mapper = Files.readString(CALCULATION_MAPPER);
        var matcher = SNAPSHOT_QUERY.matcher(mapper);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1)
                .replace("&lt;=", "<=")
                .replace("&gt;=", ">=")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("#{dataAsOf}", "?")
                .replace("#{periodEndExclusive}", "?")
                .replace("#{periodStart}", "?")
                .replace("#{companyId}", "?");
    }

    private void execute(String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }
}
