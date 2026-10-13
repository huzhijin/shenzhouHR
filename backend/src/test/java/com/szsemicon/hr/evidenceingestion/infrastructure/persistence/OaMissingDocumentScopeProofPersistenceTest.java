package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.evidenceingestion.application.MissingOaReportScopeProof;
import java.util.List;
import java.util.Set;
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
import org.junit.jupiter.params.provider.ValueSource;

/** Executes the production mapper: a missing form may be irrelevant, never presumed cancelled. */
class OaMissingDocumentScopeProofPersistenceTest {
    private static final String KEY = "OVERTIME:outside";

    @ParameterizedTest
    @ValueSource(strings = {"OVERTIME", "PUNCH_CORRECTION"})
    void completeDisjointHistoryOfSharedSourceIsProvablyOutsideTarget(String type) throws Exception {
        try (SqlSession session = session()) {
            String key = type + ":outside";
            observation(session, "first", "shared", key, type, "outside");
            observation(session, "second", "shared", key, type, "outside");
            execute(session, "UPDATE oa_attendance_document SET source_status='REVOKED' WHERE oa_attendance_document_id='oa-second'");
            var mapper = session.getMapper(AttendanceEvidenceMapper.class);
            var rows = mapper.findMissingOaKeyScopeRows("shared", "target-company", List.of(key));
            assertThat(rows).hasSize(2);
            assertThat(rows).allSatisfy(row -> {
                assertThat(row.employeeCompanyId()).isEqualTo("outside-company");
                assertThat(row.documentIdentityConsistent()).isTrue();
                assertThat(row.hasTargetReportFact()).isFalse();
            });
            assertThat(proven(session, "shared", key)).containsExactly(key);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unsafeHistories")
    void incompleteOrConflictingHistoryNeverDisappearsThroughJoins(String description, String[] sql) throws Exception {
        try (SqlSession session = session()) {
            observation(session, "first", "shared", KEY, "OVERTIME", "outside");
            execute(session, sql);
            assertThat(proven(session, "shared", KEY)).as(description).isEmpty();
        }
    }

    private static Stream<Arguments> unsafeHistories() {
        return Stream.of(
                scenario("raw-only newer observation",
                        "INSERT INTO raw_attendance_fact VALUES ('raw-new','shared','OVERTIME:outside','v-new','OA_DOCUMENT')"),
                scenario("normalization remains quarantined",
                        "UPDATE normalized_attendance_record SET validation_status='QUARANTINED'"),
                scenario("unresolved older normalization is not hidden by latest revision",
                        "INSERT INTO normalized_attendance_record VALUES ('norm-old','raw-first',0,'VALID','OA_INTERVAL')"),
                scenario("no OA document", "DELETE FROM oa_attendance_document"),
                scenario("mismatched document source", "UPDATE oa_attendance_document SET attendance_source_id='foreign'"),
                scenario("mismatched document key", "UPDATE oa_attendance_document SET source_business_key='OVERTIME:wrong'"),
                scenario("mismatched document version", "UPDATE oa_attendance_document SET source_version='wrong'"),
                scenario("wrong raw fact kind", "UPDATE raw_attendance_fact SET fact_kind='PUNCH_POINT'"),
                scenario("wrong normalized kind", "UPDATE normalized_attendance_record SET record_kind='PUNCH_POINT'"),
                scenario("type disagrees with requested business key", "UPDATE oa_attendance_document SET document_type='LEAVE'"),
                scenario("historical unmatched", "UPDATE employee_match_decision SET match_status='UNMATCHED'"),
                scenario("no matched employee", "UPDATE employee_match_decision SET employee_id=NULL"),
                scenario("current employee is target", "UPDATE employee_match_decision SET employee_id='target'"),
                scenario("current number equals target", "UPDATE employee SET employee_number='TARGET-001' WHERE employee_id='outside'"),
                scenario("outside historical number equals target",
                        "INSERT INTO employee_version VALUES ('outside-old','outside','TARGET-001')"),
                scenario("target historical number equals outside",
                        "INSERT INTO employee_version VALUES ('target-old','target','OUTSIDE-001')"),
                scenario("source SZT alias reaches target SZST number",
                        "UPDATE employee SET employee_number='SZT0687' WHERE employee_id='outside'",
                        "UPDATE employee SET employee_number='SZST0687' WHERE employee_id='target'"),
                scenario("target SZT alias reaches source SZST number",
                        "UPDATE employee SET employee_number='SZST0709' WHERE employee_id='outside'",
                        "UPDATE employee SET employee_number='SZT0709' WHERE employee_id='target'"),
                scenario("blank current source number", "UPDATE employee SET employee_number=' ' WHERE employee_id='outside'"),
                scenario("no source employee history", "DELETE FROM employee_version WHERE employee_id='outside'"),
                scenario("blank source employee history", "UPDATE employee_version SET employee_number=' ' WHERE employee_id='outside'"),
                scenario("blank target historical number", "INSERT INTO employee_version VALUES ('target-blank','target',' ')")
        );
    }

    @Test
    void changedEmployeeAcrossVersionsIsNotProvenEvenWhenBothAreOutside() throws Exception {
        try (SqlSession session = session()) {
            observation(session, "old", "shared", KEY, "OVERTIME", "outside");
            observation(session, "new", "shared", KEY, "OVERTIME", "other-outside");
            assertThat(proven(session, "shared", KEY)).isEmpty();
        }
    }

    @Test
    void oldTargetMatchBlocksEvenAfterCurrentVersionMovesToAnotherCompany() throws Exception {
        try (SqlSession session = session()) {
            observation(session, "old", "shared", KEY, "OVERTIME", "target");
            observation(session, "new", "shared", KEY, "OVERTIME", "outside");
            assertThat(proven(session, "shared", KEY)).isEmpty();
        }
    }

    @Test
    void targetReportDependencyOnAnOldVersionBlocksWithoutAnyCurrentPinOrDateFilter() throws Exception {
        try (SqlSession session = session()) {
            observation(session, "old", "shared", KEY, "OVERTIME", "outside");
            observation(session, "new", "shared", KEY, "OVERTIME", "outside");
            execute(session,
                    "UPDATE oa_attendance_document SET source_status='REVOKED' WHERE oa_attendance_document_id='oa-old'",
                    "INSERT INTO attendance_report_oa_fact VALUES ('historical-fact','obsolete-projection','target-company','oa-old','2025-01-01 00:00:00')");
            var rows = session.getMapper(AttendanceEvidenceMapper.class)
                    .findMissingOaKeyScopeRows("shared", "target-company", List.of(KEY));
            assertThat(rows).hasSize(2).allSatisfy(row -> assertThat(row.hasTargetReportFact()).isTrue());
            assertThat(proven(session, "shared", KEY)).isEmpty();
        }
    }

    @Test
    void sameBusinessKeyInAnotherSourceDoesNotConcealOrCreateADependency() throws Exception {
        try (SqlSession session = session()) {
            observation(session, "shared", "shared", KEY, "OVERTIME", "outside");
            observation(session, "foreign", "foreign", KEY, "OVERTIME", "target");
            execute(session, "INSERT INTO attendance_report_oa_fact VALUES ('foreign-fact','old-pin','target-company','oa-foreign','2025-01-01 00:00:00')");
            assertThat(proven(session, "shared", KEY)).containsExactly(KEY);
            assertThat(proven(session, "foreign", KEY)).isEmpty();
            assertThat(proven(session, "absent-source", KEY)).isEmpty();
        }
    }

    @Test
    void targetNumberCatalogContainsCurrentAndAllHistoricalNumbersAcrossEmploymentStates() throws Exception {
        try (SqlSession session = session()) {
            execute(session,
                    "INSERT INTO employee_version VALUES ('target-prior','target','TARGET-OLD')",
                    "INSERT INTO employee VALUES ('target-left','target-company','LEFT-CURRENT')",
                    "INSERT INTO employee_version VALUES ('left-prior','target-left','LEFT-OLD')");
            assertThat(session.getMapper(AttendanceEvidenceMapper.class).findCompanyEmployeeNumbers("target-company"))
                    .containsExactlyInAnyOrder("TARGET-001", "TARGET-OLD", "LEFT-CURRENT", "LEFT-OLD");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"LEAVE", "LEAVE_REVOCATION", "BUSINESS_TRIP", "UNKNOWN"})
    void otherDocumentFamiliesRemainStrictEvenWithCompleteOutsideHistory(String type) throws Exception {
        try (SqlSession session = session()) {
            String key = type + ":outside";
            observation(session, "other", "shared", key, type, "outside");
            assertThat(proven(session, "shared", key)).isEmpty();
        }
    }

    @Test
    void everyRequestedKeyRequiresItsOwnCompleteHistory() throws Exception {
        try (SqlSession session = session()) {
            observation(session, "first", "shared", KEY, "OVERTIME", "outside");
            assertThat(proven(session, "shared", KEY, "PUNCH_CORRECTION:no-history"))
                    .containsExactly(KEY);
        }
    }

    @Test
    void anyLinkedEvidenceKeepsTheKeyStrictEvenWhenAllOaMatchesBelongToAnotherCompany() throws Exception {
        try (SqlSession session = session()) {
            observation(session, "first", "shared", "PUNCH_CORRECTION:outside", "PUNCH_CORRECTION", "outside");
            execute(session, "INSERT INTO evidence_link VALUES ('historical-link','target-punch','raw-first','norm-first','old-match','PRIMARY','2025-01-01 00:00:00')");
            var rows = session.getMapper(AttendanceEvidenceMapper.class).findMissingOaKeyScopeRows(
                    "shared", "target-company", List.of("PUNCH_CORRECTION:outside"));
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().hasTargetReportFact()).isTrue();
            assertThat(proven(session, "shared", "PUNCH_CORRECTION:outside")).isEmpty();
        }
    }

    private static Arguments scenario(String description, String... statements) {
        return Arguments.of(description, statements);
    }

    private static Set<String> proven(SqlSession session, String source, String... keys) {
        session.clearCache();
        var mapper = session.getMapper(AttendanceEvidenceMapper.class);
        return MissingOaReportScopeProof.provenUnrelatedKeys("target-company", List.of(keys),
                mapper.findMissingOaKeyScopeRows(source, "target-company", List.of(keys)),
                mapper.findCompanyEmployeeNumbers("target-company"));
    }

    private static void observation(SqlSession session, String id, String source, String key,
                                    String type, String employee) throws Exception {
        insert(session, "INSERT INTO raw_attendance_fact VALUES (?, ?, ?, ?, 'OA_DOCUMENT')",
                "raw-" + id, source, key, "v-" + id);
        insert(session, "INSERT INTO normalized_attendance_record VALUES (?, ?, 1, 'VALID', 'OA_INTERVAL')",
                "norm-" + id, "raw-" + id);
        insert(session, "INSERT INTO oa_attendance_document VALUES (?, ?, ?, ?, ?, ?, 'APPROVED')",
                "oa-" + id, "norm-" + id, source, key, "v-" + id, type);
        insert(session, "INSERT INTO employee_match_decision VALUES (?, ?, 'MATCHED')", "norm-" + id, employee);
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
        configuration.setEnvironment(new Environment("missing-scope", new JdbcTransactionFactory(),
                new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:missing-scope-" + UUID.randomUUID()
                        + ";MODE=MySQL", "sa", "")));
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
                "CREATE TABLE oa_attendance_document (oa_attendance_document_id VARCHAR(36) PRIMARY KEY, normalized_attendance_record_id VARCHAR(36) REFERENCES normalized_attendance_record, attendance_source_id VARCHAR(36) REFERENCES attendance_source, source_business_key VARCHAR(191), source_version VARCHAR(128), document_type VARCHAR(32), source_status VARCHAR(32))",
                "CREATE TABLE employee_match_decision (normalized_attendance_record_id VARCHAR(36) PRIMARY KEY REFERENCES normalized_attendance_record, employee_id VARCHAR(36) REFERENCES employee, match_status VARCHAR(32))",
                "CREATE TABLE attendance_report_oa_fact (attendance_report_oa_fact_id VARCHAR(36) PRIMARY KEY, attendance_report_projection_id VARCHAR(36), company_id VARCHAR(36), oa_attendance_document_id VARCHAR(36) REFERENCES oa_attendance_document, created_at TIMESTAMP(6))",
                "CREATE TABLE evidence_link (evidence_link_id VARCHAR(36) PRIMARY KEY, effective_attendance_event_id VARCHAR(36), raw_attendance_fact_id VARCHAR(36) REFERENCES raw_attendance_fact, normalized_attendance_record_id VARCHAR(36) REFERENCES normalized_attendance_record, employee_match_decision_id VARCHAR(36), link_type VARCHAR(32), created_at TIMESTAMP(6))",
                "INSERT INTO attendance_source VALUES ('shared','outside-company'),('foreign','foreign-company')",
                "INSERT INTO employee VALUES ('target','target-company','TARGET-001'),('outside','outside-company','OUTSIDE-001'),('other-outside','other-company','OUTSIDE-002')",
                "INSERT INTO employee_version VALUES ('target-v1','target','TARGET-001'),('outside-v1','outside','OUTSIDE-001'),('other-v1','other-outside','OUTSIDE-002')");
        return session;
    }
}
