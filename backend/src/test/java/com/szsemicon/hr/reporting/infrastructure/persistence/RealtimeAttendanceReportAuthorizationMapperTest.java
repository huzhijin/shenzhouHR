package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class RealtimeAttendanceReportAuthorizationMapperTest {

    private static final String RESOURCE =
            "mappers/AttendanceReportMapper.xml";
    private static final String NAMESPACE =
            "com.szsemicon.hr.reporting.infrastructure.persistence."
                    + "AttendanceReportMapper.";
    private static final String AUTHORIZATION_TIME =
            "2026-08-17T04:00:00Z";

    @Test
    void executesProjectionFreeCurrentScopeExpansionInH2() throws Exception {
        Configuration configuration = configuration();
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:realtime-report-authorization;"
                        + "MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            createSchema(connection);
            seedAuthorizationGraph(connection);

            assertThat(firstColumn(
                            configuration,
                            connection,
                            "listAuthorizedCompanies",
                            Map.of(
                                    "principalId", "principal-1",
                                    "capabilityCode", "ATTENDANCE_REPORT:READ",
                                    "authorizationTime",
                                            java.time.Instant.parse(
                                                    AUTHORIZATION_TIME))))
                    .containsExactly("company-a");

            Map<String, Object> authorizationParameters = Map.of(
                    "principalId", "principal-1",
                    "capabilityCode", "ATTENDANCE_REPORT:READ",
                    "companyId", "company-a",
                    "authorizationTime",
                            java.time.Instant.parse(AUTHORIZATION_TIME));
            assertThat(firstColumn(
                            configuration,
                            connection,
                            "listRealtimeAuthorizedScopes",
                            authorizationParameters))
                    .containsExactly("scope-root");

            var scope = new ReportRows.ScopeRow(
                    "scope-root",
                    "ORGANIZATION",
                    null,
                    "organization-root",
                    true,
                    "employee-self");
            Map<String, Object> expandedParameters = Map.of(
                    "companyId", "company-a",
                    "firstScopes", List.of(scope),
                    "secondScopes", List.of(scope),
                    "authorizationTime",
                            java.time.Instant.parse(AUTHORIZATION_TIME));
            assertThat(firstColumn(
                            configuration,
                            connection,
                            "listAuthorizedEmployeeIdsInScopeIntersection",
                            expandedParameters))
                    .containsExactly("employee-child", "employee-self");
            assertThat(firstColumn(
                            configuration,
                            connection,
                            "listAuthorizedOrganizationIds",
                            Map.of(
                                    "companyId", "company-a",
                                    "scopes", List.of(scope),
                                    "authorizationTime",
                                            java.time.Instant.parse(
                                                    AUTHORIZATION_TIME))))
                    .containsExactly(
                            "organization-child", "organization-root");

            assertThat(firstColumn(
                            configuration,
                            connection,
                            "listRealtimeAuthorizedScopes",
                            Map.of(
                                    "principalId", "principal-1",
                                    "capabilityCode", "ATTENDANCE_REPORT:READ",
                                    "companyId", "company-b",
                                    "authorizationTime",
                                            java.time.Instant.parse(
                                                    AUTHORIZATION_TIME))))
                    .isEmpty();
        }
    }

    private static List<String> firstColumn(
            Configuration configuration,
            Connection connection,
            String statementName,
            Map<String, Object> parameters) throws Exception {
        MappedStatement statement = configuration.getMappedStatement(
                NAMESPACE + statementName);
        BoundSql boundSql = statement.getBoundSql(parameters);
        try (var prepared = connection.prepareStatement(boundSql.getSql())) {
            new DefaultParameterHandler(statement, parameters, boundSql)
                    .setParameters(prepared);
            try (var resultSet = prepared.executeQuery()) {
                List<String> values = new ArrayList<>();
                while (resultSet.next()) {
                    values.add(resultSet.getString(1));
                }
                return List.copyOf(values);
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

    private static void createSchema(Connection connection) throws Exception {
        for (String ddl : SCHEMA) {
            connection.createStatement().execute(ddl);
        }
    }

    private static void seedAuthorizationGraph(Connection connection)
            throws Exception {
        for (String statement : DATA) {
            connection.createStatement().execute(statement);
        }
    }

    private static final String[] SCHEMA = {
        """
        CREATE TABLE company (
            company_id VARCHAR(36), name VARCHAR(200), status VARCHAR(32)
        )
        """,
        """
        CREATE TABLE auth_principal (
            principal_id VARCHAR(36), employee_id VARCHAR(36),
            status VARCHAR(32)
        )
        """,
        """
        CREATE TABLE auth_principal_role_assignment (
            assignment_id VARCHAR(36), principal_id VARCHAR(36),
            role_id VARCHAR(36), data_scope_id VARCHAR(36),
            valid_from TIMESTAMP, valid_to TIMESTAMP
        )
        """,
        """
        CREATE TABLE auth_role_capability (
            role_id VARCHAR(36), capability_id VARCHAR(36)
        )
        """,
        """
        CREATE TABLE auth_capability (
            capability_id VARCHAR(36), capability_code VARCHAR(128)
        )
        """,
        """
        CREATE TABLE auth_data_scope (
            scope_id VARCHAR(36), scope_type VARCHAR(32),
            company_id VARCHAR(36), organization_id VARCHAR(36),
            include_descendants BOOLEAN,
            valid_from TIMESTAMP, valid_to TIMESTAMP
        )
        """,
        """
        CREATE TABLE employee (
            employee_id VARCHAR(36), company_id VARCHAR(36)
        )
        """,
        """
        CREATE TABLE organization_identity (
            organization_id VARCHAR(36), company_id VARCHAR(36),
            identity_status VARCHAR(32)
        )
        """,
        """
        CREATE TABLE organization_current_projection (
            organization_id VARCHAR(36), current_version_id VARCHAR(36)
        )
        """,
        """
        CREATE TABLE organization_version (
            organization_version_id VARCHAR(36), organization_id VARCHAR(36),
            status VARCHAR(32), effective_from TIMESTAMP,
            effective_to TIMESTAMP
        )
        """,
        """
        CREATE TABLE organization_current_closure (
            ancestor_organization_id VARCHAR(36),
            descendant_organization_id VARCHAR(36)
        )
        """,
        """
        CREATE TABLE employment_assignment (
            employee_id VARCHAR(36), organization_id VARCHAR(36),
            record_status VARCHAR(32), version_valid_to TIMESTAMP,
            effective_from TIMESTAMP, effective_to TIMESTAMP
        )
        """
    };

    private static final String[] DATA = {
        """
        INSERT INTO company VALUES
          ('company-a', '神州半导体', 'ACTIVE'),
          ('company-b', '过期授权公司', 'ACTIVE')
        """,
        """
        INSERT INTO auth_principal VALUES
          ('principal-1', 'employee-self', 'ACTIVE')
        """,
        """
        INSERT INTO auth_capability VALUES
          ('cap-report-read', 'ATTENDANCE_REPORT:READ')
        """,
        """
        INSERT INTO auth_role_capability VALUES
          ('role-report', 'cap-report-read')
        """,
        """
        INSERT INTO auth_data_scope VALUES
          ('scope-root', 'ORGANIZATION', NULL, 'organization-root', TRUE,
           TIMESTAMP '2026-01-01 00:00:00', NULL),
          ('scope-expired-company', 'COMPANY', 'company-b', NULL, TRUE,
           TIMESTAMP '2026-01-01 00:00:00',
           TIMESTAMP '2026-08-01 00:00:00')
        """,
        """
        INSERT INTO auth_principal_role_assignment VALUES
          ('assignment-root', 'principal-1', 'role-report', 'scope-root',
           TIMESTAMP '2026-01-01 00:00:00', NULL),
          ('assignment-expired', 'principal-1', 'role-report',
           'scope-expired-company', TIMESTAMP '2026-01-01 00:00:00', NULL)
        """,
        """
        INSERT INTO employee VALUES
          ('employee-self', 'company-a'),
          ('employee-child', 'company-a'),
          ('employee-outside', 'company-a'),
          ('employee-b', 'company-b')
        """,
        """
        INSERT INTO organization_identity VALUES
          ('organization-root', 'company-a', 'ACTIVE'),
          ('organization-child', 'company-a', 'ACTIVE'),
          ('organization-outside', 'company-a', 'ACTIVE')
        """,
        """
        INSERT INTO organization_current_projection VALUES
          ('organization-root', 'version-root'),
          ('organization-child', 'version-child'),
          ('organization-outside', 'version-outside')
        """,
        """
        INSERT INTO organization_version VALUES
          ('version-root', 'organization-root', 'ACTIVE',
           TIMESTAMP '2026-01-01 00:00:00', NULL),
          ('version-child', 'organization-child', 'ACTIVE',
           TIMESTAMP '2026-01-01 00:00:00', NULL),
          ('version-outside', 'organization-outside', 'ACTIVE',
           TIMESTAMP '2026-01-01 00:00:00', NULL)
        """,
        """
        INSERT INTO organization_current_closure VALUES
          ('organization-root', 'organization-root'),
          ('organization-root', 'organization-child'),
          ('organization-child', 'organization-child'),
          ('organization-outside', 'organization-outside')
        """,
        """
        INSERT INTO employment_assignment VALUES
          ('employee-self', 'organization-root', 'ACTIVE', NULL,
           TIMESTAMP '2026-01-01 00:00:00', NULL),
          ('employee-child', 'organization-child', 'ACTIVE', NULL,
           TIMESTAMP '2026-01-01 00:00:00', NULL),
          ('employee-outside', 'organization-outside', 'ACTIVE', NULL,
           TIMESTAMP '2026-01-01 00:00:00', NULL)
        """
    };
}
