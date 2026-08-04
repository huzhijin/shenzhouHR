package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class SelfAttendanceDashboardMapperXmlTest {

    private static final String RESOURCE =
            "mappers/SelfAttendanceDashboardMapper.xml";
    private static final String NAMESPACE =
            "com.szsemicon.hr.reporting.infrastructure.persistence."
                    + "SelfAttendanceDashboardMapper.";

    @Test
    void parsesRegistersAndPreparesEverySelfDashboardRead()
            throws Exception {
        Configuration configuration = configuration();
        var statements = java.util.List.of(
                "resolveAuthorizedSelf",
                "listLatestPublishedSelfProjection",
                "listSelfDailyFacts",
                "listSelfDailyIssueCounts",
                "listSelfTodayIssueLabels",
                "listSelfExceptionTypeDistribution",
                "listSelfRecentExceptions");
        assertThat(configuration.getMappedStatementNames())
                .contains(statements.stream()
                        .map(name -> NAMESPACE + name)
                        .toArray(String[]::new));
        var parameters = Map.ofEntries(
                Map.entry("principalId", "principal-self"),
                Map.entry(
                        "capabilityCode",
                        "ATTENDANCE_SELF:READ"),
                Map.entry("companyId", "company-a"),
                Map.entry("projectionId", "projection-a"),
                Map.entry(
                        "periodStart",
                        LocalDate.of(2026, 7, 1)),
                Map.entry(
                        "periodEndExclusive",
                        LocalDate.of(2026, 8, 1)),
                Map.entry(
                        "trendStart",
                        LocalDate.of(2026, 7, 23)),
                Map.entry(
                        "businessDate",
                        LocalDate.of(2026, 7, 29)),
                Map.entry(
                        "authorizationTime",
                        Instant.parse("2026-07-29T01:00:00Z")));

        try (var connection = DriverManager.getConnection(
                "jdbc:h2:mem:self-dashboard-sql;MODE=MySQL")) {
            for (String ddl : PREPARE_SCHEMA) {
                connection.createStatement().execute(ddl);
            }
            for (String name : statements) {
                String sql = configuration
                        .getMappedStatement(NAMESPACE + name)
                        .getBoundSql(parameters)
                        .getSql();
                try (var statement =
                        connection.prepareStatement(sql)) {
                    assertThat(statement).isNotNull();
                }
            }
        }
    }

    private static Configuration configuration() throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream input =
                Resources.getResourceAsStream(RESOURCE)) {
            new XMLMapperBuilder(
                            input,
                            configuration,
                            RESOURCE,
                            configuration.getSqlFragments())
                    .parse();
        }
        return configuration;
    }

    private static final String[] PREPARE_SCHEMA = {
        """
        CREATE TABLE auth_principal (
            principal_id VARCHAR(36),
            employee_id VARCHAR(36),
            status VARCHAR(32)
        )
        """,
        """
        CREATE TABLE employee (
            employee_id VARCHAR(36),
            company_id VARCHAR(36)
        )
        """,
        """
        CREATE TABLE company (
            company_id VARCHAR(36),
            status VARCHAR(32)
        )
        """,
        """
        CREATE TABLE employee_current_projection (
            employee_id VARCHAR(36),
            current_version_id VARCHAR(36)
        )
        """,
        """
        CREATE TABLE employee_version (
            employee_version_id VARCHAR(36),
            employee_id VARCHAR(36),
            status VARCHAR(32),
            effective_from DATE,
            effective_to DATE
        )
        """,
        """
        CREATE TABLE auth_principal_role_assignment (
            principal_id VARCHAR(36),
            role_id VARCHAR(36),
            data_scope_id VARCHAR(36),
            valid_from TIMESTAMP,
            valid_to TIMESTAMP
        )
        """,
        """
        CREATE TABLE auth_role_capability (
            role_id VARCHAR(36),
            capability_id VARCHAR(36)
        )
        """,
        """
        CREATE TABLE auth_capability (
            capability_id VARCHAR(36),
            capability_code VARCHAR(128)
        )
        """,
        """
        CREATE TABLE auth_data_scope (
            scope_id VARCHAR(36),
            scope_type VARCHAR(32),
            company_id VARCHAR(36),
            organization_id VARCHAR(36),
            valid_from TIMESTAMP,
            valid_to TIMESTAMP
        )
        """,
        """
        CREATE TABLE attendance_report_projection (
            attendance_report_projection_id VARCHAR(36),
            company_id VARCHAR(36),
            period_start DATE,
            period_end_exclusive DATE,
            projection_version VARCHAR(128),
            source_versions_json VARCHAR(1000),
            data_as_of TIMESTAMP,
            period_state VARCHAR(32),
            status VARCHAR(32),
            published_at TIMESTAMP
        )
        """,
        """
        CREATE TABLE attendance_report_daily_fact (
            attendance_report_projection_id VARCHAR(36),
            company_id VARCHAR(36),
            employee_id VARCHAR(36),
            business_date DATE,
            shift_label VARCHAR(200),
            scheduled_minutes BIGINT,
            confirmed_scheduled_work_minutes BIGINT,
            recognized_overtime_minutes BIGINT,
            leave_or_time_off_minutes BIGINT,
            first_punch_at TIMESTAMP,
            last_punch_at TIMESTAMP
        )
        """,
        """
        CREATE TABLE attendance_report_exception_fact (
            attendance_report_projection_id VARCHAR(36),
            company_id VARCHAR(36),
            employee_id VARCHAR(36),
            exception_case_id VARCHAR(128),
            business_date DATE,
            exception_type VARCHAR(64),
            severity VARCHAR(16),
            state VARCHAR(32),
            exception_minutes BIGINT,
            safe_evidence_summary VARCHAR(500)
        )
        """
    };
}
