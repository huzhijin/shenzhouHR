package com.szsemicon.hr.reporting.infrastructure.persistence;

import com.szsemicon.hr.reporting.application.QueryPageRows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.attendance.domain.LeaveType;
import com.szsemicon.hr.reporting.application.AttendanceReportDependencyPlan;
import com.szsemicon.hr.reporting.application.AttendanceReportFactProjector.ProjectionFacts;
import com.szsemicon.hr.reporting.application.AttendanceReportProjectionPublisher;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.OaTemporalShape;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PeriodState;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PublicationResult;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PublishCommand;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.VerifiedCalculatedFacts;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.VerifiedOaDocumentFact;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.VerifiedProjectionMetadata;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DayType;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionSeverity;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionState;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.PlannedWindowTestFixture;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calculation regressions establish the new minutes; this integration verifies
 * that already-published minutes are actually replaced in the current report.
 * No publisher, writer, mapper, or query is mocked. Each publication commits a
 * real transaction; every assertion reads through a newly opened SQL session.
 */
class AttendanceReportRecalculationPersistenceTest {
    private static final String COMPANY = "company-1";
    private static final String MA = "employee-ma";
    private static final String OTHER = "employee-other";
    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
    private static final LocalDate SEPT_9 = SEPTEMBER.atDay(9);
    private static final LocalDate SEPT_16 = SEPTEMBER.atDay(16);
    private static final Instant NOW = Instant.parse("2026-10-02T08:00:00Z");
    private static final String FORMULA = "FULL_CALCULATION_OA_FORM_HOURS_V8";
    private SqlSessionFactory sessions;
    private JdbcTemplate jdbc;

    @BeforeEach
    void createIsolatedRealMapperDatabase() throws Exception {
        var dataSource = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:republication-" + UUID.randomUUID()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/report-recalculation-persistence-schema.sql"));
        }
        jdbc = new JdbcTemplate(dataSource);
        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("republication", new JdbcTransactionFactory(), dataSource));
        // H2 CAST(varchar AS JSON) stores a JSON string rather than parsing the
        // array as MySQL does. Adapt only this JDBC representation, not SQL or
        // publication behavior. All VARCHAR fields keep their normal handler.
        configuration.getTypeHandlerRegistry().register(String.class, JdbcType.OTHER,
                new H2JsonTextHandler());
        for (String resource : List.of("mappers/AttendanceReportProjectionWriteMapper.xml",
                "mappers/AttendanceReportQueryPageMapper.xml")) {
            try (var input = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, configuration, resource,
                        configuration.getSqlFragments()).parse();
            }
        }
        sessions = new SqlSessionFactoryBuilder().build(configuration);
        seedIdentityAndSource(MA, "001", "马振雯");
        seedIdentityAndSource(OTHER, "002", "另一员工");
        seedOa("oa-ma-9", MA);
        seedOa("oa-ma-16", MA);
        seedOa("oa-other", OTHER);
    }

    @Test
    void employeeWindowRecalculationReplacesOldSeptemberFactsAndPreservesOthers() {
        var old9 = daily(MA, SEPT_9, 180, true);
        var old16 = daily(MA, SEPT_16, 180, true);
        var other = daily(OTHER, SEPT_9, 60, true);
        var original = publish(command("a", 30, List.of(old9, old16, other),
                List.of(oa("oa-ma-9", MA, SEPT_9, 180),
                        oa("oa-ma-16", MA, SEPT_16, 180),
                        oa("oa-other", OTHER, SEPT_9, 60))), null, null);
        assertDay(original.projectionId(), MA, SEPT_9, 180, 300);
        assertDay(original.projectionId(), MA, SEPT_16, 180, 300);
        assertCurrentQueries(original.projectionId(), 360, 600, 180, 180);

        // Full approved revocation: replace only Sept 9; Sept 16 stays at 3/5.
        var fullCommand = command("b", 20, List.of(daily(MA, SEPT_9, 0, false)),
                List.of(oa("oa-ma-9", MA, SEPT_9, 0)));
        var full = publish(fullCommand, SEPT_9, SEPT_9.plusDays(1), MA, original.projectionVersion());
        assertThat(full.projectionId()).isNotEqualTo(original.projectionId());
        assertDay(full.projectionId(), MA, SEPT_9, 0, 480);
        assertDay(full.projectionId(), MA, SEPT_16, 180, 300);
        assertDay(full.projectionId(), OTHER, SEPT_9, 60, 420);
        assertCurrentQueries(full.projectionId(), 180, 780, 0, 180);
        assertExceptions(full.projectionId(), List.of(caseId(MA, SEPT_16), caseId(OTHER, SEPT_9)));

        // Partial approved revocation: Sept 16 becomes 2/6; Sept 9 stays 0/8.
        var partialCommand = command("c", 10, List.of(daily(MA, SEPT_16, 120, false)),
                List.of(oa("oa-ma-16", MA, SEPT_16, 120)));
        var partial = publish(partialCommand, SEPT_16, SEPT_16.plusDays(1), MA, full.projectionVersion());
        assertDay(partial.projectionId(), MA, SEPT_9, 0, 480);
        assertDay(partial.projectionId(), MA, SEPT_16, 120, 360);
        assertDay(partial.projectionId(), OTHER, SEPT_9, 60, 420);
        assertCurrentQueries(partial.projectionId(), 120, 840, 0, 120);
        assertExceptions(partial.projectionId(), List.of(caseId(OTHER, SEPT_9)));

        // Old immutable projections are retained for audit, not mixed into the
        // current pin. Replaying the same result cannot add duplicate rows.
        assertDay(original.projectionId(), MA, SEPT_9, 180, 300);
        assertExceptions(original.projectionId(), List.of(
                caseId(MA, SEPT_9), caseId(MA, SEPT_16), caseId(OTHER, SEPT_9)));
        // A retry of this calculation carries the same baseline it used the
        // first time; binding it to its own result would be a new calculation.
        var replay = publish(partialCommand, SEPT_16, SEPT_16.plusDays(1), MA, full.projectionVersion());
        assertThat(replay.created()).isFalse();
        assertThat(replay.projectionId()).isEqualTo(partial.projectionId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance_report_projection", Long.class)).isEqualTo(3);
        assertCurrentQueries(partial.projectionId(), 120, 840, 0, 120);
    }

    @Test
    void wholeMonthRepublicationReadsOnlyTheNewSeptemberProjection() {
        var original = publish(originalMonth(), null, null);
        var replacement = command("b", 20,
                List.of(daily(MA, SEPT_9, 0, false), daily(MA, SEPT_16, 120, false),
                        daily(OTHER, SEPT_9, 60, true)),
                List.of(oa("oa-ma-9", MA, SEPT_9, 0), oa("oa-ma-16", MA, SEPT_16, 120),
                        oa("oa-other", OTHER, SEPT_9, 60)));
        var recalculated = publish(replacement, null, null);

        assertThat(recalculated.projectionId()).isNotEqualTo(original.projectionId());
        assertDay(recalculated.projectionId(), MA, SEPT_9, 0, 480);
        assertDay(recalculated.projectionId(), MA, SEPT_16, 120, 360);
        assertDay(recalculated.projectionId(), OTHER, SEPT_9, 60, 420);
        assertCurrentQueries(recalculated.projectionId(), 120, 840, 0, 120);
        assertExceptions(recalculated.projectionId(), List.of(caseId(OTHER, SEPT_9)));
        assertDay(original.projectionId(), MA, SEPT_9, 180, 300);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance_report_projection", Long.class))
                .isEqualTo(2);
    }

    @Test
    void companyWindowRecalculationPreservesPriorOaAndReplacesBothEmployeesWithoutDuplicatingCrossWindowLeave() {
        var priorDay = SEPT_9.minusDays(1);
        seedOa("oa-prior-day", MA);
        var crossWindowStart = Instant.parse("2026-09-08T15:00:00Z"); // Sept 8, 23:00 Shanghai
        var crossWindowEnd = Instant.parse("2026-09-09T03:00:00Z"); // Sept 9, 11:00 Shanghai
        var priorOa = oa("oa-prior-day", MA, priorDay, 60);
        var original = publish(command("a", 30,
                List.of(daily(MA, priorDay, 60, true), daily(MA, SEPT_9, 180, true),
                        daily(OTHER, SEPT_9, 180, true)),
                List.of(priorOa, oa("oa-ma-9", MA, crossWindowStart, crossWindowEnd, 180),
                        oa("oa-other", OTHER, SEPT_9, 180))), null, null);

        // The company-wide day window has no employee filter. The overlapping
        // original keeps its full interval, but replaces the old OA row once.
        var replacement = command("b", 20,
                List.of(daily(MA, SEPT_9, 0, false), daily(OTHER, SEPT_9, 120, false)),
                List.of(oa("oa-ma-9", MA, crossWindowStart, crossWindowEnd, 0),
                        oa("oa-other", OTHER, SEPT_9, 120)));
        var recalculated = publish(replacement, SEPT_9, SEPT_9.plusDays(1), null);

        assertDay(recalculated.projectionId(), MA, priorDay, 60, 420);
        assertDay(recalculated.projectionId(), MA, SEPT_9, 0, 480);
        assertDay(recalculated.projectionId(), OTHER, SEPT_9, 120, 360);
        assertExceptions(recalculated.projectionId(), List.of(caseId(MA, priorDay)));
        try (var session = sessions.openSession()) {
            var reader = session.getMapper(AttendanceReportQueryPageMapper.class);
            var pin = reader.findLatestPin(COMPANY, SEPTEMBER.atDay(1), SEPTEMBER.plusMonths(1).atDay(1));
            assertThat(pin.projectionId()).isEqualTo(recalculated.projectionId());
            var current = query(pin.projectionId());
            assertThat(reader.listDailyCells(current)).hasSize(3);
            assertThat(reader.listEmployeeDailyAggregates(current)).hasSize(2)
                    .anySatisfy(row -> {
                        assertThat(row.employeeId()).isEqualTo(MA);
                        assertThat(row.leaveMinutes()).isEqualTo(60);
                        assertThat(row.actualWorkMinutes()).isEqualTo(900);
                    }).anySatisfy(row -> {
                        assertThat(row.employeeId()).isEqualTo(OTHER);
                        assertThat(row.leaveMinutes()).isEqualTo(120);
                        assertThat(row.actualWorkMinutes()).isEqualTo(360);
                    });
            var documents = reader.listOaDocuments(current);
            assertThat(documents).hasSize(3)
                    .anySatisfy(row -> {
                        assertThat(row.documentId()).isEqualTo("oa-prior-day");
                        assertThat(row.recognizedMinutes()).isEqualTo(60);
                    }).anySatisfy(row -> {
                        assertThat(row.documentId()).isEqualTo("oa-ma-9");
                        assertThat(row.startAt()).isEqualTo(crossWindowStart);
                        assertThat(row.endExclusive()).isEqualTo(crossWindowEnd);
                        assertThat(row.recognizedMinutes()).isZero();
                    }).anySatisfy(row -> {
                        assertThat(row.documentId()).isEqualTo("oa-other");
                        assertThat(row.recognizedMinutes()).isEqualTo(120);
                    });
            assertThat(documents).extracting(QueryPageRows.OaRow::documentId).doesNotHaveDuplicates();
            assertThat(reader.listLeaveSummary(current)).hasSize(2)
                    .anySatisfy(row -> {
                        assertThat(row.employeeId()).isEqualTo(MA);
                        assertThat(row.recognizedMinutes()).isEqualTo(60);
                        assertThat(row.documentCount()).isEqualTo(2);
                    }).anySatisfy(row -> {
                        assertThat(row.employeeId()).isEqualTo(OTHER);
                        assertThat(row.recognizedMinutes()).isEqualTo(120);
                        assertThat(row.documentCount()).isEqualTo(1);
                    });
        }
        assertDay(original.projectionId(), MA, SEPT_9, 180, 300);
        assertDay(original.projectionId(), OTHER, SEPT_9, 180, 300);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance_report_oa_fact WHERE attendance_report_projection_id = ?", Long.class, recalculated.projectionId()))
                .isEqualTo(3);
    }

    @Test
    void canceledLeaveWithNoReplacementOaFactRemovesStaleCurrentLeaveAndException() {
        var original = publish(originalMonth(), null, null);
        // A canceled/invalid source contributes no OA fact at all. The old
        // published leave still has to disappear from this employee's window.
        var canceled = command("b", 20, List.of(daily(MA, SEPT_9, 0, false)), List.of());
        var recalculated = publish(canceled, SEPT_9, SEPT_9.plusDays(1));

        assertDay(recalculated.projectionId(), MA, SEPT_9, 0, 480);
        assertDay(recalculated.projectionId(), MA, SEPT_16, 180, 300);
        assertDay(recalculated.projectionId(), OTHER, SEPT_9, 60, 420);
        assertExceptions(recalculated.projectionId(), List.of(caseId(MA, SEPT_16), caseId(OTHER, SEPT_9)));
        try (var session = sessions.openSession()) {
            var reader = session.getMapper(AttendanceReportQueryPageMapper.class);
            var pin = reader.findLatestPin(COMPANY, SEPTEMBER.atDay(1), SEPTEMBER.plusMonths(1).atDay(1));
            assertThat(pin.projectionId()).isEqualTo(recalculated.projectionId());
            var current = query(pin.projectionId());
            assertThat(reader.listDailyCells(current)).hasSize(3).anySatisfy(row -> {
                assertThat(row.employeeId()).isEqualTo(MA);
                assertThat(row.businessDate()).isEqualTo(SEPT_9);
                assertThat(row.leaveType()).isNull();
            });
            assertThat(reader.listEmployeeDailyAggregates(current)).hasSize(2).anySatisfy(row -> {
                assertThat(row.employeeId()).isEqualTo(MA);
                assertThat(row.leaveMinutes()).isEqualTo(180);
                assertThat(row.actualWorkMinutes()).isEqualTo(780);
            });
            assertThat(reader.listOaDocuments(current)).extracting(QueryPageRows.OaRow::documentId)
                    .containsExactlyInAnyOrder("oa-ma-16", "oa-other");
            assertThat(reader.listLeaveSummary(current)).hasSize(2).anySatisfy(row -> {
                assertThat(row.employeeId()).isEqualTo(MA);
                assertThat(row.recognizedMinutes()).isEqualTo(180);
                assertThat(row.documentCount()).isEqualTo(1);
            });
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance_report_oa_fact WHERE attendance_report_projection_id = ?", Long.class, recalculated.projectionId()))
                .isEqualTo(2);
        assertDay(original.projectionId(), MA, SEPT_9, 180, 300);
    }

    @Test
    void narrowWindowWithPlannerReplacesOutOfWindowLeaveDailyAndKeepsOtherEmployee() {
        seedOa("oa-ma-21", MA);
        // Old pin: MA has leave on Sept 9 and 21, OTHER has leave on Sept 9
        var old9 = daily(MA, SEPT_9, 180, true);
        var old21 = daily(MA, LocalDate.of(2026, 9, 21), 480, true);
        var oldOther = daily(OTHER, SEPT_9, 60, true);
        var original = publish(command("a", 30,
                List.of(old9, old21, oldOther),
                List.of(oa("oa-ma-9", MA, SEPT_9, 180),
                        oa("oa-ma-21", MA, LocalDate.of(2026, 9, 21), 480),
                        oa("oa-other", OTHER, SEPT_9, 60))), null, null);
        assertDay(original.projectionId(), MA, SEPT_9, 180, 300);
        assertDay(original.projectionId(), MA, LocalDate.of(2026, 9, 21), 480, 0);
        assertDay(original.projectionId(), OTHER, SEPT_9, 60, 420);

        // Use real planner bridge with narrow window (Sept 8-9), leave on 21 fully revoked
        // Planner expands to full month when Sept 21 changed
        var plan = PlannedWindowTestFixture.plan(
                COMPANY,
                SEPTEMBER,
                original.projectionVersion(),
                NOW.minusSeconds(20),
                LocalDate.of(2026, 9, 8),
                LocalDate.of(2026, 9, 9),
                List.of(MA)
        );

        assertThat(plan.writeStartInclusive()).isEqualTo(SEPTEMBER.atDay(1));
        assertThat(plan.writeEndExclusive()).isEqualTo(SEPTEMBER.plusMonths(1).atDay(1));
        assertThat(plan.employeeIds()).containsExactly(MA);

        var revokedCommand = command("b", 20,
                List.of(daily(MA, SEPT_9, 0, false),
                        daily(MA, LocalDate.of(2026, 9, 21), 0, false)),
                List.of(oa("oa-ma-9", MA, SEPT_9, 0),
                        oa("oa-ma-21", MA, LocalDate.of(2026, 9, 21), 0)));

        var recalc = publish(revokedCommand, plan.writeStartInclusive(),
                plan.writeEndExclusive(), MA, original.projectionVersion());

        // New pin: Sept 21 daily replaced (leave 0), Sept 9 replaced, OTHER copied unchanged
        assertDay(recalc.projectionId(), MA, SEPT_9, 0, 480);
        assertDay(recalc.projectionId(), MA, LocalDate.of(2026, 9, 21), 0, 480);
        assertDay(recalc.projectionId(), OTHER, SEPT_9, 60, 420);
        // Old pin preserved
        assertDay(original.projectionId(), MA, LocalDate.of(2026, 9, 21), 480, 0);
    }

    @Test
    void rejectedReplacementRollsBackDraftAndKeepsPreviouslyPublishedReport() {
        var original = publish(originalMonth(), null, null);
        var corrected = daily(MA, SEPT_9, 0, false);
        var invalidReference = new VerifiedCalculatedFacts("nonexistent-employee-version",
                corrected.employmentAssignmentId(), corrected.facts());
        var invalidCommand = command("b", 20, List.of(invalidReference),
                List.of(oa("oa-ma-9", MA, SEPT_9, 0)));

        assertThatThrownBy(() -> publish(invalidCommand, SEPT_9, SEPT_9.plusDays(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("daily report fact references are invalid");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance_report_projection", Long.class))
                .isEqualTo(1);
        assertCurrentQueries(original.projectionId(), 360, 600, 180, 180);
        assertExceptions(original.projectionId(), List.of(
                caseId(MA, SEPT_9), caseId(MA, SEPT_16), caseId(OTHER, SEPT_9)));
    }

    private PublishCommand originalMonth() {
        return command("a", 30,
                List.of(daily(MA, SEPT_9, 180, true), daily(MA, SEPT_16, 180, true),
                        daily(OTHER, SEPT_9, 60, true)),
                List.of(oa("oa-ma-9", MA, SEPT_9, 180), oa("oa-ma-16", MA, SEPT_16, 180),
                        oa("oa-other", OTHER, SEPT_9, 60)));
    }

    private PublicationResult publish(PublishCommand command, LocalDate start, LocalDate end) {
        return publish(command, start, end, MA);
    }

    private PublicationResult publish(PublishCommand command, LocalDate start, LocalDate end, String employeeId) {
        return publish(command, start, end, employeeId, null);
    }

    private PublicationResult publish(
            PublishCommand command, LocalDate start, LocalDate end, String employeeId,
            String expectedBaseProjectionVersion) {
        try (var session = sessions.openSession(false)) {
            // One-row batches use the real batch mapper without H2's inability
            // to infer types for unbound parameters across UNION ALL branches.
            var writer = new MyBatisAttendanceReportProjectionWriter(
                    session.getMapper(AttendanceReportProjectionWriteMapper.class),
                    JsonMapper.builder().build(), 1);
            var publisher = new AttendanceReportProjectionPublisher(writer, Clock.fixed(NOW, ZoneOffset.UTC));
            String baseline = expectedBaseProjectionVersion;
            if (start != null && baseline == null) {
                baseline = writer.findLatestPublished(COMPANY, SEPTEMBER.atDay(1))
                        .orElseThrow().projectionVersion();
            }
            var result = start == null ? publisher.publish(command)
                    : publisher.publishScoped(command, start, end,
                            employeeId == null ? List.of() : List.of(employeeId), baseline);
            session.commit();
            return result;
        }
    }

    private void assertCurrentQueries(String expectedPin, long maLeave, long maWork, long ninth, long sixteenth) {
        try (var session = sessions.openSession()) {
            var reader = session.getMapper(AttendanceReportQueryPageMapper.class);
            var pin = reader.findLatestPin(COMPANY, SEPTEMBER.atDay(1), SEPTEMBER.plusMonths(1).atDay(1));
            assertThat(pin.projectionId()).isEqualTo(expectedPin);
            var query = query(pin.projectionId());
            assertThat(reader.countEmployeeDailyAggregates(query)).isEqualTo(2);
            assertThat(reader.listEmployeeDailyAggregates(query)).hasSize(2)
                    .anySatisfy(row -> {
                        assertThat(row.employeeId()).isEqualTo(MA);
                        assertThat(row.leaveMinutes()).isEqualTo(maLeave);
                        assertThat(row.actualWorkMinutes()).isEqualTo(maWork);
                    }).anySatisfy(row -> {
                        assertThat(row.employeeId()).isEqualTo(OTHER);
                        assertThat(row.leaveMinutes()).isEqualTo(60);
                        assertThat(row.actualWorkMinutes()).isEqualTo(420);
                    });
            assertThat(reader.listDailyCells(query)).hasSize(3).anySatisfy(row -> {
                assertThat(row.employeeId()).isEqualTo(MA);
                assertThat(row.businessDate()).isEqualTo(SEPT_9);
                assertThat(row.leaveType()).isEqualTo(ninth == 0 ? null : "PERSONAL");
            });
            assertThat(reader.listOaDocuments(query)).hasSize(3)
                    .anySatisfy(row -> {
                        assertThat(row.documentId()).isEqualTo("oa-ma-9");
                        assertThat(row.recognizedMinutes()).isEqualTo(ninth);
                    }).anySatisfy(row -> {
                        assertThat(row.documentId()).isEqualTo("oa-ma-16");
                        assertThat(row.recognizedMinutes()).isEqualTo(sixteenth);
                    });
            assertThat(reader.listLeaveSummary(query)).hasSize(2).anySatisfy(row -> {
                assertThat(row.employeeId()).isEqualTo(MA);
                assertThat(row.recognizedMinutes()).isEqualTo(maLeave);
                assertThat(row.documentCount()).isEqualTo(2);
            });
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance_report_daily_fact WHERE attendance_report_projection_id = ?", Long.class, expectedPin)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attendance_report_oa_fact WHERE attendance_report_projection_id = ?", Long.class, expectedPin)).isEqualTo(3);
    }

    private QueryPageRows.FactQuery query(String projectionId) {
        return new QueryPageRows.FactQuery(List.of(projectionId), COMPANY,
                SEPTEMBER.atDay(1), SEPTEMBER.plusMonths(1).atDay(1), List.of(), List.of(MA, OTHER),
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, 0, 100);
    }

    private void assertDay(String projectionId, String employee, LocalDate day, long leave, long work) {
        var rows = jdbc.queryForList("SELECT leave_or_time_off_minutes, actual_work_minutes FROM attendance_report_daily_fact WHERE attendance_report_projection_id = ? AND employee_id = ? AND business_date = ?", projectionId, employee, day);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(((Number) row.get("leave_or_time_off_minutes")).longValue()).isEqualTo(leave);
            assertThat(((Number) row.get("actual_work_minutes")).longValue()).isEqualTo(work);
        });
    }

    private void assertExceptions(String projectionId, List<String> expected) {
        assertThat(jdbc.queryForList("SELECT exception_case_id FROM attendance_report_exception_fact WHERE attendance_report_projection_id = ?", String.class, projectionId))
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    private PublishCommand command(String digest, long secondsBeforeNow,
            List<VerifiedCalculatedFacts> facts, List<VerifiedOaDocumentFact> oa) {
        return new PublishCommand(new VerifiedProjectionMetadata(COMPANY, SEPTEMBER, PeriodState.OPEN,
                FORMULA, List.of("calculation-v1", "oa-v1"), digest.repeat(64), NOW.minusSeconds(secondsBeforeNow), "principal-1"), facts, oa, List.of());
    }

    private VerifiedCalculatedFacts daily(String employee, LocalDate day, long leave, boolean oldException) {
        long work = 480 - leave;
        var fact = new DailyFact(employee + ":" + day, COMPANY, employee,
                MA.equals(employee) ? "001" : "002", MA.equals(employee) ? "马振雯" : "另一员工",
                "org-1", "org-v1", "生产部", day, DayType.WEEKDAY, "白班", 480, work,
                0, 0, 0, 0, 0, leave, 0, work, 1, leave == 0 ? 1 : 0.5,
                0, 0, 0, 0, instant(day), instant(day).plusSeconds(9 * 3600), "calculation-v1",
                "d".repeat(64), leave == 0 ? null : LeaveType.PERSONAL);
        var exceptions = oldException ? List.of(new ExceptionFact(caseId(employee, day), employee,
                fact.employeeNumber(), fact.employeeName(), "org-1", "生产部", day,
                "FAKE_OVERTIME", ExceptionSeverity.WARNING, ExceptionState.OPEN, 60,
                "原因码=FAKE_OVERTIME；证据数量=1", "calculation-v1")) : List.<ExceptionFact>of();
        return new VerifiedCalculatedFacts(employee + "-v1", employee + "-assignment", new ProjectionFacts(fact, exceptions));
    }

    private VerifiedOaDocumentFact oa(String document, String employee, LocalDate day, long minutes) {
        return oa(document, employee, instant(day), instant(day).plusSeconds(3 * 3600), minutes);
    }

    private VerifiedOaDocumentFact oa(String document, String employee, Instant start, Instant end, long minutes) {
        return new VerifiedOaDocumentFact(COMPANY, document, employee, employee + "-v1",
                employee + "-assignment", "org-1", "org-v1", "LEAVE", "PERSONAL", OaTemporalShape.INTERVAL,
                null, start, end, minutes, "APPROVED", "oa-v1");
    }

    private static Instant instant(LocalDate day) {
        return day.atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    private static String caseId(String employee, LocalDate day) {
        return "old-case:" + employee + ":" + day;
    }

    private void seedIdentityAndSource(String employee, String number, String name) {
        jdbc.update("MERGE INTO company KEY(company_id) VALUES (?)", COMPANY);
        jdbc.update("MERGE INTO attendance_source KEY(attendance_source_id) VALUES ('oa-source', ?, 'OA_ATTENDANCE')", COMPANY);
        jdbc.update("MERGE INTO auth_principal KEY(principal_id) VALUES ('principal-1', 'ACTIVE')");
        jdbc.update("MERGE INTO organization_identity KEY(organization_id) VALUES ('org-1', ?)", COMPANY);
        jdbc.update("MERGE INTO organization_version KEY(organization_version_id) VALUES ('org-v1', 'org-1', '生产部', TIMESTAMP '2026-01-01 00:00:00', NULL)");
        jdbc.update("INSERT INTO employee VALUES (?, ?, ?)", employee, COMPANY, number);
        jdbc.update("INSERT INTO employee_version VALUES (?, ?, ?, ?, DATE '2026-01-01', NULL)", employee + "-v1", employee, number, name);
        jdbc.update("INSERT INTO employment_assignment VALUES (?, ?, 'org-1', TIMESTAMP '2026-01-01 00:00:00', NULL)", employee + "-assignment", employee);
    }

    private void seedOa(String document, String employee) {
        jdbc.update("INSERT INTO raw_attendance_fact VALUES (?, ?, 'OA_DOCUMENT', 'oa-source')", document + "-raw", COMPANY);
        jdbc.update("INSERT INTO normalized_attendance_record VALUES (?, ?, 'VALID', 'OA_INTERVAL')", document + "-norm", document + "-raw");
        jdbc.update("INSERT INTO employee_match_decision VALUES (?, 'MATCHED', ?)", document + "-norm", employee);
        jdbc.update("INSERT INTO oa_attendance_document VALUES (?, ?, ?, 'LEAVE', 'APPROVED', 'oa-v1', 'oa-source')", document, document + "-norm", "LEAVE:" + document);
    }

    public static final class H2JsonTextHandler extends BaseTypeHandler<String> {
        @Override public void setNonNullParameter(PreparedStatement ps, int i, String value, JdbcType jdbcType) throws SQLException { ps.setString(i, value); }
        @Override public String getNullableResult(ResultSet rs, String name) throws SQLException { return jsonText(rs.getString(name)); }
        @Override public String getNullableResult(ResultSet rs, int index) throws SQLException { return jsonText(rs.getString(index)); }
        @Override public String getNullableResult(CallableStatement cs, int index) throws SQLException { return jsonText(cs.getString(index)); }
        private String jsonText(String json) {
            if (json == null || !json.startsWith("\"")) return json;
            return JsonMapper.builder().build().readValue(json, String.class);
        }
    }
}
