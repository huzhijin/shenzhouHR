package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.evidenceingestion.application.AttendanceSourceReadModels.OaDocumentView;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.List;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

class AttendanceSourceReadOaMappingTest {

    @Test
    void pendingCandidateDoesNotApproveRevocationOrUnrecognisedSourceState() throws Exception {
        String xml;
        try (var input = Resources.getResourceAsStream("mappers/AttendanceSourceReadMapper.xml")) {
            xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var match = Pattern.compile("CASE WHEN document\\.source_status IN.*?END AS effective_candidate",
                Pattern.DOTALL).matcher(xml);
        assertThat(match.find()).isTrue();
        String candidate = match.group().replace("&lt;", "<").replace("&gt;", ">");
        String sql = "SELECT case_id, " + candidate + """
                 FROM (VALUES
                     ('approved', 'LEAVE', 'APPROVED', 'v1'),
                     ('pending-leave', 'LEAVE', 'PENDING', 'v1'),
                     ('pending-revocation', 'LEAVE_REVOCATION', 'PENDING', 'v1'),
                     ('legacy-pending', 'LEAVE', 'UNKNOWN', '2026-09-09T08:30:00:state=0:Asia/Shanghai'),
                     ('legacy-pending-revocation', 'LEAVE_REVOCATION', 'UNKNOWN', '2026-09-09T08:30:00:state=0:Asia/Shanghai'),
                     ('unknown-state', 'LEAVE', 'UNKNOWN', '2026-09-09T08:30:00:state=1:Asia/Shanghai'),
                     ('malformed-version', 'LEAVE', 'UNKNOWN', 'state=0'),
                     ('revoked', 'LEAVE', 'REVOKED', '2026-09-09T08:30:00:state=0:Asia/Shanghai'),
                     ('approved-revocation', 'LEAVE_REVOCATION', 'APPROVED', 'v1')
                 ) document(case_id, document_type, source_status, source_version)
                """;
        Map<String, Boolean> effective = new LinkedHashMap<>();
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:oa-candidate-status");
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            while (result.next()) {
                effective.put(result.getString("case_id"), result.getBoolean("effective_candidate"));
            }
        }
        assertThat(effective).containsExactlyInAnyOrderEntriesOf(Map.of(
                "approved", true, "pending-leave", true, "pending-revocation", false,
                "legacy-pending", true, "legacy-pending-revocation", false,
                "unknown-state", false, "malformed-version", false, "revoked", false,
                "approved-revocation", true));
    }

    @Test
    void readsActualOaResultMapWithNameBeforeTimesAndNullableCredits() throws Exception {
        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("oa-mapping-regression",
                new JdbcTransactionFactory(), new UnpooledDataSource(
                        "org.h2.Driver", "jdbc:h2:mem:oa-mapping-regression", "sa", "")));
        String resource = "mappers/AttendanceSourceReadMapper.xml";
        try (var input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource,
                    configuration.getSqlFragments()).parse();
        }
        var production = configuration.getMappedStatement(
                AttendanceSourceReadMapper.class.getName() + ".listOaDocuments");
        // Exercise the production mapping against a real JDBC result set.
        // A mapper parse or mock repository would miss the name -> Instant bug.
        String projection = """
                SELECT 'document-1' AS document_id,
                       'LEAVE:1' AS source_document_id,
                       'v1' AS source_version,
                       'LEAVE' AS document_type,
                       'APPROVED' AS source_status,
                       1 AS effective_candidate,
                       'E001' AS employee_number,
                       '马振雯' AS employee_name,
                       '生产部' AS department,
                       'PERSONAL' AS leave_type,
                       NULL AS overtime_treatment,
                       NULL AS payroll_credit_minutes,
                       120 AS time_off_credit_minutes,
                       NULL AS recognized_work_minutes,
                       TIMESTAMP WITH TIME ZONE '2026-09-16 00:00:00+00' AS interval_start,
                       TIMESTAMP WITH TIME ZONE '2026-09-16 02:00:00+00' AS interval_end_exclusive
                """;
        configuration.addMappedStatement(new MappedStatement.Builder(configuration,
                "oaMappingProbe", new StaticSqlSource(configuration, projection),
                SqlCommandType.SELECT).resultMaps(production.getResultMaps()).build());
        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession()) {
            List<OaDocumentView> rows = session.selectList("oaMappingProbe");
            assertThat(rows).singleElement().satisfies(row -> {
                assertThat(row.documentId()).isEqualTo("document-1");
                assertThat(row.sourceDocumentId()).isEqualTo("LEAVE:1");
                assertThat(row.sourceVersion()).isEqualTo("v1");
                assertThat(row.documentType()).isEqualTo("LEAVE");
                assertThat(row.sourceStatus()).isEqualTo("APPROVED");
                assertThat(row.effectiveCandidate()).isTrue();
                assertThat(row.employeeNumber()).isEqualTo("E001");
                assertThat(row.employeeName()).isEqualTo("马振雯");
                assertThat(row.department()).isEqualTo("生产部");
                assertThat(row.leaveType()).isEqualTo("PERSONAL");
                assertThat(row.intervalStart()).isEqualTo(Instant.parse("2026-09-16T00:00:00Z"));
                assertThat(row.intervalEndExclusive()).isEqualTo(Instant.parse("2026-09-16T02:00:00Z"));
                assertThat(row.overtimeTreatment()).isNull();
                assertThat(row.payrollCreditMinutes()).isNull();
                assertThat(row.timeOffCreditMinutes()).isEqualTo(120);
                assertThat(row.recognizedWorkMinutes()).isNull();
            });
        }
    }
}
