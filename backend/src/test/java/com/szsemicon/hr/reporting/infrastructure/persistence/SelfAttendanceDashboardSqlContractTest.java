package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SelfAttendanceDashboardSqlContractTest {

    private static final Path MAPPER = Path.of(
            "src/main/resources/mappers/"
                    + "SelfAttendanceDashboardMapper.xml");

    @Test
    void authorizationRequiresExactSelfCapabilityBindingAndSelfScope()
            throws Exception {
        String xml = Files.readString(MAPPER);
        String projectionVisibility = between(
                xml,
                "<sql id=\"selfProjectionVisibility\">",
                "</sql>");
        String factVisibility = between(
                xml,
                "<sql id=\"selfFactVisibility\">",
                "</sql>");
        String authorization =
                select(xml, "resolveAuthorizedSelf");

        for (String predicate : List.of(
                projectionVisibility,
                factVisibility,
                authorization)) {
            assertThat(predicate)
                    .contains(
                            "capability.capability_code ="
                                    + " #{capabilityCode}")
                    .contains("'ATTENDANCE_SELF:READ'")
                    .contains("principal.status = 'ACTIVE'")
                    .contains("principal.employee_id IS NOT NULL")
                    .contains("employee_version.status = 'ACTIVE'")
                    .contains("data_scope.scope_type = 'SELF'")
                    .contains("data_scope.company_id IS NULL")
                    .contains(
                            "data_scope.organization_id IS NULL")
                    .contains("role_assignment.valid_from")
                    .contains("role_assignment.valid_to")
                    .contains("data_scope.valid_from")
                    .contains("data_scope.valid_to")
                    .doesNotContain(
                            "ATTENDANCE_DASHBOARD:READ",
                            "ATTENDANCE_REPORT:READ",
                            "role_code",
                            "scope_type = 'COMPANY'",
                            "scope_type = 'ORGANIZATION'",
                            "${");
        }
        assertThat(factVisibility)
                .contains(
                        "principal.employee_id = fact.employee_id")
                .contains(
                        "self_employee.employee_id = fact.employee_id")
                .contains(
                        "self_employee.company_id = fact.company_id")
                .contains("projection.status = 'PUBLISHED'");
    }

    @Test
    void projectionIsLatestPublishedForTheBoundEmployeesCompany()
            throws Exception {
        String query = select(
                Files.readString(MAPPER),
                "listLatestPublishedSelfProjection");

        assertThat(query)
                .contains("projection.company_id = #{companyId}")
                .contains("projection.status = 'PUBLISHED'")
                .contains(
                        "projection.published_at"
                                + " &lt;= #{authorizationTime}")
                .contains(
                        "<include refid=\"selfProjectionVisibility\"/>")
                .contains("NOT EXISTS (")
                .contains("newer_projection.published_at")
                .contains("LIMIT 1")
                .doesNotContain("${");
    }

    @Test
    void everyFactQueryReappliesSelfVisibilityAndReturnsSafeFieldsOnly()
            throws Exception {
        String xml = Files.readString(MAPPER);
        String daily = select(xml, "listSelfDailyFacts");
        String issueCounts =
                select(xml, "listSelfDailyIssueCounts");
        String todayLabels =
                select(xml, "listSelfTodayIssueLabels");
        String distribution =
                select(xml, "listSelfExceptionTypeDistribution");
        String recent =
                select(xml, "listSelfRecentExceptions");

        for (String query : List.of(
                daily,
                issueCounts,
                todayLabels,
                distribution,
                recent)) {
            assertThat(query)
                    .contains(
                            "fact.attendance_report_projection_id"
                                    + " = #{projectionId}")
                    .contains("fact.company_id = #{companyId}")
                    .contains(
                            "<include refid=\"selfFactVisibility\"/>")
                    .doesNotContain("${");
            assertThat(selectList(query))
                    .doesNotContain(
                            "employee_id",
                            "employee_number",
                            "employee_name",
                            "organization_id",
                            "organization_name",
                            "company_id",
                            "raw_punch",
                            "device",
                            "latitude",
                            "longitude",
                            "payroll");
        }
        for (String query : List.of(
                issueCounts,
                todayLabels,
                distribution,
                recent)) {
            assertThat(query)
                    .contains(
                            "'OPEN', 'PENDING_EVIDENCE',"
                                    + " 'PENDING_REVIEW'")
                    .doesNotContain("'RESOLVED'");
        }
        assertThat(daily)
                .contains(
                        "fact.confirmed_scheduled_work_minutes")
                .contains(
                        "fact.leave_or_time_off_minutes")
                .contains("ORDER BY fact.business_date");
        assertThat(distribution)
                .contains("GROUP BY fact.exception_type")
                .contains(
                        "ORDER BY count DESC, fact.exception_type");
        assertThat(recent)
                .contains("fact.safe_evidence_summary")
                .contains("fact.business_date DESC")
                .contains("LIMIT 10");
        assertThat(selectList(recent))
                .doesNotContain("exception_case_id");
    }

    private static String selectList(String query) {
        return between(query, "SELECT", "FROM");
    }

    private static String select(String xml, String id) {
        return between(xml, "<select id=\"" + id + "\"", "</select>");
    }

    private static String between(
            String value, String start, String end) {
        int startIndex = value.indexOf(start);
        int endIndex = value.indexOf(
                end, startIndex + start.length());
        return value.substring(startIndex, endIndex + end.length());
    }
}
