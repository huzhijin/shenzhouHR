package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.reporting.application.AttendanceReportProjectionWriter;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.OaTemporalShape;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PeriodState;
import com.szsemicon.hr.reporting.application.QueryPageRows;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * Integration test for HR paper fact write/read cycle. Tests that HR adjustments
 * (ENTRY/EXIT punches and DAY_TYPE intervals) write to attendance_report_hr_paper_fact
 * and are readable via listAuthorizedOaFactsForEmployees alongside legacy OA facts.
 * Uses dedicated schema with real MyBatisAttendanceReportProjectionWriter.
 */
public class HrPaperFactWriteReadTest {
    private static final String COMPANY = "company-1";
    private static final String EMPLOYEE_A = "employee-a";
    private static final String EMPLOYEE_B = "employee-b";
    private static final String PRINCIPAL = "principal-1";
    private static final LocalDate SEPT_8 = LocalDate.of(2026, 9, 8);
    private static final LocalDate SEPT_9 = LocalDate.of(2026, 9, 9);
    private static final Instant NOW = Instant.parse("2026-10-12T08:00:00Z");
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    /**
     * H2 compatibility function for MySQL FIND_IN_SET.
     * Returns 1-based position of needle in comma-delimited haystack, or 0 if not found.
     */
    public static int findInSet(String needle, String haystack) {
        if (needle == null || haystack == null) return 0;
        String[] tokens = haystack.split(",", -1);
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i].equals(needle)) return i + 1;
        }
        return 0;
    }

    private SqlSessionFactory sessions;
    private JdbcTemplate jdbc;
    private MyBatisAttendanceReportProjectionWriter writer;

    @BeforeEach
    void createIsolatedDatabase() throws Exception {
        // Each test gets independent H2 in-memory database with UTC session
        var dataSource = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:hr-paper-" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;TIME ZONE=UTC", "sa", "");

        jdbc = new JdbcTemplate(dataSource);

        // Load schema from classpath
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/hr-paper-write-read-schema.sql"));
        }

        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("hr-paper",
                new JdbcTransactionFactory(), dataSource));
        configuration.getTypeHandlerRegistry().register(String.class, JdbcType.OTHER,
                new AttendanceReportRecalculationPersistenceTest.H2JsonTextHandler());

        for (String resource : List.of(
                "mappers/AttendanceReportProjectionWriteMapper.xml",
                "mappers/AttendanceReportMapper.xml",
                "mappers/AttendanceReportQueryPageMapper.xml")) {
            try (var input = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, configuration, resource,
                        configuration.getSqlFragments()).parse();
            }
        }

        sessions = new SqlSessionFactoryBuilder().build(configuration);
        writer = new MyBatisAttendanceReportProjectionWriter(
                sessions.getConfiguration().getMapper(
                        AttendanceReportProjectionWriteMapper.class, sessions.openSession()),
                JsonMapper.builder().build(),
                1);

        seedFixtures();
    }

    @Test
    void entryPunchWritesToHrPaperFactAndReadsBackWith17Columns() {
        String projectionId = createProjection("proj-entry");
        String adjustmentId = seedAdjustment(EMPLOYEE_A, SEPT_9, instant(SEPT_9, 9, 0), null, null);
        String sourceKey = generateSourceDocumentKey("ENTRY", adjustmentId);

        try (SqlSession session = sessions.openSession()) {
            AttendanceReportProjectionWriteMapper mapper = session.getMapper(
                    AttendanceReportProjectionWriteMapper.class);
            MyBatisAttendanceReportProjectionWriter sessionWriter =
                    new MyBatisAttendanceReportProjectionWriter(mapper,
                            JsonMapper.builder().build(), 500);

            // Test single-fact writer entry point (line 159)
            sessionWriter.appendOaDocumentFact(
                    new AttendanceReportProjectionWriter.OaDocumentFactWrite(
                            UUID.randomUUID().toString(),
                            projectionId,
                            COMPANY,
                            sourceKey,
                            EMPLOYEE_A,
                            EMPLOYEE_A + "-v1",
                            EMPLOYEE_A + "-assignment",
                            "org-1",
                            "org-v1",
                            "PUNCH_CORRECTION",
                            null,
                            OaTemporalShape.POINT,
                            instant(SEPT_9, 9, 0),
                            null,
                            null,
                            0,
                            "APPROVED",
                            "hr-v1",
                            NOW,
                            null,
                            null,
                            null,
                            adjustmentId,
                            "ENTRY"));
            session.commit();
        }

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_report_hr_paper_fact WHERE hr_adjustment_id = ?",
                Long.class, adjustmentId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_report_oa_fact WHERE attendance_report_projection_id = ?",
                Long.class, projectionId)).isZero();

        List<ReportRows.OaDocumentRow> rows = readOaFactsForEmployees(projectionId,
                List.of(EMPLOYEE_A));
        assertThat(rows).hasSize(1);
        ReportRows.OaDocumentRow row = rows.get(0);
        assertThat(row.documentId()).isEqualTo(sourceKey);
        assertThat(row.employeeId()).isEqualTo(EMPLOYEE_A);
        assertThat(row.documentType()).isEqualTo("PUNCH_CORRECTION");
        assertThat(row.startAt()).isEqualTo(instant(SEPT_9, 9, 0));
        // For POINT facts, reader contract returns point + 1 microsecond
        assertThat(row.endExclusive()).isEqualTo(instant(SEPT_9, 9, 0).plusNanos(1000));
        assertThat(row.recognizedMinutes()).isZero();
        assertThat(row.hrAdjustmentId()).isEqualTo(adjustmentId);
        assertThat(row.hrSourceKind()).isEqualTo("ENTRY");
        assertThat(row.sourceStatus()).isEqualTo("APPROVED");
    }

    @Test
    void exitPunchWritesToHrPaperFactAndReadsBackWith17Columns() {
        String projectionId = createProjection("proj-exit");
        String adjustmentId = seedAdjustment(EMPLOYEE_A, SEPT_9, null, instant(SEPT_9, 18, 0), null);
        String sourceKey = generateSourceDocumentKey("EXIT", adjustmentId);

        try (SqlSession session = sessions.openSession()) {
            AttendanceReportProjectionWriteMapper mapper = session.getMapper(
                    AttendanceReportProjectionWriteMapper.class);
            MyBatisAttendanceReportProjectionWriter sessionWriter =
                    new MyBatisAttendanceReportProjectionWriter(mapper,
                            JsonMapper.builder().build(), 500);

            sessionWriter.appendOaDocumentFacts(List.of(
                    new AttendanceReportProjectionWriter.OaDocumentFactWrite(
                            UUID.randomUUID().toString(),
                            projectionId,
                            COMPANY,
                            sourceKey,
                            EMPLOYEE_A,
                            EMPLOYEE_A + "-v1",
                            EMPLOYEE_A + "-assignment",
                            "org-1",
                            "org-v1",
                            "PUNCH_CORRECTION",
                            null,
                            OaTemporalShape.POINT,
                            instant(SEPT_9, 18, 0),
                            null,
                            null,
                            0,
                            "APPROVED",
                            "hr-v1",
                            NOW,
                            null,
                            null,
                            null,
                            adjustmentId,
                            "EXIT")));
            session.commit();
        }

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_report_hr_paper_fact WHERE hr_source_kind = ?",
                Long.class, "EXIT")).isEqualTo(1);

        List<ReportRows.OaDocumentRow> rows = readOaFactsForEmployees(projectionId,
                List.of(EMPLOYEE_A));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).hrSourceKind()).isEqualTo("EXIT");
        assertThat(rows.get(0).startAt()).isEqualTo(instant(SEPT_9, 18, 0));
        // For POINT facts, reader contract returns point + 1 microsecond
        assertThat(rows.get(0).endExclusive()).isEqualTo(instant(SEPT_9, 18, 0).plusNanos(1000));
    }

    @Test
    void dayTypeWithZeroMinutesWritesToHrPaperFact() {
        String projectionId = createProjection("proj-daytype");
        String adjustmentId = seedAdjustment(EMPLOYEE_A, SEPT_9, null, null, "SICK_LEAVE");
        String sourceKey = generateSourceDocumentKey("DAY_TYPE:SICK_LEAVE", adjustmentId);

        Instant intervalStart = instant(SEPT_9, 0, 0);
        Instant intervalEnd = instant(SEPT_9, 23, 59).plusSeconds(60);

        try (SqlSession session = sessions.openSession()) {
            AttendanceReportProjectionWriteMapper mapper = session.getMapper(
                    AttendanceReportProjectionWriteMapper.class);
            MyBatisAttendanceReportProjectionWriter sessionWriter =
                    new MyBatisAttendanceReportProjectionWriter(mapper,
                            JsonMapper.builder().build(), 500);

            sessionWriter.appendOaDocumentFacts(List.of(
                    new AttendanceReportProjectionWriter.OaDocumentFactWrite(
                            UUID.randomUUID().toString(),
                            projectionId,
                            COMPANY,
                            sourceKey,
                            EMPLOYEE_A,
                            EMPLOYEE_A + "-v1",
                            EMPLOYEE_A + "-assignment",
                            "org-1",
                            "org-v1",
                            "LEAVE",
                            "SICK",
                            OaTemporalShape.INTERVAL,
                            null,
                            intervalStart,
                            intervalEnd,
                            0,
                            "APPROVED",
                            "hr-v1",
                            NOW,
                            null,
                            null,
                            null,
                            adjustmentId,
                            "DAY_TYPE:SICK_LEAVE")));
            session.commit();
        }

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_report_hr_paper_fact WHERE hr_source_kind = ?",
                Long.class, "DAY_TYPE:SICK_LEAVE")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT recognized_minutes FROM attendance_report_hr_paper_fact WHERE hr_adjustment_id = ?",
                Long.class, adjustmentId)).isZero();
    }

    @Test
    void legacyPaperOvertimeWritesToOaFactNotHrPaperFact() {
        String projectionId = createProjection("proj-legacy-paper");
        String oaDocId = seedOaDocument("PAPER:ot-legacy-123", "OVERTIME", EMPLOYEE_A);

        try (SqlSession session = sessions.openSession()) {
            AttendanceReportProjectionWriteMapper mapper = session.getMapper(
                    AttendanceReportProjectionWriteMapper.class);
            MyBatisAttendanceReportProjectionWriter sessionWriter =
                    new MyBatisAttendanceReportProjectionWriter(mapper,
                            JsonMapper.builder().build(), 500);

            sessionWriter.appendOaDocumentFacts(List.of(
                    new AttendanceReportProjectionWriter.OaDocumentFactWrite(
                            UUID.randomUUID().toString(),
                            projectionId,
                            COMPANY,
                            oaDocId,
                            EMPLOYEE_A,
                            EMPLOYEE_A + "-v1",
                            EMPLOYEE_A + "-assignment",
                            "org-1",
                            "org-v1",
                            "OVERTIME",
                            null,
                            OaTemporalShape.INTERVAL,
                            null,
                            instant(SEPT_9, 18, 0),
                            instant(SEPT_9, 20, 0),
                            120,
                            "APPROVED",
                            "oa-v1",
                            NOW,
                            null,
                            null,
                            "PAPER",
                            null, // no hrAdjustmentId
                            null)));
            session.commit();
        }

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_report_oa_fact WHERE oa_attendance_document_id = ?",
                Long.class, oaDocId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_report_hr_paper_fact",
                Long.class)).isZero();

        List<ReportRows.OaDocumentRow> rows = readOaFactsForEmployees(projectionId,
                List.of(EMPLOYEE_A));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).documentId()).isEqualTo(oaDocId);
        assertThat(rows.get(0).sourceOrigin()).isEqualTo("PAPER");
        assertThat(rows.get(0).hrAdjustmentId()).isNull();
    }

    @Test
    void invalidSourceWithForgedAdjustmentIdWritesZeroRows() {
        String projectionId = createProjection("proj-forged");
        String forgedAdjustmentId = "forged-adjustment-id";
        String sourceKey = generateSourceDocumentKey("ENTRY", forgedAdjustmentId);

        try (SqlSession session = sessions.openSession()) {
            AttendanceReportProjectionWriteMapper mapper = session.getMapper(
                    AttendanceReportProjectionWriteMapper.class);
            MyBatisAttendanceReportProjectionWriter sessionWriter =
                    new MyBatisAttendanceReportProjectionWriter(mapper,
                            JsonMapper.builder().build(), 500);

            sessionWriter.appendOaDocumentFacts(List.of(
                    new AttendanceReportProjectionWriter.OaDocumentFactWrite(
                            UUID.randomUUID().toString(),
                            projectionId,
                            COMPANY,
                            sourceKey,
                            EMPLOYEE_A,
                            EMPLOYEE_A + "-v1",
                            EMPLOYEE_A + "-assignment",
                            "org-1",
                            "org-v1",
                            "PUNCH_CORRECTION",
                            null,
                            OaTemporalShape.POINT,
                            instant(SEPT_9, 9, 0),
                            null,
                            null,
                            0,
                            "APPROVED",
                            "hr-v1",
                            NOW,
                            null,
                            null,
                            null,
                            forgedAdjustmentId,
                            "ENTRY")));
            session.commit();
        }

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_report_hr_paper_fact",
                Long.class)).isZero();
    }

    @Test
    void copyPreservesHrAndOaFacts() {
        String sourceProjection = createProjection("proj-source");
        String adjustmentId = seedAdjustment(EMPLOYEE_A, SEPT_9, instant(SEPT_9, 9, 0), null, null);
        String oaDocId = seedOaDocument("OA:leave-123", "LEAVE", EMPLOYEE_A);
        String sourceKey = generateSourceDocumentKey("ENTRY", adjustmentId);

        try (SqlSession session = sessions.openSession()) {
            AttendanceReportProjectionWriteMapper mapper = session.getMapper(
                    AttendanceReportProjectionWriteMapper.class);
            MyBatisAttendanceReportProjectionWriter sessionWriter =
                    new MyBatisAttendanceReportProjectionWriter(mapper,
                            JsonMapper.builder().build(), 500);

            sessionWriter.appendOaDocumentFacts(List.of(
                    new AttendanceReportProjectionWriter.OaDocumentFactWrite(
                            UUID.randomUUID().toString(),
                            sourceProjection,
                            COMPANY,
                            sourceKey,
                            EMPLOYEE_A,
                            EMPLOYEE_A + "-v1",
                            EMPLOYEE_A + "-assignment",
                            "org-1",
                            "org-v1",
                            "PUNCH_CORRECTION",
                            null,
                            OaTemporalShape.POINT,
                            instant(SEPT_9, 9, 0),
                            null,
                            null,
                            0,
                            "APPROVED",
                            "hr-v1",
                            NOW,
                            null,
                            null,
                            null,
                            adjustmentId,
                            "ENTRY"),
                    new AttendanceReportProjectionWriter.OaDocumentFactWrite(
                            UUID.randomUUID().toString(),
                            sourceProjection,
                            COMPANY,
                            oaDocId,
                            EMPLOYEE_A,
                            EMPLOYEE_A + "-v1",
                            EMPLOYEE_A + "-assignment",
                            "org-1",
                            "org-v1",
                            "LEAVE",
                            "ANNUAL",
                            OaTemporalShape.INTERVAL,
                            null,
                            instant(SEPT_9, 9, 0),
                            instant(SEPT_9, 18, 0),
                            480,
                            "APPROVED",
                            "oa-v1",
                            NOW,
                            null,
                            null,
                            null,
                            null,
                            null)));
            session.commit();
        }

        String targetProjection = createProjection("proj-target");
        try (SqlSession session = sessions.openSession()) {
            AttendanceReportProjectionWriteMapper mapper = session.getMapper(
                    AttendanceReportProjectionWriteMapper.class);
            MyBatisAttendanceReportProjectionWriter sessionWriter =
                    new MyBatisAttendanceReportProjectionWriter(mapper,
                            JsonMapper.builder().build(), 500);

            sessionWriter.copyOaDocumentFactsExceptEmployeeWindow(
                    sourceProjection,
                    targetProjection,
                    instant(SEPT_8, 0, 0),
                    instant(SEPT_8, 23, 59).plusSeconds(60),
                    NOW,
                    EMPLOYEE_B,
                    List.of(),
                    List.of());
            session.commit();
        }

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_report_hr_paper_fact WHERE attendance_report_projection_id = ?",
                Long.class, targetProjection)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_report_oa_fact WHERE attendance_report_projection_id = ?",
                Long.class, targetProjection)).isEqualTo(1);

        List<ReportRows.OaDocumentRow> rows = readOaFactsForEmployees(targetProjection,
                List.of(EMPLOYEE_A));
        assertThat(rows).hasSize(2)
                .anySatisfy(row -> assertThat(row.hrAdjustmentId()).isEqualTo(adjustmentId))
                .anySatisfy(row -> assertThat(row.documentId()).isEqualTo(oaDocId));
    }

    @Test
    void utcBusinessDateValidationWorks() {
        String projectionId = createProjection("proj-utc");
        // Shanghai Sept 8 23:30 = UTC Sept 8 15:30 (same date)
        Instant adjustmentInstant = ZonedDateTime.of(2026, 9, 8, 23, 30, 0, 0, SHANGHAI)
                .toInstant();
        String adjustmentId = seedAdjustment(EMPLOYEE_A, SEPT_8, adjustmentInstant, null, null);
        String sourceKey = generateSourceDocumentKey("ENTRY", adjustmentId);

        try (SqlSession session = sessions.openSession()) {
            AttendanceReportProjectionWriteMapper mapper = session.getMapper(
                    AttendanceReportProjectionWriteMapper.class);
            MyBatisAttendanceReportProjectionWriter sessionWriter =
                    new MyBatisAttendanceReportProjectionWriter(mapper,
                            JsonMapper.builder().build(), 500);

            sessionWriter.appendOaDocumentFacts(List.of(
                    new AttendanceReportProjectionWriter.OaDocumentFactWrite(
                            UUID.randomUUID().toString(),
                            projectionId,
                            COMPANY,
                            sourceKey,
                            EMPLOYEE_A,
                            EMPLOYEE_A + "-v1",
                            EMPLOYEE_A + "-assignment",
                            "org-1",
                            "org-v1",
                            "PUNCH_CORRECTION",
                            null,
                            OaTemporalShape.POINT,
                            adjustmentInstant,
                            null,
                            null,
                            0,
                            "APPROVED",
                            "hr-v1",
                            NOW,
                            null,
                            null,
                            null,
                            adjustmentId,
                            "ENTRY")));
            session.commit();
        }

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_report_hr_paper_fact WHERE hr_adjustment_id = ?",
                Long.class, adjustmentId)).isEqualTo(1);
    }

    @Test
    void utcCrossDayDayTypeValidationWorks() {
        String projectionId = createProjection("proj-utc-cross");
        // Shanghai Sept 9 00:00:00 = UTC Sept 8 16:00:00 (previous day in UTC)
        Instant shanghaiMidnight = ZonedDateTime.of(2026, 9, 9, 0, 0, 0, 0, SHANGHAI)
                .toInstant(); // This is UTC 2026-09-08T16:00:00Z
        Instant nextMidnight = shanghaiMidnight.plus(java.time.Duration.ofDays(1));

        String adjustmentId = seedAdjustment(EMPLOYEE_A, SEPT_9, null, null, "SICK_LEAVE");
        String sourceKey = generateSourceDocumentKey("DAY_TYPE:SICK_LEAVE", adjustmentId);

        try (SqlSession session = sessions.openSession()) {
            AttendanceReportProjectionWriteMapper mapper = session.getMapper(
                    AttendanceReportProjectionWriteMapper.class);
            MyBatisAttendanceReportProjectionWriter sessionWriter =
                    new MyBatisAttendanceReportProjectionWriter(mapper,
                            JsonMapper.builder().build(), 500);

            sessionWriter.appendOaDocumentFacts(List.of(
                    new AttendanceReportProjectionWriter.OaDocumentFactWrite(
                            UUID.randomUUID().toString(),
                            projectionId,
                            COMPANY,
                            sourceKey,
                            EMPLOYEE_A,
                            EMPLOYEE_A + "-v1",
                            EMPLOYEE_A + "-assignment",
                            "org-1",
                            "org-v1",
                            "LEAVE",
                            "SICK",
                            OaTemporalShape.INTERVAL,
                            null,
                            shanghaiMidnight,
                            nextMidnight,
                            0,
                            "APPROVED",
                            "hr-v1",
                            NOW,
                            null,
                            null,
                            null,
                            adjustmentId,
                            "DAY_TYPE:SICK_LEAVE")));
            session.commit();
        }

        // Verify row written to HR paper fact table
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_report_hr_paper_fact WHERE hr_adjustment_id = ?",
                Long.class, adjustmentId)).isEqualTo(1);

        // Verify connection is UTC and row business_date matches Shanghai date (2026-09-09)
        String sessionTz = jdbc.queryForObject(
                "SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME = 'TIME ZONE'",
                String.class);
        assertThat(sessionTz).isEqualTo("UTC");

        LocalDate storedBusinessDate = jdbc.queryForObject(
                "SELECT business_date FROM attendance_hr_punch_adjustment WHERE adjustment_id = ?",
                LocalDate.class, adjustmentId);
        assertThat(storedBusinessDate).isEqualTo(SEPT_9);
    }

    @Test
    void employeeIsolationPreventsWrongEmployeeValidation() {
        String projectionId = createProjection("proj-isolation");
        String adjustmentA = seedAdjustment(EMPLOYEE_A, SEPT_9, instant(SEPT_9, 9, 0), null, null);
        String adjustmentB = seedAdjustment(EMPLOYEE_B, SEPT_9, instant(SEPT_9, 10, 0), null, null);
        String sourceKey = generateSourceDocumentKey("ENTRY", adjustmentB);

        try (SqlSession session = sessions.openSession()) {
            AttendanceReportProjectionWriteMapper mapper = session.getMapper(
                    AttendanceReportProjectionWriteMapper.class);
            MyBatisAttendanceReportProjectionWriter sessionWriter =
                    new MyBatisAttendanceReportProjectionWriter(mapper,
                            JsonMapper.builder().build(), 500);

            // Try to use adjustmentB (employee B) to write employee A's fact
            sessionWriter.appendOaDocumentFacts(List.of(
                    new AttendanceReportProjectionWriter.OaDocumentFactWrite(
                            UUID.randomUUID().toString(),
                            projectionId,
                            COMPANY,
                            sourceKey,
                            EMPLOYEE_A, // Wrong employee
                            EMPLOYEE_A + "-v1",
                            EMPLOYEE_A + "-assignment",
                            "org-1",
                            "org-v1",
                            "PUNCH_CORRECTION",
                            null,
                            OaTemporalShape.POINT,
                            instant(SEPT_9, 10, 0),
                            null,
                            null,
                            0,
                            "APPROVED",
                            "hr-v1",
                            NOW,
                            null,
                            null,
                            null,
                            adjustmentB,
                            "ENTRY")));
            session.commit();
        }

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_report_hr_paper_fact WHERE hr_adjustment_id = ?",
                Long.class, adjustmentB)).isZero();
    }

    @Test
    void asOfTemporalFilterRejectsAdjustmentCreatedAfterAsOf() {
        String projectionId = createProjection("proj-asof");
        Instant adjustmentCreatedAt = NOW.plusSeconds(3600); // Created after NOW
        String adjustmentId = seedAdjustmentWithTimestamp(EMPLOYEE_A, SEPT_9,
                instant(SEPT_9, 9, 0), null, null, adjustmentCreatedAt);
        String sourceKey = generateSourceDocumentKey("ENTRY", adjustmentId);

        try (SqlSession session = sessions.openSession()) {
            AttendanceReportProjectionWriteMapper mapper = session.getMapper(
                    AttendanceReportProjectionWriteMapper.class);
            MyBatisAttendanceReportProjectionWriter sessionWriter =
                    new MyBatisAttendanceReportProjectionWriter(mapper,
                            JsonMapper.builder().build(), 500);

            sessionWriter.appendOaDocumentFacts(List.of(
                    new AttendanceReportProjectionWriter.OaDocumentFactWrite(
                            UUID.randomUUID().toString(),
                            projectionId,
                            COMPANY,
                            sourceKey,
                            EMPLOYEE_A,
                            EMPLOYEE_A + "-v1",
                            EMPLOYEE_A + "-assignment",
                            "org-1",
                            "org-v1",
                            "PUNCH_CORRECTION",
                            null,
                            OaTemporalShape.POINT,
                            instant(SEPT_9, 9, 0),
                            null,
                            null,
                            0,
                            "APPROVED",
                            "hr-v1",
                            NOW, // data_as_of is NOW, adjustment created after
                            null,
                            null,
                            null,
                            adjustmentId,
                            "ENTRY")));
            session.commit();
        }

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_report_hr_paper_fact",
                Long.class)).isZero();
    }

    @Test
    void reversedAdjustmentWritesZeroRows() {
        String projectionId = createProjection("proj-reversed");
        Instant reversedAt = NOW.minusSeconds(3600);
        String adjustmentId = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO attendance_hr_punch_adjustment VALUES (?, ?, ?, ?, ?, NULL, NULL, 'test', ?, ?, ?, ?)",
                adjustmentId, COMPANY, EMPLOYEE_A, SEPT_9, instant(SEPT_9, 9, 0),
                PRINCIPAL, NOW.minusSeconds(7200), reversedAt, PRINCIPAL);
        String sourceKey = generateSourceDocumentKey("ENTRY", adjustmentId);

        try (SqlSession session = sessions.openSession()) {
            AttendanceReportProjectionWriteMapper mapper = session.getMapper(
                    AttendanceReportProjectionWriteMapper.class);
            MyBatisAttendanceReportProjectionWriter sessionWriter =
                    new MyBatisAttendanceReportProjectionWriter(mapper,
                            JsonMapper.builder().build(), 500);

            sessionWriter.appendOaDocumentFacts(List.of(
                    new AttendanceReportProjectionWriter.OaDocumentFactWrite(
                            UUID.randomUUID().toString(),
                            projectionId,
                            COMPANY,
                            sourceKey,
                            EMPLOYEE_A,
                            EMPLOYEE_A + "-v1",
                            EMPLOYEE_A + "-assignment",
                            "org-1",
                            "org-v1",
                            "PUNCH_CORRECTION",
                            null,
                            OaTemporalShape.POINT,
                            instant(SEPT_9, 9, 0),
                            null,
                            null,
                            0,
                            "APPROVED",
                            "hr-v1",
                            NOW,
                            null,
                            null,
                            null,
                            adjustmentId,
                            "ENTRY")));
            session.commit();
        }

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_report_hr_paper_fact",
                Long.class)).isZero();
    }

    private void seedFixtures() {
        jdbc.update("MERGE INTO company KEY(company_id) VALUES (?)", COMPANY);
        jdbc.update("MERGE INTO attendance_source KEY(attendance_source_id) VALUES ('oa-source', ?, 'OA_ATTENDANCE')", COMPANY);
        jdbc.update("MERGE INTO auth_principal KEY(principal_id) VALUES (?, 'ACTIVE')", PRINCIPAL);
        jdbc.update("MERGE INTO organization_identity KEY(organization_id) VALUES ('org-1', ?, NULL)", COMPANY);
        jdbc.update("INSERT INTO organization_version VALUES ('org-v1', 'org-1', 'Production', TIMESTAMP '2026-01-01 00:00:00', NULL)");

        for (String employee : List.of(EMPLOYEE_A, EMPLOYEE_B)) {
            jdbc.update("INSERT INTO employee VALUES (?, ?, ?)", employee, COMPANY, employee + "-num");
            jdbc.update("INSERT INTO employee_version VALUES (?, ?, ?, ?, DATE '2026-01-01', NULL)",
                    employee + "-v1", employee, employee + "-num", employee + "-name");
            jdbc.update("INSERT INTO employment_assignment VALUES (?, ?, 'org-1', TIMESTAMP '2026-01-01 00:00:00', NULL)",
                    employee + "-assignment", employee);
        }
    }

    private String createProjection(String seed) {
        String projectionId = UUID.randomUUID().toString();
        // Use proper 64-character SHA-256 hex digest
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(seed.getBytes(StandardCharsets.UTF_8));
            String digest = HexFormat.of().formatHex(hash);
            jdbc.update(
                    "INSERT INTO attendance_report_projection VALUES (?, ?, DATE '2026-09-01', DATE '2026-10-01', 'OPEN', ?, 'formula-v1', ?, ?, ?, 'DRAFT', ?, NULL, ?, ?)",
                    projectionId, COMPANY, seed + "-version",
                    "{\"sources\":[\"src-1\"]}", digest, digest,
                    NOW, PRINCIPAL, NOW);
            return projectionId;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String generateSourceDocumentKey(String kind, String adjustmentId) {
        // Generate stable UUID from kind+adjustmentId to fit VARCHAR(36)
        // ENTRY/EXIT/DAY_TYPE prefix would exceed 36 chars, so use UUID namespace
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((kind + ":" + adjustmentId).getBytes(StandardCharsets.UTF_8));
            return UUID.nameUUIDFromBytes(hash).toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String seedAdjustment(String employee, LocalDate date, Instant onDuty, Instant offDuty, String dayTypes) {
        return seedAdjustmentWithTimestamp(employee, date, onDuty, offDuty, dayTypes, NOW.minusSeconds(3600));
    }

    private String seedAdjustmentWithTimestamp(String employee, LocalDate date, Instant onDuty,
            Instant offDuty, String dayTypes, Instant createdAt) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO attendance_hr_punch_adjustment VALUES (?, ?, ?, ?, ?, ?, ?, 'test reason', ?, ?, NULL, NULL)",
                id, COMPANY, employee, date, onDuty, offDuty, dayTypes, PRINCIPAL, createdAt);
        return id;
    }

    private String seedOaDocument(String businessKey, String documentType, String employee) {
        String id = UUID.randomUUID().toString();
        // Use separate UUIDs for raw and normalized IDs to fit VARCHAR(36)
        String rawId = UUID.randomUUID().toString();
        String normId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO raw_attendance_fact VALUES (?, ?, 'OA_DOCUMENT', 'oa-source')", rawId, COMPANY);
        jdbc.update("INSERT INTO normalized_attendance_record VALUES (?, ?, 'VALID', 'OA_INTERVAL')", normId, rawId);
        jdbc.update("INSERT INTO employee_match_decision VALUES (?, 'MATCHED', ?)", normId, employee);
        jdbc.update(
                "INSERT INTO oa_attendance_document VALUES (?, ?, ?, ?, 'APPROVED', 'oa-v1', 'oa-source')",
                id, normId, businessKey, documentType);
        return id;
    }

    private Instant instant(LocalDate date, int hour, int minute) {
        return ZonedDateTime.of(date.getYear(), date.getMonthValue(), date.getDayOfMonth(),
                hour, minute, 0, 0, SHANGHAI).toInstant();
    }

    @Test
    void queryPageReadersReturnBothHrAndOaFacts() {
        String projectionId = createProjection("proj-querypage");
        String adjustmentId = seedAdjustment(EMPLOYEE_A, SEPT_9, instant(SEPT_9, 9, 0), null, null);
        String oaDocId = seedOaDocument("PAPER:ot-456", "OVERTIME", EMPLOYEE_A);
        String sourceKey = generateSourceDocumentKey("ENTRY", adjustmentId);

        try (SqlSession session = sessions.openSession()) {
            AttendanceReportProjectionWriteMapper mapper = session.getMapper(
                    AttendanceReportProjectionWriteMapper.class);
            MyBatisAttendanceReportProjectionWriter sessionWriter =
                    new MyBatisAttendanceReportProjectionWriter(mapper,
                            JsonMapper.builder().build(), 500);

            sessionWriter.appendOaDocumentFacts(List.of(
                    new AttendanceReportProjectionWriter.OaDocumentFactWrite(
                            UUID.randomUUID().toString(),
                            projectionId,
                            COMPANY,
                            sourceKey,
                            EMPLOYEE_A,
                            EMPLOYEE_A + "-v1",
                            EMPLOYEE_A + "-assignment",
                            "org-1",
                            "org-v1",
                            "PUNCH_CORRECTION",
                            null,
                            OaTemporalShape.POINT,
                            instant(SEPT_9, 9, 0),
                            null,
                            null,
                            0,
                            "APPROVED",
                            "hr-v1",
                            NOW,
                            null,
                            null,
                            null,
                            adjustmentId,
                            "ENTRY"),
                    new AttendanceReportProjectionWriter.OaDocumentFactWrite(
                            UUID.randomUUID().toString(),
                            projectionId,
                            COMPANY,
                            oaDocId,
                            EMPLOYEE_A,
                            EMPLOYEE_A + "-v1",
                            EMPLOYEE_A + "-assignment",
                            "org-1",
                            "org-v1",
                            "OVERTIME",
                            null,
                            OaTemporalShape.INTERVAL,
                            null,
                            instant(SEPT_9, 18, 0),
                            instant(SEPT_9, 20, 0),
                            120,
                            "APPROVED",
                            "oa-v1",
                            NOW,
                            null,
                            null,
                            "PAPER",
                            null,
                            null)));
            session.commit();
        }

        try (SqlSession session = sessions.openSession()) {
            AttendanceReportQueryPageMapper queryMapper = session.getMapper(
                    AttendanceReportQueryPageMapper.class);
            QueryPageRows.FactQuery query = new QueryPageRows.FactQuery(
                    List.of(projectionId), COMPANY,
                    LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1),
                    List.of(), List.of(EMPLOYEE_A, EMPLOYEE_B),
                    null, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null,
                    0, 100);

            // Test countOaDocuments
            long count = queryMapper.countOaDocuments(query);
            assertThat(count).isEqualTo(2);

            // Test listOaDocuments
            List<QueryPageRows.OaRow> oaRows = queryMapper.listOaDocuments(query);
            assertThat(oaRows).hasSize(2)
                    .anySatisfy(row -> {
                        assertThat(row.documentId()).isEqualTo(sourceKey);
                        assertThat(row.sourceOrigin()).isEqualTo("PAPER");
                    })
                    .anySatisfy(row -> {
                        assertThat(row.documentId()).isEqualTo(oaDocId);
                        assertThat(row.sourceOrigin()).isEqualTo("PAPER");
                    });

            // Test listOaForEmployees
            List<QueryPageRows.OaRow> employeeRows = queryMapper.listOaForEmployees(query);
            assertThat(employeeRows).hasSize(2)
                    .extracting(QueryPageRows.OaRow::documentId)
                    .containsExactlyInAnyOrder(sourceKey, oaDocId);
        }
    }

    private List<ReportRows.OaDocumentRow> readOaFactsForEmployees(String projectionId,
            Collection<String> employeeIds) {
        try (SqlSession session = sessions.openSession()) {
            AttendanceReportMapper mapper = session.getMapper(AttendanceReportMapper.class);
            Instant periodStart = instant(LocalDate.of(2026, 9, 1), 0, 0);
            Instant periodEnd = instant(LocalDate.of(2026, 10, 1), 0, 0);
            return mapper.listAuthorizedOaFactsForEmployees(projectionId, COMPANY,
                    periodStart, periodEnd, null, List.copyOf(employeeIds));
        }
    }
}
