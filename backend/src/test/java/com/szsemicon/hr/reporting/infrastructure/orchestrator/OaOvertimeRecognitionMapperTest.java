package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.OaOvertimeRecognitionRow;
import java.time.*;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OaOvertimeRecognitionMapperTest {
    private static final String NS = AttendanceReportCalculationMapper.class.getName() + ".";
    private static final Instant KNOWN = Instant.parse("2026-10-06T10:00:00Z");
    private static final YearMonth PERIOD = YearMonth.of(2026, 9);

    @Test void readsOnlyKnownCompanyMonthDecisionsAndRetainsReversalHistory() throws Exception {
        try (var s = session()) {
            assertThat(rows(s, KNOWN.minusNanos(1))).isEmpty();
            assertThat(rows(s, KNOWN)).singleElement().satisfies(r -> {
                assertThat(r.recognizedMinutes()).isEqualTo(510);
                assertThat(r.overtimeType()).isEqualTo(com.szsemicon.hr.attendance.domain.OvertimeType.PAID);
                assertThat(r.businessDate()).isEqualTo(PERIOD.atDay(5));
                assertThat(r.expectedIntervalStart()).isEqualTo(Instant.parse("2026-09-05T00:30:00Z"));
            });
            execute(s, "UPDATE attendance_report_oa_overtime_recognition SET reversed_at=TIMESTAMP WITH TIME ZONE '2026-10-06 11:00:00+00'");
            assertThat(rows(s, KNOWN)).singleElement().satisfies(r -> assertThat(r.reversedAt()).isEqualTo(KNOWN.plusSeconds(3600)));
            assertThat(rows(s, KNOWN.plusSeconds(3600))).hasSize(1);
            execute(s, "UPDATE attendance_report_oa_overtime_recognition SET company_id='other'");
            assertThat(rows(s, KNOWN)).isEmpty();
            execute(s, "UPDATE attendance_report_oa_overtime_recognition SET company_id='company',business_date=DATE '2026-10-05'");
            assertThat(rows(s, KNOWN)).isEmpty();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"attendance_source_id", "source_business_key", "source_version", "oa_attendance_document_id"})
    void exactImmutableSourceIdentityMustMatch(String field) throws Exception {
        try (var s = session()) {
            execute(s, "UPDATE attendance_report_oa_overtime_recognition SET " + field + "='other'");
            assertThat(rows(s, KNOWN)).isEmpty();
        }
    }

    @Test void partialPinMarkerQueryIsBoundToExactCompanyAndPublishedProjection() throws Exception {
        try (var s = session()) {
            var bound = s.getConfiguration().getMappedStatement(NS + "findRecognitionMarkersForProjection")
                    .getBoundSql(Map.of("companyId", "company", "projectionVersion", "prior"));
            String sql = bound.getSql().replaceAll("\\s+", " ");
            assertThat(sql).contains("CROSS JOIN JSON_TABLE", "projection.company_id = ?", "projection.projection_version = ?",
                    "projection.status = 'PUBLISHED'", "marker.version_marker LIKE 'DECISION.OT:%'");
            assertThat(bound.getParameterMappings()).extracting(p -> p.getProperty()).containsExactly("companyId", "projectionVersion");
        }
    }

    @Test void freshnessAdapterUsesSameCompanyMonthCutoffAndDecisionFingerprint() throws Exception {
        try (var s = session()) {
            var mapper = mock(AttendanceReportCalculationMapper.class);
            var decision = rows(s, KNOWN);
            when(mapper.findOaOvertimeRecognitions("company", PERIOD.atDay(1), PERIOD.plusMonths(1).atDay(1), KNOWN)).thenReturn(decision);
            assertThat(new AttendanceReportDecisionVersions(mapper).versions("company", PERIOD, KNOWN))
                    .isEqualTo(OaOvertimeRecognition.markers(decision, KNOWN));
            verify(mapper).findOaOvertimeRecognitions("company", PERIOD.atDay(1), PERIOD.plusMonths(1).atDay(1), KNOWN);
        }
    }

    private static List<OaOvertimeRecognitionRow> rows(SqlSession session, Instant cutoff) {
        return session.selectList(NS + "findOaOvertimeRecognitions", Map.of("companyId", "company", "periodStart", PERIOD.atDay(1),
                "periodEndExclusive", PERIOD.plusMonths(1).atDay(1), "dataAsOf", cutoff));
    }
    private static void execute(SqlSession session, String sql) throws Exception {
        try (var statement = session.getConnection().createStatement()) { statement.execute(sql); }
        session.clearCache();
    }
    private static SqlSession session() throws Exception {
        var configuration = new Configuration(new Environment("test", new JdbcTransactionFactory(),
                new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:recognition-" + UUID.randomUUID() + ";MODE=MySQL", "sa", "")));
        String resource = "mappers/AttendanceReportCalculationMapper.xml";
        try (var stream = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(stream, configuration, resource, configuration.getSqlFragments()).parse();
        }
        var s = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        execute(s, "SET TIME ZONE 'UTC'");
        execute(s, "CREATE TABLE oa_attendance_document (oa_attendance_document_id VARCHAR(64),attendance_source_id VARCHAR(64),source_business_key VARCHAR(191),source_version VARCHAR(128))");
        execute(s, "CREATE TABLE attendance_report_oa_overtime_recognition (recognition_id VARCHAR(64),company_id VARCHAR(64),employee_id VARCHAR(64),attendance_source_id VARCHAR(64),source_business_key VARCHAR(191),source_version VARCHAR(128),oa_attendance_document_id VARCHAR(64),business_date DATE,expected_interval_start TIMESTAMP WITH TIME ZONE,expected_interval_end TIMESTAMP WITH TIME ZONE,overtime_type VARCHAR(32),recognized_minutes INT,knowledge_at TIMESTAMP WITH TIME ZONE,reversed_at TIMESTAMP WITH TIME ZONE,user_confirmation_sha256 VARCHAR(64),evidence_sha256 VARCHAR(64),request_id VARCHAR(64))");
        execute(s, "INSERT INTO oa_attendance_document VALUES ('doc','source','OVERTIME:1','v1')");
        execute(s, "INSERT INTO attendance_report_oa_overtime_recognition VALUES ('recognition','company','employee','source','OVERTIME:1','v1','doc',DATE '2026-09-05',TIMESTAMP WITH TIME ZONE '2026-09-05 00:30:00+00',TIMESTAMP WITH TIME ZONE '2026-09-05 10:00:00+00','PAID',510,TIMESTAMP WITH TIME ZONE '2026-10-06 10:00:00+00',NULL,'a','b','request')");
        return s;
    }
}
