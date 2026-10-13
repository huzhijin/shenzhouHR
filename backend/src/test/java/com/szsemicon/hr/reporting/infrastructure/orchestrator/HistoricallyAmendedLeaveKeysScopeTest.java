package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@link AttendanceReportCalculationMapper#findHistoricallyAmendedLeaveKeys}
 * with employeeIds scope: single-employee filtering, serial matching, fallback interval,
 * dataAsOf cutoff, and real historical scenarios (same businessKey old serial amended,
 * original in Aug but amendment moved into Sep).
 */
class HistoricallyAmendedLeaveKeysScopeTest {

    private static final String NAMESPACE = AttendanceReportCalculationMapper.class.getName() + ".";
    private static final Instant WINDOW_START = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant DATA_AS_OF = Instant.parse("2026-10-08T00:00:00Z");

    @Test
    void singleEmployeeScopeReturnsOnlyThatEmployeesAmendedKeys() throws Exception {
        try (SqlSession s = session()) {
            setupAliceSerialMatch(s);
            setupBobFallbackOverlap(s);

            var keys = s.selectList(NAMESPACE + "findHistoricallyAmendedLeaveKeys",
                    params("company-1", List.of("emp-alice")));

            assertThat(keys).containsExactly("OA:LEAVE:alice-1");
        }
    }

    @Test
    void nullEmployeeIdsReturnsAllCompanyAmendedKeys() throws Exception {
        try (SqlSession s = session()) {
            setupAliceSerialMatch(s);
            setupBobFallbackOverlap(s);

            var keys = s.selectList(NAMESPACE + "findHistoricallyAmendedLeaveKeys",
                    params("company-1", null));

            assertThat(keys).containsExactlyInAnyOrder("OA:LEAVE:alice-1", "OA:LEAVE:bob-1");
        }
    }

    @Test
    void emptyEmployeeIdsReturnsAllCompanyAmendedKeys() throws Exception {
        try (SqlSession s = session()) {
            setupAliceSerialMatch(s);
            setupBobFallbackOverlap(s);

            var keys = s.selectList(NAMESPACE + "findHistoricallyAmendedLeaveKeys",
                    params("company-1", List.of()));

            assertThat(keys).containsExactlyInAnyOrder("OA:LEAVE:alice-1", "OA:LEAVE:bob-1");
        }
    }

    @Test
    void serialMatchIncludesAmendedKey() throws Exception {
        try (SqlSession s = session()) {
            setupAliceSerialMatch(s);

            var keys = s.selectList(NAMESPACE + "findHistoricallyAmendedLeaveKeys",
                    params("company-1", List.of("emp-alice")));

            assertThat(keys).contains("OA:LEAVE:alice-1");
        }
    }

    @Test
    void fallbackIntervalOverlapIncludesAmendedKey() throws Exception {
        try (SqlSession s = session()) {
            setupBobFallbackOverlap(s);

            var keys = s.selectList(NAMESPACE + "findHistoricallyAmendedLeaveKeys",
                    params("company-1", List.of("emp-bob")));

            assertThat(keys).contains("OA:LEAVE:bob-1");
        }
    }

    @Test
    void amendmentCreatedAfterDataAsOfExcluded() throws Exception {
        try (SqlSession s = session()) {
            setupAliceSerialMatch(s);
            // Alice QJD-002: amendment created_at='2026-10-10' > dataAsOf='2026-10-08'
            insertEmployee(s, "emp-alice", "company-1");
            insertNormalizedRecord(s, "rec-alice-leave-2", "2026-09-20 00:00:00", "2026-09-23 00:00:00", null);
            insertOaDocument(s, "oa-alice-leave-2", "OA", "LEAVE:alice-2", "LEAVE", "QJD-002",
                    "rec-alice-leave-2", "2026-09-01 08:00:00", null);
            insertMatch(s, "match-alice-leave-2", "rec-alice-leave-2", "emp-alice");

            insertNormalizedRecord(s, "rec-alice-amend-2", "2026-09-21 00:00:00", "2026-09-22 00:00:00", null);
            insertOaDocument(s, "oa-alice-amend-2", "OA", "REVOCATION:alice-2", "LEAVE_REVOCATION", null,
                    "rec-alice-amend-2", "2026-10-10 00:00:00", "QJD-002");
            insertMatch(s, "match-alice-amend-2", "rec-alice-amend-2", "emp-alice");

            var keys = s.selectList(NAMESPACE + "findHistoricallyAmendedLeaveKeys",
                    params("company-1", List.of("emp-alice")));

            assertThat(keys).doesNotContain("OA:LEAVE:alice-2");
            assertThat(keys).containsExactly("OA:LEAVE:alice-1");
        }
    }

    @Test
    void multipleEmployeesReturnsUnionOfTheirAmendedKeys() throws Exception {
        try (SqlSession s = session()) {
            setupAliceSerialMatch(s);
            setupBobFallbackOverlap(s);

            var keys = s.selectList(NAMESPACE + "findHistoricallyAmendedLeaveKeys",
                    params("company-1", List.of("emp-alice", "emp-bob")));

            assertThat(keys).containsExactlyInAnyOrder("OA:LEAVE:alice-1", "OA:LEAVE:bob-1");
        }
    }

    @Test
    void employeeWithNoAmendmentsReturnsEmpty() throws Exception {
        try (SqlSession s = session()) {
            setupAliceSerialMatch(s);

            var keys = s.selectList(NAMESPACE + "findHistoricallyAmendedLeaveKeys",
                    params("company-1", List.of("emp-nonexistent")));

            assertThat(keys).isEmpty();
        }
    }

    @Test
    void sameBusinessKeyOldSerialAmendedReturnsOnce() throws Exception {
        // Real scenario: same source_business_key='LEAVE:charlie-1', original has serial 'QJD-OLD',
        // later version has serial 'QJD-NEW', amendment matches 'QJD-OLD' → still returns the key once.
        try (SqlSession s = session()) {
            insertCompany(s, "company-1");
            insertEmployee(s, "emp-charlie", "company-1");

            // First version: serial QJD-OLD
            insertNormalizedRecord(s, "rec-charlie-v1", "2026-09-05 00:00:00", "2026-09-08 00:00:00", null);
            insertOaDocument(s, "oa-charlie-v1", "OA", "LEAVE:charlie-1", "LEAVE", "QJD-OLD",
                    "rec-charlie-v1", "2026-09-02 08:00:00", null);
            insertMatch(s, "match-charlie-v1", "rec-charlie-v1", "emp-charlie");

            // Second version: same businessKey, serial QJD-NEW
            insertNormalizedRecord(s, "rec-charlie-v2", "2026-09-05 00:00:00", "2026-09-08 00:00:00", null);
            insertOaDocument(s, "oa-charlie-v2", "OA", "LEAVE:charlie-1", "LEAVE", "QJD-NEW",
                    "rec-charlie-v2", "2026-09-03 10:00:00", null);
            insertMatch(s, "match-charlie-v2", "rec-charlie-v2", "emp-charlie");

            // Amendment matches old serial QJD-OLD
            insertNormalizedRecord(s, "rec-charlie-amend", "2026-09-06 00:00:00", "2026-09-07 00:00:00", null);
            insertOaDocument(s, "oa-charlie-amend", "OA", "REVOCATION:charlie-1", "LEAVE_REVOCATION", null,
                    "rec-charlie-amend", "2026-09-04 10:00:00", "QJD-OLD");
            insertMatch(s, "match-charlie-amend", "rec-charlie-amend", "emp-charlie");

            var keys = s.selectList(NAMESPACE + "findHistoricallyAmendedLeaveKeys",
                    params("company-1", List.of("emp-charlie")));

            // DISTINCT ensures only one 'OA:LEAVE:charlie-1' despite two versions
            assertThat(keys).hasSize(1);
            assertThat(keys).containsExactly("OA:LEAVE:charlie-1");
        }
    }

    @Test
    void originalInAugustButAmendmentMovedIntoSeptemberStillReturned() throws Exception {
        // Real scenario: original interval Aug 28-30, amendment interval Sep 1-2,
        // query window Sep 1 - Oct 1 → amendment overlaps window, so key returned.
        try (SqlSession s = session()) {
            insertCompany(s, "company-1");
            insertEmployee(s, "emp-dave", "company-1");

            // Original: Aug 28-30 (before Sep window)
            insertNormalizedRecord(s, "rec-dave-aug", "2026-08-28 00:00:00", "2026-08-31 00:00:00", null);
            insertOaDocument(s, "oa-dave-aug", "OA", "LEAVE:dave-1", "LEAVE", "QJD-AUG",
                    "rec-dave-aug", "2026-08-25 08:00:00", null);
            insertMatch(s, "match-dave-aug", "rec-dave-aug", "emp-dave");

            // Amendment: Sep 1-2 (inside Sep window)
            insertNormalizedRecord(s, "rec-dave-amend-sep", "2026-09-01 00:00:00", "2026-09-03 00:00:00", null);
            insertOaDocument(s, "oa-dave-amend-sep", "OA", "REVOCATION:dave-1", "LEAVE_REVOCATION", null,
                    "rec-dave-amend-sep", "2026-08-30 10:00:00", "QJD-AUG");
            insertMatch(s, "match-dave-amend-sep", "rec-dave-amend-sep", "emp-dave");

            var keys = s.selectList(NAMESPACE + "findHistoricallyAmendedLeaveKeys",
                    params("company-1", List.of("emp-dave")));

            assertThat(keys).containsExactly("OA:LEAVE:dave-1");
        }
    }

    private void setupAliceSerialMatch(SqlSession s) throws Exception {
        insertCompany(s, "company-1");
        insertEmployee(s, "emp-alice", "company-1");
        insertNormalizedRecord(s, "rec-alice-leave-1", "2026-09-10 00:00:00", "2026-09-13 00:00:00", null);
        insertOaDocument(s, "oa-alice-leave-1", "OA", "LEAVE:alice-1", "LEAVE", "QJD-001",
                "rec-alice-leave-1", "2026-09-01 08:00:00", null);
        insertMatch(s, "match-alice-leave-1", "rec-alice-leave-1", "emp-alice");

        insertNormalizedRecord(s, "rec-alice-amend-1", "2026-09-11 00:00:00", "2026-09-12 00:00:00", null);
        insertOaDocument(s, "oa-alice-amend-1", "OA", "REVOCATION:alice-1", "LEAVE_REVOCATION", null,
                "rec-alice-amend-1", "2026-09-05 10:00:00", "QJD-001");
        insertMatch(s, "match-alice-amend-1", "rec-alice-amend-1", "emp-alice");
    }

    private void setupBobFallbackOverlap(SqlSession s) throws Exception {
        insertCompany(s, "company-1");
        insertEmployee(s, "emp-bob", "company-1");
        insertNormalizedRecord(s, "rec-bob-leave-1", "2026-09-15 00:00:00", "2026-09-18 00:00:00", null);
        insertOaDocument(s, "oa-bob-leave-1", "OA", "LEAVE:bob-1", "LEAVE", null,
                "rec-bob-leave-1", "2026-09-02 08:00:00", null);
        insertMatch(s, "match-bob-leave-1", "rec-bob-leave-1", "emp-bob");

        insertNormalizedRecord(s, "rec-bob-amend-1", "2026-09-16 00:00:00", "2026-09-17 00:00:00", null);
        insertOaDocument(s, "oa-bob-amend-1", "OA", "REVOCATION:bob-1", "LEAVE_REVOCATION", null,
                "rec-bob-amend-1", "2026-09-06 10:00:00", null);
        insertMatch(s, "match-bob-amend-1", "rec-bob-amend-1", "emp-bob");
    }

    private java.util.Map<String, Object> params(String companyId, List<String> employeeIds) {
        return java.util.Map.of(
                "companyId", companyId,
                "windowStart", WINDOW_START,
                "windowEndExclusive", WINDOW_END,
                "dataAsOf", DATA_AS_OF,
                "employeeIds", employeeIds != null ? employeeIds : List.of());
    }

    private void insertCompany(SqlSession s, String id) throws Exception {
        s.getConnection().createStatement().execute(
                "INSERT INTO company (company_id) VALUES ('" + id + "')");
    }

    private void insertEmployee(SqlSession s, String empId, String companyId) throws Exception {
        s.getConnection().createStatement().execute(
                "INSERT INTO employee (employee_id, company_id) VALUES ('" + empId + "', '" + companyId + "')");
    }

    private void insertNormalizedRecord(SqlSession s, String id, String start, String end, String point) throws Exception {
        try (var p = s.getConnection().prepareStatement(
                "INSERT INTO normalized_attendance_record (normalized_attendance_record_id, interval_start, interval_end, point_instant) " +
                        "VALUES (?, ?, ?, ?)")) {
            p.setString(1, id);
            p.setString(2, start);
            p.setString(3, end);
            p.setString(4, point);
            p.executeUpdate();
        }
    }

    private void insertOaDocument(SqlSession s, String id, String sourceId, String businessKey, String type,
                                  String serial, String recId, String createdAt, String originalSerial) throws Exception {
        try (var p = s.getConnection().prepareStatement(
                "INSERT INTO oa_attendance_document (oa_attendance_document_id, attendance_source_id, source_business_key, " +
                        "document_type, leave_serial, normalized_attendance_record_id, created_at, revoked_at, original_leave_serial) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?)")) {
            p.setString(1, id);
            p.setString(2, sourceId);
            p.setString(3, businessKey);
            p.setString(4, type);
            p.setString(5, serial);
            p.setString(6, recId);
            p.setString(7, createdAt);
            p.setString(8, originalSerial);
            p.executeUpdate();
        }
    }

    private void insertMatch(SqlSession s, String id, String recId, String empId) throws Exception {
        try (var p = s.getConnection().prepareStatement(
                "INSERT INTO employee_match_decision (employee_match_decision_id, normalized_attendance_record_id, employee_id, match_status) " +
                        "VALUES (?, ?, ?, 'MATCHED')")) {
            p.setString(1, id);
            p.setString(2, recId);
            p.setString(3, empId);
            p.executeUpdate();
        }
    }

    private SqlSession session() throws Exception {
        Configuration cfg = new Configuration();
        cfg.setEnvironment(new Environment("test", new JdbcTransactionFactory(),
                new UnpooledDataSource("org.h2.Driver",
                        "jdbc:h2:mem:amended-keys-" + UUID.randomUUID() + ";MODE=MySQL", "sa", "")));
        String resource = "mappers/AttendanceReportCalculationMapper.xml";
        try (var input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, cfg, resource, cfg.getSqlFragments()).parse();
        }
        SqlSession s = new SqlSessionFactoryBuilder().build(cfg).openSession(true);
        try (var stmt = s.getConnection().createStatement()) {
            stmt.execute("SET TIME ZONE 'UTC'");
            stmt.execute("CREATE TABLE company (company_id VARCHAR(64))");
            stmt.execute("CREATE TABLE employee (employee_id VARCHAR(64), company_id VARCHAR(64))");
            stmt.execute("CREATE TABLE normalized_attendance_record (" +
                    "normalized_attendance_record_id VARCHAR(64), " +
                    "interval_start TIMESTAMP, interval_end TIMESTAMP, point_instant TIMESTAMP)");
            stmt.execute("CREATE TABLE oa_attendance_document (" +
                    "oa_attendance_document_id VARCHAR(64), attendance_source_id VARCHAR(64), " +
                    "source_business_key VARCHAR(64), document_type VARCHAR(64), leave_serial VARCHAR(64), " +
                    "normalized_attendance_record_id VARCHAR(64), created_at TIMESTAMP, revoked_at TIMESTAMP, " +
                    "original_leave_serial VARCHAR(64))");
            stmt.execute("CREATE TABLE employee_match_decision (" +
                    "employee_match_decision_id VARCHAR(64), normalized_attendance_record_id VARCHAR(64), " +
                    "employee_id VARCHAR(64), match_status VARCHAR(64))");
        }
        return s;
    }
}
