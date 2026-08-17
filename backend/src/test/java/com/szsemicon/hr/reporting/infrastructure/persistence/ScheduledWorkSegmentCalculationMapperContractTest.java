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

class ScheduledWorkSegmentCalculationMapperContractTest {

    private static final Path CALCULATION_MAPPER = Path.of(
            "src/main/resources/mappers/AttendanceReportCalculationMapper.xml");
    private static final Path V7 = Path.of(
            "src/main/resources/db/migration/"
                    + "V7__attendance_setup_and_base_policies.sql");
    private static final Path V11 = Path.of(
            "src/main/resources/db/migration/V11__unify_company_dimension.sql");
    private static final Path V35 = Path.of(
            "src/main/resources/db/migration/"
                    + "V35__punch_window_and_period_close_policies.sql");

    @Test
    void mapperParsesAndSchedulingReadsOnlyRealMigratedTables() throws Exception {
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
                        + "findScheduledWorkSegments");

        String statement = scheduledStatement();
        assertThat(statement).contains(
                "attendance_group_assignment",
                "attendance_assignment_timeline",
                "attendance_group_revision",
                "attendance_group_timeline",
                "location_timeline",
                "calendar_publication_timeline",
                "shift_publication_timeline",
                "json_table(",
                "effective_shift.segments_json",
                "'$.segments[*]' columns",
                "segment_type varchar(16) path '$.segmenttype'");
        assertThat(statement).doesNotContain(
                "attendance_group_membership",
                "shift_segment",
                "publication_state",
                ".group_revision_id");

        String v7 = normalized(Files.readString(V7));
        String v11 = normalized(Files.readString(V11));
        assertThat(v7).contains(
                "create table attendance_group_assignment",
                "attendance_group_revision_id",
                "create table attendance_assignment_timeline",
                "create table attendance_group_timeline",
                "create table shift_publication_timeline",
                "segments_json json not null",
                "create table calendar_publication_timeline",
                "'workday', 'weekend', 'public_holiday', 'special_workday'");
        assertThat(v11).contains(
                "alter table attendance_group rename column legal_entity_id to company_id",
                "alter table shift_template rename column legal_entity_id to company_id",
                "alter table work_calendar rename column legal_entity_id to company_id");
    }

    @Test
    void schedulingIsCompanyBusinessDateAndKnowledgeTimeScoped() throws Exception {
        String statement = scheduledStatement();

        assertThat(statement).contains(
                "from company company join employee employee on "
                        + "employee.company_id = company.company_id",
                "where company.company_id = #{companyid}",
                "company.status = 'active'",
                "attendance_group.company_id = employee.company_id",
                "work_calendar.company_id = employee.company_id",
                "group_shift_template.company_id = employee.company_id",
                "effective_shift_template.company_id = employee.company_id",
                "organization.company_id = employee.company_id",
                "effective_shift.time_zone_snapshot = 'asia/shanghai'",
                "assignment.effective_from &lt;= calendar_day.business_date",
                "calendar_version.effective_from &lt;= calendar_day.business_date",
                "calendar_version.effective_to &gt; calendar_day.business_date",
                "assignment_state.recorded_at &lt;= #{dataasof}",
                "group_state.recorded_at &lt;= #{dataasof}",
                "calendar_publication.recorded_at &lt;= #{dataasof}",
                "shift_publication.recorded_at &lt;= #{dataasof}",
                "having count(*) = 1",
                "having count(distinct schedule_authority_key) = 1");
        assertThat(statement).contains(
                "latest_group_template.resource_type = 'shift_template'",
                "latest_effective_template.resource_type = 'shift_template'",
                "latest_group_template.occurred_at &lt;= #{dataasof}",
                "latest_effective_template.occurred_at &lt;= #{dataasof}",
                ") &lt;&gt; 'shift_template_inactive'");
        assertThat(statement).contains(
                "from attendance_group_assignment successor",
                "from attendance_assignment_timeline newer_assignment",
                "from attendance_group_timeline newer_group",
                "from calendar_publication_timeline newer_calendar",
                "from shift_publication_timeline newer_shift");
    }

    @Test
    void workSegmentsPreserveOvernightOffsetsAndSpecialWorkdays() throws Exception {
        String statement = scheduledStatement();

        assertThat(statement).contains(
                "calendar_day.day_type in ( 'workday', 'special_workday')",
                "segment.start_day_offset between 0 and 1",
                "segment.end_day_offset between segment.start_day_offset and 1",
                "interval segment.start_day_offset day",
                "interval segment.end_day_offset day",
                "segment.segment_type = 'work'",
                "segment_order for ordinality",
                "start_local_time varchar(8) path '$.startlocaltime'",
                "end_local_time varchar(8) path '$.endlocaltime'",
                "cast(segment.start_local_time as time)",
                "cast(segment.end_local_time as time)",
                "convert_tz(",
                "'+08:00', '+00:00') as segment_start_utc",
                "'+08:00', '+00:00') as segment_end_utc",
                "schedule.segment_start_utc as segmentstart",
                "schedule.segment_end_utc as segmentend");
        assertThat(statement).contains(
                "calendar_day.shift_version_override_id is null",
                "effective_shift.shift_version_id = calendar_day.shift_version_override_id");
    }

    @Test
    void punchWindowsRequireOnePublishedCompanyPolicyAndAreNotInvented()
            throws Exception {
        String statement = scheduledStatement();
        String v35 = normalized(Files.readString(V35));

        assertThat(statement).contains(
                "template.template_code = 'punch_window'",
                "policy_scope.company_id = #{companyid}",
                "publication.action = 'published'",
                "publication.recorded_at &lt;= #{dataasof}",
                "later.action in ( 'deactivate_scheduled', 'rolled_back')",
                "unique_punch_window_policy",
                "having count(*) = 1",
                "interval punch_window.arrival_before_minutes minute",
                "interval punch_window.arrival_after_minutes minute",
                "interval punch_window.departure_before_minutes minute",
                "interval punch_window.departure_after_minutes minute");
        assertThat(v35).contains(
                "'punch_window'",
                "'arrivalbeforeminutes'",
                "'arrivalafterminutes'",
                "'departurebeforeminutes'",
                "'departureafterminutes'",
                "insert into attendance_policy_lifecycle_event");
    }

    @Test
    void schedulingAndCalendarMapperMethodsRequireDataAsOf() throws Exception {
        Class<?> mapper = Class.forName(
                "com.szsemicon.hr.reporting.infrastructure.orchestrator."
                        + "AttendanceReportCalculationMapper");
        Method schedule = mapper.getDeclaredMethod(
                "findScheduledWorkSegments",
                String.class,
                LocalDate.class,
                LocalDate.class,
                Instant.class);
        Method calendar = mapper.getDeclaredMethod(
                "findPublishedCalendarDays",
                String.class,
                LocalDate.class,
                LocalDate.class,
                Instant.class);

        assertThat(param(schedule, "dataAsOf")).isNotNull();
        assertThat(param(calendar, "dataAsOf")).isNotNull();

        String mapperXml = normalized(Files.readString(CALCULATION_MAPPER));
        String calendarStatement = section(
                mapperXml,
                "<select id=\"findpublishedcalendardays\"",
                "</select>");
        assertThat(calendarStatement).contains(
                "timeline.recorded_at &lt;= #{dataasof}",
                "newer.recorded_at &lt;= #{dataasof}",
                "newer.business_effective_from &lt;= day.business_date",
                "calendar.company_id = #{companyid}");
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

    private static String scheduledStatement() throws Exception {
        String mapper = normalized(Files.readString(CALCULATION_MAPPER));
        return section(
                mapper,
                "<select id=\"findscheduledworksegments\"",
                "</select>");
    }

    private static String section(String value, String start, String end) {
        int from = value.indexOf(start);
        assertThat(from).as("section start").isGreaterThanOrEqualTo(0);
        int to = value.indexOf(end, from);
        assertThat(to).as("section end").isGreaterThan(from);
        return value.substring(from, to);
    }

    private static String normalized(String value) {
        return value.replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
