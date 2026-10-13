package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.attendance.domain.OvertimeType;
import com.szsemicon.hr.attendance.domain.LeaveType;
import com.szsemicon.hr.evidenceingestion.application.EvidenceRows.OaObservationRow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

/** Production mapper SQL against the real referenced column names and relationships. */
class OaObservationPersistenceTest {
    @Test
    void latestObservationUsesContextClassificationAndSeparatelyFindsSafeResolvedPredecessor() throws Exception {
        try (SqlSession session = session()) {
            var mapper = session.getMapper(AttendanceEvidenceMapper.class);
            var latest = mapper.findLatestOaObservation("source", "OVERTIME:1", false);
            assertThat(latest.sourceVersion()).isEqualTo("v2");
            assertThat(latest.oaAttendanceDocumentId()).isNull();
            assertThat(latest.knowledgeRank()).isNull();
            assertThat(latest.employeeId()).isNull();
            assertThat(latest.overtimeType()).isNull();
            assertThat(latest.canonicalPayloadDigest()).isEqualTo("digest2");
            assertThat(latest.receivedAt()).isEqualTo(Instant.parse("2026-10-02T00:00:00.000001Z"));
            var prior = mapper.findLatestOaObservation("source", "OVERTIME:1", true);
            assertThat(prior.sourceVersion()).isEqualTo("v1");
            assertThat(prior.overtimeType()).isEqualTo(OvertimeType.PAID);
            assertThat(prior.employeeId()).isEqualTo("employee");
            assertThat(prior.knowledgeRank()).isEqualTo(100L);
            assertThat(prior.startInstant()).isEqualTo(Instant.parse("2026-09-09T00:30:00Z"));
            assertThat(prior.endInstant()).isEqualTo(Instant.parse("2026-09-09T03:30:00Z"));
            assertThat(prior.employmentPeriodId()).isEqualTo("assignment");
            assertThat(prior.resolverSnapshotDigest()).isEqualTo("resolver-digest");
            assertThat(prior.documentType()).isEqualTo("OVERTIME");
            assertThat(prior.sourceStatus()).isEqualTo("APPROVED");
            assertThat(mapper.findOaBusinessKeysOverlapping("source", Instant.parse("2026-09-09T00:00:00Z"), Instant.parse("2026-09-10T00:00:00Z")))
                    .containsExactly("OVERTIME:1");
            assertThat(mapper.findLatestOaObservation("other", "OVERTIME:1", false)).isNull();
        }
    }

    @Test
    void constructorMappingUsesColumnNamesWhenProjectionOrderChanges() throws Exception {
        try (SqlSession session = session()) {
            var configuration = session.getConfiguration();
            var production = configuration.getMappedStatement(
                    AttendanceEvidenceMapper.class.getName() + ".findLatestOaObservation");
            // Reuse the production constructor result map with a deliberately
            // reversed SELECT. This catches fragile positional record mapping.
            String reversed = """
                    SELECT oa.original_leave_serial, oa.leave_serial, oa.leave_type,
                           context.overtime_type, matched.resolver_snapshot_digest,
                           matched.employment_period_id, matched.employee_id,
                           raw.interval_end AS end_instant, raw.interval_start AS start_instant,
                           oa.source_status, oa.document_type, oa.oa_attendance_document_id,
                           oa.knowledge_rank, raw.received_at, raw.canonical_payload_digest,
                           raw.source_version, raw.raw_attendance_fact_id
                    FROM raw_attendance_fact raw
                    JOIN normalized_attendance_record normalized ON normalized.raw_attendance_fact_id = raw.raw_attendance_fact_id
                    JOIN oa_attendance_document oa ON oa.normalized_attendance_record_id = normalized.normalized_attendance_record_id
                    JOIN oa_attendance_document_context context ON context.oa_attendance_document_id = oa.oa_attendance_document_id
                    JOIN employee_match_decision matched ON matched.normalized_attendance_record_id = normalized.normalized_attendance_record_id
                    WHERE oa.oa_attendance_document_id = 'oa1'
                    """;
            configuration.addMappedStatement(new MappedStatement.Builder(configuration,
                    "reversedObservation", new StaticSqlSource(configuration, reversed), SqlCommandType.SELECT)
                    .resultMaps(production.getResultMaps()).build());
            OaObservationRow reversedRow = session.selectOne("reversedObservation");
            assertThat(reversedRow).isEqualTo(session.getMapper(AttendanceEvidenceMapper.class)
                    .findLatestOaObservation("source", "OVERTIME:1", true));
        }
    }

    @Test
    void timestampThenKnowledgeRankSelectCurrentObservationInsteadOfLexicographicUuid() throws Exception {
        try (SqlSession session = session()) {
            execute(session,
                    "INSERT INTO raw_attendance_fact VALUES ('zz-old', 'source', 'LEAVE:1', 'v1', 'd1', '2026-10-02 01:00:00', 'OA_DOCUMENT', '2026-09-09 00:00:00', '2026-09-09 03:00:00', NULL)",
                    "INSERT INTO raw_attendance_fact VALUES ('aa-current', 'source', 'LEAVE:1', 'v2', 'd2', '2026-10-02 01:00:00', 'OA_DOCUMENT', '2026-09-09 00:00:00', '2026-09-09 03:00:00', NULL)",
                    "INSERT INTO normalized_attendance_record VALUES ('zz-old','norm-old',1),('aa-current','norm-current',1)",
                    "INSERT INTO oa_attendance_document (oa_attendance_document_id, normalized_attendance_record_id, knowledge_rank, document_type, source_status, leave_type, leave_serial, original_leave_serial) VALUES ('zz-approved','norm-old',100,'LEAVE','APPROVED','PERSONAL','请假原单001',NULL),('aa-revoked','norm-current',101,'LEAVE','REVOKED','PERSONAL','请假原单001',NULL)",
                    "INSERT INTO employee_match_decision VALUES ('norm-old','employee','assignment','resolver','MATCHED'),('norm-current','employee','assignment','resolver','MATCHED')");
            var mapper = session.getMapper(AttendanceEvidenceMapper.class);
            var current = mapper.findLatestOaObservation("source", "LEAVE:1", false);
            assertThat(current.rawAttendanceFactId()).isEqualTo("aa-current");
            assertThat(current.sourceStatus()).isEqualTo("REVOKED");
            assertThat(current.leaveType()).isEqualTo(LeaveType.PERSONAL);
            assertThat(current.leaveSerial()).isEqualTo("请假原单001");
            assertThat(current.overtimeType()).isNull();

            execute(session, "INSERT INTO raw_attendance_fact VALUES ('later-quarantine', 'source', 'LEAVE:1', 'v3', 'd3', '2026-10-02 01:00:00.000001', 'OA_DOCUMENT', '2026-09-09 00:00:00', '2026-09-09 02:00:00', NULL)");
            session.clearCache();
            assertThat(mapper.findLatestOaObservation("source", "LEAVE:1", false).sourceVersion()).isEqualTo("v3");
            assertThat(mapper.findLatestOaObservation("source", "LEAVE:1", true).sourceStatus()).isEqualTo("REVOKED");
        }
    }

    @Test
    void legacyEqualRanksUseTheSameDocumentTieBreakerAsReportReaders() throws Exception {
        try (SqlSession session = session()) {
            execute(session,
                    "INSERT INTO raw_attendance_fact VALUES ('zz-raw', 'source', 'LEAVE:tie', 'v1', 'd1', '2026-10-02 01:00:00', 'OA_DOCUMENT', '2026-09-09 00:00:00', '2026-09-09 03:00:00', NULL)",
                    "INSERT INTO raw_attendance_fact VALUES ('aa-raw', 'source', 'LEAVE:tie', 'v2', 'd2', '2026-10-02 01:00:00', 'OA_DOCUMENT', '2026-09-09 00:00:00', '2026-09-09 03:00:00', NULL)",
                    "INSERT INTO normalized_attendance_record VALUES ('zz-raw','tie-first',1),('aa-raw','tie-second',1)",
                    "INSERT INTO oa_attendance_document (oa_attendance_document_id, normalized_attendance_record_id, knowledge_rank, document_type, source_status, leave_type, leave_serial, original_leave_serial) VALUES ('aa-document','tie-first',100,'LEAVE','APPROVED','PERSONAL','L1',NULL),('zz-document','tie-second',100,'LEAVE','REVOKED','PERSONAL','L1',NULL)",
                    "INSERT INTO employee_match_decision VALUES ('tie-first','employee','assignment','resolver','MATCHED'),('tie-second','employee','assignment','resolver','MATCHED')");
            var mapper = session.getMapper(AttendanceEvidenceMapper.class);
            for (boolean resolvedOnly : List.of(false, true)) {
                var current = mapper.findLatestOaObservation("source", "LEAVE:tie", resolvedOnly);
                assertThat(current.rawAttendanceFactId()).isEqualTo("aa-raw");
                assertThat(current.oaAttendanceDocumentId()).isEqualTo("zz-document");
                assertThat(current.sourceStatus()).isEqualTo("REVOKED");
            }
        }
    }

    @Test
    void latestNormalizationRevisionKeepsPointShapeAndMatchedIdentityInAnotherCompany() throws Exception {
        try (SqlSession session = session()) {
            execute(session,
                    "INSERT INTO raw_attendance_fact VALUES ('raw-point','source','LEAVE_REVOCATION:1','v1','point-digest','2026-10-02 01:00:00','OA_DOCUMENT',NULL,NULL,'2026-09-09 00:00:00')",
                    "INSERT INTO normalized_attendance_record VALUES ('raw-point','point-unmatched',1),('raw-point','point-rematched',2)",
                    "INSERT INTO employee_match_decision VALUES ('point-unmatched',NULL,NULL,NULL,'UNMATCHED'),('point-rematched','employee-other-company','other-assignment','other-resolver','MATCHED')",
                    "INSERT INTO oa_attendance_document (oa_attendance_document_id, normalized_attendance_record_id, knowledge_rank, document_type, source_status, leave_type, leave_serial, original_leave_serial) VALUES ('oa-point','point-rematched',200,'LEAVE_REVOCATION','APPROVED',NULL,'销假001','请假原单001')");
            var row = session.getMapper(AttendanceEvidenceMapper.class)
                    .findLatestOaObservation("source", "LEAVE_REVOCATION:1", true);
            assertThat(row.startInstant()).isEqualTo(Instant.parse("2026-09-09T00:00:00Z"));
            assertThat(row.endInstant()).isEqualTo(row.startInstant());
            assertThat(row.employeeId()).isEqualTo("employee-other-company");
            assertThat(row.employmentPeriodId()).isEqualTo("other-assignment");
            assertThat(row.leaveSerial()).isEqualTo("销假001");
            assertThat(row.originalLeaveSerial()).isEqualTo("请假原单001");
        }
    }

    @Test
    void windowKeysIncludeHistoricalOverlapAndSharedSourceEmployeesUsingHalfOpenBounds() throws Exception {
        try (SqlSession session = session()) {
            execute(session,
                    "INSERT INTO raw_attendance_fact VALUES ('point-start','source','PUNCH_CORRECTION:1','v1','d','2026-10-02 01:00:00','OA_DOCUMENT',NULL,NULL,'2026-09-09 00:00:00')",
                    "INSERT INTO raw_attendance_fact VALUES ('point-end','source','PUNCH_CORRECTION:2','v1','d','2026-10-02 01:00:00','OA_DOCUMENT',NULL,NULL,'2026-09-10 00:00:00')",
                    "INSERT INTO raw_attendance_fact VALUES ('end-at-start','source','LEAVE:before','v1','d','2026-10-02 01:00:00','OA_DOCUMENT','2026-09-08 22:00:00','2026-09-09 00:00:00',NULL)",
                    "INSERT INTO raw_attendance_fact VALUES ('cross-window','source','LEAVE:cross','v1','d','2026-10-02 01:00:00','OA_DOCUMENT','2026-09-08 22:00:00','2026-09-09 01:00:00',NULL)",
                    "INSERT INTO raw_attendance_fact VALUES ('foreign-source','foreign','LEAVE:foreign','v1','d','2026-10-02 01:00:00','OA_DOCUMENT','2026-09-09 00:00:00','2026-09-09 01:00:00',NULL)",
                    "INSERT INTO raw_attendance_fact VALUES ('non-oa','source','PUNCH:1','v1','d','2026-10-02 01:00:00','PUNCH_POINT',NULL,NULL,'2026-09-09 02:00:00')",
                    "INSERT INTO raw_attendance_fact VALUES ('moved-latest','source','OVERTIME:1','v3','d','2026-10-02 02:00:00','OA_DOCUMENT','2026-11-01 00:00:00','2026-11-01 03:00:00',NULL)",
                    "INSERT INTO normalized_attendance_record VALUES ('cross-window','cross-norm',1)",
                    "INSERT INTO employee_match_decision VALUES ('cross-norm','employee-other-company','other-assignment','other-resolver','MATCHED')");
            assertThat(session.getMapper(AttendanceEvidenceMapper.class).findOaBusinessKeysOverlapping(
                    "source", Instant.parse("2026-09-09T00:00:00Z"), Instant.parse("2026-09-10T00:00:00Z")))
                    .containsExactly("LEAVE:cross", "OVERTIME:1", "PUNCH_CORRECTION:1");
        }
    }

    @Test
    void historicalAmendmentKeysRemainDependenciesWhenBothCurrentVersionsMoveAndLoseTheirSerialLink() throws Exception {
        try (SqlSession session = session()) {
            observation(session, "original-old", "source", "LEAVE:930", "LEAVE", "APPROVED",
                    "employee-other-company", "MATCHED", "2026-09-30 00:30:00", "2026-09-30 03:30:00", null,
                    " L-930 ", null);
            observation(session, "original-new", "source", "LEAVE:930", "LEAVE", "REVOKED",
                    "employee-other-company", "MATCHED", "2026-11-01 00:30:00", "2026-11-01 03:30:00", null,
                    "L-new", null);
            observation(session, "amendment-old", "source", "LEAVE_REVOCATION:930", "LEAVE_REVOCATION", "APPROVED",
                    "employee-other-company", "MATCHED", null, null, "2026-10-05 00:30:00", "R-930", "L-930");
            observation(session, "amendment-new", "source", "LEAVE_REVOCATION:930", "LEAVE_REVOCATION", "REVOKED",
                    "employee-other-company", "MATCHED", null, null, "2026-11-05 00:30:00", "R-930", null);

            assertThat(session.getMapper(AttendanceEvidenceMapper.class).findOaBusinessKeysOverlapping(
                    "source", Instant.parse("2026-09-29T16:00:00Z"), Instant.parse("2026-09-30T16:00:00Z")))
                    .containsExactly("LEAVE:930", "LEAVE_REVOCATION:930");
        }
    }

    @Test
    void historicalSerialLookupNeverCrossesSourceMatchedEmployeeOrBlankSerial() throws Exception {
        try (SqlSession session = session()) {
            observation(session, "original", "source", "LEAVE:930", "LEAVE", "APPROVED",
                    "employee-other-company", "MATCHED", "2026-09-30 00:30:00", "2026-09-30 03:30:00", null,
                    "L-930", null);
            observation(session, "foreign", "foreign", "LEAVE_REVOCATION:foreign", "LEAVE_REVOCATION", "APPROVED",
                    "employee-other-company", "MATCHED", null, null, "2026-10-05 00:30:00", null, "L-930");
            observation(session, "other-person", "source", "LEAVE_REVOCATION:person", "LEAVE_REVOCATION", "APPROVED",
                    "employee", "MATCHED", null, null, "2026-10-05 00:30:00", null, "L-930");
            observation(session, "unmatched", "source", "LEAVE_REVOCATION:unmatched", "LEAVE_REVOCATION", "APPROVED",
                    "employee-other-company", "UNMATCHED", null, null, "2026-10-05 00:30:00", null, "L-930");
            observation(session, "other-serial", "source", "LEAVE_REVOCATION:serial", "LEAVE_REVOCATION", "APPROVED",
                    "employee-other-company", "MATCHED", null, null, "2026-10-05 00:30:00", null, "L-other");
            observation(session, "no-serial", "source", "LEAVE_REVOCATION:blank", "LEAVE_REVOCATION", "APPROVED",
                    "employee-other-company", "MATCHED", null, null, "2026-10-05 00:30:00", null, "   ");

            assertThat(session.getMapper(AttendanceEvidenceMapper.class).findOaBusinessKeysOverlapping(
                    "source", Instant.parse("2026-09-29T16:00:00Z"), Instant.parse("2026-09-30T16:00:00Z")))
                    .containsExactly("LEAVE:930");
        }
    }

    @Test
    void seriallessHistoricalAmendmentWithinAFullOriginalIntervalIsRereadEvenOutsideTheTargetDay() throws Exception {
        try (SqlSession session = session()) {
            observation(session, "long-original", "source", "LEAVE:long", "LEAVE", "APPROVED",
                    "employee", "MATCHED", "2026-09-29 00:00:00", "2026-10-02 00:00:00", null, null, null);
            observation(session, "serialless", "source", "LEAVE_REVOCATION:contained", "LEAVE_REVOCATION", "APPROVED",
                    "employee", "MATCHED", null, null, "2026-10-01 01:00:00", null, null);
            observation(session, "not-contained", "source", "LEAVE_REVOCATION:outside", "LEAVE_REVOCATION", "APPROVED",
                    "employee", "MATCHED", null, null, "2026-10-03 00:00:00", null, null);

            assertThat(session.getMapper(AttendanceEvidenceMapper.class).findOaBusinessKeysOverlapping(
                    "source", Instant.parse("2026-09-29T16:00:00Z"), Instant.parse("2026-09-30T16:00:00Z")))
                    .containsExactly("LEAVE:long", "LEAVE_REVOCATION:contained");
        }
    }

    private static void observation(
            SqlSession session, String id, String source, String key, String type, String status,
            String employee, String matchStatus, String start, String end, String point,
            String leaveSerial, String originalSerial) throws Exception {
        var connection = session.getConnection();
        try (var insert = connection.prepareStatement("INSERT INTO raw_attendance_fact VALUES (?, ?, ?, ?, 'digest', '2026-10-04 00:00:00', 'OA_DOCUMENT', ?, ?, ?)")) {
            insert.setString(1, "raw-" + id); insert.setString(2, source);
            insert.setString(3, key); insert.setString(4, "version-" + id);
            insert.setString(5, start); insert.setString(6, end); insert.setString(7, point);
            insert.executeUpdate();
        }
        try (var insert = connection.prepareStatement("INSERT INTO normalized_attendance_record VALUES (?, ?, 1)")) {
            insert.setString(1, "raw-" + id); insert.setString(2, "norm-" + id); insert.executeUpdate();
        }
        try (var insert = connection.prepareStatement("INSERT INTO oa_attendance_document (oa_attendance_document_id, normalized_attendance_record_id, knowledge_rank, document_type, source_status, leave_serial, original_leave_serial, attendance_source_id) VALUES (?, ?, 100, ?, ?, ?, ?, ?)")) {
            insert.setString(1, "oa-" + id); insert.setString(2, "norm-" + id);
            insert.setString(3, type); insert.setString(4, status); insert.setString(5, leaveSerial);
            insert.setString(6, originalSerial); insert.setString(7, source); insert.executeUpdate();
        }
        try (var insert = connection.prepareStatement("INSERT INTO employee_match_decision VALUES (?, ?, 'assignment', 'resolver', ?)")) {
            insert.setString(1, "norm-" + id); insert.setString(2, employee); insert.setString(3, matchStatus);
            insert.executeUpdate();
        }
    }

    private static void execute(SqlSession session, String... statements) throws Exception {
        try (var statement = session.getConnection().createStatement()) {
            for (String sql : statements) { statement.execute(sql); }
        }
    }

    private SqlSession session() throws Exception {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("observation", new JdbcTransactionFactory(),
                new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:observation-" + UUID.randomUUID() + ";MODE=MySQL", "sa", "")));
        String resource = "mappers/AttendanceEvidenceMapper.xml";
        try (var input = Resources.getResourceAsStream(resource)) { new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse(); }
        SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        try (var statement = session.getConnection().createStatement()) {
            for (String sql : List.of(
                    "SET TIME ZONE 'UTC'",
                    "CREATE TABLE attendance_source (attendance_source_id VARCHAR(36) PRIMARY KEY, company_id VARCHAR(36))",
                    "CREATE TABLE employee (employee_id VARCHAR(36) PRIMARY KEY, company_id VARCHAR(36))",
                    "CREATE TABLE raw_attendance_fact (raw_attendance_fact_id VARCHAR(36) PRIMARY KEY, attendance_source_id VARCHAR(36) REFERENCES attendance_source, source_business_key VARCHAR(191), source_version VARCHAR(128), canonical_payload_digest VARCHAR(64), received_at TIMESTAMP(6), fact_kind VARCHAR(32), interval_start TIMESTAMP(6), interval_end TIMESTAMP(6), source_instant TIMESTAMP(6), UNIQUE(attendance_source_id, source_business_key, source_version))",
                    "CREATE TABLE normalized_attendance_record (raw_attendance_fact_id VARCHAR(36) REFERENCES raw_attendance_fact, normalized_attendance_record_id VARCHAR(36) PRIMARY KEY, normalization_revision INT, UNIQUE(raw_attendance_fact_id, normalization_revision))",
                    "CREATE TABLE oa_attendance_document (oa_attendance_document_id VARCHAR(36) PRIMARY KEY, normalized_attendance_record_id VARCHAR(36) UNIQUE REFERENCES normalized_attendance_record, knowledge_rank BIGINT, document_type VARCHAR(32), source_status VARCHAR(32), leave_type VARCHAR(32), leave_serial VARCHAR(64), original_leave_serial VARCHAR(64), created_at TIMESTAMP(6) DEFAULT TIMESTAMP '2026-10-02 00:00:00', attendance_source_id VARCHAR(36) DEFAULT 'source' REFERENCES attendance_source)",
                    // V48 puts overtime_type on context, never on the OA document.
                    "CREATE TABLE oa_attendance_document_context (oa_attendance_document_id VARCHAR(36) PRIMARY KEY REFERENCES oa_attendance_document, overtime_type VARCHAR(20) CHECK(overtime_type IS NULL OR overtime_type IN ('PAID','COMPENSATORY','VOLUNTARY')))",
                    "CREATE TABLE employee_match_decision (normalized_attendance_record_id VARCHAR(36) PRIMARY KEY REFERENCES normalized_attendance_record, employee_id VARCHAR(36) REFERENCES employee, employment_period_id VARCHAR(36), resolver_snapshot_digest VARCHAR(64), match_status VARCHAR(32))",
                    "INSERT INTO attendance_source VALUES ('source','oa-source-company'),('foreign','foreign-source-company')",
                    "INSERT INTO employee VALUES ('employee','oa-source-company'),('employee-other-company','company-b')",
                    "INSERT INTO raw_attendance_fact VALUES ('raw1', 'source', 'OVERTIME:1', 'v1', 'digest1', '2026-10-02 00:00:00.000000', 'OA_DOCUMENT', '2026-09-09 00:30:00', '2026-09-09 03:30:00', NULL)",
                    "INSERT INTO raw_attendance_fact VALUES ('raw2', 'source', 'OVERTIME:1', 'v2', 'digest2', '2026-10-02 00:00:00.000001', 'OA_DOCUMENT', '2026-09-09 00:30:00', '2026-09-09 02:30:00', NULL)",
                    "INSERT INTO normalized_attendance_record VALUES ('raw1','normalized1',1), ('raw2','normalized2',1)",
                    "INSERT INTO oa_attendance_document (oa_attendance_document_id, normalized_attendance_record_id, knowledge_rank, document_type, source_status, leave_type, leave_serial, original_leave_serial) VALUES ('oa1','normalized1',100,'OVERTIME','APPROVED',NULL,NULL,NULL)",
                    "INSERT INTO oa_attendance_document_context VALUES ('oa1','PAID')",
                    "INSERT INTO employee_match_decision VALUES ('normalized1','employee','assignment','resolver-digest','MATCHED'), ('normalized2',NULL,NULL,NULL,'UNMATCHED')")) {
                statement.execute(sql);
            }
        }
        return session;
    }
}
