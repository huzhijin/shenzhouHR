package com.szsemicon.hr.wave7;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.attendance.domain.OvertimeType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class BusinessRulesAlignmentMigrationContractTest {

    private static final Path V48 = Path.of(
            "src/main/resources/db/migration/"
                    + "V48__business_rules_alignment_schema.sql");
    private static final Path ROLLBACK = Path.of(
            "src/main/resources/db/migration/"
                    + "ROLLBACK_V48__business_rules_alignment_schema.sql");
    private static final Path OA_EVIDENCE = Path.of(
            "..",
            "docs",
            "verification",
            "oa-live",
            "2026-08-10",
            "EVIDENCE.md");
    private static final Path OA_EVIDENCE_DIRECTORY =
            OA_EVIDENCE.getParent();
    private static final Pattern SCREENSHOT_DIGEST_ROW = Pattern.compile(
            "\\[[^]]*]\\((screenshots/[^)]+\\.png)\\)"
                    + "[^\\n]*?`([0-9a-f]{64})`");

    @Test
    void overtimeClassificationUsesExistingEvidenceAndDailyFactTables()
            throws Exception {
        String sql = Files.readString(V48);

        assertThat(sql)
                .contains(
                        "ALTER TABLE oa_attendance_document_context",
                        "ADD COLUMN overtime_type VARCHAR(20)",
                        "ck_oa_document_overtime_type",
                        "ALTER TABLE attendance_report_daily_fact",
                        "paid_overtime_minutes",
                        "compensatory_overtime_minutes",
                        "voluntary_overtime_minutes",
                        "total_overtime_minutes",
                        "ck_att_report_daily_overtime_total")
                .doesNotContain("oa_overtime_evidence");
    }

    @Test
    void leaveClassificationIsConstrainedOnSourceAndDailyFacts()
            throws Exception {
        String sql = Files.readString(V48);

        assertThat(sql)
                .contains(
                        "ALTER TABLE attendance_report_daily_fact",
                        "ADD COLUMN leave_type VARCHAR(50) NULL",
                        "ADD INDEX ix_att_report_daily_leave_type",
                        "ck_att_report_daily_leave_type",
                        "ALTER TABLE oa_attendance_document",
                        "ADD COLUMN leave_type VARCHAR(32) NULL",
                        "ck_oa_document_leave_type",
                        "'PERSONAL', 'COMPENSATORY'");
    }

    @Test
    void monthlyPunchQuotaIsUniquelyEnforcedOnlyForConsumingStates()
            throws Exception {
        String sql = Files.readString(V48);

        assertThat(sql)
                .contains(
                        "quota_consuming_marker TINYINT",
                        "WHEN status IN ('PENDING', 'APPROVED') THEN 1",
                        "UNIQUE KEY uq_punch_correction_month_quota",
                        "(employee_id, request_month, quota_consuming_marker)",
                        "KEY ix_punch_correction_employee_date")
                .doesNotContain("UNIQUE KEY uq_punch_correction_employee_date");
    }

    @Test
    void oaEnumMappingsHaveEffectiveTypeSpecificUniqueIndexes()
            throws Exception {
        String sql = Files.readString(V48);

        assertThat(sql)
                .contains(
                        "UNIQUE KEY uq_oa_enum_table_field_bigint",
                        "(oa_table_name, oa_field_name, enum_id_bigint)",
                        "UNIQUE KEY uq_oa_enum_table_field_varchar",
                        "(oa_table_name, oa_field_name, enum_id_varchar)")
                .doesNotContain("uq_oa_enum_table_field_id");
    }

    @Test
    void punchCorrectionCapabilitiesHaveRollbackOwnedIdentifiers()
            throws Exception {
        String migration = Files.readString(V48);
        String rollback = Files.readString(ROLLBACK);

        assertThat(migration).contains(
                "48000000-0000-4000-8000-000000000001",
                "48000000-0000-4000-8000-000000000002",
                "48000000-0000-4000-8000-000000000003");
        assertThat(rollback)
                .contains("WHERE capability.capability_id IN (",
                        "WHERE capability_id IN (")
                .doesNotContain("WHERE capability_code IN (");
    }

    @Test
    void rollbackRemovesEveryOvertimeClassificationAddition()
            throws Exception {
        String sql = Files.readString(ROLLBACK);

        assertThat(sql)
                .contains(
                        "DROP CHECK ck_att_report_daily_overtime_total",
                        "DROP COLUMN total_overtime_minutes",
                        "DROP COLUMN voluntary_overtime_minutes",
                        "DROP COLUMN compensatory_overtime_minutes",
                        "DROP COLUMN paid_overtime_minutes",
                        "ALTER TABLE oa_attendance_document_context",
                        "DROP CHECK ck_oa_document_overtime_type",
                        "DROP INDEX ix_oa_document_overtime_type",
                        "DROP COLUMN overtime_type")
                .doesNotContain("oa_overtime_evidence");
    }

    @Test
    void rollbackRemovesEveryLeaveClassificationAddition()
            throws Exception {
        String sql = Files.readString(ROLLBACK);

        assertThat(sql).contains(
                "ALTER TABLE oa_attendance_document",
                "DROP CHECK ck_oa_document_leave_type",
                "DROP INDEX ix_oa_document_leave_type",
                "ALTER TABLE attendance_report_daily_fact",
                "DROP CHECK ck_att_report_daily_leave_type",
                "DROP INDEX ix_att_report_daily_leave_type");
    }

    @Test
    void overtimeMappingsMatchTheReviewedOaEvidenceSnapshot()
            throws Exception {
        String evidence = Files.readString(OA_EVIDENCE);

        assertThat(evidence).contains(
                "### OA-B5-03C 加班类别完整映射",
                "| `-6539634143789166714` | 加班费 | 112022 |",
                "| `5912806790045781226` | 调休 | 5066 |",
                "| `4337518111002608138` | 义务加班 | 89 |",
                "| `<NULL>` | `<RAW_NULL>` | 30 |",
                "四组合计 117207");
        assertThat(OvertimeType.fromOaEnumId(-6539634143789166714L))
                .isEqualTo(OvertimeType.PAID);
        assertThat(OvertimeType.fromOaEnumId(5912806790045781226L))
                .isEqualTo(OvertimeType.COMPENSATORY);
        assertThat(OvertimeType.fromOaEnumId(4337518111002608138L))
                .isEqualTo(OvertimeType.VOLUNTARY);
        assertThat(OvertimeType.fromOaEnumId(null)).isNull();
    }

    @Test
    void everyReviewedOaScreenshotMatchesItsRecordedSha256()
            throws Exception {
        String evidence = Files.readString(OA_EVIDENCE);
        Matcher matcher = SCREENSHOT_DIGEST_ROW.matcher(evidence);
        var reviewedScreenshots = new HashSet<Path>();

        while (matcher.find()) {
            Path relativePath = Path.of(matcher.group(1));
            Path screenshot = OA_EVIDENCE_DIRECTORY.resolve(relativePath);
            assertThat(Files.isRegularFile(screenshot))
                    .as("reviewed OA screenshot %s exists", relativePath)
                    .isTrue();
            assertThat(sha256(screenshot))
                    .as("reviewed OA screenshot %s is unchanged", relativePath)
                    .isEqualTo(matcher.group(2));
            assertThat(reviewedScreenshots.add(relativePath))
                    .as("each reviewed screenshot has one canonical digest row")
                    .isTrue();
        }

        assertThat(reviewedScreenshots)
                .as("all 2026-08-10 reviewed OA screenshots are hash-bound")
                .hasSize(45);
        try (var files = Files.list(
                OA_EVIDENCE_DIRECTORY.resolve("screenshots"))) {
            assertThat(files
                    .filter(path -> path.getFileName().toString()
                            .endsWith(".png"))
                    .map(path -> Path.of(
                            "screenshots",
                            path.getFileName().toString())))
                    .containsExactlyInAnyOrderElementsOf(reviewedScreenshots);
        }
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(path)));
    }
}
