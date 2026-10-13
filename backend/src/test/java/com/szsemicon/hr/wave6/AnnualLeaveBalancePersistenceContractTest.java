package com.szsemicon.hr.wave6;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AnnualLeaveBalancePersistenceContractTest {

    private static final Path V44 = Path.of(
            "src/main/resources/db/migration/"
                    + "V44__annual_leave_balance_idempotency.sql");
    private static final Path MAPPER = Path.of(
            "src/main/resources/mappers/AnnualLeaveMapper.xml");
    private static final Path CONTROLLER = Path.of(
            "src/main/java/com/szsemicon/hr/leavetimeaccount/interfaces/rest/"
                    + "AnnualLeaveManagementController.java");

    @Test
    void v44PersistsClaimDigestAndCompletedMutationResult() throws Exception {
        String sql = Files.readString(V44);

        assertThat(sql)
                .contains(
                        "CREATE TABLE annual_leave_balance_idempotency_record",
                        "UNIQUE KEY uq_annual_leave_balance_idempotency_key",
                        "(principal_id, idempotency_key)",
                        "operation IN ('SET_OPENING', 'ADJUST')",
                        "request_digest REGEXP '^[0-9a-f]{64}$'",
                        "record_status IN ('PROCESSING', 'COMPLETED')",
                        "resulting_ledger_entry_id",
                        "resulting_balance_hours",
                        "resulting_row_version",
                        "completed_at",
                        "ENGINE=InnoDB",
                        "FOREIGN KEY (time_account_id) REFERENCES time_account")
                .doesNotContain(
                        "UPDATE time_account",
                        "INSERT INTO time_account_ledger_entry");
    }

    @Test
    void mapperBindsAccountToEffectiveEmploymentAndLocksBeforeSequencing()
            throws Exception {
        String xml = Files.readString(MAPPER);
        String employment = between(
                xml,
                "<select id=\"findCurrentEmployments\"",
                "</select>");
        String accountLookup = between(
                xml,
                "<select id=\"findTimeAccount\"",
                "</select>");
        String accountLock = between(
                xml,
                "<select id=\"lockTimeAccount\"",
                "</select>");

        assertThat(employment)
                .contains(
                        "ea.current_version_marker = 1",
                        "ea.record_status = 'ACTIVE'",
                        "employee.employment_status = 'ACTIVE'",
                        "ea.effective_from &lt;= #{at}",
                        "ea.effective_to &gt; #{at}")
                .doesNotContain("LIMIT 1");
        assertThat(accountLookup)
                .contains("ta.employment_period_id = #{employmentPeriodId}")
                .doesNotContain("LIMIT 1");
        assertThat(accountLock)
                .contains(
                        "ta.employee_id = #{employeeId}",
                        "ta.employment_period_id = #{employmentPeriodId}",
                        "FOR UPDATE");
        assertThat(xml)
                .contains(
                        "<select id=\"nextSequenceNo\"",
                        "SUM(amount_hours)",
                        "reservation_status IN ('RESERVED', 'CONFIRMED')");
    }

    @Test
    void mapperClaimsAndLocksIdempotencyWithoutOverwritingOriginalPayload()
            throws Exception {
        String xml = Files.readString(MAPPER);

        assertThat(xml)
                .contains(
                        "<insert id=\"claimBalanceIdempotency\"",
                        "ON DUPLICATE KEY UPDATE",
                        "<select id=\"lockBalanceIdempotency\"",
                        "WHERE principal_id = #{principalId}",
                        "AND idempotency_key = #{idempotencyKey}",
                        "FOR UPDATE",
                        "<update id=\"completeBalanceIdempotency\"",
                        "AND claim_token = #{claimToken}",
                        "AND record_status = 'PROCESSING'")
                .doesNotContain(
                        "WHERE time_account_id = #{accountId}\n"
                                + "          AND idempotency_key",
                        "request_digest = VALUES(request_digest)",
                        "operation = VALUES(operation)");
    }

    @Test
    void annualLeaveWritesRequireCallerSuppliedIdempotencyKey() throws Exception {
        String controller = Files.readString(CONTROLLER);

        assertThat(controller)
                .contains("            @RequestHeader(\"Idempotency-Key\") String idempotencyKey) {");
        assertThat(controller.split("@RequestHeader\\(\"Idempotency-Key\"\\)", -1))
                .hasSize(5);
        assertThat(controller)
                .doesNotContain(
                        "@RequestHeader(value = \"Idempotency-Key\", required = false)",
                        "UUID.randomUUID()");
    }

    private static String between(String value, String start, String end) {
        int startAt = value.indexOf(start);
        int endAt = value.indexOf(end, startAt + start.length());
        assertThat(startAt).isGreaterThanOrEqualTo(0);
        assertThat(endAt).isGreaterThan(startAt);
        return value.substring(startAt, endAt);
    }
}
