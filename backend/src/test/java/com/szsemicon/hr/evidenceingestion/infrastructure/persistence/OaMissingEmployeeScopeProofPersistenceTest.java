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

/** Real mapper proof for selected employees; company-wide missing-key rules remain strict. */
class OaMissingEmployeeScopeProofPersistenceTest {
    private static final String COMPANY = "target-company";
    private static final List<String> TARGETS = List.of("target");
    private static final String KEY = "OVERTIME:peer";

    @ParameterizedTest
    @ValueSource(strings = {"OVERTIME", "PUNCH_CORRECTION", "LEAVE"})
    void sameCompanyPeerHistoryIsUnrelatedToSelectedEmployeeButNotToWholeCompany(String type) throws Exception {
        try (SqlSession session = session()) {
            String key = type + ":peer";
            observation(session, "old", "shared", key, type, "peer");
            observation(session, "new", "shared", key, type, "peer");
            execute(session, "UPDATE oa_attendance_document SET source_status='REVOKED' WHERE oa_attendance_document_id='oa-new'");
            var mapper = session.getMapper(AttendanceEvidenceMapper.class);
            var rows = mapper.findMissingOaKeyEmployeeScopeRows("shared", COMPANY, TARGETS, List.of(key));
            assertThat(rows).hasSize(2).allSatisfy(row -> {
                assertThat(row.employeeCompanyId()).isEqualTo(COMPANY);
                assertThat(row.employeeId()).isEqualTo("peer");
                assertThat(row.documentIdentityConsistent()).isTrue();
                assertThat(row.hasTargetReportFact()).isFalse();
            });
            assertThat(proven(session, "shared", TARGETS, key)).containsExactly(key);
            assertThat(MissingOaReportScopeProof.provenUnrelatedKeys(COMPANY, List.of(key),
                    mapper.findMissingOaKeyScopeRows("shared", COMPANY, List.of(key)),
                    mapper.findCompanyEmployeeNumbers(COMPANY))).isEmpty();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unsafeHistories")
    void incompleteIdentityAliasesOrContradictoryHistoryCannotBeExcluded(String description, String[] statements)
            throws Exception {
        try (SqlSession session = session()) {
            observation(session, "first", "shared", KEY, "OVERTIME", "peer");
            execute(session, statements);
            assertThat(proven(session, "shared", TARGETS, KEY)).as(description).isEmpty();
        }
    }

    @ParameterizedTest(name = "leave: {0}")
    @MethodSource("unsafeHistories")
    void missingLeaveRequiresTheSameCompleteIdentityAndDependencyProof(String description, String[] statements)
            throws Exception {
        try (SqlSession session = session()) {
            String key = "LEAVE:peer";
            observation(session, "first", "shared", key, "LEAVE", "peer");
            String[] leaveStatements = java.util.Arrays.stream(statements)
                    .map(sql -> sql.replace("OVERTIME:peer", key)
                            .replace("document_type='LEAVE'", "document_type='OVERTIME'"))
                    .toArray(String[]::new);
            execute(session, leaveStatements);
            assertThat(proven(session, "shared", TARGETS, key)).as(description).isEmpty();
        }
    }

    private static Stream<Arguments> unsafeHistories() {
        return Stream.of(
                scenario("newer raw without normalization or document",
                        "INSERT INTO raw_attendance_fact VALUES ('raw-new','shared','OVERTIME:peer','v-new','OA_DOCUMENT')"),
                scenario("old unresolved normalization cannot be hidden by latest revision",
                        "INSERT INTO normalized_attendance_record VALUES ('norm-old','raw-first',0,'VALID','OA_INTERVAL')"),
                scenario("invalid normalization", "UPDATE normalized_attendance_record SET validation_status='QUARANTINED'"),
                scenario("wrong normalized record kind", "UPDATE normalized_attendance_record SET record_kind='PUNCH_POINT'"),
                scenario("wrong raw record kind", "UPDATE raw_attendance_fact SET fact_kind='PUNCH_POINT'"),
                scenario("missing document", "DELETE FROM oa_attendance_document"),
                scenario("document source differs from raw", "UPDATE oa_attendance_document SET attendance_source_id='foreign'"),
                scenario("document key differs from raw", "UPDATE oa_attendance_document SET source_business_key='OVERTIME:other'"),
                scenario("document version differs from raw", "UPDATE oa_attendance_document SET source_version='other'"),
                scenario("document family disagrees with key", "UPDATE oa_attendance_document SET document_type='LEAVE'"),
                scenario("unmatched employee", "UPDATE employee_match_decision SET match_status='UNMATCHED'"),
                scenario("null employee", "UPDATE employee_match_decision SET employee_id=NULL"),
                scenario("selected target employee", "UPDATE employee_match_decision SET employee_id='target'"),
                scenario("peer current number overlaps selected target",
                        "UPDATE employee SET employee_number='TARGET-001' WHERE employee_id='peer'"),
                scenario("peer historical number overlaps selected target",
                        "INSERT INTO employee_version VALUES ('peer-prior','peer','TARGET-001')"),
                scenario("selected target historical number overlaps peer",
                        "INSERT INTO employee_version VALUES ('target-prior','target','PEER-001')"),
                scenario("peer alias reaches selected target number",
                        "UPDATE employee SET employee_number='SZT0687' WHERE employee_id='peer'",
                        "UPDATE employee SET employee_number='SZST0687' WHERE employee_id='target'"),
                scenario("selected target alias reaches peer number",
                        "UPDATE employee SET employee_number='SZT0709' WHERE employee_id='target'",
                        "UPDATE employee SET employee_number='SZST0709' WHERE employee_id='peer'"),
                scenario("missing peer history", "DELETE FROM employee_version WHERE employee_id='peer'"),
                scenario("blank peer current number", "UPDATE employee SET employee_number=' ' WHERE employee_id='peer'"),
                scenario("blank peer historical number", "UPDATE employee_version SET employee_number=' ' WHERE employee_id='peer'"),
                scenario("blank selected target historical number",
                        "INSERT INTO employee_version VALUES ('target-blank','target',' ')")
        );
    }

    @Test
    void employeeChangingAcrossVersionsIsNotProvenEvenWhenNeitherEmployeeIsSelected() throws Exception {
        try (SqlSession session = session()) {
            observation(session, "old", "shared", KEY, "OVERTIME", "peer");
            observation(session, "new", "shared", KEY, "OVERTIME", "second-peer");
            assertThat(proven(session, "shared", TARGETS, KEY)).isEmpty();
        }
    }

    @Test
    void oldSelectedEmployeeMatchStillBlocksAfterLatestVersionMovesToPeer() throws Exception {
        try (SqlSession session = session()) {
            observation(session, "old", "shared", KEY, "OVERTIME", "target");
            observation(session, "new", "shared", KEY, "OVERTIME", "peer");
            assertThat(proven(session, "shared", TARGETS, KEY)).isEmpty();
        }
    }

    @Test
    void sameCompanyPeerOldReportFactDoesNotBlockSelectedTargetButTargetAnyOldProjectionDoes() throws Exception {
        try (SqlSession session = session()) {
            observation(session, "old", "shared", KEY, "OVERTIME", "peer");
            observation(session, "new", "shared", KEY, "OVERTIME", "peer");
            execute(session,
                    "UPDATE oa_attendance_document SET source_status='REVOKED' WHERE oa_attendance_document_id='oa-old'",
                    "INSERT INTO attendance_report_oa_fact VALUES ('peer-fact','obsolete-projection','target-company','oa-old','peer','2025-01-01 00:00:00')");
            assertThat(proven(session, "shared", TARGETS, KEY)).containsExactly(KEY);
            var mapper = session.getMapper(AttendanceEvidenceMapper.class);
            assertThat(mapper.findMissingOaKeyScopeRows("shared", COMPANY, List.of(KEY)))
                    .allSatisfy(row -> assertThat(row.hasTargetReportFact()).isTrue());
            execute(session,
                    "INSERT INTO attendance_report_oa_fact VALUES ('target-fact','another-old-projection','target-company','oa-old','target','2024-01-01 00:00:00')");
            assertThat(mapper.findMissingOaKeyEmployeeScopeRows("shared", COMPANY, TARGETS, List.of(KEY)))
                    .hasSize(2).allSatisfy(row -> assertThat(row.hasTargetReportFact()).isTrue());
            assertThat(proven(session, "shared", TARGETS, KEY)).isEmpty();
        }
    }

    @Test
    void anyEvidenceLinkBlocksEvenWhenTheLinkIsHistoricalAndNotAttributedToSelectedEmployee() throws Exception {
        try (SqlSession session = session()) {
            String key = "PUNCH_CORRECTION:peer";
            observation(session, "old", "shared", key, "PUNCH_CORRECTION", "peer");
            observation(session, "new", "shared", key, "PUNCH_CORRECTION", "peer");
            execute(session, "INSERT INTO evidence_link VALUES ('old-link','outside-effective-event','raw-old','norm-old','outside-old-match','PRIMARY','2025-01-01 00:00:00')");
            assertThat(session.getMapper(AttendanceEvidenceMapper.class)
                    .findMissingOaKeyEmployeeScopeRows("shared", COMPANY, TARGETS, List.of(key)))
                    .anySatisfy(row -> assertThat(row.hasTargetReportFact()).isTrue());
            assertThat(proven(session, "shared", TARGETS, key)).isEmpty();
        }
    }

    @Test
    void sourceIdentityAndEachRequestedKeyHaveIndependentProofs() throws Exception {
        try (SqlSession session = session()) {
            observation(session, "shared", "shared", KEY, "OVERTIME", "peer");
            observation(session, "foreign", "foreign", KEY, "OVERTIME", "target");
            execute(session, "INSERT INTO attendance_report_oa_fact VALUES ('foreign-fact','old-pin','target-company','oa-foreign','target','2025-01-01 00:00:00')");
            assertThat(proven(session, "shared", TARGETS, KEY, "PUNCH_CORRECTION:no-history")).containsExactly(KEY);
            assertThat(proven(session, "foreign", TARGETS, KEY)).isEmpty();
            assertThat(proven(session, "absent-source", TARGETS, KEY)).isEmpty();
        }
    }

    @Test
    void numberCatalogContainsOnlySelectedEmployeesCurrentAndAllHistoricalNumbers() throws Exception {
        try (SqlSession session = session()) {
            execute(session,
                    "INSERT INTO employee_version VALUES ('target-prior','target','TARGET-OLD')",
                    "INSERT INTO employee_version VALUES ('peer-prior','peer','PEER-OLD')",
                    "INSERT INTO employee_version VALUES ('second-prior','second-peer','SECOND-OLD')");
            var mapper = session.getMapper(AttendanceEvidenceMapper.class);
            assertThat(mapper.findReportEmployeeNumbers(COMPANY, TARGETS))
                    .containsExactlyInAnyOrder("TARGET-001", "TARGET-OLD");
            assertThat(mapper.findReportEmployeeNumbers(COMPANY, List.of("target", "second-peer")))
                    .containsExactlyInAnyOrder("TARGET-001", "TARGET-OLD", "PEER-002", "SECOND-OLD");
        }
    }

    @Test
    void everyRequestedEmployeeMustExistAndBelongToTheCompanyBeforeAnyNumbersAreReturned() throws Exception {
        try (SqlSession session = session()) {
            var mapper = session.getMapper(AttendanceEvidenceMapper.class);
            assertThat(mapper.findReportEmployeeNumbers(COMPANY, List.of("target", "missing"))).isEmpty();
            assertThat(mapper.findReportEmployeeNumbers(COMPANY, List.of("target", "outside"))).isEmpty();
            assertThat(mapper.findReportEmployeeNumbers("outside-company", TARGETS)).isEmpty();
            assertThat(mapper.findReportEmployeeNumbers(COMPANY, List.of())).isEmpty();
            observation(session, "first", "shared", KEY, "OVERTIME", "peer");
            assertThat(proven(session, "shared", List.of("target", "missing"), KEY)).isEmpty();
            assertThat(proven(session, "shared", List.of("target", "outside"), KEY)).isEmpty();
        }
    }

    @Test
    void emptyTargetSetCannotUseOtherwiseCompleteHistoryToExcludeMissingKey() throws Exception {
        try (SqlSession session = session()) {
            observation(session, "first", "shared", KEY, "OVERTIME", "peer");
            var mapper = session.getMapper(AttendanceEvidenceMapper.class);
            var rows = mapper.findMissingOaKeyEmployeeScopeRows("shared", COMPANY, TARGETS, List.of(KEY));
            assertThat(MissingOaReportScopeProof.provenUnrelatedEmployeeKeys(COMPANY, List.of(), List.of(KEY),
                    rows, mapper.findReportEmployeeNumbers(COMPANY, TARGETS))).isEmpty();
        }
    }

    @Test
    void targetHistoricalBlankNumberInvalidatesWholeCatalogAndRejectsProof() throws Exception {
        try (SqlSession session = session()) {
            observation(session, "first", "shared", KEY, "OVERTIME", "peer");
            execute(session, "INSERT INTO employee_version VALUES ('target-blank','target',' ')");
            assertThat(session.getMapper(AttendanceEvidenceMapper.class).findReportEmployeeNumbers(COMPANY, TARGETS))
                    .isEmpty();
            assertThat(proven(session, "shared", TARGETS, KEY)).isEmpty();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("incompleteTargetCatalogs")
    void anySelectedEmployeeWithMissingOrBlankIdentityInvalidatesTheWholeRequestedScope(
            String description, String[] statements) throws Exception {
        try (SqlSession session = session()) {
            observation(session, "first", "shared", KEY, "OVERTIME", "peer");
            execute(session, statements);
            List<String> selected = List.of("target", "second-peer");
            assertThat(session.getMapper(AttendanceEvidenceMapper.class).findReportEmployeeNumbers(COMPANY, selected))
                    .as(description).isEmpty();
            assertThat(proven(session, "shared", selected, KEY)).as(description).isEmpty();
        }
    }

    private static Stream<Arguments> incompleteTargetCatalogs() {
        return Stream.of(
                scenario("selected employee has no history",
                        "DELETE FROM employee_version WHERE employee_id='target'"),
                scenario("selected employee has null historical number",
                        "UPDATE employee_version SET employee_number=NULL WHERE employee_id='target'"),
                scenario("selected employee has blank historical number",
                        "UPDATE employee_version SET employee_number=' ' WHERE employee_id='target'"),
                scenario("selected employee has null current number",
                        "UPDATE employee SET employee_number=NULL WHERE employee_id='target'"),
                scenario("selected employee has blank current number",
                        "UPDATE employee SET employee_number=' ' WHERE employee_id='target'"),
                scenario("second selected employee has no history despite valid first employee",
                        "DELETE FROM employee_version WHERE employee_id='second-peer'"),
                scenario("second selected employee has an additional null historical number",
                        "INSERT INTO employee_version VALUES ('second-null','second-peer',NULL)")
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"LEAVE_REVOCATION", "OUTING", "EXEMPT_PUNCH", "UNKNOWN"})
    void otherDocumentFamiliesRemainStrictForEmployeeSelection(String type) throws Exception {
        try (SqlSession session = session()) {
            String key = type + ":peer";
            observation(session, "first", "shared", key, type, "peer");
            assertThat(proven(session, "shared", TARGETS, key)).isEmpty();
        }
    }

    private static Arguments scenario(String description, String... statements) {
        return Arguments.of(description, statements);
    }

    private static Set<String> proven(SqlSession session, String source, List<String> employees, String... keys) {
        session.clearCache();
        var mapper = session.getMapper(AttendanceEvidenceMapper.class);
        return MissingOaReportScopeProof.provenUnrelatedEmployeeKeys(COMPANY, employees, List.of(keys),
                mapper.findMissingOaKeyEmployeeScopeRows(source, COMPANY, employees, List.of(keys)),
                mapper.findReportEmployeeNumbers(COMPANY, employees));
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
        session.clearCache();
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
        configuration.setEnvironment(new Environment("missing-employee-scope", new JdbcTransactionFactory(),
                new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:missing-employee-" + UUID.randomUUID()
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
                "CREATE TABLE attendance_report_oa_fact (attendance_report_oa_fact_id VARCHAR(36) PRIMARY KEY, attendance_report_projection_id VARCHAR(36), company_id VARCHAR(36), oa_attendance_document_id VARCHAR(36) REFERENCES oa_attendance_document, employee_id VARCHAR(36) REFERENCES employee, created_at TIMESTAMP(6))",
                "CREATE TABLE evidence_link (evidence_link_id VARCHAR(36) PRIMARY KEY, effective_attendance_event_id VARCHAR(36), raw_attendance_fact_id VARCHAR(36) REFERENCES raw_attendance_fact, normalized_attendance_record_id VARCHAR(36) REFERENCES normalized_attendance_record, employee_match_decision_id VARCHAR(36), link_type VARCHAR(32), created_at TIMESTAMP(6))",
                "INSERT INTO attendance_source VALUES ('shared','outside-company'),('foreign','foreign-company')",
                "INSERT INTO employee VALUES ('target','target-company','TARGET-001'),('peer','target-company','PEER-001'),('second-peer','target-company','PEER-002'),('outside','outside-company','OUTSIDE-001')",
                "INSERT INTO employee_version VALUES ('target-v1','target','TARGET-001'),('peer-v1','peer','PEER-001'),('second-v1','second-peer','PEER-002'),('outside-v1','outside','OUTSIDE-001')");
        return session;
    }
}
