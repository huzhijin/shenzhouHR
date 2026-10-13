package com.szsemicon.hr.wave6;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class OaTimeAccountOperationsContractTest {

    private static final Path RECONCILIATION = Path.of(
            "../deploy/mysql/sql/reconcile-szsc-oa-time-account.sql");
    private static final Path DELI_EVIDENCE = Path.of(
            "../deploy/mysql/sql/verify-deli-attendance-evidence-chain.sql");

    @Test
    void reconciliationIsReadOnlyAndNormalizesOaBusinessKeys() throws Exception {
        String sql = Files.readString(RECONCILIATION);
        String executableSql = withoutLineComments(sql);

        assertThat(sql)
                .contains(
                        "reservation.reservation_status IN ('RESERVED', 'CONFIRMED')",
                        "JOIN oa_time_account_reservation reservation",
                        "SUBSTRING(document.source_business_key, 7)",
                        "SUBSTRING(document.source_business_key, 18)",
                        "account.balance_hours <> COALESCE(SUM(entry.amount_hours), 0.00)",
                        "POSITIVE_AVAILABLE_AFTER_EXPIRY_EVENT",
                        "credit_entry.time_account_id <> credit.time_account_id",
                        "expiry_reversal.time_account_id <> credit.time_account_id",
                        "credit.credit_reversal_ledger_entry_id IS NOT NULL",
                        "COALESCE(item.result_code, '') <> 'IDEMPOTENT_REPLAY'",
                        "run.run_status = 'FAILED'")
                .doesNotContain("run.run_status IN ('FAILED', 'SKIPPED_LOCKED')");
        assertThat(executableSql).doesNotContainPattern(
                "(?m)^(UPDATE|DELETE|INSERT|TRUNCATE|ALTER|DROP)\\b");
    }

    @Test
    void reconciliationUsesV48ClassificationsAndDeterministicLatestOaVersions()
            throws Exception {
        String sql = normalized(Files.readString(RECONCILIATION));
        String latestVersionOrdering = "row_number() over ( partition by "
                + "document.attendance_source_id, "
                + "document.source_business_key order by "
                + "document.knowledge_rank desc, document.created_at desc, "
                + "document.oa_attendance_document_id desc ) as version_rank";

        assertThat(occurrences(sql, latestVersionOrdering)).isEqualTo(3);
        assertThat(occurrences(sql, "where ranked.version_rank = 1"))
                .isEqualTo(3);
        assertThat(sql)
                .contains(
                        "left join oa_time_account_reservation reservation",
                        "document.document_type = 'leave' and "
                                + "document.leave_type in "
                                + "('annual', 'compensatory')",
                        "reservation.reservation_id is null or "
                                + "reservation.reservation_status <> 'consumed'",
                        "document.document_type = 'leave_revocation' and "
                                + "document.leave_type in "
                                + "('annual', 'compensatory')",
                        "join oa_attendance_document_context context",
                        "context.overtime_type = 'compensatory'",
                        "left join oa_time_off_credit credit",
                        "substring(document.source_business_key, 10)",
                        "credit.time_off_credit_id is null",
                        "'missing_time_off_credit' as anomaly_kind")
                .doesNotContain("from oa_attendance_document newer");
    }

    @Test
    void deliEvidenceSeparatesFullSuccessFromPartialAndKeepsBrokenPrefixesVisible()
            throws Exception {
        String sql = Files.readString(DELI_EVIDENCE);
        String executableSql = withoutLineComments(sql);

        assertThat(sql)
                .contains(
                        "job.status = 'SUCCEEDED'",
                        "last_fully_successful_sync_at",
                        "historical_quarantined_count",
                        "latest_job.quarantined_count",
                        "latest_job.safe_error_code",
                        "LEFT JOIN normalized_attendance_record normalized",
                        "LEFT JOIN employee_match_decision match_decision",
                        "normalized.validation_status = 'QUARANTINED'",
                        "page.quarantined_count > 0",
                        "page.record_count <> page.accepted_count + page.quarantined_count");
        assertThat(executableSql).doesNotContainPattern(
                "(?m)^(UPDATE|DELETE|INSERT|TRUNCATE|ALTER|DROP)\\b");
    }

    private static String withoutLineComments(String sql) {
        return sql.lines()
                .map(line -> line.replaceFirst("--.*$", "").trim().toUpperCase())
                .filter(line -> !line.isEmpty())
                .reduce("", (left, right) -> left + right + "\n");
    }

    private static String normalized(String sql) {
        return sql.replaceAll("--.*(?:\\R|$)", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static int occurrences(String value, String fragment) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(fragment, offset)) >= 0) {
            count++;
            offset += fragment.length();
        }
        return count;
    }
}
