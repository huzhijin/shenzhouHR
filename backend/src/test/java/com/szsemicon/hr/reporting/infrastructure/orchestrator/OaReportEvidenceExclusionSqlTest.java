package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PeriodState;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real four-reader SQL, then its actual result drives the production engine. */
class OaReportEvidenceExclusionSqlTest {
    private static final String NS = AttendanceReportCalculationMapper.class.getName() + ".";
    private static final LocalDate DAY = LocalDate.of(2026, 9, 22);
    private static final Instant BEFORE = Instant.parse("2026-10-06T00:00:00Z");
    private static final Instant EXCLUDED = Instant.parse("2026-10-06T01:00:00Z");
    private static final Instant REVERSED = Instant.parse("2026-10-06T02:00:00Z");
    private static final List<String> READERS = List.of("findEffectiveOaDocuments",
            "findEffectiveOaDocumentsForEmployee", "findReportableOaDocuments", "findReportableOaDocumentsForEmployee");

    @Test void findOaEvidenceExclusionsReturnsPointExclusionInMonthWindow() throws Exception {
        try (var s = session()) {
            doc(s, "point", "PUNCH_CORRECTION:1", "v1", 1, "PENDING", at(8, 30), at(8, 30));
            excludeV74(s, "point", "PUNCH_CORRECTION", at(8, 30), at(8, 30), null);
            var exclusions = s.<OaEvidenceExclusionRow>selectList(NS + "findOaEvidenceExclusions",
                    Map.of("companyId", "company", "windowStart", Instant.parse("2026-08-31T16:00:00Z"),
                            "windowEndExclusive", Instant.parse("2026-09-30T16:00:00Z"), "dataAsOf", EXCLUDED));
            assertThat(exclusions).hasSize(1);
            assertThat(exclusions.get(0).exclusionId()).isEqualTo("exclude-point");
            assertThat(exclusions.get(0).documentType()).isEqualTo("PUNCH_CORRECTION");
            assertThat(exclusions.get(0).pointInstant()).isEqualTo(at(8, 30));
        }
    }

    @Test void findOaEvidenceExclusionsReturnsIntervalExclusionInMonthWindow() throws Exception {
        try (var s = session()) {
            doc(s, "leave", "LEAVE:1", "v1", 1, "APPROVED", at(8, 30), at(12, 0));
            excludeV74(s, "leave", "LEAVE", at(8, 30), at(12, 0), null);
            var exclusions = s.<OaEvidenceExclusionRow>selectList(NS + "findOaEvidenceExclusions",
                    Map.of("companyId", "company", "windowStart", Instant.parse("2026-08-31T16:00:00Z"),
                            "windowEndExclusive", Instant.parse("2026-09-30T16:00:00Z"), "dataAsOf", EXCLUDED));
            assertThat(exclusions).hasSize(1);
            assertThat(exclusions.get(0).intervalStart()).isEqualTo(at(8, 30));
            assertThat(exclusions.get(0).intervalEnd()).isEqualTo(at(12, 0));
        }
    }

    @Test void findOaEvidenceExclusionsFiltersWrongCompany() throws Exception {
        try (var s = session()) {
            doc(s, "point", "PUNCH_CORRECTION:1", "v1", 1, "PENDING", at(8, 30), at(8, 30));
            excludeV74(s, "point", "PUNCH_CORRECTION", at(8, 30), at(8, 30), null);
            execute(s, "UPDATE attendance_report_oa_evidence_exclusion SET company_id='other'");
            var exclusions = s.<OaEvidenceExclusionRow>selectList(NS + "findOaEvidenceExclusions",
                    Map.of("companyId", "company", "windowStart", Instant.parse("2026-08-31T16:00:00Z"),
                            "windowEndExclusive", Instant.parse("2026-09-30T16:00:00Z"), "dataAsOf", EXCLUDED));
            assertThat(exclusions).isEmpty();
        }
    }

    @Test void findOaEvidenceExclusionsSeesActiveBeforeReversal() throws Exception {
        try (var s = session()) {
            doc(s, "point", "PUNCH_CORRECTION:1", "v1", 1, "PENDING", at(8, 30), at(8, 30));
            excludeV74(s, "point", "PUNCH_CORRECTION", at(8, 30), at(8, 30), REVERSED);
            var beforeReversal = s.<OaEvidenceExclusionRow>selectList(NS + "findOaEvidenceExclusions",
                    Map.of("companyId", "company", "windowStart", Instant.parse("2026-08-31T16:00:00Z"),
                            "windowEndExclusive", Instant.parse("2026-09-30T16:00:00Z"), "dataAsOf", EXCLUDED));
            assertThat(beforeReversal).hasSize(1);
            var beforeMarkers = OaEvidenceExclusion.markers(beforeReversal, EXCLUDED);
            var afterReversal = s.<OaEvidenceExclusionRow>selectList(NS + "findOaEvidenceExclusions",
                    Map.of("companyId", "company", "windowStart", Instant.parse("2026-08-31T16:00:00Z"),
                            "windowEndExclusive", Instant.parse("2026-09-30T16:00:00Z"), "dataAsOf", REVERSED));
            assertThat(afterReversal).hasSize(1);
            var afterMarkers = OaEvidenceExclusion.markers(afterReversal, REVERSED);
            assertThat(beforeReversal).isEqualTo(afterReversal);
            assertThat(beforeMarkers).isNotEqualTo(afterMarkers);
        }
    }

    @Test void appliesAtKnowledgeCutoffAndReversalIsHistoricalNotDestructive() throws Exception {
        try (var s = session()) {
            doc(s, "point", "PUNCH_CORRECTION:1", "v1", 1, "PENDING", at(8, 30), at(8, 30));
            exclude(s, "point");
            all(s, BEFORE, "PUNCH_CORRECTION:1");
            all(s, EXCLUDED);
            execute(s, "UPDATE attendance_report_oa_evidence_exclusion SET reversed_at=TIMESTAMP WITH TIME ZONE '2026-10-06 02:00:00+00'");
            all(s, EXCLUDED);
            all(s, REVERSED, "PUNCH_CORRECTION:1");
            // No fabricated revocation; the immutable source status still says pending.
            try (var st = s.getConnection().createStatement();
                    var source = st.executeQuery("SELECT source_status FROM oa_attendance_document")) {
                assertThat(source.next()).isTrue();
                assertThat(source.getString(1)).isEqualTo("PENDING");
            }
            assertThat(keys(s, "findEffectiveOaDocuments", BEFORE)).containsExactly("PUNCH_CORRECTION:1");
        }
    }

    @Test void excludingLatestNeverResurrectsOlderApprovedObservation() throws Exception {
        try (var s = session()) {
            doc(s, "old", "PUNCH_CORRECTION:1", "v1", 1, "APPROVED", at(8, 30), at(8, 30));
            doc(s, "latest", "PUNCH_CORRECTION:1", "v2", 2, "PENDING", at(9, 30), at(9, 30));
            exclude(s, "latest");
            all(s, EXCLUDED);
            doc(s, "new-observation", "PUNCH_CORRECTION:1", "v3", 3, "APPROVED", at(8, 40), at(8, 40));
            all(s, EXCLUDED, "PUNCH_CORRECTION:1");
            List<OaDocumentRow> rows = s.selectList(NS + "findEffectiveOaDocuments", params(EXCLUDED));
            assertThat(rows).singleElement().satisfies(row -> assertThat(row.startInstant()).isEqualTo(at(8, 40)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"company_id", "employee_id", "attendance_source_id", "source_business_key",
            "source_version", "oa_attendance_document_id"})
    void everyBoundIdentityFieldMustMatch(String field) throws Exception {
        try (var s = session()) {
            doc(s, "point", "PUNCH_CORRECTION:1", "v1", 1, "PENDING", at(8, 30), at(8, 30));
            exclude(s, "point");
            execute(s, "UPDATE attendance_report_oa_evidence_exclusion SET " + field + "='other'");
            all(s, EXCLUDED, "PUNCH_CORRECTION:1");
        }
    }

    @Test void unrelatedBusinessKeyIsNotExcluded() throws Exception {
        try (var s = session()) {
            doc(s, "excluded", "PUNCH_CORRECTION:1", "v1", 1, "PENDING", at(8, 30), at(8, 30));
            doc(s, "kept", "PUNCH_CORRECTION:2", "v1", 1, "APPROVED", at(23, 30), at(23, 30));
            exclude(s, "excluded");
            all(s, EXCLUDED, "PUNCH_CORRECTION:2");
        }
    }

    @Test void excludedAmendmentCannotPullOriginalFromOutsideWindow() throws Exception {
        try (var s = session()) {
            doc(s, "leave", "LEAVE:1", "v1", 1, "APPROVED", at(8, 30).minusSeconds(86400), at(12, 0).minusSeconds(86400));
            doc(s, "move", "LEAVE_REVOCATION:1", "v1", 1, "APPROVED", at(8, 30), at(12, 0));
            execute(s, "UPDATE oa_attendance_document SET leave_type='PERSONAL',leave_serial='serial' WHERE oa_attendance_document_id='leave'");
            execute(s, "UPDATE oa_attendance_document SET original_leave_serial='serial' WHERE oa_attendance_document_id='move'");
            var window = Map.of("companyId", "company", "employeeId", "employee", "dataAsOf", EXCLUDED,
                    "windowStart", at(0, 0), "windowEndExclusive", at(0, 0).plusSeconds(86400));
            assertThat(s.<OaReportFactRow>selectList(NS + "findReportableOaDocuments", window))
                    .extracting(OaReportFactRow::sourceBusinessKey).containsExactly("LEAVE:1");
            exclude(s, "move");
            for (var reader : READERS) assertThat(s.selectList(NS + reader, window)).as(reader).isEmpty();
        }
    }

    @Test void exclusionOfOriginalDoesNotPermitRevocationFallbackToRetiredVersion() throws Exception {
        try (var s = session()) {
            doc(s, "leave-old", "LEAVE:1", "v1", 1, "APPROVED", at(8, 30), at(12, 0));
            doc(s, "leave-new", "LEAVE:1", "v2", 2, "APPROVED", at(8, 30), at(12, 0));
            doc(s, "move", "LEAVE_REVOCATION:1", "v1", 1, "APPROVED", at(8, 30).minusSeconds(86400), at(8, 30).minusSeconds(86400));
            execute(s, "UPDATE oa_attendance_document SET leave_type='PERSONAL',leave_serial='serial' WHERE document_type='LEAVE'");
            execute(s, "UPDATE oa_attendance_document SET original_leave_serial='serial' WHERE oa_attendance_document_id='move'");
            exclude(s, "leave-new");
            var window = Map.of("companyId", "company", "employeeId", "employee", "dataAsOf", EXCLUDED,
                    "windowStart", at(0, 0), "windowEndExclusive", at(0, 0).plusSeconds(86400));
            for (var reader : READERS) assertThat(s.selectList(NS + reader, window)).as(reader).isEmpty();
        }
    }

    @Test void actualSqlExcludesBothOldMakeupPointsFromDailyAndOvertimeDocumentTogether() throws Exception {
        try (var s = session()) {
            doc(s, "entry", "PUNCH_CORRECTION:-888057588827274600", "legacy-entry", 1, "PENDING", at(8, 30), at(8, 30));
            doc(s, "exit", "PUNCH_CORRECTION:628353716576996644", "legacy-exit", 1, "PENDING", at(23, 30), at(23, 30));
            doc(s, "ot", "OVERTIME:-4286441259637521285", "v1", 1, "APPROVED", at(18, 30), at(23, 30));
            doc(s, "outing", "OUTING:8242336396638505936", "v1", 1, "APPROVED", at(8, 30), at(23, 30));
            execute(s, "INSERT INTO oa_attendance_document_context VALUES ('ot','ACTIVATED','PAID')");
            exclude(s, "entry"); exclude(s, "exit");
            verifyEngine(s, BEFORE, 300, 2);
            verifyEngine(s, EXCLUDED, 0, 0);
        }
    }

    @Test void explicitDraftNeverEntersAnyReaderAndCannotDisplaceSeparateApprovedAnnualLeave() throws Exception {
        try (var s = session()) {
            doc(s, "draft", "LEAVE:-2044581916237414171", "state=NULL", 1, "DRAFT", at(8, 30), at(12, 0));
            doc(s, "annual", "LEAVE:-4577660915740619130", "state=3", 1, "APPROVED", at(8, 30), at(12, 0));
            execute(s, "UPDATE oa_attendance_document SET leave_type='ANNUAL' WHERE document_type='LEAVE'");
            all(s, EXCLUDED, "LEAVE:-4577660915740619130");
            var effective = s.<OaDocumentRow>selectList(NS + "findEffectiveOaDocuments", params(EXCLUDED));
            var intervals = OaDocumentConverter.toIntervalEvidence(effective);
            assertThat(intervals).hasSize(1);
            assertThat(java.time.Duration.between(effective.getFirst().startInstant(), effective.getFirst().endInstant()).toMinutes())
                    .isEqualTo(210);
            assertThat(OaDocumentConverter.amendedLeaveIntervals(effective)).isEmpty();
        }
    }

    @Test void independentApprovedAmendmentCannotActivateDraftOriginalOrCreateLeaveMinutes() throws Exception {
        try (var s = session()) {
            doc(s, "draft", "LEAVE:draft", "v1", 1, "DRAFT", at(8, 30), at(12, 0));
            doc(s, "amendment", "LEAVE_REVOCATION:1", "v1", 1, "APPROVED", at(9, 0), at(11, 0));
            execute(s, "UPDATE oa_attendance_document SET leave_type='ANNUAL',leave_serial='DRAFT-SERIAL' WHERE oa_attendance_document_id='draft'");
            execute(s, "UPDATE oa_attendance_document SET original_leave_serial='DRAFT-SERIAL' WHERE oa_attendance_document_id='amendment'");
            for (var reader : List.of("findEffectiveOaDocuments", "findEffectiveOaDocumentsForEmployee")) {
                var effective = s.<OaDocumentRow>selectList(NS + reader, params(EXCLUDED));
                assertThat(effective).extracting(OaDocumentRow::sourceBusinessKey).containsExactly("LEAVE_REVOCATION:1");
                // Existing no-original behavior produces no leave evidence;
                // the missing-draft proof must not manufacture an original.
                assertThat(OaDocumentConverter.toIntervalEvidence(effective)).isEmpty();
                assertThat(OaDocumentConverter.amendedLeaveIntervals(effective)).isEmpty();
            }
            for (var reader : List.of("findReportableOaDocuments", "findReportableOaDocumentsForEmployee")) {
                assertThat(s.selectList(NS + reader, params(EXCLUDED))).isEmpty();
            }
        }
    }

    @Test void twoEffectiveOriginalsWithSameReferenceStillFailInsteadOfUsingDraftProofAsAmbiguityResolution() throws Exception {
        try (var s = session()) {
            doc(s, "draft", "LEAVE:draft", "v1", 1, "DRAFT", at(8, 30), at(12, 0));
            doc(s, "one", "LEAVE:one", "v1", 1, "APPROVED", at(8, 30), at(12, 0));
            doc(s, "two", "LEAVE:two", "v1", 1, "APPROVED", at(8, 30), at(12, 0));
            doc(s, "amendment", "LEAVE_REVOCATION:1", "v1", 1, "APPROVED", at(9, 0), at(11, 0));
            execute(s, "UPDATE oa_attendance_document SET leave_type='ANNUAL',leave_serial='SHARED-SERIAL' WHERE document_type='LEAVE'");
            execute(s, "UPDATE oa_attendance_document SET original_leave_serial='SHARED-SERIAL' WHERE oa_attendance_document_id='amendment'");
            for (var reader : List.of("findEffectiveOaDocuments", "findEffectiveOaDocumentsForEmployee")) {
                var effective = s.<OaDocumentRow>selectList(NS + reader, params(EXCLUDED));
                assertThat(effective).extracting(OaDocumentRow::sourceBusinessKey)
                        .containsExactlyInAnyOrder("LEAVE:one", "LEAVE:two", "LEAVE_REVOCATION:1");
                assertThatThrownBy(() -> OaDocumentConverter.toIntervalEvidence(effective))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("ambiguous original leave reference");
            }
        }
    }

    private static void verifyEngine(SqlSession s, Instant cutoff, long overtime, long corrections) {
        var m = mock(AttendanceReportCalculationMapper.class);
        when(m.findAttendanceSourceVersions("company", cutoff)).thenReturn(List.of(
                new SourceInputVersionRow("source", "OA_ATTENDANCE", 1, 1, 1L, "a".repeat(64), cutoff),
                new SourceInputVersionRow("deli", "DELI_CLOUD", 1, 1, 1L, "b".repeat(64), cutoff)));
        when(m.findEmployeeIdentityIntervals(eq("company"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(new EmployeeIdentityIntervalRow("employee", "v1", "SZST0630", "龙隆",
                        "assignment", "org", "org-v1", "现场服务部", LocalDate.of(2026, 5, 18), null,
                        LocalDate.of(2026, 5, 18), null, LocalDate.of(2026, 5, 18), null)));
        when(m.findPublishedCalendarDays(eq("company"), any(LocalDate.class), any(LocalDate.class), eq(cutoff)))
                .thenReturn(List.of(new CalendarDayRow("employee", DAY, "WEEKDAY", 1, 1)));
        when(m.findScheduledWorkSegments(eq("company"), any(LocalDate.class), any(LocalDate.class), eq(cutoff)))
                .thenReturn(List.of(segment("am", at(8, 30), at(12, 0)), segment("pm", at(13, 30), at(18, 0))));
        when(m.findAttendancePolicies(eq("company"), any(LocalDate.class), any(LocalDate.class), eq(cutoff)))
                .thenReturn(List.of(new AttendancePolicyRow(
                        "employee", DAY,
                        1, 1, true, 15,
                        1, true, 15, 1, false,
                        1, true, 7, "NEXT_DAY_START_AFTER_FULL_DAYS",
                        1, true, 48)));
        when(m.findEffectiveOaDocuments(eq("company"), any(Instant.class), any(Instant.class), eq(cutoff)))
                .thenAnswer(call -> s.selectList(NS + "findEffectiveOaDocuments", params(cutoff)));
        when(m.findReportableOaDocuments(eq("company"), any(Instant.class), any(Instant.class), eq(cutoff)))
                .thenAnswer(call -> s.selectList(NS + "findReportableOaDocuments", params(cutoff)));
        var result = new FullCalculationEngineOrchestrator(m).assemble("company", YearMonth.of(2026, 9),
                PeriodState.OPEN, "audit", cutoff);
        var daily = result.calculatedFacts().stream().map(f -> f.facts().dailyFact())
                .filter(f -> f.businessDate().equals(DAY)).findFirst().orElseThrow();
        assertThat(daily.paidOvertimeMinutes()).isEqualTo(overtime);
        assertThat(daily.confirmedScheduledWorkMinutes()).isEqualTo(480); // approved OUTING remains
        assertThat(result.oaDocumentFacts()).filteredOn(f -> f.documentType().equals("OVERTIME"))
                .singleElement().satisfies(f -> assertThat(f.recognizedMinutes()).isEqualTo(overtime));
        assertThat(result.oaDocumentFacts().stream().filter(f -> f.documentType().equals("PUNCH_CORRECTION")).count())
                .isEqualTo(corrections);
        if (corrections == 0) {
            assertThat(daily.firstPunchAt()).isNull(); assertThat(daily.lastPunchAt()).isNull();
        }
    }

    private static ShiftSegmentRow segment(String id, Instant start, Instant end) {
        return new ShiftSegmentRow("employee", DAY, id, start, end,
                start.minusSeconds(3600), start.plusSeconds(3600), end.minusSeconds(3600), end.plusSeconds(3600));
    }
    private static Instant at(int hour, int minute) {
        return DAY.atTime(hour, minute).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
    }
    private static Map<String, Object> params(Instant cutoff) {
        return Map.of("companyId", "company", "employeeId", "employee", "dataAsOf", cutoff,
                "windowStart", Instant.parse("2026-08-31T16:00:00Z"), "windowEndExclusive", Instant.parse("2026-09-30T16:00:00Z"));
    }
    private static List<String> keys(SqlSession s, String reader, Instant cutoff) {
        return s.selectList(NS + reader, params(cutoff)).stream().map(row -> row instanceof OaDocumentRow e
                ? e.sourceBusinessKey() : ((OaReportFactRow) row).sourceBusinessKey()).toList();
    }
    private static void all(SqlSession s, Instant cutoff, String... keys) {
        s.clearCache();
        for (var reader : READERS) assertThat(keys(s, reader, cutoff)).as(reader).containsExactlyInAnyOrder(keys);
    }
    private static void doc(SqlSession s, String id, String key, String version, long rank, String status,
            Instant start, Instant end) throws Exception {
        execute(s, "INSERT INTO raw_attendance_fact VALUES ('raw-" + id + "','Asia/Shanghai')");
        boolean point = key.startsWith("PUNCH_CORRECTION:") || start.equals(end);
        try (var ps = s.getConnection().prepareStatement("INSERT INTO normalized_attendance_record VALUES (?,?,'VALID',?,?,?)")) {
            ps.setString(1, "n-" + id); ps.setString(2, "raw-" + id);
            ps.setObject(3, point ? null : start.atOffset(java.time.ZoneOffset.UTC));
            ps.setObject(4, point ? null : end.atOffset(java.time.ZoneOffset.UTC));
            ps.setObject(5, point ? start.atOffset(java.time.ZoneOffset.UTC) : null); ps.executeUpdate();
        }
        execute(s, "INSERT INTO employee_match_decision VALUES ('n-" + id + "','MATCHED','employee','assignment')");
        try (var ps = s.getConnection().prepareStatement("INSERT INTO oa_attendance_document VALUES (?,'source',?,?,?,NULL,NULL,NULL,? ,?,?,TIMESTAMP WITH TIME ZONE '2026-09-01 00:00:00+00',TIMESTAMP WITH TIME ZONE '2026-10-01 00:00:00+00')")) {
            ps.setString(1,id); ps.setString(2,key); ps.setString(3,version); ps.setString(4,key.split(":")[0]);
            ps.setString(5,status); ps.setString(6,"n-"+id); ps.setLong(7,rank); ps.executeUpdate();
        }
        s.clearCache();
    }
    private static void exclude(SqlSession s, String id) throws Exception {
        execute(s, "INSERT INTO attendance_report_oa_evidence_exclusion " +
                "SELECT 'exclude-" + id + "','company','employee',attendance_source_id,source_business_key,source_version," +
                "oa_attendance_document_id,document_type,NULL,NULL,NULL," +
                "TIMESTAMP WITH TIME ZONE '2026-10-06 01:00:00+00','req-" + id + "'," +
                "'userconf-sha256','evidence-sha256',TIMESTAMP WITH TIME ZONE '2026-10-06 01:00:00+00',NULL " +
                "FROM oa_attendance_document WHERE oa_attendance_document_id='" + id + "'");
    }
    private static void excludeV74(SqlSession s, String id, String docType, Instant start, Instant end, Instant reversedAt) throws Exception {
        boolean point = start.equals(end);
        try (var ps = s.getConnection().prepareStatement(
                "INSERT INTO attendance_report_oa_evidence_exclusion " +
                "SELECT 'exclude-" + id + "','company','employee',attendance_source_id,source_business_key,source_version,oa_attendance_document_id,?," +
                "?,?,?,?,'req-" + id + "','userconf-sha256','evidence-sha256',TIMESTAMP WITH TIME ZONE '2026-10-06 01:00:00+00',? " +
                "FROM oa_attendance_document WHERE oa_attendance_document_id='" + id + "'")) {
            ps.setString(1, docType);
            ps.setObject(2, point ? null : start.atOffset(java.time.ZoneOffset.UTC));
            ps.setObject(3, point ? null : end.atOffset(java.time.ZoneOffset.UTC));
            ps.setObject(4, point ? start.atOffset(java.time.ZoneOffset.UTC) : null);
            ps.setObject(5, Instant.parse("2026-10-06T01:00:00Z").atOffset(java.time.ZoneOffset.UTC));
            ps.setObject(6, reversedAt != null ? reversedAt.atOffset(java.time.ZoneOffset.UTC) : null);
            ps.executeUpdate();
        }
        s.clearCache();
    }
    private static void execute(SqlSession s, String sql) throws Exception {
        try (var st = s.getConnection().createStatement()) { st.execute(sql); }
        s.clearCache();
    }
    private static SqlSession session() throws Exception {
        var configuration = new Configuration(new Environment("test", new JdbcTransactionFactory(),
                new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:exclusion-" + UUID.randomUUID() + ";MODE=MySQL", "sa", "")));
        String resource = "mappers/AttendanceReportCalculationMapper.xml";
        try (var stream = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(stream, configuration, resource, configuration.getSqlFragments()).parse();
        }
        var s = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        for (var ddl : List.of("SET TIME ZONE 'UTC'",
                "CREATE TABLE employee (employee_id VARCHAR(64),company_id VARCHAR(64),employee_number VARCHAR(64))",
                "CREATE TABLE raw_attendance_fact (raw_attendance_fact_id VARCHAR(64),source_time_zone VARCHAR(64))",
                "CREATE TABLE normalized_attendance_record (normalized_attendance_record_id VARCHAR(64),raw_attendance_fact_id VARCHAR(64),validation_status VARCHAR(32),interval_start TIMESTAMP WITH TIME ZONE,interval_end TIMESTAMP WITH TIME ZONE,point_instant TIMESTAMP WITH TIME ZONE)",
                "CREATE TABLE employee_match_decision (normalized_attendance_record_id VARCHAR(64),match_status VARCHAR(32),employee_id VARCHAR(64),employment_period_id VARCHAR(64))",
                "CREATE TABLE oa_attendance_document (oa_attendance_document_id VARCHAR(64),attendance_source_id VARCHAR(64),source_business_key VARCHAR(191),source_version VARCHAR(128),document_type VARCHAR(32),leave_type VARCHAR(32),leave_serial VARCHAR(64),original_leave_serial VARCHAR(64),source_status VARCHAR(32),normalized_attendance_record_id VARCHAR(64),knowledge_rank BIGINT,first_submitted_at TIMESTAMP WITH TIME ZONE,created_at TIMESTAMP WITH TIME ZONE)",
                "CREATE TABLE oa_attendance_document_context (oa_attendance_document_id VARCHAR(64),activation_decision VARCHAR(32),overtime_type VARCHAR(32))",
                "CREATE TABLE attendance_report_oa_evidence_exclusion (exclusion_id VARCHAR(64),company_id VARCHAR(64),employee_id VARCHAR(64),attendance_source_id VARCHAR(64),source_business_key VARCHAR(191),source_version VARCHAR(128),oa_attendance_document_id VARCHAR(64),document_type VARCHAR(32),interval_start TIMESTAMP WITH TIME ZONE,interval_end TIMESTAMP WITH TIME ZONE,point_instant TIMESTAMP WITH TIME ZONE,as_of_effective_time TIMESTAMP WITH TIME ZONE,request_id VARCHAR(64),user_confirmation_sha256 VARCHAR(64),evidence_sha256 VARCHAR(64),knowledge_at TIMESTAMP WITH TIME ZONE,reversed_at TIMESTAMP WITH TIME ZONE)",
                "INSERT INTO employee VALUES ('employee','company','SZST0630')")) execute(s, ddl);
        return s;
    }
}
