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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OaDocumentLatestVersionSqlTest {

    private static final String COMPANY_ID = "company-1";
    private static final String SOURCE_ID = "oa-source-1";
    private static final Path CALCULATION_MAPPER = Path.of(
            "src/main/resources/mappers/AttendanceReportCalculationMapper.xml");

    private Connection connection;
    private Configuration configuration;

    @BeforeEach
    void createProjectionSchema() throws Exception {
        connection = DriverManager.getConnection(
                "jdbc:h2:mem:oa-version-" + UUID.randomUUID()
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
    void approvedThenRevokedLeavesNoEffectiveDocument() throws Exception {
        insertVersion("approved", "OUTING:252", "v1", "APPROVED", 1, 1);
        insertVersion("revoked", "OUTING:252", "v2", "REVOKED", 2, 2);

        assertThat(effectiveBusinessKeys()).isEmpty();
    }

    @Test
    void pendingLatestOutingIsEffective() throws Exception {
        insertVersion("pending", "OUTING:252", "v1", "PENDING", 1, 1);

        assertThat(effectiveBusinessKeys()).containsExactly("OUTING:252");
    }

    @Test
    void pendingLatestOvertimeIsEffectiveWhenActivated() throws Exception {
        insertVersion("pending", "OVERTIME:172", "v1", "PENDING", 1, 1);
        insertOvertimeContext("pending");

        assertThat(effectiveBusinessKeys()).containsExactly("OVERTIME:172");
    }

    @ParameterizedTest
    @ValueSource(strings = {"REVOKED", "REJECTED", "DRAFT", "UNKNOWN"})
    void inactiveLatestExemptionNeverResurrectsApprovedExemption(String status) throws Exception {
        insertVersion("approved-exempt", "EXEMPT_PUNCH:1", "v1", "APPROVED", 1, 1);
        insertVersion("inactive-exempt", "EXEMPT_PUNCH:1", "v2", status, 2, 2);
        assertAllQueries(List.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "MODIFIED", "SUPPLEMENTED", "PENDING"})
    void validMatchedExemptionKeepsExistingEffectiveStatusPolicy(String status) throws Exception {
        insertVersion("exempt", "EXEMPT_PUNCH:1", "v1", status, 1, 1);
        assertAllQueries(List.of("EXEMPT_PUNCH:1"));
        execute("UPDATE employee_match_decision SET match_status='UNMATCHED'");
        assertAllQueries(List.of());
    }

    @Test
    void invalidLatestExemptionCannotReuseOlderValidVersion() throws Exception {
        insertVersion("approved-exempt", "EXEMPT_PUNCH:1", "v1", "APPROVED", 1, 1);
        insertVersion("invalid-exempt", "EXEMPT_PUNCH:1", "v2", "APPROVED", 2, 2);
        execute("UPDATE normalized_attendance_record SET validation_status='INVALID' "
                + "WHERE normalized_attendance_record_id='normalized-invalid-exempt'");
        assertAllQueries(List.of());
    }

    @Test
    void pendingThenApprovedReturnsOnlyTheApprovedVersion() throws Exception {
        insertVersion("pending", "OVERTIME:172", "v1", "PENDING", 1, 1);
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
        return businessKeys("findEffectiveOaDocuments", dataAsOf);
    }

    private List<String> businessKeys(String queryId, Timestamp dataAsOf)
            throws Exception {
        var values = Map.of("dataAsOf", dataAsOf, "companyId", COMPANY_ID,
                "employeeId", "employee-1", "windowStart", Timestamp.valueOf("2026-08-15 00:00:00"),
                "windowEndExclusive", Timestamp.valueOf("2026-08-16 00:00:00"));
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
            assertThat(businessKeys(query, Timestamp.valueOf("2026-08-16 00:00:00")))
                    .as(query).containsExactlyElementsOf(expected);
        }
    }

    @Test
    void onlyStrictLegacyStateZeroRemainsEffectiveInEveryReader() throws Exception {
        insertVersion("pending", "LEAVE:0", "2026-08-15T08:00:00.123:state=0:Asia/Shanghai", "UNKNOWN", 1, 1);
        insertVersion("unknown", "LEAVE:1", "2026-08-15T08:00:00:state=1:Asia/Shanghai", "UNKNOWN", 1, 1);
        insertVersion("unsupported", "LEAVE:99", "2026-08-15T08:00:00:state=99:Asia/Shanghai", "UNKNOWN", 1, 1);
        insertVersion("old", "LEAVE:old", "v1", "UNKNOWN", 1, 1);
        assertAllQueries(List.of("LEAVE:0"));
    }

    @Test
    void latestUnknownSuppressesOlderApprovedInEveryReader() throws Exception {
        insertVersion("approved", "LEAVE:1", "v1", "APPROVED", 1, 1);
        insertVersion("unknown", "LEAVE:1", "2026-08-15T08:00:00:state=1:Asia/Shanghai", "UNKNOWN", 2, 2);
        assertAllQueries(List.of());
    }

    @Test
    void latestReassignedVersionDoesNotResurrectOldEmployeeDocument() throws Exception {
        insertVersion("approved", "LEAVE:1", "v1", "APPROVED", 1, 1);
        insertVersion("reassigned", "LEAVE:1", "v2", "REVOKED", 2, 2);
        execute("UPDATE employee_match_decision SET employee_id='employee-2' WHERE normalized_attendance_record_id='normalized-reassigned'");
        assertAllQueries(List.of());
    }

    @Test
    void approvedLikeAndPendingRemainEffectiveAcrossAllReaders() throws Exception {
        insertVersion("approved", "LEAVE:approved", "v1", "APPROVED", 1, 1);
        insertVersion("modified", "LEAVE:modified", "v1", "MODIFIED", 1, 1);
        insertVersion("pending", "LEAVE:pending", "v1", "PENDING", 1, 1);
        insertVersion("supplemented", "LEAVE:supplemented", "v1", "SUPPLEMENTED", 1, 1);
        assertAllQueries(List.of("LEAVE:approved", "LEAVE:modified", "LEAVE:pending", "LEAVE:supplemented"));
    }

    @Test
    void pointRevocationIsLoadedThroughOriginalSerialEvenOutsideWindow() throws Exception {
        insertVersion("leave", "LEAVE:1", "v1", "APPROVED", 1, 1);
        execute("UPDATE oa_attendance_document SET leave_serial='SERIAL-1' WHERE oa_attendance_document_id='leave'");
        insertVersion("revocation", "LEAVE_REVOCATION:1", "v1", "APPROVED", 1, 1);
        execute("UPDATE oa_attendance_document SET original_leave_serial='SERIAL-1' WHERE oa_attendance_document_id='revocation'");
        execute("UPDATE normalized_attendance_record SET interval_start=NULL, interval_end=NULL, point_instant=TIMESTAMP '2026-08-20 10:00:00' WHERE normalized_attendance_record_id='normalized-revocation'");
        for (String query : List.of("findEffectiveOaDocuments", "findEffectiveOaDocumentsForEmployee")) {
            assertThat(businessKeys(query, Timestamp.valueOf("2026-08-16 00:00:00")))
                    .as(query).containsExactlyInAnyOrder("LEAVE:1", "LEAVE_REVOCATION:1");
        }
        execute("UPDATE oa_attendance_document SET attendance_source_id='other-source' WHERE oa_attendance_document_id='revocation'");
        assertThat(effectiveBusinessKeys()).containsExactly("LEAVE:1");
    }

    @Test
    void pendingRevocationNeverCancelsApprovedLeave() throws Exception {
        insertVersion("leave", "LEAVE:1", "v1", "APPROVED", 1, 1);
        insertVersion("pending", "LEAVE_REVOCATION:1", "v1", "PENDING", 1, 1);
        assertThat(effectiveBusinessKeys()).containsExactly("LEAVE:1");
    }

    @Test
    void movedActualDayLoadsTheSameEmployeeOriginalOutsideWindowInEveryReader() throws Exception {
        movedOriginalFixture();
        for (String query : List.of("findEffectiveOaDocuments", "findEffectiveOaDocumentsForEmployee",
                "findReportableOaDocuments", "findReportableOaDocumentsForEmployee")) {
            var expected = query.startsWith("findEffective") ? List.of("LEAVE_REVOCATION:1", "LEAVE:1") : List.of("LEAVE:1");
            assertThat(businessKeys(query, Timestamp.valueOf("2026-08-16 00:00:00"))).containsExactlyInAnyOrderElementsOf(expected);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "REVOKED", "REJECTED", "DRAFT"})
    void inactiveAmendmentCannotPullUnrelatedOriginalIntoWindow(String status) throws Exception {
        movedOriginalFixture();
        execute("UPDATE oa_attendance_document SET source_status='" + status + "' WHERE oa_attendance_document_id='moved'");
        assertAllQueries(List.of());
    }

    @Test
    void inverseAssociationNeverUsesAnotherSourceOrEmployeeWithTheSameSerial() throws Exception {
        movedOriginalFixture();
        execute("UPDATE oa_attendance_document SET attendance_source_id='other-source' WHERE oa_attendance_document_id='moved'");
        for (String query : List.of("findEffectiveOaDocuments", "findEffectiveOaDocumentsForEmployee",
                "findReportableOaDocuments", "findReportableOaDocumentsForEmployee")) {
            assertThat(businessKeys(query, Timestamp.valueOf("2026-08-16 00:00:00"))).doesNotContain("LEAVE:1");
        }
        execute("UPDATE oa_attendance_document SET attendance_source_id='oa-source-1' WHERE oa_attendance_document_id='moved'");
        execute("INSERT INTO employee VALUES ('employee-2','company-1','E002')");
        execute("UPDATE employee_match_decision SET employee_id='employee-2' WHERE normalized_attendance_record_id='normalized-moved'");
        assertThat(businessKeys("findReportableOaDocuments", Timestamp.valueOf("2026-08-16 00:00:00"))).isEmpty();
    }

    private void movedOriginalFixture() throws Exception {
        insertVersion("leave", "LEAVE:1", "v1", "APPROVED", 1, 1);
        execute("UPDATE oa_attendance_document SET leave_serial='SERIAL-1' WHERE oa_attendance_document_id='leave'");
        execute("UPDATE normalized_attendance_record SET interval_start=TIMESTAMP '2026-08-16 13:30:00', interval_end=TIMESTAMP '2026-08-16 18:00:00' WHERE normalized_attendance_record_id='normalized-leave'");
        insertVersion("moved", "LEAVE_REVOCATION:1", "v1", "APPROVED", 1, 1);
        execute("UPDATE oa_attendance_document SET original_leave_serial='SERIAL-1' WHERE oa_attendance_document_id='moved'");
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
                    businessKey.substring(0, businessKey.indexOf(':')));
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
