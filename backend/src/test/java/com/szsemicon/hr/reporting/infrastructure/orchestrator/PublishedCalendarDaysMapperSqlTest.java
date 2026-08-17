package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.reporting.infrastructure.orchestrator
        .AttendanceReportCalculationRows.CalendarDayRow;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;

/** Executable H2 contract for employee-scoped calendar resolution. */
class PublishedCalendarDaysMapperSqlTest {

    private static final String RESOURCE =
            "mappers/AttendanceReportCalculationMapper.xml";

    @Test
    void mapsEachEmployeesAttendanceGroupCalendarIntoFiveColumnRows()
            throws Exception {
        Configuration configuration = configuration();
        try (Connection connection = DriverManager.getConnection(
                        "jdbc:h2:mem:published-calendar-"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
                SqlSession session = new SqlSessionFactoryBuilder()
                        .build(configuration)
                        .openSession(connection)) {
            createSchema(connection);
            seedTwoEmployeeCalendars(connection);

            List<CalendarDayRow> rows = session
                    .getMapper(AttendanceReportCalculationMapper.class)
                    .findPublishedCalendarDays(
                            "company-1",
                            LocalDate.parse("2026-08-01"),
                            LocalDate.parse("2026-09-01"),
                            Instant.parse("2026-08-17T04:00:00Z"));

            assertThat(rows)
                    .containsExactly(
                            new CalendarDayRow(
                                    "employee-a",
                                    LocalDate.parse("2026-08-15"),
                                    "WORKDAY",
                                    1,
                                    1),
                            new CalendarDayRow(
                                    "employee-b",
                                    LocalDate.parse("2026-08-15"),
                                    "PUBLIC_HOLIDAY",
                                    1,
                                    1));
        }
    }

    private static Configuration configuration() throws Exception {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        try (InputStream input = Resources.getResourceAsStream(RESOURCE)) {
            // DAY is accepted as a table alias by MySQL but reserved by H2.
            // Keep the production statement intact and adapt only that alias
            // in this executable compatibility test.
            String mapper = new String(
                    input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace(
                            "JOIN work_calendar_day day",
                            "JOIN work_calendar_day calendar_day")
                    .replace("day.", "calendar_day.");
            new XMLMapperBuilder(
                            new ByteArrayInputStream(
                                    mapper.getBytes(StandardCharsets.UTF_8)),
                            configuration,
                            RESOURCE,
                            configuration.getSqlFragments())
                    .parse();
        }
        return configuration;
    }

    private static void createSchema(Connection connection) throws Exception {
        for (String statement : SCHEMA) {
            connection.createStatement().execute(statement);
        }
    }

    private static void seedTwoEmployeeCalendars(Connection connection)
            throws Exception {
        for (String statement : DATA) {
            connection.createStatement().execute(statement);
        }
    }

    private static final String[] SCHEMA = {
        """
        CREATE TABLE company (
            company_id VARCHAR(36) PRIMARY KEY,
            status VARCHAR(32) NOT NULL,
            created_at TIMESTAMP NOT NULL
        )
        """,
        """
        CREATE TABLE employee (
            employee_id VARCHAR(36) PRIMARY KEY,
            company_id VARCHAR(36) NOT NULL,
            created_at TIMESTAMP NOT NULL
        )
        """,
        """
        CREATE TABLE organization_identity (
            organization_id VARCHAR(36) PRIMARY KEY,
            company_id VARCHAR(36) NOT NULL,
            identity_status VARCHAR(32) NOT NULL,
            created_at TIMESTAMP NOT NULL
        )
        """,
        """
        CREATE TABLE employment_assignment (
            assignment_id VARCHAR(36) PRIMARY KEY,
            employee_id VARCHAR(36) NOT NULL,
            organization_id VARCHAR(36) NOT NULL,
            effective_from TIMESTAMP NOT NULL,
            effective_to TIMESTAMP,
            record_status VARCHAR(32) NOT NULL,
            version_valid_to TIMESTAMP,
            created_at TIMESTAMP NOT NULL
        )
        """,
        """
        CREATE TABLE location (
            location_id VARCHAR(36) PRIMARY KEY,
            company_id VARCHAR(36) NOT NULL,
            created_at TIMESTAMP NOT NULL
        )
        """,
        """
        CREATE TABLE location_revision (
            location_revision_id VARCHAR(36) PRIMARY KEY,
            location_id VARCHAR(36) NOT NULL,
            time_zone VARCHAR(64) NOT NULL,
            effective_from DATE NOT NULL,
            created_at TIMESTAMP NOT NULL
        )
        """,
        """
        CREATE TABLE location_timeline (
            location_timeline_id VARCHAR(36) PRIMARY KEY,
            location_id VARCHAR(36) NOT NULL,
            location_revision_id VARCHAR(36) NOT NULL,
            event_sequence INTEGER NOT NULL,
            state VARCHAR(32) NOT NULL,
            business_effective_from DATE NOT NULL,
            recorded_at TIMESTAMP NOT NULL
        )
        """,
        """
        CREATE TABLE work_calendar (
            work_calendar_id VARCHAR(36) PRIMARY KEY,
            company_id VARCHAR(36) NOT NULL,
            location_id VARCHAR(36) NOT NULL,
            created_at TIMESTAMP NOT NULL
        )
        """,
        """
        CREATE TABLE work_calendar_version (
            work_calendar_version_id VARCHAR(36) PRIMARY KEY,
            work_calendar_id VARCHAR(36) NOT NULL,
            time_zone_snapshot VARCHAR(64) NOT NULL,
            effective_from DATE NOT NULL,
            effective_to DATE NOT NULL,
            created_at TIMESTAMP NOT NULL
        )
        """,
        """
        CREATE TABLE work_calendar_day (
            work_calendar_day_id VARCHAR(36) PRIMARY KEY,
            work_calendar_version_id VARCHAR(36) NOT NULL,
            business_date DATE NOT NULL,
            day_type VARCHAR(32) NOT NULL,
            created_at TIMESTAMP NOT NULL
        )
        """,
        """
        CREATE TABLE calendar_publication_timeline (
            calendar_publication_timeline_id VARCHAR(36) PRIMARY KEY,
            work_calendar_id VARCHAR(36) NOT NULL,
            work_calendar_version_id VARCHAR(36) NOT NULL,
            event_sequence INTEGER NOT NULL,
            state VARCHAR(32) NOT NULL,
            business_effective_from DATE NOT NULL,
            recorded_at TIMESTAMP NOT NULL
        )
        """,
        """
        CREATE TABLE attendance_group (
            attendance_group_id VARCHAR(36) PRIMARY KEY,
            company_id VARCHAR(36) NOT NULL,
            created_at TIMESTAMP NOT NULL
        )
        """,
        """
        CREATE TABLE attendance_group_revision (
            attendance_group_revision_id VARCHAR(36) PRIMARY KEY,
            attendance_group_id VARCHAR(36) NOT NULL,
            location_revision_id VARCHAR(36) NOT NULL,
            work_calendar_id VARCHAR(36) NOT NULL,
            effective_from DATE NOT NULL,
            created_at TIMESTAMP NOT NULL
        )
        """,
        """
        CREATE TABLE attendance_group_timeline (
            attendance_group_timeline_id VARCHAR(36) PRIMARY KEY,
            attendance_group_id VARCHAR(36) NOT NULL,
            attendance_group_revision_id VARCHAR(36) NOT NULL,
            event_sequence INTEGER NOT NULL,
            state VARCHAR(32) NOT NULL,
            business_effective_from DATE NOT NULL,
            recorded_at TIMESTAMP NOT NULL
        )
        """,
        """
        CREATE TABLE attendance_group_assignment (
            attendance_group_assignment_id VARCHAR(36) PRIMARY KEY,
            employee_id VARCHAR(36) NOT NULL,
            attendance_group_revision_id VARCHAR(36) NOT NULL,
            effective_from DATE NOT NULL,
            supersedes_assignment_id VARCHAR(36),
            created_at TIMESTAMP NOT NULL
        )
        """,
        """
        CREATE TABLE attendance_assignment_timeline (
            attendance_assignment_timeline_id VARCHAR(36) PRIMARY KEY,
            attendance_group_assignment_id VARCHAR(36) NOT NULL,
            employee_id VARCHAR(36) NOT NULL,
            event_sequence INTEGER NOT NULL,
            state VARCHAR(32) NOT NULL,
            business_effective_from DATE NOT NULL,
            recorded_at TIMESTAMP NOT NULL
        )
        """
    };

    private static final String[] DATA = {
        """
        INSERT INTO company VALUES (
            'company-1', 'ACTIVE', TIMESTAMP '2026-01-01 00:00:00')
        """,
        """
        INSERT INTO employee VALUES
          ('employee-a', 'company-1', TIMESTAMP '2026-01-01 00:00:00'),
          ('employee-b', 'company-1', TIMESTAMP '2026-01-01 00:00:00')
        """,
        """
        INSERT INTO organization_identity VALUES
          ('organization-a', 'company-1', 'ACTIVE',
           TIMESTAMP '2026-01-01 00:00:00'),
          ('organization-b', 'company-1', 'ACTIVE',
           TIMESTAMP '2026-01-01 00:00:00')
        """,
        """
        INSERT INTO employment_assignment VALUES
          ('employment-a', 'employee-a', 'organization-a',
           TIMESTAMP '2026-01-01 00:00:00', NULL, 'ACTIVE', NULL,
           TIMESTAMP '2026-01-01 00:00:00'),
          ('employment-b', 'employee-b', 'organization-b',
           TIMESTAMP '2026-01-01 00:00:00', NULL, 'ACTIVE', NULL,
           TIMESTAMP '2026-01-01 00:00:00')
        """,
        """
        INSERT INTO location VALUES
          ('location-a', 'company-1', TIMESTAMP '2026-01-01 00:00:00'),
          ('location-b', 'company-1', TIMESTAMP '2026-01-01 00:00:00')
        """,
        """
        INSERT INTO location_revision VALUES
          ('location-revision-a', 'location-a', 'Asia/Shanghai',
           DATE '2026-01-01', TIMESTAMP '2026-01-01 00:00:00'),
          ('location-revision-b', 'location-b', 'Asia/Shanghai',
           DATE '2026-01-01', TIMESTAMP '2026-01-01 00:00:00')
        """,
        """
        INSERT INTO location_timeline VALUES
          ('location-timeline-a', 'location-a', 'location-revision-a',
           1, 'ACTIVE', DATE '2026-01-01',
           TIMESTAMP '2026-01-01 00:00:00'),
          ('location-timeline-b', 'location-b', 'location-revision-b',
           1, 'ACTIVE', DATE '2026-01-01',
           TIMESTAMP '2026-01-01 00:00:00')
        """,
        """
        INSERT INTO work_calendar VALUES
          ('calendar-a', 'company-1', 'location-a',
           TIMESTAMP '2026-01-01 00:00:00'),
          ('calendar-b', 'company-1', 'location-b',
           TIMESTAMP '2026-01-01 00:00:00')
        """,
        """
        INSERT INTO work_calendar_version VALUES
          ('calendar-version-a', 'calendar-a', 'Asia/Shanghai',
           DATE '2026-01-01', DATE '2027-01-01',
           TIMESTAMP '2026-01-01 00:00:00'),
          ('calendar-version-b', 'calendar-b', 'Asia/Shanghai',
           DATE '2026-01-01', DATE '2027-01-01',
           TIMESTAMP '2026-01-01 00:00:00')
        """,
        """
        INSERT INTO work_calendar_day VALUES
          ('calendar-day-a', 'calendar-version-a', DATE '2026-08-15',
           'WORKDAY', TIMESTAMP '2026-01-01 00:00:00'),
          ('calendar-day-b', 'calendar-version-b', DATE '2026-08-15',
           'PUBLIC_HOLIDAY', TIMESTAMP '2026-01-01 00:00:00')
        """,
        """
        INSERT INTO calendar_publication_timeline VALUES
          ('calendar-publication-a', 'calendar-a', 'calendar-version-a',
           1, 'PUBLISHED', DATE '2026-01-01',
           TIMESTAMP '2026-01-01 00:00:00'),
          ('calendar-publication-b', 'calendar-b', 'calendar-version-b',
           1, 'PUBLISHED', DATE '2026-01-01',
           TIMESTAMP '2026-01-01 00:00:00')
        """,
        """
        INSERT INTO attendance_group VALUES
          ('group-a', 'company-1', TIMESTAMP '2026-01-01 00:00:00'),
          ('group-b', 'company-1', TIMESTAMP '2026-01-01 00:00:00')
        """,
        """
        INSERT INTO attendance_group_revision VALUES
          ('group-revision-a', 'group-a', 'location-revision-a',
           'calendar-a', DATE '2026-01-01',
           TIMESTAMP '2026-01-01 00:00:00'),
          ('group-revision-b', 'group-b', 'location-revision-b',
           'calendar-b', DATE '2026-01-01',
           TIMESTAMP '2026-01-01 00:00:00')
        """,
        """
        INSERT INTO attendance_group_timeline VALUES
          ('group-timeline-a', 'group-a', 'group-revision-a',
           1, 'ACTIVE', DATE '2026-01-01',
           TIMESTAMP '2026-01-01 00:00:00'),
          ('group-timeline-b', 'group-b', 'group-revision-b',
           1, 'ACTIVE', DATE '2026-01-01',
           TIMESTAMP '2026-01-01 00:00:00')
        """,
        """
        INSERT INTO attendance_group_assignment VALUES
          ('group-assignment-a', 'employee-a', 'group-revision-a',
           DATE '2026-01-01', NULL, TIMESTAMP '2026-01-01 00:00:00'),
          ('group-assignment-b', 'employee-b', 'group-revision-b',
           DATE '2026-01-01', NULL, TIMESTAMP '2026-01-01 00:00:00')
        """,
        """
        INSERT INTO attendance_assignment_timeline VALUES
          ('assignment-timeline-a', 'group-assignment-a', 'employee-a',
           1, 'ACTIVE', DATE '2026-01-01',
           TIMESTAMP '2026-01-01 00:00:00'),
          ('assignment-timeline-b', 'group-assignment-b', 'employee-b',
           1, 'ACTIVE', DATE '2026-01-01',
           TIMESTAMP '2026-01-01 00:00:00')
        """
    };
}
