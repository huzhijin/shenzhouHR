package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OaDailyMinutesPublicationSqlTest {
    private static final String NS=AttendanceReportProjectionWriteMapper.class.getName()+".";
    @ParameterizedTest
    @ValueSource(strings={"insertOaDocumentFact","insertOaDocumentFacts"})
    void singleAndBatchPublicationAndPartialCopyPreserveExactDateShares(String statement)throws Exception {
        try(var session=OaReportLegacyPendingPublicationTest.session()) {
            OaReportLegacyPendingPublicationTest.seedDocument(session,"form","APPROVED","v1");
            var row=OaReportLegacyPendingPublicationTest.row("form","APPROVED","v1");
            String json="{\"2026-09-09\":180}";
            row.put("recognizedMinutesByDateJson",json);
            assertThat(session.insert(NS+statement, statement.endsWith("Facts")?Map.of("rows",List.of(row)):row)).isEqualTo(1);
            try(var sql=session.getConnection().createStatement();var result=sql.executeQuery("SELECT recognized_minutes_by_date_json FROM attendance_report_oa_fact")) {
                assertThat(result.next()).isTrue();assertThat(result.getString(1)).isEqualTo(json);
            }
            var copy=new java.util.HashMap<String,Object>();
            copy.put("sourceProjectionId","projection");copy.put("targetProjectionId","copy");
            copy.put("createdAt",Instant.parse("2026-10-06T00:00:00Z"));
            copy.put("employeeIds",List.of("another-employee"));
            copy.put("windowStart",Instant.parse("2026-09-08T16:00:00Z"));
            copy.put("windowEndExclusive",Instant.parse("2026-09-10T16:00:00Z"));
            assertThat(session.insert(NS+"copyOaDocumentFactsExceptEmployeeWindow",copy)).isEqualTo(1);
            try(var sql=session.getConnection().createStatement();var result=sql.executeQuery("SELECT recognized_minutes_by_date_json FROM attendance_report_oa_fact WHERE attendance_report_projection_id='copy'")) {
                assertThat(result.next()).isTrue();assertThat(result.getString(1)).isEqualTo(json);
            }
        }
    }
}
