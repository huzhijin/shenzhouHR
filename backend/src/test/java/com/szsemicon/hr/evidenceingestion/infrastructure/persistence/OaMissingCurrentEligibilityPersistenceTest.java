package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.evidenceingestion.application.MissingOaReportScopeProof;
import java.time.Instant;
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

/** Exact production SQL plus complete-history negative proof, without mutating source status. */
class OaMissingCurrentEligibilityPersistenceTest {
    private static final String COMPANY = "target-company";
    private static final List<String> TARGETS = List.of("target");
    private static final String KEY = "OVERTIME:old";
    private static final Instant AS_OF = Instant.parse("2026-10-06T00:00:00Z");

    @Test
    void oldTargetReportCanBeReplacedWhenItsCurrentOvertimeContextCannotContribute() throws Exception {
        try (SqlSession session = session()) {
            observation(session, "old", "shared", KEY, "OVERTIME", "target");
            execute(session, "UPDATE oa_attendance_document SET source_status='UNKNOWN'",
                    "INSERT INTO attendance_report_oa_fact VALUES ('old-fact','old-pin','target-company','oa-old','target','2026-10-01 01:00:00')");
            assertThat(proven(session, KEY)).containsExactly(KEY);
            var mapper = session.getMapper(AttendanceEvidenceMapper.class);
            assertThat(mapper.findMissingOaKeyEmployeeScopeRows("shared", COMPANY, TARGETS, List.of(KEY)))
                    .allSatisfy(row -> assertThat(row.hasTargetReportFact()).isTrue());
            try (var statement = session.getConnection().createStatement();
                    var result = statement.executeQuery("SELECT source_status FROM oa_attendance_document")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("UNKNOWN");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAID", "COMPENSATORY", "VOLUNTARY", "UNKNOWN_TYPE", ""})
    void anyNonNullTypeWithActivatedContextStillRequiresCurrentSource(String type) throws Exception {
        try (SqlSession session = session()) {
            observation(session, "old", "shared", KEY, "OVERTIME", "target");
            insert(session, "INSERT INTO oa_attendance_document_context VALUES ('oa-old','ACTIVATED',?)", type);
            assertThat(proven(session, KEY)).isEmpty();
        }
    }

    @Test
    void nullTypeOrInactiveContextMatchesExistingReaderNonEligibility() throws Exception {
        try (SqlSession session = session()) {
            observation(session, "old", "shared", KEY, "OVERTIME", "target");
            execute(session, "INSERT INTO oa_attendance_document_context VALUES ('oa-old','ACTIVATED',NULL)");
            assertThat(proven(session, KEY)).containsExactly(KEY);
            execute(session, "UPDATE oa_attendance_document_context SET activation_decision='BLOCKED',overtime_type='PAID'");
            assertThat(proven(session, KEY)).containsExactly(KEY);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"knowledge", "created", "id"})
    void latestPrecedenceIsAppliedBeforeContextEligibility(String precedence) throws Exception {
        try (SqlSession session = session()) {
            observation(session, "a", "shared", KEY, "OVERTIME", "target");
            observation(session, "z", "shared", KEY, "OVERTIME", "target");
            String winner = "z";
            if (precedence.equals("knowledge")) {
                execute(session, "UPDATE oa_attendance_document SET knowledge_rank=2,created_at='2026-09-30 00:00:00' WHERE oa_attendance_document_id='oa-a'");
                winner = "a";
            } else if (precedence.equals("created")) {
                execute(session, "UPDATE oa_attendance_document SET created_at='2026-10-02 00:00:00' WHERE oa_attendance_document_id='oa-a'");
                winner = "a";
            }
            insert(session, "INSERT INTO oa_attendance_document_context VALUES (?,'ACTIVATED','PAID')", "oa-" + winner);
            assertThat(proven(session, KEY)).isEmpty();
            execute(session, "DELETE FROM oa_attendance_document_context");
            insert(session, "INSERT INTO oa_attendance_document_context VALUES (?,'ACTIVATED','PAID')", "oa-" + (winner.equals("a") ? "z" : "a"));
            assertThat(proven(session, KEY)).containsExactly(KEY);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unsafeHistory")
    void incompleteOrAmbiguousHistoryCannotBeProved(String description, String sql) throws Exception {
        try (SqlSession session = session()) {
            observation(session, "old", "shared", KEY, "OVERTIME", "target");
            execute(session, sql);
            assertThat(proven(session, KEY)).as(description).isEmpty();
        }
    }

    private static Stream<Arguments> unsafeHistory() {
        return Stream.of(
                Arguments.of("unresolved raw revision", "INSERT INTO raw_attendance_fact VALUES ('raw-opaque','shared','OVERTIME:old','opaque','OA_DOCUMENT')"),
                Arguments.of("unresolved normalization", "INSERT INTO normalized_attendance_record VALUES ('norm-opaque','raw-old',2,'VALID','OA_INTERVAL')"),
                Arguments.of("invalid", "UPDATE normalized_attendance_record SET validation_status='INVALID'"),
                Arguments.of("kind", "UPDATE normalized_attendance_record SET record_kind='PUNCH_POINT'"),
                Arguments.of("raw kind", "UPDATE raw_attendance_fact SET fact_kind='PUNCH_POINT'"),
                Arguments.of("source mismatch", "UPDATE oa_attendance_document SET attendance_source_id='foreign'"),
                Arguments.of("key mismatch", "UPDATE oa_attendance_document SET source_business_key='OVERTIME:other'"),
                Arguments.of("version mismatch", "UPDATE oa_attendance_document SET source_version='other'"),
                Arguments.of("type mismatch", "UPDATE oa_attendance_document SET document_type='PUNCH_CORRECTION'"),
                Arguments.of("unmatched", "UPDATE employee_match_decision SET match_status='UNMATCHED'"),
                Arguments.of("nonselected identity", "UPDATE employee_match_decision SET employee_id='peer'"),
                Arguments.of("wrong company", "UPDATE employee SET company_id='outside-company' WHERE employee_id='target'"),
                Arguments.of("missing employee history", "DELETE FROM employee_version WHERE employee_id='target'"),
                Arguments.of("blank historical number", "UPDATE employee_version SET employee_number=' ' WHERE employee_id='target'"),
                Arguments.of("blank current number", "UPDATE employee SET employee_number=NULL WHERE employee_id='target'"),
                Arguments.of("independently consumable evidence", "INSERT INTO evidence_link VALUES ('link','event','raw-old','norm-old','match','PRIMARY','2026-10-01 00:00:00')"),
                Arguments.of("report belongs to another employee", "INSERT INTO attendance_report_oa_fact VALUES ('fact','pin','target-company','oa-old','peer','2026-10-01 00:00:00')"),
                Arguments.of("report belongs to another company", "INSERT INTO attendance_report_oa_fact VALUES ('fact','pin','outside-company','oa-old','target','2026-10-01 00:00:00')"),
                Arguments.of("latest after proof clock", "UPDATE oa_attendance_document SET created_at='2026-10-07 00:00:00'"));
    }

    @Test
    void allHistoryMustResolveToOneSelectedEmployeeEvenWhenBothAreInScope() throws Exception {
        try (SqlSession session = session()) {
            observation(session, "old", "shared", KEY, "OVERTIME", "target");
            observation(session, "new", "shared", KEY, "OVERTIME", "peer");
            assertThat(proven(session, List.of("target", "peer"), KEY, AS_OF)).isEmpty();
        }
    }

    @Test
    void futureVersionCannotBeHiddenByTheCutoffEvenIfPastVersionWasInactive() throws Exception {
        try (SqlSession session = session()) {
            observation(session, "old", "shared", KEY, "OVERTIME", "target");
            observation(session, "new", "shared", KEY, "OVERTIME", "target");
            execute(session, "UPDATE oa_attendance_document SET created_at='2026-10-07 00:00:00' WHERE oa_attendance_document_id='oa-new'");
            assertThat(proven(session, KEY)).isEmpty();
        }
    }

    @Test
    void punchRequiresExactAuditedExclusionAtKnowledgeCutoffAndPreservesRaw() throws Exception {
        try (SqlSession session = session()) {
            String key = "PUNCH_CORRECTION:old";
            observation(session, "old", "shared", key, "PUNCH_CORRECTION", "target");
            assertThat(proven(session, key)).isEmpty();
            exclusion(session, key);
            assertThat(proven(session, key)).containsExactly(key);
            assertThat(proven(session, TARGETS, key, AS_OF.minusNanos(1000))).isEmpty();
            execute(session, "UPDATE attendance_report_oa_evidence_exclusion SET reversed_at='2026-10-06 00:00:00.000001'");
            assertThat(proven(session, key)).containsExactly(key);
            assertThat(proven(session, TARGETS, key, AS_OF.plusNanos(1000))).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"company_id", "employee_id", "attendance_source_id", "source_business_key", "source_version", "oa_attendance_document_id"})
    void exclusionCannotTransferAcrossAnyIdentityCoordinate(String column) throws Exception {
        try (SqlSession session = session()) {
            String key = "PUNCH_CORRECTION:old";
            observation(session, "old", "shared", key, "PUNCH_CORRECTION", "target");
            exclusion(session, key);
            execute(session, "UPDATE attendance_report_oa_evidence_exclusion SET " + column + "='different'");
            assertThat(proven(session, key)).isEmpty();
        }
    }

    @Test
    void exclusionOfOldVersionCannotResurrectItAfterNewVersionBecomesLatest() throws Exception {
        try (SqlSession session = session()) {
            String key = "PUNCH_CORRECTION:old";
            observation(session, "old", "shared", key, "PUNCH_CORRECTION", "target");
            exclusion(session, key);
            observation(session, "new", "shared", key, "PUNCH_CORRECTION", "target");
            execute(session, "UPDATE oa_attendance_document SET knowledge_rank=2 WHERE oa_attendance_document_id='oa-new'");
            assertThat(proven(session, key)).isEmpty();
        }
    }

    @Test
    void emptyScopeMissingEvidenceAndUnsupportedFamilyRemainStrict() throws Exception {
        try (SqlSession session = session()) {
            observation(session, "old", "shared", KEY, "OVERTIME", "target");
            var mapper = session.getMapper(AttendanceEvidenceMapper.class);
            assertThat(mapper.findMissingOaCurrentEligibilityRows("shared", COMPANY, List.of(), List.of(KEY), AS_OF)).isEmpty();
            assertThat(mapper.findMissingOaCurrentEligibilityRows("shared", COMPANY, TARGETS, List.of(), AS_OF)).isEmpty();
            assertThat(proven(session, List.of(), KEY, AS_OF)).isEmpty();
            assertThat(proven(session, "OVERTIME:absent")).isEmpty();
            observation(session, "leave", "shared", "LEAVE:old", "LEAVE", "target");
            assertThat(proven(session, "LEAVE:old")).isEmpty();
            assertThat(mapper.findMissingOaCurrentEligibilityRows("foreign", COMPANY, TARGETS, List.of(KEY), AS_OF)).isEmpty();
        }
    }

    @ParameterizedTest(name = "company source {0}, context {1}/{2} => missing proven {3}")
    @MethodSource("companyEligibilityCases")
    void companyProofUsesActualStatusAndContextWithoutInferringCancellation(
            String status, String activation, String type, boolean expected) throws Exception {
        try (SqlSession session = session()) {
            observation(session, "old", "shared", KEY, "OVERTIME", "target");
            insert(session, "UPDATE oa_attendance_document SET source_status=?", status);
            insert(session, "INSERT INTO oa_attendance_document_context VALUES ('oa-old',?,?)", activation, type);
            // An old zero or positive report row is not itself proof of current eligibility.
            execute(session, "INSERT INTO attendance_report_oa_fact VALUES ('old-fact','old-pin','target-company','oa-old','target','2026-10-01 01:00:00')");
            assertThat(provenCompany(session, KEY).contains(KEY)).isEqualTo(expected);
            try (var st = session.getConnection().createStatement();
                    var rs = st.executeQuery("SELECT source_status FROM oa_attendance_document")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo(status);
            }
        }
    }

    private static Stream<Arguments> companyEligibilityCases() {
        return Stream.of(
                Arguments.of("REVOKED", "ACTIVATED", "PAID", true),
                Arguments.of("REJECTED", "ACTIVATED", "COMPENSATORY", true),
                Arguments.of("DRAFT", "ACTIVATED", "VOLUNTARY", true),
                Arguments.of("APPROVED", "ACTIVATED", "PAID", false),
                Arguments.of("PENDING", "ACTIVATED", "PAID", false),
                Arguments.of("MODIFIED", "ACTIVATED", "PAID", false),
                Arguments.of("SUPPLEMENTED", "ACTIVATED", "PAID", false),
                Arguments.of("APPROVED", "BLOCKED", "PAID", true),
                Arguments.of("PENDING", "ACTIVATED", null, true),
                Arguments.of("UNKNOWN", null, null, false),
                Arguments.of("UNRECOGNIZED", null, null, false),
                Arguments.of(null, null, null, false));
    }

    @Test
    void companyStrictLegacyPendingRequiresExactStateZeroVersionAndKeepsActivatedFormsStrict() throws Exception {
        try (SqlSession session = session()) {
            observation(session, "old", "shared", KEY, "OVERTIME", "target");
            String version = "2026-09-12T08:30:00:state=0:Asia/Shanghai";
            insert(session, "UPDATE raw_attendance_fact SET source_version=?", version);
            insert(session, "UPDATE oa_attendance_document SET source_version=?,source_status='UNKNOWN'", version);
            assertThat(provenCompany(session, KEY)).containsExactly(KEY);
            execute(session, "INSERT INTO oa_attendance_document_context VALUES ('oa-old','ACTIVATED','PAID')");
            assertThat(provenCompany(session, KEY)).isEmpty();
            execute(session, "DELETE FROM oa_attendance_document_context");
            insert(session, "UPDATE raw_attendance_fact SET source_version=?", version + ":unverified");
            insert(session, "UPDATE oa_attendance_document SET source_version=?", version + ":unverified");
            assertThat(provenCompany(session, KEY)).isEmpty();
        }
    }

    @ParameterizedTest(name = "company unsafe {0}")
    @MethodSource("unsafeCompanyHistory")
    void companyProofRetainsCompleteHistoryAndEveryIdentityGuard(String label, String mutation) throws Exception {
        try (SqlSession session = session()) {
            observation(session, "old", "shared", KEY, "OVERTIME", "target");
            execute(session, "UPDATE oa_attendance_document SET source_status='REVOKED'", mutation);
            assertThat(provenCompany(session, KEY)).as(label).isEmpty();
        }
    }

    private static Stream<Arguments> unsafeCompanyHistory() {
        return unsafeHistory().filter(a -> !a.get()[0].equals("nonselected identity"));
    }

    @Test
    void companyDoesNotResurrectOldApprovedOrIgnoreNewEligibleVersionAtSameBusinessKey() throws Exception {
        try (SqlSession session = session()) {
            observation(session, "old", "shared", KEY, "OVERTIME", "target");
            execute(session, "INSERT INTO oa_attendance_document_context VALUES ('oa-old','ACTIVATED','PAID')");
            observation(session, "revoked", "shared", KEY, "OVERTIME", "target");
            execute(session, "UPDATE oa_attendance_document SET source_status='REVOKED',knowledge_rank=2 WHERE oa_attendance_document_id='oa-revoked'");
            assertThat(provenCompany(session, KEY)).containsExactly(KEY);
            observation(session, "new", "shared", KEY, "OVERTIME", "target");
            execute(session, "UPDATE oa_attendance_document SET knowledge_rank=3 WHERE oa_attendance_document_id='oa-new'",
                    "INSERT INTO oa_attendance_document_context VALUES ('oa-new','ACTIVATED','PAID')");
            assertThat(provenCompany(session, KEY)).isEmpty();
        }
    }

    @Test
    void sameCompanyOtherEmployeeIsNotAnUnrelatedScopeAndMustIndependentlyFailReaderGate() throws Exception {
        try (SqlSession session = session()) {
            observation(session, "old", "shared", KEY, "OVERTIME", "peer");
            execute(session, "INSERT INTO oa_attendance_document_context VALUES ('oa-old','ACTIVATED','PAID')");
            assertThat(provenCompany(session, KEY)).isEmpty();
            execute(session, "UPDATE oa_attendance_document SET source_status='REVOKED'");
            assertThat(provenCompany(session, KEY)).containsExactly(KEY);
            observation(session, "new", "shared", KEY, "OVERTIME", "target");
            execute(session, "UPDATE oa_attendance_document SET source_status='REVOKED'");
            assertThat(provenCompany(session, KEY)).isEmpty(); // ownership moved, even inside one company
        }
    }

    @Test
    void companyPunchStillNeedsExactKnowledgeBoundExclusionAndNewSourceVersionDoesNotInheritIt() throws Exception {
        try (SqlSession session = session()) {
            String key = "PUNCH_CORRECTION:old";
            observation(session, "old", "shared", key, "PUNCH_CORRECTION", "target");
            execute(session, "UPDATE oa_attendance_document SET source_status='REVOKED'");
            assertThat(provenCompany(session, key)).isEmpty(); // status alone never bypasses PC audit
            exclusion(session, key);
            assertThat(provenCompany(session, key)).containsExactly(key);
            execute(session, "UPDATE attendance_report_oa_evidence_exclusion SET knowledge_at='2026-10-07 00:00:00'");
            assertThat(provenCompany(session, key)).isEmpty();
            execute(session, "UPDATE attendance_report_oa_evidence_exclusion SET knowledge_at='2026-10-06 00:00:00',reversed_at='2026-10-06 00:00:00'");
            assertThat(provenCompany(session, key)).isEmpty();
            execute(session, "UPDATE attendance_report_oa_evidence_exclusion SET reversed_at=NULL");
            observation(session, "new", "shared", key, "PUNCH_CORRECTION", "target");
            execute(session, "UPDATE oa_attendance_document SET knowledge_rank=2 WHERE oa_attendance_document_id='oa-new'");
            assertThat(provenCompany(session, key)).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"company_id", "employee_id", "attendance_source_id", "source_business_key", "source_version", "oa_attendance_document_id"})
    void companyExclusionCannotTransferAcrossAnyCoordinate(String column) throws Exception {
        try (SqlSession session = session()) {
            String key = "PUNCH_CORRECTION:old";
            observation(session, "old", "shared", key, "PUNCH_CORRECTION", "target");
            exclusion(session, key);
            execute(session, "UPDATE attendance_report_oa_evidence_exclusion SET " + column + "='different'");
            assertThat(provenCompany(session, key)).isEmpty();
        }
    }

    @Test
    void companyProofDoesNotAdmitDraftLeaveEvenWithNoPublishedMinutesAndEmptyEmployeeApiStaysEmpty() throws Exception {
        try (SqlSession session = session()) {
            observation(session, "old", "shared", "LEAVE:old", "LEAVE", "target");
            execute(session, "UPDATE oa_attendance_document SET source_status='DRAFT'");
            assertThat(provenCompany(session, "LEAVE:old")).isEmpty();
            var mapper = session.getMapper(AttendanceEvidenceMapper.class);
            assertThat(mapper.findMissingOaCompanyCurrentEligibilityRows("shared", COMPANY, List.of(), AS_OF)).isEmpty();
            assertThat(mapper.findMissingOaCurrentEligibilityRows("shared", COMPANY, List.of(), List.of(KEY), AS_OF)).isEmpty();
        }
    }

    private static Set<String> provenCompany(SqlSession session, String key) {
        var mapper = session.getMapper(AttendanceEvidenceMapper.class);
        return MissingOaReportScopeProof.provenNoncontributingCompanyKeys(COMPANY, List.of(key),
                mapper.findMissingOaKeyScopeRows("shared", COMPANY, List.of(key)),
                mapper.findCompanyEmployeeNumbers(COMPANY),
                mapper.findMissingOaCompanyCurrentEligibilityRows("shared", COMPANY, List.of(key), AS_OF), AS_OF);
    }

    @Test
    void neverEffectiveDraftIsHarmlessInOwningAndOtherCompanyButCurrentApprovedReplacementStaysStrict() throws Exception {
        try (SqlSession session = session()) {
            String key = "LEAVE:-2044581916237414171";
            observation(session, "old", "shared", key, "LEAVE", "target");
            execute(session, "UPDATE oa_attendance_document SET source_status='DRAFT'");
            assertThat(provenDraft(session, COMPANY, key)).containsExactly(key);
            assertThat(provenDraft(session, "another-company", key)).containsExactly(key);
            observation(session, "replacement", "shared", "LEAVE:real-approved", "LEAVE", "target");
            assertThat(provenDraft(session, COMPANY, key)).containsExactly(key);
            assertThat(provenDraft(session, COMPANY, "LEAVE:real-approved")).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPROVED", "PENDING", "UNKNOWN", "REVOKED", "REJECTED"})
    void aDraftWithAnyOtherHistoricalStatusIsNotNeverEffective(String historicalStatus) throws Exception {
        try (SqlSession session = session()) {
            String key = "LEAVE:draft";
            observation(session, "old", "shared", key, "LEAVE", "target");
            insert(session, "UPDATE oa_attendance_document SET source_status=?", historicalStatus);
            observation(session, "current", "shared", key, "LEAVE", "target");
            execute(session, "UPDATE oa_attendance_document SET source_status='DRAFT',knowledge_rank=2 WHERE oa_attendance_document_id='oa-current'");
            assertThat(provenDraft(session, COMPANY, key)).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"target-company", "another-company"})
    void anyPreviouslyPublishedDraftFactIncludingZeroAndAnyCompanyBlocksTheProof(String factCompany) throws Exception {
        try (SqlSession session = session()) {
            String key = "LEAVE:draft";
            observation(session, "old", "shared", key, "LEAVE", "target");
            execute(session, "UPDATE oa_attendance_document SET source_status='DRAFT'",
                    "ALTER TABLE attendance_report_oa_fact ADD recognized_minutes INT DEFAULT 0");
            insert(session, "INSERT INTO attendance_report_oa_fact (attendance_report_oa_fact_id,attendance_report_projection_id,company_id,oa_attendance_document_id,employee_id,created_at,recognized_minutes) VALUES ('fact','historical-pin',?,'oa-old','target','2026-09-30 00:00:00',0)", factCompany);
            session.clearCache();
            assertThat(provenDraft(session, COMPANY, key)).isEmpty();
        }
    }

    @ParameterizedTest(name = "draft unsafe {0}")
    @MethodSource("unsafeDraftHistory")
    void draftRetainsAllHistoryIdentityAndIndependentEvidenceGuards(String label, String sql) throws Exception {
        try (SqlSession session = session()) {
            String key = "LEAVE:draft";
            observation(session, "old", "shared", key, "LEAVE", "target");
            execute(session, "UPDATE oa_attendance_document SET source_status='DRAFT'", sql);
            assertThat(provenDraft(session, COMPANY, key)).as(label).isEmpty();
        }
    }

    private static Stream<Arguments> unsafeDraftHistory() {
        return Stream.of(
                Arguments.of("raw without normalized record", "INSERT INTO raw_attendance_fact VALUES ('raw-opaque','shared','LEAVE:draft','opaque','OA_DOCUMENT')"),
                Arguments.of("invalid", "UPDATE normalized_attendance_record SET validation_status='INVALID'"),
                Arguments.of("source identity", "UPDATE oa_attendance_document SET attendance_source_id='foreign'"),
                Arguments.of("source version", "UPDATE oa_attendance_document SET source_version='different'"),
                Arguments.of("unmatched", "UPDATE employee_match_decision SET match_status='UNMATCHED'"),
                Arguments.of("missing aliases", "DELETE FROM employee_version WHERE employee_id='target'"),
                Arguments.of("blank current number", "UPDATE employee SET employee_number=NULL WHERE employee_id='target'"),
                Arguments.of("blank historic number", "UPDATE employee_version SET employee_number='' WHERE employee_id='target'"),
                Arguments.of("independent punch", "INSERT INTO evidence_link VALUES ('link','event','raw-old','norm-old','match','PRIMARY','2026-10-01 00:00:00')"),
                Arguments.of("future observation", "UPDATE oa_attendance_document SET created_at='2026-10-07 00:00:00'"));
    }

    @Test
    void draftCannotHideAChangeOfMatchedEmployeeEvenInsideSameCompany() throws Exception {
        try (SqlSession session = session()) {
            String key = "LEAVE:draft";
            observation(session, "old", "shared", key, "LEAVE", "target");
            observation(session, "new", "shared", key, "LEAVE", "peer");
            execute(session, "UPDATE oa_attendance_document SET source_status='DRAFT'");
            assertThat(provenDraft(session, COMPANY, key)).isEmpty();
        }
    }

    private static Set<String> provenDraft(SqlSession session, String company, String key) {
        var mapper = session.getMapper(AttendanceEvidenceMapper.class);
        return MissingOaReportScopeProof.provenNeverEffectiveDraftLeaveKeys(company, List.of(key),
                mapper.findMissingOaKeyScopeRows("shared", company, List.of(key)),
                mapper.findMissingOaCompanyCurrentEligibilityRows("shared", company, List.of(key), AS_OF), AS_OF);
    }

    private static void exclusion(SqlSession session, String key) throws Exception {
        insert(session, "INSERT INTO attendance_report_oa_evidence_exclusion VALUES ('exclude','target-company','target','shared',?,'v-old','oa-old','2026-10-06 00:00:00',NULL)", key);
        session.clearCache();
    }

    private static Set<String> proven(SqlSession session, String key) {
        return proven(session, TARGETS, key, AS_OF);
    }

    private static Set<String> proven(SqlSession session, List<String> targets, String key, Instant asOf) {
        var mapper = session.getMapper(AttendanceEvidenceMapper.class);
        return MissingOaReportScopeProof.provenNoncontributingEmployeeKeys(COMPANY, targets, List.of(key),
                mapper.findMissingOaKeyEmployeeScopeRows("shared", COMPANY, targets, List.of(key)),
                mapper.findReportEmployeeNumbers(COMPANY, targets),
                mapper.findMissingOaCurrentEligibilityRows("shared", COMPANY, targets, List.of(key), asOf), asOf);
    }

    private static void observation(SqlSession session, String id, String source, String key,
                                    String type, String employee) throws Exception {
        insert(session, "INSERT INTO raw_attendance_fact VALUES (?, ?, ?, ?, 'OA_DOCUMENT')",
                "raw-" + id, source, key, "v-" + id);
        insert(session, "INSERT INTO normalized_attendance_record VALUES (?, ?, 1, 'VALID', 'OA_INTERVAL')",
                "norm-" + id, "raw-" + id);
        insert(session, "INSERT INTO oa_attendance_document (oa_attendance_document_id, normalized_attendance_record_id, attendance_source_id, source_business_key, source_version, document_type, source_status) VALUES (?, ?, ?, ?, ?, ?, 'APPROVED')",
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
                "CREATE TABLE oa_attendance_document (oa_attendance_document_id VARCHAR(36) PRIMARY KEY, normalized_attendance_record_id VARCHAR(36) REFERENCES normalized_attendance_record, attendance_source_id VARCHAR(36) REFERENCES attendance_source, source_business_key VARCHAR(191), source_version VARCHAR(128), document_type VARCHAR(32), source_status VARCHAR(32), knowledge_rank BIGINT DEFAULT 1, created_at TIMESTAMP(6) DEFAULT '2026-10-01 00:00:00')",
                "CREATE TABLE employee_match_decision (normalized_attendance_record_id VARCHAR(36) PRIMARY KEY REFERENCES normalized_attendance_record, employee_id VARCHAR(36) REFERENCES employee, match_status VARCHAR(32))",
                "CREATE TABLE attendance_report_oa_fact (attendance_report_oa_fact_id VARCHAR(36) PRIMARY KEY, attendance_report_projection_id VARCHAR(36), company_id VARCHAR(36), oa_attendance_document_id VARCHAR(36) REFERENCES oa_attendance_document, employee_id VARCHAR(36) REFERENCES employee, created_at TIMESTAMP(6))",
                "CREATE TABLE evidence_link (evidence_link_id VARCHAR(36) PRIMARY KEY, effective_attendance_event_id VARCHAR(36), raw_attendance_fact_id VARCHAR(36) REFERENCES raw_attendance_fact, normalized_attendance_record_id VARCHAR(36) REFERENCES normalized_attendance_record, employee_match_decision_id VARCHAR(36), link_type VARCHAR(32), created_at TIMESTAMP(6))",
                "CREATE TABLE oa_attendance_document_context (oa_attendance_document_id VARCHAR(36) PRIMARY KEY REFERENCES oa_attendance_document, activation_decision VARCHAR(40), overtime_type VARCHAR(32))",
                "CREATE TABLE attendance_report_oa_evidence_exclusion (exclusion_id VARCHAR(36) PRIMARY KEY, company_id VARCHAR(36), employee_id VARCHAR(36), attendance_source_id VARCHAR(36), source_business_key VARCHAR(191), source_version VARCHAR(128), oa_attendance_document_id VARCHAR(36), knowledge_at TIMESTAMP(6), reversed_at TIMESTAMP(6))",
                "INSERT INTO attendance_source VALUES ('shared','outside-company'),('foreign','foreign-company')",
                "INSERT INTO employee VALUES ('target','target-company','TARGET-001'),('peer','target-company','PEER-001'),('second-peer','target-company','PEER-002'),('outside','outside-company','OUTSIDE-001')",
                "INSERT INTO employee_version VALUES ('target-v1','target','TARGET-001'),('peer-v1','peer','PEER-001'),('second-v1','second-peer','PEER-002'),('outside-v1','outside','OUTSIDE-001')");
        return session;
    }
}
