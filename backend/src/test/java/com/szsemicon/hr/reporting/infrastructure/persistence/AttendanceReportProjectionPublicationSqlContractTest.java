package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class AttendanceReportProjectionPublicationSqlContractTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V10__formal_attendance_reporting.sql");
    private static final Path COMPANY_MIGRATION = Path.of(
            "src/main/resources/db/migration/"
                    + "V11__unify_company_dimension.sql");
    private static final Path MAPPER = Path.of(
            "src/main/resources/mappers/"
                    + "AttendanceReportProjectionWriteMapper.xml");

    @Test
    void projectionSchemaMakesContentIdempotentAndDraftPublishExplicit()
            throws Exception {
        String sql = normalize(Files.readString(MIGRATION));
        String companySql = normalize(Files.readString(COMPANY_MIGRATION));

        assertThat(sql)
                .contains("UNIQUE KEY uq_att_report_projection_content "
                        + "(legal_entity_id, period_start, "
                        + "projection_digest)")
                .contains("CHECK (status IN ('DRAFT', 'PUBLISHED'))")
                .contains("(status = 'DRAFT' AND published_at IS NULL)")
                .contains("(status = 'PUBLISHED' "
                        + "AND published_at IS NOT NULL)");
        assertThat(companySql).contains(
                "ALTER TABLE attendance_report_projection "
                        + "RENAME COLUMN legal_entity_id TO company_id, "
                        + "ALGORITHM = INPLACE;");
    }

    @Test
    void everyFactInsertIsDraftBoundAndUsesTrustedIdentityJoins()
            throws Exception {
        String sql = normalize(Files.readString(MAPPER));

        assertThat(sql)
                .contains("projection.status = 'DRAFT'")
                .contains("JOIN employee_version employee_version")
                .contains("JOIN employment_assignment employment")
                .contains("JOIN organization_version organization_version")
                .contains("JOIN oa_attendance_document document")
                .contains("normalized.validation_status = 'VALID'")
                .contains("match_decision.match_status = 'MATCHED'")
                .contains("document.source_version = #{sourceVersion}")
                .contains("ORDER BY projection.published_at DESC, "
                        + "projection.attendance_report_projection_id DESC "
                        + "LIMIT 1 FOR UPDATE")
                .contains("AND status = 'DRAFT' "
                        + "AND published_at IS NULL");
    }

    @Test
    void oaAndTimeAccountJoinsMustNotBeClaimedAsTemporalValidation()
            throws Exception {
        String xml = Files.readString(MAPPER);
        String oaInsert = mappedInsert(xml, "insertOaDocumentFact");
        String timeAccountInsert =
                mappedInsert(xml, "insertTimeAccountFact");

        assertThat(oaInsert)
                .contains("OA_TEMPORAL_IDENTITY_ANCHOR_NOT_SIGNED")
                .contains(
                        "employee_version.employee_version_id "
                                + "= #{employeeVersionId}",
                        "employment.assignment_id = "
                                + "match_decision.employment_period_id",
                        "organization_version.organization_version_id "
                                + "= #{organizationVersionId}")
                .doesNotContain(
                        "employee_version.effective_from",
                        "employee_version.effective_to",
                        "employment.effective_from",
                        "employment.effective_to",
                        "organization_version.effective_from",
                        "organization_version.effective_to");

        assertThat(timeAccountInsert)
                .contains(
                        "TIME_ACCOUNT_IDENTITY_SNAPSHOT_ANCHOR_NOT_SIGNED")
                .contains(
                        "employee_version.employee_version_id "
                                + "= #{employeeVersionId}",
                        "organization_version.organization_version_id "
                                + "= #{organizationVersionId}")
                .doesNotContain(
                        "employee_version.effective_from",
                        "employee_version.effective_to",
                        "employment_assignment",
                        "organization_version.effective_from",
                        "organization_version.effective_to");
    }

    @Test
    void publicationMapperCannotOverwriteOrDeleteFactRows()
            throws Exception {
        String sql = normalize(Files.readString(MAPPER))
                .toLowerCase(Locale.ROOT);

        assertThat(sql)
                .doesNotContain(
                        "update attendance_report_daily_fact",
                        "update attendance_report_oa_fact",
                        "update attendance_report_exception_fact",
                        "update attendance_report_time_account_fact",
                        "delete from attendance_report_daily_fact",
                        "delete from attendance_report_oa_fact",
                        "delete from attendance_report_exception_fact",
                        "delete from attendance_report_time_account_fact");
    }

    private static String normalize(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private static String mappedInsert(String xml, String id) {
        String openingTag = "<insert id=\"" + id + "\">";
        int statementStart = xml.indexOf(openingTag);
        int commentStart = xml.lastIndexOf("<!--", statementStart);
        int statementEnd = xml.indexOf("</insert>", statementStart);

        assertThat(statementStart).isNotNegative();
        assertThat(commentStart).isNotNegative();
        assertThat(statementEnd).isGreaterThan(statementStart);
        return normalize(xml.substring(
                commentStart, statementEnd + "</insert>".length()));
    }
}
