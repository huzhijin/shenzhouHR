package com.szsemicon.hr.reporting.infrastructure.persistence;

import static com.szsemicon.hr.reporting.infrastructure.persistence.OaReportLegacyPendingPublicationTest.row;
import static com.szsemicon.hr.reporting.infrastructure.persistence.OaReportLegacyPendingPublicationTest.seedDocument;
import static com.szsemicon.hr.reporting.infrastructure.persistence.OaReportLegacyPendingPublicationTest.session;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Executes both unchanged production publication statements against real source/employee joins. */
class OaSharedSourcePublicationTest {
    private static final String NAMESPACE = AttendanceReportProjectionWriteMapper.class.getName() + ".";

    @ParameterizedTest
    @ValueSource(strings = {"insertOaDocumentFact", "insertOaDocumentFacts"})
    void publishesFiveSeptember30TimeOffFormsToTheirMatchedEmployeesCompany(String statement) throws Exception {
        try (SqlSession session = session()) {
            execute(session, "UPDATE attendance_source SET company_id='source-owner'");
            for (int index = 1; index <= 5; index++) {
                String id = "time-off-" + index;
                String employee = "employee-" + index;
                seedDocument(session, id, "APPROVED", "source-v1");
                execute(session,
                        "UPDATE raw_attendance_fact SET company_id='source-owner' WHERE raw_attendance_fact_id='raw-" + id + "'",
                        "INSERT INTO employee VALUES ('" + employee + "', 'company')",
                        "INSERT INTO employee_version VALUES ('" + employee + "-version', '" + employee + "')",
                        "INSERT INTO employment_assignment VALUES ('" + employee + "-assignment', '" + employee + "', 'organization')",
                        "UPDATE employee_match_decision SET employee_id='" + employee + "' WHERE normalized_attendance_record_id='normalised-" + id + "'");
                var row = row(id, "APPROVED", "source-v1");
                row.put("employeeId", employee);
                row.put("employeeVersionId", employee + "-version");
                row.put("employmentAssignmentId", employee + "-assignment");
                row.put("leaveTypeCode", "COMPENSATORY");
                row.put("intervalStart", Instant.parse("2026-09-30T00:30:00Z"));
                row.put("intervalEndExclusive", Instant.parse("2026-09-30T09:30:00Z"));
                row.put("recognizedMinutes", 480L);
                assertThat(insert(session, statement, row)).as(id).isEqualTo(1);
            }
            try (var query = session.getConnection().createStatement();
                    var facts = query.executeQuery("""
                            SELECT company_id, leave_type_code, COUNT(*), COUNT(DISTINCT employee_id), SUM(recognized_minutes)
                            FROM attendance_report_oa_fact GROUP BY company_id, leave_type_code
                            """)) {
                assertThat(facts.next()).isTrue();
                assertThat(facts.getString(1)).isEqualTo("company");
                assertThat(facts.getString(2)).isEqualTo("COMPENSATORY");
                assertThat(facts.getInt(3)).isEqualTo(5);
                assertThat(facts.getInt(4)).isEqualTo(5);
                assertThat(facts.getInt(5)).isEqualTo(2400);
                assertThat(facts.next()).isFalse();
            }
            // Publication never relocates or rewrites the immutable source rows.
            try (var query = session.getConnection().createStatement();
                    var raw = query.executeQuery("SELECT COUNT(*) FROM raw_attendance_fact WHERE company_id='source-owner'")) {
                assertThat(raw.next()).isTrue();
                assertThat(raw.getInt(1)).isEqualTo(5);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"insertOaDocumentFact", "insertOaDocumentFacts"})
    void keepsExistingSameCompanyPublicationForNonSharedSources(String statement) throws Exception {
        try (SqlSession session = session()) {
            seedDocument(session, "same-company", "APPROVED", "source-v1");
            execute(session, "UPDATE attendance_source SET source_type='STANDARD_XLSX'");
            assertThat(insert(session, statement, row("same-company", "APPROVED", "source-v1")))
                    .isEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"insertOaDocumentFact", "insertOaDocumentFacts"})
    void doesNotShareNonOaSourcesAcrossCompanies(String statement) throws Exception {
        for (String sourceType : List.of("DELI_CLOUD", "DEVICE_EXCEL", "STANDARD_XLSX")) {
            try (SqlSession session = session()) {
                seedSharedDocument(session);
                execute(session, "UPDATE attendance_source SET source_type='" + sourceType + "'");
                assertThat(insert(session, statement, row("shared", "APPROVED", "source-v1")))
                        .as(sourceType).isZero();
                assertNoFacts(session);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"insertOaDocumentFact", "insertOaDocumentFacts"})
    void sharedSourceCannotBypassSourceIdentityOrTargetEmployeeAuthorization(String statement) throws Exception {
        for (String invalidIdentity : List.of(
                "UPDATE raw_attendance_fact SET attendance_source_id='unrelated-source'",
                "UPDATE oa_attendance_document SET attendance_source_id='unrelated-source'",
                "UPDATE attendance_source SET company_id='unrelated-owner'",
                "DELETE FROM attendance_source",
                "UPDATE employee_match_decision SET match_status='UNMATCHED'",
                "UPDATE employee_match_decision SET employee_id='different-employee'",
                "UPDATE employee SET company_id='outside-company'",
                "UPDATE employee_version SET employee_id='different-employee'",
                "UPDATE employment_assignment SET employee_id='different-employee'",
                "UPDATE organization_identity SET company_id='outside-company'",
                "UPDATE organization_version SET organization_id='different-organization'",
                "UPDATE oa_attendance_document SET source_version='unverified-version'",
                "UPDATE normalized_attendance_record SET validation_status='QUARANTINED'",
                "UPDATE attendance_report_projection SET status='PUBLISHED'")) {
            try (SqlSession session = session()) {
                seedSharedDocument(session);
                execute(session, invalidIdentity);
                assertThat(insert(session, statement, row("shared", "APPROVED", "source-v1")))
                        .as(invalidIdentity).isZero();
                assertNoFacts(session);
            }
        }
    }

    private static void seedSharedDocument(SqlSession session) throws Exception {
        seedDocument(session, "shared", "APPROVED", "source-v1");
        execute(session,
                "UPDATE attendance_source SET company_id='source-owner'",
                "UPDATE raw_attendance_fact SET company_id='source-owner'");
    }

    private static int insert(SqlSession session, String statement, Map<String, Object> row) {
        // H2 cannot infer SELECT ? UNION ALL SELECT ? parameter types. Execute
        // the production batch INSERT unchanged with one item per batch.
        Object parameters = statement.endsWith("Facts") ? Map.of("rows", List.of(row)) : row;
        return session.insert(NAMESPACE + statement, parameters);
    }

    private static void execute(SqlSession session, String... statements) throws Exception {
        try (var statement = session.getConnection().createStatement()) {
            for (String sql : statements) { statement.execute(sql); }
        }
    }

    private static void assertNoFacts(SqlSession session) throws Exception {
        try (var query = session.getConnection().createStatement();
                var rows = query.executeQuery("SELECT COUNT(*) FROM attendance_report_oa_fact")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getInt(1)).isZero();
        }
    }
}
