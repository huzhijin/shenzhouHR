package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real publication INSERTs must accept the normalised read result without mutating raw evidence. */
class OaReportLegacyPendingPublicationTest {

    private static final String NAMESPACE = AttendanceReportProjectionWriteMapper.class.getName() + ".";
    private static final String LEGACY_PENDING = "2026-09-09T08:30:00:state=0:Asia/Shanghai";

    @ParameterizedTest
    @ValueSource(strings = {"insertOaDocumentFact", "insertOaDocumentFacts"})
    void publishesKnownLegacyPendingAndKeepsImmutableSource(String statement) throws Exception {
        try (SqlSession session = session()) {
            seedDocument(session, "legacy", "UNKNOWN", LEGACY_PENDING);
            Map<String, Object> first = row("legacy", "PENDING", LEGACY_PENDING);
            if (statement.endsWith("Facts")) {
                seedDocument(session, "modern", "PENDING", "modern-v1");
                // H2 cannot infer parameters across SELECT ? UNION ALL SELECT ?.
                // Execute the unchanged production batch statement with one row per batch.
                for (Map<String, Object> value : List.of(first, row("modern", "PENDING", "modern-v1"))) {
                    assertThat(session.insert(NAMESPACE + statement, Map.of("rows", List.of(value))))
                            .isEqualTo(1);
                }
            } else {
                assertThat(session.insert(NAMESPACE + statement, first)).isEqualTo(1);
            }
            try (var query = session.getConnection().createStatement();
                    var result = query.executeQuery("SELECT source_status, recognized_minutes FROM attendance_report_oa_fact WHERE oa_attendance_document_id='legacy'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("PENDING");
                assertThat(result.getInt(2)).isEqualTo(180);
                assertThat(result.next()).isFalse();
            }
            try (var query = session.getConnection().createStatement();
                    var result = query.executeQuery("SELECT source_status, source_version FROM oa_attendance_document WHERE oa_attendance_document_id='legacy'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("UNKNOWN");
                assertThat(result.getString(2)).isEqualTo(LEGACY_PENDING);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"insertOaDocumentFact", "insertOaDocumentFacts"})
    void refusesUnrecognisedStatesPretendingToBePending(String statement) throws Exception {
        try (SqlSession session = session()) {
            List<String> versions = List.of(
                    "2026-09-09T08:30:00:state=1:Asia/Shanghai",
                    "2026-09-09T08:30:00:state=00:Asia/Shanghai", "v1");
            for (int index = 0; index < versions.size(); index++) {
                String id = "unknown-" + index;
                String version = versions.get(index);
                seedDocument(session, id, "UNKNOWN", version);
                Map<String, Object> row = row(id, "PENDING", version);
                Object parameters = statement.endsWith("Facts") ? Map.of("rows", List.of(row)) : row;
                assertThat(session.insert(NAMESPACE + statement, parameters)).as(version).isZero();
            }
            try (var query = session.getConnection().createStatement();
                    var result = query.executeQuery("SELECT COUNT(*) FROM attendance_report_oa_fact")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isZero();
            }
        }
    }

    static SqlSession session() throws Exception {
        var configuration = new Configuration();
        configuration.setEnvironment(new Environment("oa-pending-write",
                new JdbcTransactionFactory(), new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:oa-pending-write-" + UUID.randomUUID() + ";MODE=MySQL", "sa", "")));
        String resource = "mappers/AttendanceReportProjectionWriteMapper.xml";
        try (var input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        try (var statement = session.getConnection().createStatement()) {
            for (String ddl : List.of(
                    "CREATE TABLE attendance_report_projection (attendance_report_projection_id VARCHAR(64), company_id VARCHAR(64), status VARCHAR(16))",
                    "CREATE TABLE attendance_source (attendance_source_id VARCHAR(64) PRIMARY KEY, company_id VARCHAR(64), source_type VARCHAR(32))",
                    "CREATE TABLE oa_attendance_document (oa_attendance_document_id VARCHAR(64), document_type VARCHAR(32), source_status VARCHAR(32), source_version VARCHAR(128), normalized_attendance_record_id VARCHAR(64), attendance_source_id VARCHAR(64))",
                    "CREATE TABLE normalized_attendance_record (normalized_attendance_record_id VARCHAR(64), raw_attendance_fact_id VARCHAR(64), validation_status VARCHAR(32), record_kind VARCHAR(32))",
                    "CREATE TABLE raw_attendance_fact (raw_attendance_fact_id VARCHAR(64), company_id VARCHAR(64), fact_kind VARCHAR(32), attendance_source_id VARCHAR(64))",
                    "CREATE TABLE employee_match_decision (normalized_attendance_record_id VARCHAR(64), match_status VARCHAR(32), employee_id VARCHAR(64))",
                    "CREATE TABLE employee (employee_id VARCHAR(64), company_id VARCHAR(64))",
                    "CREATE TABLE employee_version (employee_version_id VARCHAR(64), employee_id VARCHAR(64))",
                    "CREATE TABLE employment_assignment (assignment_id VARCHAR(64), employee_id VARCHAR(64), organization_id VARCHAR(64))",
                    "CREATE TABLE organization_identity (organization_id VARCHAR(64), company_id VARCHAR(64))",
                    "CREATE TABLE organization_version (organization_version_id VARCHAR(64), organization_id VARCHAR(64))",
                    """
                    CREATE TABLE attendance_report_oa_fact (
                        attendance_report_oa_fact_id VARCHAR(64), attendance_report_projection_id VARCHAR(64),
                        company_id VARCHAR(64), oa_attendance_document_id VARCHAR(64), employee_id VARCHAR(64),
                        employee_version_id VARCHAR(64), employment_period_id VARCHAR(64), organization_id VARCHAR(64),
                        organization_version_id VARCHAR(64), document_type VARCHAR(32), leave_type_code VARCHAR(32),
                        temporal_shape VARCHAR(32), point_instant TIMESTAMP, interval_start TIMESTAMP, interval_end TIMESTAMP,
                        recognized_minutes BIGINT, recognized_minutes_by_date_json VARCHAR(8000), allocation_semantics VARCHAR(32), source_status VARCHAR(32), source_version VARCHAR(128), created_at TIMESTAMP,
                        UNIQUE (attendance_report_projection_id, oa_attendance_document_id))
                    """,
                    "INSERT INTO attendance_report_projection VALUES ('projection', 'company', 'DRAFT')",
                    "INSERT INTO attendance_source VALUES ('oa-source', 'company', 'OA_ATTENDANCE')",
                    "INSERT INTO employee VALUES ('employee', 'company')",
                    "INSERT INTO employee_version VALUES ('employee-version', 'employee')",
                    "INSERT INTO employment_assignment VALUES ('assignment', 'employee', 'organization')",
                    "INSERT INTO organization_identity VALUES ('organization', 'company')",
                    "INSERT INTO organization_version VALUES ('organization-version', 'organization')")) {
                statement.execute(ddl);
            }
        }
        return session;
    }

    static void seedDocument(SqlSession session, String id, String status, String version) throws Exception {
        try (var query = session.getConnection().prepareStatement("INSERT INTO oa_attendance_document VALUES (?, 'LEAVE', ?, ?, ?, 'oa-source')")) {
            query.setString(1, id);
            query.setString(2, status);
            query.setString(3, version);
            query.setString(4, "normalised-" + id);
            query.executeUpdate();
        }
        try (var statement = session.getConnection().createStatement()) {
            statement.execute("INSERT INTO normalized_attendance_record VALUES ('normalised-" + id + "', 'raw-" + id + "', 'VALID', 'OA_INTERVAL')");
            statement.execute("INSERT INTO raw_attendance_fact VALUES ('raw-" + id + "', 'company', 'OA_DOCUMENT', 'oa-source')");
            statement.execute("INSERT INTO employee_match_decision VALUES ('normalised-" + id + "', 'MATCHED', 'employee')");
        }
    }

    static Map<String, Object> row(String id, String status, String version) {
        Map<String, Object> row = new HashMap<>();
        row.put("rowId", UUID.randomUUID().toString());
        row.put("projectionId", "projection");
        row.put("companyId", "company");
        row.put("oaAttendanceDocumentId", id);
        row.put("employeeId", "employee");
        row.put("employeeVersionId", "employee-version");
        row.put("employmentAssignmentId", "assignment");
        row.put("organizationId", "organization");
        row.put("organizationVersionId", "organization-version");
        row.put("documentType", "LEAVE");
        row.put("leaveTypeCode", "PERSONAL");
        row.put("temporalShape", "INTERVAL");
        row.put("pointInstant", null);
        row.put("intervalStart", Instant.parse("2026-09-09T00:30:00Z"));
        row.put("intervalEndExclusive", Instant.parse("2026-09-09T03:30:00Z"));
        row.put("recognizedMinutes", 180L);
        row.put("sourceStatus", status);
        row.put("sourceVersion", version);
        row.put("createdAt", Instant.parse("2026-10-02T00:00:00Z"));
        return row;
    }
}
