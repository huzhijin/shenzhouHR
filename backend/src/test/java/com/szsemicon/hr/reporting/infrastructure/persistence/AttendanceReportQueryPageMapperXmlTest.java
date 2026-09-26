package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class AttendanceReportQueryPageMapperXmlTest {

    private static final String RESOURCE =
            "mappers/AttendanceReportQueryPageMapper.xml";
    private static final Path MAPPER = Path.of(
            "src/main/resources/mappers/AttendanceReportQueryPageMapper.xml");
    private static final String NAMESPACE =
            "com.szsemicon.hr.reporting.infrastructure.persistence."
                    + "AttendanceReportQueryPageMapper.";

    @Test
    void parsesOaDocumentStatements() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream input = Resources.getResourceAsStream(RESOURCE)) {
            new XMLMapperBuilder(
                    input,
                    configuration,
                    RESOURCE,
                    configuration.getSqlFragments())
                    .parse();
        }
        assertThat(configuration.getMappedStatementNames())
                .contains(
                        NAMESPACE + "countOaDocuments",
                        NAMESPACE + "listOaDocuments",
                        NAMESPACE + "listOaForEmployees",
                        NAMESPACE + "countMissedPunchStatEmployees",
                        NAMESPACE + "listMissedPunchStatEmployees",
                        NAMESPACE + "countLeaveStatAccounts",
                        NAMESPACE + "listLeaveStatAccounts",
                        NAMESPACE + "listMonthlyLeaveUsage");
    }

    @Test
    void oaDocumentQueriesReadOvertimeTypeColumnNotJsonExtract()
            throws Exception {
        String xml = Files.readString(MAPPER);
        String list = between(xml, "<select id=\"listOaDocuments\"", "</select>");
        String count = between(xml, "<select id=\"countOaDocuments\"", "</select>");
        String byEmployee = between(
                xml, "<select id=\"listOaForEmployees\"", "</select>");

        assertThat(xml)
                .contains("<sql id=\"oaDocumentType\">")
                .contains("IN ('LEAVE', 'TIME_OFF')")
                .contains("documentType != 'LEAVE' and documentType != 'OVERTIME'")
                .doesNotContain("JSON_EXTRACT")
                .doesNotContain("authorized_context_json");
        assertThat(list)
                .contains("context.overtime_type")
                .contains("<include refid=\"oaDocumentType\"/>");
        assertThat(count)
                .contains("<include refid=\"oaDocumentType\"/>")
                .doesNotContain("JSON_EXTRACT");
        assertThat(byEmployee)
                .contains("context.overtime_type")
                .contains("sourceOrigin");
    }

    @Test
    void exceptionHideAndDailyOvertimeIncludePendingMakeupAndPaidColumns()
            throws Exception {
        String xml = Files.readString(MAPPER);
        assertThat(xml)
                .contains("<sql id=\"hideApprovedOaCoveredExceptions\">")
                .contains("'UNKNOWN'")
                .contains("PUNCH_CORRECTION")
                .contains("countTimeOffDaily")
                .contains("listTimeOffDaily")
                .contains("paid_overtime_minutes")
                .contains("compensatory_overtime_minutes");
        String daily = between(
                xml, "<select id=\"listEmployeeDailyAggregates\"", "</select>");
        assertThat(daily)
                .contains("'TIME_OFF', 'COMPENSATORY'")
                .contains("'ANNUAL', 'ANNUAL_LEAVE'");
        String financeWhere = between(
                xml, "<sql id=\"financeOvertimePeopleWhere\">", "</sql>");
        assertThat(financeWhere)
                .contains("recognized_overtime_minutes")
                .contains("<include refid=\"authorizedEmployee\"/>");
        assertThat(financeWhere.split("dailyOvertimeTreatment").length - 1)
                .isEqualTo(1);
    }

    @Test
    void leaveStatAndMissedPunchStatQueriesAvoidSlowPerRowSubqueries()
            throws Exception {
        String xml = Files.readString(MAPPER);
        String leaveList = between(
                xml, "<select id=\"listLeaveStatAccounts\"", "</select>");
        String missedCount = between(
                xml, "<select id=\"countMissedPunchStatEmployees\"", "</select>");
        assertThat(leaveList)
                .contains("first_effective_from")
                .contains("DATE(first_hire.first_effective_from)")
                .contains("CAST(hire.effective_from AS CHAR)")
                .doesNotContain("employee.onboard_date")
                .doesNotContain("ORDER BY prior_service.row_version DESC")
                .doesNotContain("prior_service_record")
                .doesNotContain("LIMIT 1");
        assertThat(missedCount)
                .contains("missedPunchStatUniverse")
                .doesNotContain("OR EXISTS");
        assertThat(xml).contains("<sql id=\"missedPunchStatUniverse\">");
    }

    @Test
    void sourceEvidenceUsesEffectiveEventsNotOaDocumentColumns() throws Exception {
        String xml = Files.readString(MAPPER);
        String evidence = between(
                xml, "<select id=\"companyHasSourceEvidence\"", "</select>");
        assertThat(evidence)
                .contains("effective_attendance_event")
                .contains("OA_INTERVAL")
                .doesNotContain("oa_attendance_document")
                .doesNotContain("document.interval_start")
                .doesNotContain("document.employee_id");
        String usage = between(
                xml, "<select id=\"listMonthlyLeaveUsage\"", "</select>");
        assertThat(usage)
                .contains("CAST(COALESCE(SUM(documents.recognized_minutes), 0) AS SIGNED)")
                .contains("TIMESTAMPADD")
                .contains("oa_attendance_document_id")
                .contains("COMPENSATORY_OT")
                .contains("documents.kind")
                .contains("LIKE '%TIME_OFF%'")
                .doesNotContain("CONVERT_TZ");
    }

    private static String between(String xml, String start, String end) {
        int from = xml.indexOf(start);
        assertThat(from).isNotNegative();
        int to = xml.indexOf(end, from);
        assertThat(to).isGreaterThan(from);
        return xml.substring(from, to);
    }
}
