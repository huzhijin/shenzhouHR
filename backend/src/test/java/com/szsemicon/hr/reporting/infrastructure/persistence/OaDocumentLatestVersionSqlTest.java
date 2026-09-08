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
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OaDocumentLatestVersionSqlTest {

    private static final String COMPANY_ID = "company-1";
    private static final String SOURCE_ID = "oa-source-1";
    private static final Pattern EFFECTIVE_DOCUMENT_QUERY = Pattern.compile(
            "<select id=\"findEffectiveOaDocuments\"[^>]*>(.*?)</select>",
            Pattern.DOTALL);
    private static final Path CALCULATION_MAPPER = Path.of(
            "src/main/resources/mappers/AttendanceReportCalculationMapper.xml");

    private Connection connection;

    @BeforeEach
    void createProjectionSchema() throws Exception {
        connection = DriverManager.getConnection(
                "jdbc:h2:mem:oa-version-" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
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
                    employee_id VARCHAR(36) NOT NULL
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
    void approvedThenRevokedLeavesNoEffectiveDocument() throws Exception {
        insertVersion("approved", "OUTING:252", "v1", "APPROVED", 1, 1);
        insertVersion("revoked", "OUTING:252", "v2", "REVOKED", 2, 2);

        assertThat(effectiveBusinessKeys()).isEmpty();
    }

    @Test
    void pendingLatestOutingIsEffective() throws Exception {
        insertVersion("pending", "OUTING:252", "v1", "UNKNOWN", 1, 1);

        assertThat(effectiveBusinessKeys()).containsExactly("OUTING:252");
    }

    @Test
    void pendingLatestOvertimeIsEffectiveWhenActivated() throws Exception {
        insertVersion("pending", "OVERTIME:172", "v1", "UNKNOWN", 1, 1);
        insertOvertimeContext("pending");

        assertThat(effectiveBusinessKeys()).containsExactly("OVERTIME:172");
    }

    @Test
    void pendingThenApprovedReturnsOnlyTheApprovedVersion() throws Exception {
        insertVersion("pending", "OVERTIME:172", "v1", "UNKNOWN", 1, 1);
        insertVersion("approved", "OVERTIME:172", "v2", "APPROVED", 2, 2);
        insertOvertimeContext("approved");

        assertThat(effectiveBusinessKeys()).containsExactly("OVERTIME:172");
    }

    @Test
    void duplicateSameVersionCanNeverBeCountedTwice() throws Exception {
        insertVersion("duplicate-a", "OUTING:252", "v1", "APPROVED", 1, 1);
        insertVersion("duplicate-b", "OUTING:252", "v1", "APPROVED", 1, 1);

        assertThat(effectiveBusinessKeys()).containsExactly("OUTING:252");
    }

    @Test
    void versionLearnedAfterDataAsOfDoesNotRewriteHistoricalCalculation()
            throws Exception {
        insertVersion("approved", "OUTING:252", "v1", "APPROVED", 1, 1);
        insertVersion("revoked", "OUTING:252", "v2", "REVOKED", 2, 2);

        assertThat(effectiveBusinessKeys(
                Timestamp.valueOf("2026-08-15 09:00:01")))
                .containsExactly("OUTING:252");
    }

    private List<String> effectiveBusinessKeys() throws Exception {
        return effectiveBusinessKeys(
                Timestamp.valueOf("2026-08-16 00:00:00"));
    }

    private List<String> effectiveBusinessKeys(Timestamp dataAsOf)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                effectiveDocumentSql())) {
            statement.setTimestamp(1, dataAsOf);
            Timestamp windowEnd = Timestamp.valueOf("2026-08-16 00:00:00");
            Timestamp windowStart = Timestamp.valueOf("2026-08-15 00:00:00");
            statement.setTimestamp(2, windowEnd);
            statement.setTimestamp(3, windowStart);
            statement.setTimestamp(4, windowStart);
            statement.setTimestamp(5, windowEnd);
            try (ResultSet result = statement.executeQuery()) {
                List<String> keys = new ArrayList<>();
                while (result.next()) {
                    keys.add(result.getString("sourceBusinessKey"));
                }
                return keys;
            }
        }
    }

    private static String effectiveDocumentSql() throws Exception {
        String mapper = Files.readString(CALCULATION_MAPPER);
        var matcher = EFFECTIVE_DOCUMENT_QUERY.matcher(mapper);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1)
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("#{dataAsOf}", "?")
                .replace("#{companyId}", "?")
                .replace("#{windowEndExclusive}", "?")
                .replace("#{windowStart}", "?");
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
                    TIMESTAMP '2026-08-15 10:00:00',
                    TIMESTAMP '2026-08-15 12:00:00')
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
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?,
                    TIMESTAMP '2026-08-15 09:00:00', ?)
                """)) {
            statement.setString(1, id);
            statement.setString(2, SOURCE_ID);
            statement.setString(3, businessKey);
            statement.setString(4, sourceVersion);
            statement.setString(
                    5,
                    businessKey.startsWith("OVERTIME:")
                            ? "OVERTIME"
                            : "OUTING");
            statement.setString(6, status);
            statement.setString(7, normalizedId);
            statement.setLong(8, knowledgeRank);
            statement.setTimestamp(
                    9,
                    Timestamp.valueOf(
                            "2026-08-15 09:00:0" + createdSecond));
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

    private void execute(String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
