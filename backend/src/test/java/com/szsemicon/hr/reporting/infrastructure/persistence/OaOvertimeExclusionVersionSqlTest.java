package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OaOvertimeExclusionVersionSqlTest {

    private static final String COMPANY_ID = "company-1";
    private static final String SOURCE_ID = "oa-source-1";
    private static final String EMPLOYEE_ID = "employee-1";
    private static final Path CALCULATION_MAPPER = Path.of(
            "src/main/resources/mappers/AttendanceReportCalculationMapper.xml");

    private Connection connection;
    private Configuration configuration;

    @BeforeEach
    void createProjectionSchema() throws Exception {
        connection = DriverManager.getConnection(
                "jdbc:h2:mem:oa-overtime-exclusion-" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        configuration = new Configuration();
        try (var reader = Files.newBufferedReader(CALCULATION_MAPPER)) {
            new XMLMapperBuilder(reader, configuration, CALCULATION_MAPPER.toString(),
                    configuration.getSqlFragments()).parse();
        }
        execute("""
                CREATE TABLE employee (
                    employee_id VARCHAR(36) PRIMARY KEY,
                    company_id VARCHAR(36) NOT NULL,
                    employee_number VARCHAR(64) NOT NULL
                )
                """);
        execute("""
                CREATE TABLE raw_attendance_fact (
                    raw_attendance_fact_id VARCHAR(64) PRIMARY KEY,
                    source_time_zone VARCHAR(64)
                )
                """);
        execute("""
                CREATE TABLE normalized_attendance_record (
                    normalized_attendance_record_id VARCHAR(64) PRIMARY KEY,
                    raw_attendance_fact_id VARCHAR(64) NOT NULL,
                    validation_status VARCHAR(32) NOT NULL,
                    interval_start TIMESTAMP NULL,
                    interval_end TIMESTAMP NULL,
                    point_instant TIMESTAMP NULL
                )
                """);
        execute("""
                CREATE TABLE employee_match_decision (
                    normalized_attendance_record_id VARCHAR(64) PRIMARY KEY,
                    match_status VARCHAR(32) NOT NULL,
                    employee_id VARCHAR(36) NOT NULL,
                    employment_period_id VARCHAR(36)
                )
                """);
        execute("""
                CREATE TABLE oa_attendance_document (
                    oa_attendance_document_id VARCHAR(64) PRIMARY KEY,
                    attendance_source_id VARCHAR(64) NOT NULL,
                    source_business_key VARCHAR(191) NOT NULL,
                    source_version VARCHAR(128) NOT NULL,
                    document_type VARCHAR(32) NOT NULL,
                    leave_type VARCHAR(32),
                    leave_serial VARCHAR(64),
                    original_leave_serial VARCHAR(64),
                    source_status VARCHAR(32) NOT NULL,
                    normalized_attendance_record_id VARCHAR(64) NOT NULL,
                    knowledge_rank BIGINT NOT NULL,
                    first_submitted_at TIMESTAMP,
                    created_at TIMESTAMP NOT NULL
                )
                """);
        execute("""
                CREATE TABLE oa_attendance_document_context (
                    oa_attendance_document_id VARCHAR(64) PRIMARY KEY,
                    activation_decision VARCHAR(64),
                    overtime_type VARCHAR(20)
                )
                """);
        execute("""
                CREATE TABLE attendance_report_oa_evidence_exclusion (
                    exclusion_id VARCHAR(36) PRIMARY KEY,
                    company_id VARCHAR(36) NOT NULL,
                    employee_id VARCHAR(36) NOT NULL,
                    attendance_source_id VARCHAR(64) NOT NULL,
                    source_business_key VARCHAR(191) NOT NULL,
                    source_version VARCHAR(128) NOT NULL,
                    oa_attendance_document_id VARCHAR(64) NOT NULL,
                    knowledge_at TIMESTAMP NOT NULL,
                    reversed_at TIMESTAMP NULL
                )
                """);
        execute("""
                INSERT INTO employee (
                    employee_id, company_id, employee_number
                ) VALUES ('employee-1', 'company-1', 'E001')
                """);
    }

    @AfterEach
    void closeConnection() throws Exception {
        connection.close();
    }

    @Test
    void exactVersionExclusionRemovesOnlyThatVersionFromAllFourReaders() throws Exception {
        insertVersion("v1", "OVERTIME:100", "v1-state0", "APPROVED", 1, 1);
        insertOvertimeContext("v1");
        insertVersion("v2", "OVERTIME:100", "v2-state0", "APPROVED", 2, 2);
        insertOvertimeContext("v2");

        assertAllQueries(List.of("OVERTIME:100"));

        insertExclusion("v1", "OVERTIME:100", "v1-state0");

        assertAllQueries(List.of("OVERTIME:100"));
    }

    @Test
    void realisticUnknownPendingWithStateZeroVersionAndActivatedContext() throws Exception {
        insertVersion("v1", "OVERTIME:200", "2026-10-05T09:15:30:state=0:Asia/Shanghai", "APPROVED", 1, 1);
        insertOvertimeContext("v1");

        insertVersion("v2", "OVERTIME:200", "2026-10-07T16:37:37:state=0:Asia/Shanghai", "UNKNOWN", 2, 2);
        insertOvertimeContext("v2");

        assertAllQueries(List.of("OVERTIME:200"));

        insertExclusion("v2", "OVERTIME:200", "2026-10-07T16:37:37:state=0:Asia/Shanghai");

        assertAllQueries(List.of());
    }

    @Test
    void excludingLatestStateZeroDoesNotResurrectOldApproved() throws Exception {
        insertVersion("v1", "OVERTIME:200", "2026-10-05T09:15:30:state=0:Asia/Shanghai", "APPROVED", 1, 1);
        insertOvertimeContext("v1");

        insertVersion("v2", "OVERTIME:200", "2026-10-07T16:37:37:state=0:Asia/Shanghai", "UNKNOWN", 2, 2);
        insertOvertimeContext("v2");

        insertExclusion("v2", "OVERTIME:200", "2026-10-07T16:37:37:state=0:Asia/Shanghai");

        assertAllQueries(List.of());
    }

    @Test
    void newVersionV3DoesNotInheritStateZeroExclusion() throws Exception {
        insertVersion("v2", "OVERTIME:200", "2026-10-07T16:37:37:state=0:Asia/Shanghai", "UNKNOWN", 2, 2);
        insertOvertimeContext("v2");
        insertExclusion("v2", "OVERTIME:200", "2026-10-07T16:37:37:state=0:Asia/Shanghai");

        insertVersion("v3", "OVERTIME:200", "2026-10-08T10:20:45:state=0:Asia/Shanghai", "UNKNOWN", 3, 3);
        insertOvertimeContext("v3");

        assertAllQueries(List.of("OVERTIME:200"));
    }

    @Test
    void excludingLatestVersionMakesAllFourReadersEmpty() throws Exception {
        insertVersion("v1", "OVERTIME:100", "v1-state0", "APPROVED", 1, 1);
        insertOvertimeContext("v1");
        insertVersion("v2", "OVERTIME:100", "v2-state0", "APPROVED", 2, 2);
        insertOvertimeContext("v2");

        insertExclusion("v2", "OVERTIME:100", "v2-state0");

        assertAllQueries(List.of());
    }

    @Test
    void newVersionV3DoesNotInheritV2Exclusion() throws Exception {
        insertVersion("v2", "OVERTIME:100", "v2-state0", "APPROVED", 2, 2);
        insertOvertimeContext("v2");
        insertExclusion("v2", "OVERTIME:100", "v2-state0");

        insertVersion("v3", "OVERTIME:100", "v3-state0", "APPROVED", 3, 3);
        insertOvertimeContext("v3");

        assertAllQueries(List.of("OVERTIME:100"));
    }

    @Test
    void futureKnowledgeAtDoesNotExcludeCurrentCalculationInAllReaders() throws Exception {
        insertVersion("v1", "OVERTIME:100", "v1-state0", "APPROVED", 1, 1);
        insertOvertimeContext("v1");

        execute("INSERT INTO attendance_report_oa_evidence_exclusion " +
                "(exclusion_id, company_id, employee_id, attendance_source_id, " +
                "source_business_key, source_version, oa_attendance_document_id, " +
                "knowledge_at, reversed_at) " +
                "VALUES ('excl-1', 'company-1', 'employee-1', 'oa-source-1', " +
                "'OVERTIME:100', 'v1-state0', 'v1', '2026-10-09 00:00:00', NULL)");

        assertAllQueries(List.of("OVERTIME:100"));
    }

    @Test
    void exclusionCannotTransferAcrossDifferentEmployee() throws Exception {
        insertVersion("v1", "OVERTIME:100", "v1-state0", "APPROVED", 1, 1);
        insertOvertimeContext("v1");

        execute("INSERT INTO employee VALUES ('employee-2', 'company-1', 'E002')");
        execute("INSERT INTO attendance_report_oa_evidence_exclusion " +
                "(exclusion_id, company_id, employee_id, attendance_source_id, " +
                "source_business_key, source_version, oa_attendance_document_id, " +
                "knowledge_at, reversed_at) " +
                "VALUES ('excl-1', 'company-1', 'employee-2', 'oa-source-1', " +
                "'OVERTIME:100', 'v1-state0', 'v1', '2026-08-16 00:00:00', NULL)");

        assertAllQueries(List.of("OVERTIME:100"));
    }

    @Test
    void exclusionCannotTransferAcrossDifferentSource() throws Exception {
        insertVersion("v1", "OVERTIME:100", "v1-state0", "APPROVED", 1, 1);
        insertOvertimeContext("v1");

        execute("INSERT INTO attendance_report_oa_evidence_exclusion " +
                "(exclusion_id, company_id, employee_id, attendance_source_id, " +
                "source_business_key, source_version, oa_attendance_document_id, " +
                "knowledge_at, reversed_at) " +
                "VALUES ('excl-1', 'company-1', 'employee-1', 'other-source', " +
                "'OVERTIME:100', 'v1-state0', 'v1', '2026-08-16 00:00:00', NULL)");

        assertAllQueries(List.of("OVERTIME:100"));
    }

    @Test
    void exclusionCannotTransferAcrossDifferentCompany() throws Exception {
        insertVersion("v1", "OVERTIME:100", "v1-state0", "APPROVED", 1, 1);
        insertOvertimeContext("v1");

        execute("INSERT INTO attendance_report_oa_evidence_exclusion " +
                "(exclusion_id, company_id, employee_id, attendance_source_id, " +
                "source_business_key, source_version, oa_attendance_document_id, " +
                "knowledge_at, reversed_at) " +
                "VALUES ('excl-1', 'other-company', 'employee-1', 'oa-source-1', " +
                "'OVERTIME:100', 'v1-state0', 'v1', '2026-08-16 00:00:00', NULL)");

        assertAllQueries(List.of("OVERTIME:100"));
    }

    private List<String> effectiveBusinessKeys() throws Exception {
        return effectiveBusinessKeys(Timestamp.valueOf("2026-10-08 00:00:00"));
    }

    private List<String> effectiveBusinessKeys(Timestamp dataAsOf) throws Exception {
        return businessKeys("findEffectiveOaDocuments", dataAsOf);
    }

    private List<String> businessKeys(String queryId, Timestamp dataAsOf) throws Exception {
        var values = Map.of("dataAsOf", dataAsOf, "companyId", COMPANY_ID,
                "employeeId", EMPLOYEE_ID, "windowStart", Timestamp.valueOf("2026-10-07 00:00:00"),
                "windowEndExclusive", Timestamp.valueOf("2026-10-08 00:00:00"));
        var sql = configuration.getMappedStatement(
                "com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationMapper."
                        + queryId).getBoundSql(values);
        try (PreparedStatement statement = connection.prepareStatement(sql.getSql())) {
            for (int i = 0; i < sql.getParameterMappings().size(); i++) {
                statement.setObject(i + 1, values.get(sql.getParameterMappings().get(i).getProperty()));
            }
            try (ResultSet result = statement.executeQuery()) {
                List<String> keys = new ArrayList<>();
                while (result.next()) keys.add(result.getString("sourceBusinessKey"));
                return keys;
            }
        }
    }

    private void assertAllQueries(List<String> expected) throws Exception {
        for (String query : List.of("findEffectiveOaDocuments",
                "findEffectiveOaDocumentsForEmployee", "findReportableOaDocuments",
                "findReportableOaDocumentsForEmployee")) {
            assertThat(businessKeys(query, Timestamp.valueOf("2026-10-08 00:00:00")))
                    .as(query).containsExactlyElementsOf(expected);
        }
    }

    private void insertVersion(
            String id,
            String businessKey,
            String sourceVersion,
            String status,
            long knowledgeRank,
            int createdSecond) throws Exception {
        String rawId = "raw-" + id;
        String normalizedId = "normalized-" + id;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO raw_attendance_fact (
                    raw_attendance_fact_id, source_time_zone
                ) VALUES (?, 'Asia/Shanghai')
                """)) {
            statement.setString(1, rawId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO normalized_attendance_record (
                    normalized_attendance_record_id,
                    raw_attendance_fact_id,
                    validation_status,
                    interval_start,
                    interval_end
                ) VALUES (?, ?, 'VALID',
                    TIMESTAMP '2026-10-07 10:00:00',
                    TIMESTAMP '2026-10-07 12:00:00')
                """)) {
            statement.setString(1, normalizedId);
            statement.setString(2, rawId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO employee_match_decision (
                    normalized_attendance_record_id,
                    match_status,
                    employee_id
                ) VALUES (?, 'MATCHED', 'employee-1')
                """)) {
            statement.setString(1, normalizedId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO oa_attendance_document (
                    oa_attendance_document_id,
                    attendance_source_id,
                    source_business_key,
                    source_version,
                    document_type,
                    source_status,
                    normalized_attendance_record_id,
                    knowledge_rank,
                    first_submitted_at,
                    created_at
                ) VALUES (?, ?, ?, ?, 'OVERTIME', ?, ?, ?,
                    TIMESTAMP '2026-10-07 09:00:00', ?)
                """)) {
            statement.setString(1, id);
            statement.setString(2, SOURCE_ID);
            statement.setString(3, businessKey);
            statement.setString(4, sourceVersion);
            statement.setString(5, status);
            statement.setString(6, normalizedId);
            statement.setLong(7, knowledgeRank);
            statement.setTimestamp(8, Timestamp.valueOf("2026-10-07 09:00:0" + createdSecond));
            statement.executeUpdate();
        }
    }

    private void insertOvertimeContext(String documentId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO oa_attendance_document_context (
                    oa_attendance_document_id,
                    activation_decision,
                    overtime_type
                ) VALUES (?, 'ACTIVATED', 'PAID')
                """)) {
            statement.setString(1, documentId);
            statement.executeUpdate();
        }
    }

    private void insertExclusion(String docId, String businessKey, String sourceVersion) throws Exception {
        execute("INSERT INTO attendance_report_oa_evidence_exclusion " +
                "(exclusion_id, company_id, employee_id, attendance_source_id, " +
                "source_business_key, source_version, oa_attendance_document_id, " +
                "knowledge_at, reversed_at) " +
                "VALUES ('excl-" + docId + "', 'company-1', 'employee-1', 'oa-source-1', " +
                "'" + businessKey + "', '" + sourceVersion + "', '" + docId + "', " +
                "'2026-10-08 00:00:00', NULL)");
    }

    private void execute(String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
