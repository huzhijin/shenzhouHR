package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.evidenceingestion.application.EvidenceRows.UnparseableLeaveScopeRow;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** Real production SQL: no current-only join may conceal an unsafe historical dependency. */
class OaUnparseableLeaveScopePersistenceTest {
    private static final String KEY = "LEAVE:-4907541281623947734";
    private static final String SERIAL = "QJD-20260831020";
    private static final String LEGACY_VERSION = "2026-08-31T22:04:49:state=0:Asia/Shanghai";

    @Test
    void mapsAllNineteenFieldsAndRetainsEveryEmployeeNumberVersion() throws Exception {
        try (SqlSession session = session()) {
            execute(session, "INSERT INTO employee_version VALUES ('outside-old','outside','SZT0548')");
            var rows = rows(session);
            assertThat(rows).hasSize(2);
            assertThat(rows).extracting(UnparseableLeaveScopeRow::historicalEmployeeNumber)
                    .containsExactlyInAnyOrder("SZST0548", "SZT0548");
            assertThat(rows).allSatisfy(row -> {
                assertThat(row.sourceBusinessKey()).isEqualTo(KEY);
                assertThat(row.rawAttendanceFactId()).isEqualTo("raw-leave");
                assertThat(row.factKind()).isEqualTo("OA_DOCUMENT");
                assertThat(row.normalizedAttendanceRecordId()).isEqualTo("norm-leave");
                assertThat(row.validationStatus()).isEqualTo("VALID");
                assertThat(row.recordKind()).isEqualTo("OA_INTERVAL");
                assertThat(row.oaAttendanceDocumentId()).isEqualTo("oa-leave");
                assertThat(row.documentType()).isEqualTo("LEAVE");
                assertThat(row.documentIdentityConsistent()).isTrue();
                assertThat(row.matchStatus()).isEqualTo("MATCHED");
                assertThat(row.employeeId()).isEqualTo("outside");
                assertThat(row.employeeCompanyId()).isEqualTo("outside-company");
                assertThat(row.employeeNumber()).isEqualTo("SZST0548");
                assertThat(row.hasTargetReportFact()).isFalse();
                assertThat(row.sourceVersion()).isEqualTo(LEGACY_VERSION);
                assertThat(row.sourceStatus()).isEqualTo("UNKNOWN");
                assertThat(row.leaveSerial()).isEqualTo(SERIAL);
                assertThat(row.hasRelatedRevocation()).isFalse();
                assertThat(row.identityHistory().historicalEmployeeNumber()).isEqualTo(row.historicalEmployeeNumber());
            });
        }
    }

    @Test
    void rawOnlyObservationAndOlderIncompleteNormalizationRemainVisible() throws Exception {
        try (SqlSession session = session()) {
            execute(session,
                    "INSERT INTO raw_attendance_fact VALUES ('raw-new','shared','" + KEY + "','new-version','OA_DOCUMENT')",
                    "INSERT INTO normalized_attendance_record VALUES ('norm-old','raw-leave',0,'QUARANTINED','OA_INTERVAL')");
            var rows = rows(session);
            assertThat(rows).hasSize(3);
            assertThat(rows).filteredOn(row -> "raw-new".equals(row.rawAttendanceFactId()))
                    .singleElement().satisfies(row -> {
                        assertThat(row.normalizedAttendanceRecordId()).isNull();
                        assertThat(row.oaAttendanceDocumentId()).isNull();
                        assertThat(row.documentIdentityConsistent()).isFalse();
                    });
            assertThat(rows).filteredOn(row -> "norm-old".equals(row.normalizedAttendanceRecordId()))
                    .singleElement().satisfies(row -> {
                        assertThat(row.validationStatus()).isEqualTo("QUARANTINED");
                        assertThat(row.oaAttendanceDocumentId()).isNull();
                    });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"attendance_source_id='foreign'", "source_business_key='LEAVE:other'", "source_version='wrong'"})
    void documentIdentityMismatchCannotBeHiddenByJoin(String mutation) throws Exception {
        try (SqlSession session = session()) {
            execute(session, "UPDATE oa_attendance_document SET " + mutation);
            assertThat(rows(session)).singleElement().satisfies(row -> assertThat(row.documentIdentityConsistent()).isFalse());
        }
    }

    @Test
    void historicalTargetFactBlocksEvenWithoutCurrentPinAndRegardlessOfItsDate() throws Exception {
        try (SqlSession session = session()) {
            execute(session, "INSERT INTO attendance_report_oa_fact VALUES ('old-fact','old-pin','target-company','target','oa-leave','2025-01-01 00:00:00')");
            assertThat(rows(session)).singleElement().satisfies(row -> assertThat(row.hasTargetReportFact()).isTrue());
        }
    }

    @Test
    void anyEffectiveEvidenceLinkBlocksIndependentOfOaMatchOwnership() throws Exception {
        try (SqlSession session = session()) {
            execute(session, "INSERT INTO evidence_link VALUES ('old-link','target-event','raw-leave','norm-leave','old-match','PRIMARY','2025-01-01 00:00:00')");
            assertThat(rows(session)).singleElement().satisfies(row -> assertThat(row.hasTargetReportFact()).isTrue());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "APPROVED", "REVOKED", "REJECTED", "UNKNOWN"})
    void everyHistoricalStatusOfSerialLinkedRevocationBlocks(String status) throws Exception {
        try (SqlSession session = session()) {
            revocation(session, "rev", "shared", "  " + SERIAL + "  ", status);
            assertThat(rows(session)).singleElement().satisfies(row -> assertThat(row.hasRelatedRevocation()).isTrue());
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void blankSerialRevocationBlocksRatherThanGuessingItsFallbackAssociation(String originalSerial) throws Exception {
        try (SqlSession session = session()) {
            revocation(session, "rev", "shared", originalSerial, "PENDING");
            assertThat(rows(session)).singleElement().satisfies(row -> assertThat(row.hasRelatedRevocation()).isTrue());
        }
    }

    @Test
    void differentSerialAndDifferentSourceDoNotFabricateDependencies() throws Exception {
        try (SqlSession session = session()) {
            revocation(session, "different", "shared", "QJD-unrelated", "APPROVED");
            revocation(session, "foreign", "foreign", SERIAL, "APPROVED");
            revocation(session, "foreign-blank", "foreign", null, "PENDING");
            execute(session, "INSERT INTO raw_attendance_fact VALUES ('foreign-opaque','foreign','LEAVE_REVOCATION:opaque','v','OA_DOCUMENT')");
            assertThat(rows(session)).singleElement().satisfies(row -> assertThat(row.hasRelatedRevocation()).isFalse());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("opaqueRevocations")
    void opaqueOrMisidentifiedRevocationHistoryVetoesTheProof(String description, String[] changes) throws Exception {
        try (SqlSession session = session()) {
            revocation(session, "rev", "shared", "QJD-unrelated", "REVOKED");
            execute(session, changes);
            assertThat(rows(session)).as(description).singleElement()
                    .satisfies(row -> assertThat(row.hasRelatedRevocation()).isTrue());
        }
    }

    private static Stream<Arguments> opaqueRevocations() {
        return Stream.of(
                Arguments.of("new raw-only revocation", new String[]{"INSERT INTO raw_attendance_fact VALUES ('raw-only','shared','LEAVE_REVOCATION:opaque','v','OA_DOCUMENT')"}),
                Arguments.of("old normalization without its document", new String[]{"INSERT INTO normalized_attendance_record VALUES ('norm-incomplete','raw-rev',0,'QUARANTINED','OA_INTERVAL')"}),
                Arguments.of("document missing", new String[]{"DELETE FROM oa_attendance_document WHERE oa_attendance_document_id='oa-rev'"}),
                Arguments.of("document wrong type", new String[]{"UPDATE oa_attendance_document SET document_type='LEAVE' WHERE oa_attendance_document_id='oa-rev'"}),
                Arguments.of("document wrong source", new String[]{"UPDATE oa_attendance_document SET attendance_source_id='foreign' WHERE oa_attendance_document_id='oa-rev'"}),
                Arguments.of("document wrong business key", new String[]{"UPDATE oa_attendance_document SET source_business_key='LEAVE_REVOCATION:wrong' WHERE oa_attendance_document_id='oa-rev'"}),
                Arguments.of("document wrong source version", new String[]{"UPDATE oa_attendance_document SET source_version='wrong' WHERE oa_attendance_document_id='oa-rev'"}));
    }

    @Test
    void employeeScopePreservesEveryHistoryColumnAndAllCurrentAndHistoricalNumbers() throws Exception {
        try (SqlSession session = session()) {
            execute(session,
                    "INSERT INTO employee_version VALUES ('outside-v0','outside','SOURCE-OLD-ALIAS')",
                    "INSERT INTO employee_version VALUES ('target-v0','target','TARGET-OLD-ALIAS')",
                    "INSERT INTO employee VALUES ('target-two','target-company','TARGET-TWO'),('coworker','target-company','NOT-IN-SCOPE')",
                    "INSERT INTO employee_version VALUES ('target-two-v0','target-two','TARGET-TWO-OLD'),('target-two-v1','target-two','TARGET-TWO'),('coworker-v1','coworker','NOT-IN-SCOPE')");
            assertThat(employeeRows(session, List.of("target"))).containsExactlyElementsOf(rows(session));
            assertThat(employeeRows(session, List.of("target")))
                    .extracting(UnparseableLeaveScopeRow::employeeNumber).containsOnly("SZST0548");
            assertThat(employeeRows(session, List.of("target")))
                    .extracting(UnparseableLeaveScopeRow::historicalEmployeeNumber)
                    .containsExactlyInAnyOrder("SZST0548", "SOURCE-OLD-ALIAS");
            assertThat(session.getMapper(AttendanceEvidenceMapper.class)
                    .findReportEmployeeNumbers("target-company", List.of("target", "target-two")))
                    .containsExactlyInAnyOrder("SZST0221", "TARGET-OLD-ALIAS", "TARGET-TWO", "TARGET-TWO-OLD");
        }
    }

    @Test
    void sameCompanyOtherEmployeesOldFactDoesNotBecomeATargetFactButCompanyScopeStaysStrict() throws Exception {
        try (SqlSession session = session()) {
            execute(session,
                    "UPDATE employee SET company_id='target-company' WHERE employee_id='outside'",
                    "INSERT INTO attendance_report_oa_fact VALUES ('coworker-fact','old-pin','target-company','outside','oa-leave','2025-01-01 00:00:00')");
            assertThat(rows(session)).singleElement().satisfies(row -> assertThat(row.hasTargetReportFact()).isTrue());
            assertThat(employeeRows(session, List.of("target"))).singleElement().satisfies(row -> {
                assertThat(row.hasTargetReportFact()).isFalse();
                assertThat(row.employeeId()).isEqualTo("outside");
                assertThat(row.employeeCompanyId()).isEqualTo("target-company");
                assertThat(row.sourceVersion()).isEqualTo(LEGACY_VERSION);
                assertThat(row.leaveSerial()).isEqualTo(SERIAL);
                assertThat(row.sourceStatus()).isEqualTo("UNKNOWN");
            });
        }
    }

    @Test
    void anySelectedEmployeesHistoricalPinBlocksEvenWhenTheLatestObservationMatchesSomebodyElse() throws Exception {
        try (SqlSession session = session()) {
            execute(session,
                    "INSERT INTO employee VALUES ('target-two','target-company','TARGET-TWO')",
                    "INSERT INTO employee_version VALUES ('target-two-v1','target-two','TARGET-TWO')",
                    "INSERT INTO raw_attendance_fact VALUES ('raw-old','shared','" + KEY + "','old-version','OA_DOCUMENT')",
                    "INSERT INTO normalized_attendance_record VALUES ('norm-old','raw-old',1,'VALID','OA_INTERVAL')",
                    "INSERT INTO oa_attendance_document VALUES ('oa-old','norm-old','shared','" + KEY + "','old-version','LEAVE','REVOKED','" + SERIAL + "',NULL)",
                    "INSERT INTO employee_match_decision VALUES ('norm-old','outside','MATCHED')",
                    "INSERT INTO attendance_report_oa_fact VALUES ('historical-target','retired-pin','target-company','target-two','oa-old','2020-01-01 00:00:00')");
            assertThat(employeeRows(session, List.of("target"))).hasSize(2)
                    .allSatisfy(row -> assertThat(row.hasTargetReportFact()).isFalse());
            assertThat(employeeRows(session, List.of("target", "target-two"))).hasSize(2)
                    .allSatisfy(row -> assertThat(row.hasTargetReportFact()).isTrue());
            assertThat(employeeRows(session, List.of("target", "target-two")))
                    .extracting(UnparseableLeaveScopeRow::sourceStatus).containsExactlyInAnyOrder("UNKNOWN", "REVOKED");
        }
    }

    @Test
    void evidenceLinkStaysGlobalEvenWhenItDoesNotIdentifyAnySelectedEmployee() throws Exception {
        try (SqlSession session = session()) {
            execute(session, "INSERT INTO evidence_link VALUES ('outside-link','outside-event','raw-leave','norm-leave','outside-match','PRIMARY','2020-01-01 00:00:00')");
            assertThat(employeeRows(session, List.of("target"))).singleElement()
                    .satisfies(row -> assertThat(row.hasTargetReportFact()).isTrue());
        }
    }

    @Test
    void employeeScopeDoesNotHideRawOnlyOrIncompleteOlderNormalization() throws Exception {
        try (SqlSession session = session()) {
            execute(session,
                    "INSERT INTO raw_attendance_fact VALUES ('raw-new','shared','" + KEY + "','new-version','OA_DOCUMENT')",
                    "INSERT INTO normalized_attendance_record VALUES ('norm-unresolved','raw-leave',0,'QUARANTINED','OA_INTERVAL')");
            assertThat(employeeRows(session, List.of("target"))).containsExactlyElementsOf(rows(session));
            assertThat(employeeRows(session, List.of("target"))).hasSize(3)
                    .anySatisfy(row -> assertThat(row.normalizedAttendanceRecordId()).isNull())
                    .anySatisfy(row -> assertThat(row.validationStatus()).isEqualTo("QUARANTINED"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"attendance_source_id='foreign'", "source_business_key='LEAVE:other'", "source_version='wrong'"})
    void employeeScopeNeverTreatsAnInconsistentSourceIdentityAsComplete(String mutation) throws Exception {
        try (SqlSession session = session()) {
            execute(session, "UPDATE oa_attendance_document SET " + mutation);
            assertThat(employeeRows(session, List.of("target"))).singleElement()
                    .satisfies(row -> assertThat(row.documentIdentityConsistent()).isFalse());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "APPROVED", "REVOKED", "REJECTED", "UNKNOWN"})
    void employeeScopeStillSeesSerialLinkedRevocationsOfEveryHistoricalStatus(String status) throws Exception {
        try (SqlSession session = session()) {
            revocation(session, "rev", "shared", "  " + SERIAL + "  ", status);
            assertThat(employeeRows(session, List.of("target"))).singleElement()
                    .satisfies(row -> assertThat(row.hasRelatedRevocation()).isTrue());
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void employeeScopeNeverGuessesBlankSerialRevocationIsUnrelated(String serial) throws Exception {
        try (SqlSession session = session()) {
            revocation(session, "rev", "shared", serial, "PENDING");
            assertThat(employeeRows(session, List.of("target"))).singleElement()
                    .satisfies(row -> assertThat(row.hasRelatedRevocation()).isTrue());
        }
    }

    @ParameterizedTest(name = "employee scope: {0}")
    @MethodSource("opaqueRevocations")
    void employeeScopeKeepsOpaqueAndMisidentifiedRevocationVetoes(String description, String[] changes) throws Exception {
        try (SqlSession session = session()) {
            revocation(session, "rev", "shared", "QJD-unrelated", "REVOKED");
            execute(session, changes);
            assertThat(employeeRows(session, List.of("target"))).as(description).singleElement()
                    .satisfies(row -> assertThat(row.hasRelatedRevocation()).isTrue());
        }
    }

    @Test
    void emptyOrNullScopeAndKeysReturnNoProofRows() throws Exception {
        try (SqlSession session = session()) {
            var mapper = session.getMapper(AttendanceEvidenceMapper.class);
            assertThat(mapper.findUnparseableLeaveEmployeeScopeRows("shared", "target-company", null, List.of(KEY))).isEmpty();
            assertThat(mapper.findUnparseableLeaveEmployeeScopeRows("shared", "target-company", List.of(), List.of(KEY))).isEmpty();
            assertThat(mapper.findUnparseableLeaveEmployeeScopeRows("shared", "target-company", List.of("target"), null)).isEmpty();
            assertThat(mapper.findUnparseableLeaveEmployeeScopeRows("shared", "target-company", List.of("target"), List.of())).isEmpty();
            assertThat(mapper.findUnparseableLeaveEmployeeScopeRows("foreign", "target-company", List.of("target"), List.of(KEY))).isEmpty();
            assertThat(mapper.findUnparseableLeaveEmployeeScopeRows("shared", "target-company", List.of("target"), List.of("LEAVE:not-requested"))).isEmpty();
        }
    }

    private static List<UnparseableLeaveScopeRow> employeeRows(SqlSession session, List<String> employeeIds) {
        session.clearCache();
        return session.getMapper(AttendanceEvidenceMapper.class)
                .findUnparseableLeaveEmployeeScopeRows("shared", "target-company", employeeIds, List.of(KEY));
    }

    private static List<UnparseableLeaveScopeRow> rows(SqlSession session) {
        session.clearCache();
        return session.getMapper(AttendanceEvidenceMapper.class)
                .findUnparseableLeaveScopeRows("shared", "target-company", List.of(KEY));
    }

    private static void revocation(SqlSession session, String id, String source, String originalSerial, String status) throws Exception {
        insert(session, "INSERT INTO raw_attendance_fact VALUES (?, ?, ?, ?, 'OA_DOCUMENT')", "raw-" + id, source, "LEAVE_REVOCATION:" + id, "version-" + id);
        insert(session, "INSERT INTO normalized_attendance_record VALUES (?, ?, 1, 'VALID', 'OA_INTERVAL')", "norm-" + id, "raw-" + id);
        insert(session, "INSERT INTO oa_attendance_document VALUES (?, ?, ?, ?, ?, 'LEAVE_REVOCATION', ?, NULL, ?)",
                "oa-" + id, "norm-" + id, source, "LEAVE_REVOCATION:" + id, "version-" + id, status, originalSerial);
    }

    private static void insert(SqlSession session, String sql, String... values) throws Exception {
        try (var statement = session.getConnection().prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setString(i + 1, values[i]);
            statement.executeUpdate();
        }
    }

    private static void execute(SqlSession session, String... statements) throws Exception {
        try (var statement = session.getConnection().createStatement()) {
            for (String sql : statements) statement.execute(sql);
        }
        session.clearCache();
    }

    private static SqlSession session() throws Exception {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("unparseable-leave", new JdbcTransactionFactory(),
                new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:unparseable-leave-" + UUID.randomUUID() + ";MODE=MySQL", "sa", "")));
        String resource = "mappers/AttendanceEvidenceMapper.xml";
        try (var input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        execute(session,
                "SET TIME ZONE 'UTC'",
                "CREATE TABLE attendance_source (attendance_source_id VARCHAR(36) PRIMARY KEY, company_id VARCHAR(36))",
                "CREATE TABLE employee (employee_id VARCHAR(36) PRIMARY KEY, company_id VARCHAR(36), employee_number VARCHAR(64))",
                "CREATE TABLE employee_version (employee_version_id VARCHAR(36) PRIMARY KEY, employee_id VARCHAR(36) REFERENCES employee, employee_number VARCHAR(64))",
                "CREATE TABLE raw_attendance_fact (raw_attendance_fact_id VARCHAR(36) PRIMARY KEY, attendance_source_id VARCHAR(36) REFERENCES attendance_source, source_business_key VARCHAR(191), source_version VARCHAR(128), fact_kind VARCHAR(32), UNIQUE(attendance_source_id, source_business_key, source_version))",
                "CREATE TABLE normalized_attendance_record (normalized_attendance_record_id VARCHAR(36) PRIMARY KEY, raw_attendance_fact_id VARCHAR(36) REFERENCES raw_attendance_fact, normalization_revision INT, validation_status VARCHAR(32), record_kind VARCHAR(32), UNIQUE(raw_attendance_fact_id, normalization_revision))",
                "CREATE TABLE oa_attendance_document (oa_attendance_document_id VARCHAR(36) PRIMARY KEY, normalized_attendance_record_id VARCHAR(36) REFERENCES normalized_attendance_record, attendance_source_id VARCHAR(36) REFERENCES attendance_source, source_business_key VARCHAR(191), source_version VARCHAR(128), document_type VARCHAR(32), source_status VARCHAR(32), leave_serial VARCHAR(128), original_leave_serial VARCHAR(128))",
                "CREATE TABLE employee_match_decision (normalized_attendance_record_id VARCHAR(36) PRIMARY KEY REFERENCES normalized_attendance_record, employee_id VARCHAR(36) REFERENCES employee, match_status VARCHAR(32))",
                "CREATE TABLE attendance_report_oa_fact (attendance_report_oa_fact_id VARCHAR(36) PRIMARY KEY, attendance_report_projection_id VARCHAR(36), company_id VARCHAR(36), employee_id VARCHAR(36) REFERENCES employee, oa_attendance_document_id VARCHAR(36) REFERENCES oa_attendance_document, created_at TIMESTAMP(6))",
                "CREATE TABLE evidence_link (evidence_link_id VARCHAR(36) PRIMARY KEY, effective_attendance_event_id VARCHAR(36), raw_attendance_fact_id VARCHAR(36) REFERENCES raw_attendance_fact, normalized_attendance_record_id VARCHAR(36) REFERENCES normalized_attendance_record, employee_match_decision_id VARCHAR(36), link_type VARCHAR(32), created_at TIMESTAMP(6))",
                "INSERT INTO attendance_source VALUES ('shared','outside-company'),('foreign','foreign-company')",
                "INSERT INTO employee VALUES ('target','target-company','SZST0221'),('outside','outside-company','SZST0548')",
                "INSERT INTO employee_version VALUES ('target-v1','target','SZST0221'),('outside-v1','outside','SZST0548')");
        insert(session, "INSERT INTO raw_attendance_fact VALUES ('raw-leave','shared',?,?,'OA_DOCUMENT')", KEY, LEGACY_VERSION);
        execute(session, "INSERT INTO normalized_attendance_record VALUES ('norm-leave','raw-leave',1,'VALID','OA_INTERVAL')");
        insert(session, "INSERT INTO oa_attendance_document VALUES ('oa-leave','norm-leave','shared',?,?,'LEAVE','UNKNOWN',?,NULL)", KEY, LEGACY_VERSION, SERIAL);
        execute(session, "INSERT INTO employee_match_decision VALUES ('norm-leave','outside','MATCHED')");
        return session;
    }
}
