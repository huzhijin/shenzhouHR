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

class AttendanceReportMapperXmlTest {

    private static final String RESOURCE =
            "mappers/AttendanceReportMapper.xml";
    private static final String NAMESPACE =
            "com.szsemicon.hr.reporting.infrastructure.persistence."
                    + "AttendanceReportMapper.";

    @Test
    void parsesAndRegistersEveryAuthorizedReportRead() throws Exception {
        Configuration configuration = configuration();

        assertThat(configuration.getMappedStatementNames())
                .contains(
                        NAMESPACE + "listDashboardAuthorizedCompanies",
                        NAMESPACE
                                + "listLatestDashboardAuthorizedProjections",
                        NAMESPACE + "listDashboardAuthorizedScopes",
                        NAMESPACE + "summarizeDashboardExceptions",
                        NAMESPACE + "listDashboardDailyTrend",
                        NAMESPACE
                                + "listDashboardSeverityDistribution",
                        NAMESPACE + "listDashboardTypeDistribution",
                        NAMESPACE + "listDashboardOrganizationRanking",
                        NAMESPACE + "listDashboardExceptions",
                        NAMESPACE + "listAuthorizedCompanies",
                        NAMESPACE + "listLatestAuthorizedProjections",
                        NAMESPACE + "listAuthorizedScopes",
                        NAMESPACE + "listAuthorizedDailyFacts",
                        NAMESPACE + "listAuthorizedOaFacts",
                        NAMESPACE + "listAuthorizedExceptionFacts",
                        NAMESPACE + "listAuthorizedTimeAccountFacts");
    }

    @Test
    void latestProjectionSqlCanBePreparedAfterVisibilityIncludesExpand()
            throws Exception {
        var parameters = Map.of(
                "principalId", "principal-1",
                "capabilityCode", "ATTENDANCE_REPORT:READ",
                "periodStart", LocalDate.of(2026, 7, 1),
                "periodEndExclusive", LocalDate.of(2026, 8, 1),
                "companyId", "company-a",
                "authorizationTime",
                        Instant.parse("2026-07-29T00:00:00Z"));
        String sql = configuration()
                .getMappedStatement(
                        NAMESPACE + "listLatestAuthorizedProjections")
                .getBoundSql(parameters)
                .getSql();

        try (var connection = DriverManager.getConnection(
                "jdbc:h2:mem:attendance-report-sql-prepare;MODE=MySQL")) {
            for (String ddl : PREPARE_SCHEMA) {
                connection.createStatement().execute(ddl);
            }
            try (var statement = connection.prepareStatement(sql)) {
                assertThat(statement).isNotNull();
            }
        }
    }

    @Test
    void dashboardAnalyticsSqlCanBePreparedAfterVisibilityIncludesExpand()
            throws Exception {
        var parameters = Map.of(
                "principalId", "principal-1",
                "capabilityCode", "ATTENDANCE_DASHBOARD:READ",
                "projectionId", "projection-1",
                "companyId", "company-a",
                "trendStart", LocalDate.of(2026, 7, 23),
                "businessDate", LocalDate.of(2026, 7, 29),
                "authorizationTime",
                        Instant.parse("2026-07-29T00:00:00Z"));
        var configuration = configuration();

        try (var connection = DriverManager.getConnection(
                "jdbc:h2:mem:attendance-dashboard-analytics-sql;"
                        + "MODE=MySQL")) {
            for (String ddl : PREPARE_SCHEMA) {
                connection.createStatement().execute(ddl);
            }
            for (String statementName : java.util.List.of(
                    "listDashboardDailyTrend",
                    "listDashboardSeverityDistribution",
                    "listDashboardTypeDistribution",
                    "listDashboardOrganizationRanking")) {
                String sql = configuration
                        .getMappedStatement(NAMESPACE + statementName)
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
        try (InputStream input = Resources.getResourceAsStream(RESOURCE)) {
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
        CREATE TABLE attendance_report_projection (
            attendance_report_projection_id VARCHAR(36),
            company_id VARCHAR(36),
            period_start DATE,
            period_end_exclusive DATE,
            projection_version VARCHAR(128),
            period_state VARCHAR(32),
            source_versions_json VARCHAR(1000),
            data_as_of TIMESTAMP,
            status VARCHAR(32),
            published_at TIMESTAMP
        )
        """,
        """
        CREATE TABLE auth_principal (
            principal_id VARCHAR(36),
            status VARCHAR(32),
            employee_id VARCHAR(36)
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
            include_descendants BOOLEAN,
            valid_from TIMESTAMP,
            valid_to TIMESTAMP
        )
        """,
        """
        CREATE TABLE organization_identity (
            organization_id VARCHAR(36),
            company_id VARCHAR(36),
            identity_status VARCHAR(32)
        )
        """,
        """
        CREATE TABLE organization_current_projection (
            organization_id VARCHAR(36),
            current_version_id VARCHAR(36)
        )
        """,
        """
        CREATE TABLE organization_version (
            organization_version_id VARCHAR(36),
            organization_id VARCHAR(36),
            name VARCHAR(200),
            status VARCHAR(32),
            effective_from TIMESTAMP,
            effective_to TIMESTAMP
        )
        """,
        """
        CREATE TABLE employee (
            employee_id VARCHAR(36),
            company_id VARCHAR(36)
        )
        """,
        """
        CREATE TABLE attendance_report_exception_fact (
            attendance_report_projection_id VARCHAR(36),
            company_id VARCHAR(36),
            employee_id VARCHAR(36),
            organization_id VARCHAR(36),
            organization_version_id VARCHAR(36),
            business_date DATE,
            exception_type VARCHAR(64),
            severity VARCHAR(16),
            state VARCHAR(32)
        )
        """,
        """
        CREATE TABLE employment_assignment (
            employee_id VARCHAR(36),
            organization_id VARCHAR(36),
            record_status VARCHAR(32),
            version_valid_to TIMESTAMP,
            effective_from TIMESTAMP,
            effective_to TIMESTAMP
        )
        """,
        """
        CREATE TABLE organization_current_closure (
            ancestor_organization_id VARCHAR(36),
            descendant_organization_id VARCHAR(36)
        )
        """
    };
}
