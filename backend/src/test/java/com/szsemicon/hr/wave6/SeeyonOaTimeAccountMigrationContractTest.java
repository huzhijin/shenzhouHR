package com.szsemicon.hr.wave6;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class SeeyonOaTimeAccountMigrationContractTest {

    private static final List<String> PUBLIC_ROUTINES = List.of(
            "szsc_oa_leave_reserve",
            "szsc_oa_leave_confirm",
            "szsc_oa_leave_consume",
            "szsc_oa_leave_release",
            "szsc_oa_leave_return",
            "szsc_oa_time_off_credit",
            "szsc_oa_time_off_expire",
            "szsc_oa_time_off_can_reverse",
            "szsc_oa_time_off_reverse");
    private static final List<String> MUTATING_PUBLIC_ROUTINES = List.of(
            "szsc_oa_leave_reserve",
            "szsc_oa_leave_confirm",
            "szsc_oa_leave_consume",
            "szsc_oa_leave_release",
            "szsc_oa_leave_return",
            "szsc_oa_time_off_credit",
            "szsc_oa_time_off_expire",
            "szsc_oa_time_off_reverse");
    private static final List<String> RESULT_ALIASES = List.of(
            "AS source_request_id",
            "AS operation_status",
            "AS account_type",
            "AS account_year",
            "AS affected_hours",
            "AS balance_hours",
            "AS reserved_hours",
            "AS available_hours");

    private static final Path V41 = Path.of(
            "src/main/resources/db/migration/"
                    + "V41__seeyon_oa_time_account_operations.sql");
    private static final Path V42 = Path.of(
            "src/main/resources/db/migration/"
                    + "V42__seeyon_oa_time_account_routines.sql");

    @Test
    void v41AddsAuditedReservationCreditAndReadProjectionWithoutGuessingHistory()
            throws Exception {
        String sql = Files.readString(V41);

        assertThat(sql)
                .contains(
                        "CREATE TABLE oa_time_account_reservation",
                        "CREATE TABLE oa_time_account_operation_event",
                        "CREATE TABLE oa_leave_balance_return",
                        "CREATE TABLE oa_time_off_credit",
                        "CREATE SQL SECURITY DEFINER VIEW szsc_oa_time_account_balance_v",
                        "employee.employee_number AS employee_number",
                        "AS reserved_hours",
                        "41000000-0000-0000-0000-000000000001",
                        "SEEYON_OA_INTEGRATION",
                        "MOD(reserved_hours * 100, 50) = 0",
                        "MOD(returned_hours * 100, 50) = 0",
                        "MOD(credited_hours * 100, 50) = 0")
                .doesNotContain(
                        "binding.seeyon_oa_code AS employee_number",
                        "UPDATE time_account SET",
                        "UPDATE leave_request SET",
                        "INSERT INTO time_account_ledger_entry");
    }

    @Test
    void v42PublishesOnlyAtomicDefinerRoutinesWithOneResultShape()
            throws Exception {
        String sql = Files.readString(V42);

        assertThat(sql)
                .contains(
                        "CREATE PROCEDURE szsc_oa_leave_reserve(",
                        "CREATE PROCEDURE szsc_oa_leave_confirm(",
                        "CREATE PROCEDURE szsc_oa_leave_consume(",
                        "CREATE PROCEDURE szsc_oa_leave_release(",
                        "CREATE PROCEDURE szsc_oa_leave_return(",
                        "CREATE PROCEDURE szsc_oa_time_off_credit(",
                        "CREATE PROCEDURE szsc_oa_time_off_expire(",
                        "CREATE PROCEDURE szsc_oa_time_off_can_reverse(",
                        "CREATE PROCEDURE szsc_oa_time_off_reverse(",
                        "p_status AS operation_status",
                        "AS reserved_hours",
                        "AS available_hours",
                        "SQL SECURITY DEFINER",
                        "START TRANSACTION",
                        "FOR UPDATE",
                        "DECLARE EXIT HANDLER FOR SQLEXCEPTION",
                        "ROLLBACK;",
                        "RESIGNAL;")
                .doesNotContain("SQL SECURITY INVOKER");

        assertThat(countMatches(
                        sql,
                        Pattern.compile(
                                "CREATE PROCEDURE szsc_oa_(?!private_)[a-z_]+\\(")))
                .isEqualTo(9);
        assertThat(countMatches(
                        sql,
                        Pattern.compile(
                                "CREATE PROCEDURE szsc_oa_private_[a-z_]+\\(")))
                .isEqualTo(2);

        for (String routine : MUTATING_PUBLIC_ROUTINES) {
            assertThat(procedure(sql, routine))
                    .as(routine)
                    .contains(
                            "SQL SECURITY DEFINER",
                            "START TRANSACTION",
                            "FOR UPDATE",
                            "DECLARE EXIT HANDLER FOR SQLEXCEPTION",
                            "ROLLBACK;",
                            "RESIGNAL;",
                            "COMMIT;");
        }

        assertThat(procedure(sql, "szsc_oa_time_off_can_reverse"))
                .contains("SQL SECURITY DEFINER", "READS SQL DATA")
                .doesNotContain("START TRANSACTION", "MODIFIES SQL DATA");

        for (String routine : PUBLIC_ROUTINES) {
            assertThat(procedure(sql, routine))
                    .as(routine)
                    .contains("CALL szsc_oa_private_emit_balance(");
        }

        assertThat(procedure(sql, "szsc_oa_private_emit_balance"))
                .containsSubsequence(RESULT_ALIASES.toArray(String[]::new));
        assertThat(procedure(sql, "szsc_oa_time_off_can_reverse"))
                .containsSubsequence(RESULT_ALIASES.toArray(String[]::new));
        assertThat(procedure(sql, "szsc_oa_time_off_reverse"))
                .containsSubsequence(RESULT_ALIASES.toArray(String[]::new));

        for (String alias : RESULT_ALIASES) {
            assertThat(count(sql, alias)).as(alias).isEqualTo(3);
        }
    }

    @Test
    void routinesResolveGlobalEmployeeAndCurrentEmploymentWithoutLimitOne()
            throws Exception {
        String sql = Files.readString(V42);
        String resolver = between(
                sql,
                "CREATE PROCEDURE szsc_oa_private_resolve_account(",
                "CREATE PROCEDURE szsc_oa_private_emit_balance(");

        assertThat(resolver)
                .contains(
                        "employee.employee_number = p_employee_number",
                        "IF v_employee_count <> 1",
                        "IF v_employment_count <> 1",
                        "employment.current_version_marker = 1",
                        "employment.record_status = 'ACTIVE'",
                        "employment.effective_to > CAST(p_business_date AS DATETIME)",
                        "IF v_account_count = 0",
                        "SZSC_TIME_ACCOUNT_NOT_UNIQUE_OR_MISSING",
                        "SZSC_TIME_OFF_POLICY_NOT_UNIQUE")
                .doesNotContain(
                        "employee_source_binding",
                        "seeyon_oa_code",
                        "LIMIT 1");

        assertThat(resolver.indexOf("FROM time_account account"))
                .isLessThan(resolver.indexOf("IF v_account_count = 0"));
        assertThat(resolver.indexOf("IF v_account_count = 0"))
                .isLessThan(resolver.indexOf("FROM leave_policy_revision policy"));
    }

    @Test
    void everyBalanceBoundaryFailsClosedWhenLedgerDoesNotReconcile()
            throws Exception {
        String sql = Files.readString(V42);

        assertThat(count(sql, "SZSC_BALANCE_LEDGER_MISMATCH"))
                .isGreaterThanOrEqualTo(6);
        assertThat(sql)
                .contains(
                        "v_account_balance <> v_ledger_balance",
                        "v_balance <> v_ledger_balance",
                        "v_account_balance < v_reserved",
                        "v_balance < v_reserved",
                        "SZSC_ACCOUNT_BALANCE_CORRUPTED");

        for (String routine : MUTATING_PUBLIC_ROUTINES) {
            assertThat(procedure(sql, routine))
                    .as(routine)
                    .contains("SZSC_BALANCE_LEDGER_MISMATCH");
        }
    }

    @Test
    void yearEndPreservesHoldsAndNeverResurrectsExpiredTimeOff()
            throws Exception {
        String sql = Files.readString(V42);

        assertThat(sql)
                .contains(
                        "SET v_available = v_balance - v_reserved",
                        "'EXPIRY', -v_lot_hours",
                        "'EXPIRY', -v_remaining",
                        "reservation_status IN ('RESERVED', 'CONFIRMED')",
                        "SZSC_TIME_OFF_ACCOUNT_EXPIRED",
                        "'EXPIRY', -v_hours, 'SEEYON_OA_RELEASE'",
                        "'RETURN', p_hours, 'SEEYON_OA_RETURN'",
                        "'EXPIRY', -p_hours, 'SEEYON_OA_RETURN'",
                        "'OVERTIME_CREDIT', p_hours, 'SEEYON_OA_OVERTIME'",
                        "'EXPIRY', -p_hours, 'SEEYON_OA_OVERTIME'");
    }

    @Test
    void yearEndExpiryAttributesWholeUnusedOaLotsAndReplaysOriginalResult()
            throws Exception {
        String sql = Files.readString(V42);
        String expiry = between(
                sql,
                "CREATE PROCEDURE szsc_oa_time_off_expire(",
                "CREATE PROCEDURE szsc_oa_time_off_can_reverse(");
        String reverse = between(
                sql,
                "CREATE PROCEDURE szsc_oa_time_off_reverse(",
                "DELIMITER ;");

        assertThat(expiry)
                .contains(
                        "credit_cursor CURSOR",
                        "credit_status = 'CREDITED'",
                        "SET credit_status = 'EXPIRED'",
                        "expiry_ledger_entry_id = v_lot_expiry_id",
                        "'EXPIRY', -v_lot_hours",
                        "SET v_available = v_event_affected",
                        "SELECT payload_digest, affected_hours")
                .doesNotContain("CALL szsc_oa_private_resolve_account(");

        assertThat(reverse)
                .contains(
                        "ELSEIF v_status = 'EXPIRED'",
                        "v_expiry_ledger_id",
                        "'REVERSAL', v_hours",
                        "'REVERSAL', -v_hours");
    }

    @Test
    void expiryCanCloseUniqueOldAccountAfterEmployeeTermination()
            throws Exception {
        String sql = Files.readString(V42);
        String expiry = between(
                sql,
                "CREATE PROCEDURE szsc_oa_time_off_expire(",
                "CREATE PROCEDURE szsc_oa_time_off_can_reverse(");

        assertThat(expiry)
                .contains(
                        "FROM employee",
                        "employee_number = p_employee_number",
                        "IF v_employee_count <> 1",
                        "account_type = 'TIME_OFF'",
                        "IF v_account_count <> 1",
                        "FOR UPDATE")
                .doesNotContain(
                        "employment_status = 'ACTIVE'",
                        "employment_assignment");
    }

    @Test
    void releasedReservationCanBeFreshlyReservedButConsumedOneCannot()
            throws Exception {
        String sql = Files.readString(V42);
        String reserve = between(
                sql,
                "CREATE PROCEDURE szsc_oa_leave_reserve(",
                "CREATE PROCEDURE szsc_oa_leave_confirm(");

        assertThat(reserve)
                .contains(
                        "v_existing_status = 'RELEASED'",
                        "reservation_cycle = reservation_cycle + 1",
                        "reservation_status = 'RESERVED'",
                        "payload_digest = p_payload_digest",
                        "SZSC_CONSUMED_RESERVATION_CANNOT_RESUBMIT",
                        "SZSC_INSUFFICIENT_AVAILABLE_BALANCE");
    }

    @Test
    void cancellationBeforeCreditIsAnIdempotentNoOpButFinalReverseRechecksBalance()
            throws Exception {
        String sql = Files.readString(V42);

        assertThat(sql)
                .contains(
                        "'NO_CREDIT' AS operation_status",
                        "'NOOP' AS operation_status",
                        "SZSC_TIME_OFF_ALREADY_REVERSED",
                        "v_reverse_event_count > 0",
                        "SZSC_TIME_OFF_CREDIT_NOT_REVERSIBLE",
                        "IF v_balance - v_reserved < v_hours",
                        "credit_status = 'REVERSED'",
                        "reversal_of_entry_id");
    }

    @Test
    void persistedParametersUseNullSafeIdempotencyComparisonAndAllSignalsAreStable()
            throws Exception {
        String sql = Files.readString(V42);

        assertThat(sql)
                .contains(
                        "NOT (BINARY v_existing_employee_number",
                        "NOT (v_existing_account_type <=> p_account_type)",
                        "NOT (BINARY v_existing_original_id",
                        "NOT (v_existing_digest <=> p_payload_digest)");

        int signalCount = count(sql, "SIGNAL SQLSTATE '45000'");
        int stableSignalCount = countMatches(
                sql,
                Pattern.compile(
                        "SIGNAL\\s+SQLSTATE '45000'\\s+"
                                + "SET MESSAGE_TEXT = 'SZSC_[A-Z0-9_]+';",
                        Pattern.MULTILINE));
        assertThat(stableSignalCount).isEqualTo(signalCount);
    }

    @Test
    void eventDigestIsScopedToDocumentAndEventWhileFactsStaySingleEntry()
            throws Exception {
        String sql = Files.readString(V42);
        String leaveReturn = procedure(sql, "szsc_oa_leave_return");
        String timeOffCredit = procedure(sql, "szsc_oa_time_off_credit");

        assertThat(leaveReturn)
                .contains(
                        "operation_kind = 'LEAVE_RETURN'",
                        "source_request_id = p_revocation_request_id",
                        "source_event_id = p_source_event_id",
                        "v_existing_hours <=> p_hours",
                        "v_existing_business_date <=> p_business_date")
                .doesNotContain("v_existing_digest");
        assertThat(timeOffCredit)
                .contains(
                        "operation_kind = 'TIME_OFF_CREDIT'",
                        "source_request_id = p_overtime_line_id",
                        "source_event_id = p_source_event_id",
                        "v_existing_hours <=> p_hours",
                        "v_existing_date <=> p_business_date")
                .doesNotContain("v_existing_digest");
    }

    @Test
    void accountMutationsEnforceHalfHourGranularityAndReturnYear() throws Exception {
        String sql = Files.readString(V42);

        assertThat(count(sql, "MOD(p_hours * 100, 50) <> 0"))
                .isEqualTo(3);
        assertThat(sql)
                .contains(
                        "SZSC_HOURS_MUST_BE_HALF_HOUR_MULTIPLE",
                        "IF YEAR(p_business_date) <> v_account_year",
                        "SZSC_ACCOUNT_YEAR_MISMATCH");
    }

    @Test
    void allOaLedgerWritesUseTheDedicatedIntegrationPrincipal()
            throws Exception {
        String sql = Files.readString(V42);

        assertThat(sql)
                .contains("41000000-0000-0000-0000-000000000001")
                .doesNotContain("20000000-0000-0000-0000-000000000001");
    }

    private static int count(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static int countMatches(String value, Pattern pattern) {
        int count = 0;
        Matcher matcher = pattern.matcher(value);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static String procedure(String sql, String name) {
        String start = "CREATE PROCEDURE " + name + "(";
        int startAt = sql.indexOf(start);
        int endAt = sql.indexOf("END$$", startAt + start.length());
        assertThat(startAt).as(name).isGreaterThanOrEqualTo(0);
        assertThat(endAt).as(name).isGreaterThan(startAt);
        return sql.substring(startAt, endAt + "END$$".length());
    }

    private static String between(String value, String start, String end) {
        int startAt = value.indexOf(start);
        int endAt = value.indexOf(end, startAt + start.length());
        assertThat(startAt).isGreaterThanOrEqualTo(0);
        assertThat(endAt).isGreaterThan(startAt);
        return value.substring(startAt, endAt);
    }
}
