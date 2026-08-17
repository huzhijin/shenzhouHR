package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Locale;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class AttendancePolicyCalculationMapperContractTest {

    private static final Path CALCULATION_MAPPER = Path.of(
            "src/main/resources/mappers/AttendanceReportCalculationMapper.xml");
    private static final Path ORCHESTRATOR = Path.of(
            "src/main/java/com/szsemicon/hr/reporting/infrastructure/"
                    + "orchestrator/FullCalculationEngineOrchestrator.java");

    @Test
    void policyMapperParsesAndRequiresBusinessAndKnowledgeTime() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mappers/AttendanceReportCalculationMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(
                            input,
                            configuration,
                            resource,
                            configuration.getSqlFragments())
                    .parse();
        }

        assertThat(configuration.getMappedStatementNames()).contains(
                "com.szsemicon.hr.reporting.infrastructure.orchestrator."
                        + "AttendanceReportCalculationMapper."
                        + "findAttendancePolicies");

        Class<?> mapper = Class.forName(
                "com.szsemicon.hr.reporting.infrastructure.orchestrator."
                        + "AttendanceReportCalculationMapper");
        Method method = mapper.getDeclaredMethod(
                "findAttendancePolicies",
                String.class,
                LocalDate.class,
                LocalDate.class,
                Instant.class);
        assertThat(param(method, "companyId")).isNotNull();
        assertThat(param(method, "periodStart")).isNotNull();
        assertThat(param(method, "periodEndExclusive")).isNotNull();
        assertThat(param(method, "dataAsOf")).isNotNull();
    }

    @Test
    void lateGraceFollowsEffectiveEmployeeGroupAndPreservesCardinality()
            throws Exception {
        String statement = policyStatement();

        assertThat(statement).contains(
                "with recursive business_dates",
                "attendance_group_assignment assignment",
                "attendance_assignment_timeline assignment_state",
                "attendance_group_revision group_revision",
                "attendance_group_timeline group_state",
                "attendance_policy_binding_family family",
                "attendance_policy_binding_revision revision",
                "family.policy_kind in ( 'late_grace', "
                        + "'monthly_late_exemption')",
                "revision.attendance_group_revision_id = "
                        + "authority.attendance_group_revision_id",
                "revision.change_reason not like "
                        + "'@@effectiveto=____-__-__%'",
                "substring( revision.change_reason, 15, 10)",
                "policy_scope.company_id = #{companyid}",
                "authority.business_date",
                "recorded_at &lt;= #{dataasof}",
                "created_at &lt;= #{dataasof}",
                "count(distinct concat_ws(",
                "as attendance_group_authority_count",
                "as late_grace_policy_count",
                "as monthly_late_exemption_policy_count");
        assertThat(statement).doesNotContain(" having ");
    }

    @Test
    void deadlinesComeFromPublishedPoliciesWithoutDatabaseWallClock()
            throws Exception {
        String statement = policyStatement();
        String orchestrator = normalized(Files.readString(ORCHESTRATOR));

        assertThat(statement).contains(
                "'missing_punch', 'overtime_recognition'",
                "'$.correctionwindowdays'",
                "'$.deadlinemode'",
                "'$.submissiondeadlinehours'",
                "publication.action = 'published'",
                "later.action in ( 'deactivate_scheduled', 'rolled_back')",
                "as missing_punch_policy_count",
                "as overtime_policy_count");
        assertThat(statement).doesNotContain(
                "from dual",
                "curdate(",
                "current_date",
                "last_day(");
        assertThat(orchestrator).contains(
                ".plusdays((long) policyrow.correctionwindowdays() + 1)",
                "math.multiplyexact( "
                        + "policyrow.overtimesubmissiondeadlinehours(), 60)");
    }

    @Test
    void sourceVersionsUseCommittedDigestsAtTheKnowledgeCutoff()
            throws Exception {
        String mapperXml = normalized(Files.readString(CALCULATION_MAPPER));
        String statement = section(
                mapperXml,
                "<select id=\"findattendancesourceversions\"",
                "</select>");

        assertThat(statement).contains(
                "source.company_id = #{companyid}",
                "source.status = 'active'",
                "source.source_type in ('deli_cloud', 'oa_attendance')",
                "watermark.committed_at &lt;= #{dataasof}",
                "watermark.committed_page_digest",
                "candidate.created_at &lt;= #{dataasof}");
        assertThat(statement).doesNotContain("committed_cursor");

        Class<?> mapper = Class.forName(
                "com.szsemicon.hr.reporting.infrastructure.orchestrator."
                        + "AttendanceReportCalculationMapper");
        Method method = mapper.getDeclaredMethod(
                "findAttendanceSourceVersions",
                String.class,
                Instant.class);
        assertThat(param(method, "companyId")).isNotNull();
        assertThat(param(method, "dataAsOf")).isNotNull();
    }

    private static String policyStatement() throws Exception {
        String mapper = normalized(Files.readString(CALCULATION_MAPPER));
        return section(
                mapper,
                "<select id=\"findattendancepolicies\"",
                "</select>");
    }

    private static Parameter param(Method method, String name) {
        return Arrays.stream(method.getParameters())
                .filter(parameter -> {
                    Param annotation = parameter.getAnnotation(Param.class);
                    return annotation != null && annotation.value().equals(name);
                })
                .findFirst()
                .orElse(null);
    }

    private static String section(
            String source,
            String start,
            String end) {
        int startIndex = source.indexOf(start);
        assertThat(startIndex).isGreaterThanOrEqualTo(0);
        int endIndex = source.indexOf(end, startIndex);
        assertThat(endIndex).isGreaterThan(startIndex);
        return source.substring(startIndex, endIndex + end.length());
    }

    private static String normalized(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }
}
