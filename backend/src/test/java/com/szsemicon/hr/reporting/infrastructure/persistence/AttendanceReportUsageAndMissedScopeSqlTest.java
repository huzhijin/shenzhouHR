package com.szsemicon.hr.reporting.infrastructure.persistence;

import com.szsemicon.hr.reporting.application.QueryPageRows;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.HashMap;
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

/** Runs the production statements against UTC evidence and Shanghai business dates. */
class AttendanceReportUsageAndMissedScopeSqlTest {
    private static final String NS = AttendanceReportQueryPageMapper.class.getName() + ".";

    @ParameterizedTest
    @ValueSource(strings = {"COMPENSATORY", "TIME_OFF", "调休", "调休假"})
    void approvedStandardLeaveFormsCountAsTimeOffUsage(String leaveType) throws Exception {
        try (SqlSession session = session()) {
            person(session, "a", "001");
            oa(session, "leave", "a", "LEAVE", leaveType, "APPROVED", "2026-09-28 00:30:00", 300);
            var rows = usage(session, "TIME_OFF");
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().employeeId()).isEqualTo("a");
            assertThat(rows.getFirst().usageMonth()).isEqualTo(9);
            assertThat(rows.getFirst().recognizedMinutes()).isEqualTo(300);
            assertThat(rows.getFirst().kind()).isEqualTo("TIME_OFF");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "UNKNOWN", "DRAFT", "REVOKED", "REJECTED"})
    void nonApprovedLeaveRemainsExcludedFromAccountUsage(String status) throws Exception {
        try (SqlSession session = session()) {
            person(session, "a", "001");
            oa(session, "leave", "a", "LEAVE", "COMPENSATORY", status, "2026-09-28 00:30:00", 300);
            assertThat(usage(session, "TIME_OFF")).isEmpty();
        }
    }

    @Test
    void approvedKindsStaySeparateAndOneDocumentAcrossPinsIsCountedOnce() throws Exception {
        try (SqlSession session = session()) {
            person(session, "a", "001");
            oa(session, "leave", "a", "LEAVE", "COMPENSATORY", "MODIFIED", "2026-09-28 00:30:00", 300);
            oa(session, "annual", "a", "LEAVE", "ANNUAL", "APPROVED", "2026-09-28 00:30:00", 480);
            oa(session, "ot", "a", "OVERTIME", "COMPENSATORY", "SUPPLEMENTED", "2026-09-28 11:30:00", 120);
            oa(session, "direct", "a", "TIME_OFF", null, "APPROVED", "2026-09-29 00:30:00", 60);
            oa(session, "old-pin", "a", "LEAVE", "COMPENSATORY", "APPROVED", "2026-09-28 00:30:00", 900);
            insert(session, "UPDATE attendance_report_oa_fact SET attendance_report_projection_id='old-projection' WHERE attendance_report_oa_fact_id='old-pin'");
            oa(session, "copy", "a", "LEAVE", "COMPENSATORY", "APPROVED", "2026-09-28 00:30:00", 300);
            insert(session, "UPDATE attendance_report_oa_fact SET oa_attendance_document_id='leave' WHERE attendance_report_oa_fact_id='copy'");
            assertThat(usage(session, "TIME_OFF")).extracting(QueryPageRows.MonthlyLeaveUsageRow::kind)
                    .containsExactlyInAnyOrder("TIME_OFF", "COMPENSATORY_OT");
            assertThat(usage(session, "TIME_OFF").stream()
                    .filter(row -> row.kind().equals("TIME_OFF")).findFirst().orElseThrow().recognizedMinutes())
                    .isEqualTo(360);
            assertThat(usage(session, "ANNUAL_LEAVE").getFirst().recognizedMinutes()).isEqualTo(480);
        }
    }

    @Test
    void usageMonthUsesShanghaiDayAndNeverReturnsAnUnauthorizedEmployee() throws Exception {
        try (SqlSession session = session()) {
            person(session, "a", "001"); person(session, "b", "002");
            oa(session, "local-sep", "a", "LEAVE", "COMPENSATORY", "APPROVED", "2026-08-31 16:00:00", 60);
            oa(session, "other", "b", "LEAVE", "COMPENSATORY", "APPROVED", "2026-09-28 00:30:00", 300);
            assertThat(usage(session, "TIME_OFF")).hasSize(1);
            assertThat(usage(session, "TIME_OFF").getFirst().usageMonth()).isEqualTo(9);
            Map<String, Object> q = query();q.put("accountType", "TIME_OFF");q.put("employeeIds", List.of());
            assertThat(session.selectList(NS + "listLeaveUsageDocuments", q)).isEmpty();
        }
    }

    @Test
    void makeupCandidatesRespectHalfOpenShanghaiPointDatesAndAuthorization() throws Exception {
        try (SqlSession session = session()) {
            person(session, "a", "001"); person(session, "b", "002");
            person(session, "before", "003"); person(session, "after", "004");
            oa(session, "start", "a", "PUNCH_CORRECTION", null, "PENDING", "2026-08-31 16:00:00", 0);
            oa(session, "other", "b", "PUNCH_CORRECTION", null, "APPROVED", "2026-09-28 00:30:00", 0);
            oa(session, "before", "before", "PUNCH_CORRECTION", null, "APPROVED", "2026-08-31 15:59:59", 0);
            oa(session, "end", "after", "PUNCH_CORRECTION", null, "APPROVED", "2026-09-30 16:00:00", 0);
            Map<String, Object> q = query();q.put("employeeIds", List.of());
            assertMissed(session, q, "a", "b");
            q.put("employeeIds", List.of("a"));assertMissed(session, q, "a");
            q.put("employeeNumber", "002");assertMissed(session, q);
            q.put("employeeNumber", "001");q.put("organizationIds", List.of("other-org"));assertMissed(session, q);
        }
    }

    @Test
    void makeupLegacyPendingMustHaveExactStateZeroVersion() throws Exception {
        try (SqlSession session = session()) {
            person(session, "a", "001"); person(session, "b", "002"); person(session, "c", "003");
            oa(session, "legacy", "a", "PUNCH_CORRECTION", null, "UNKNOWN", "2026-09-28 00:30:00", 0);
            oa(session, "unknown", "b", "PUNCH_CORRECTION", null, "UNKNOWN", "2026-09-28 00:30:00", 0);
            oa(session, "revoked", "c", "PUNCH_CORRECTION", null, "REVOKED", "2026-09-28 00:30:00", 0);
            insert(session, "UPDATE attendance_report_oa_fact SET source_version='2026-09-28T08:30:00:state=0:Asia/Shanghai' WHERE attendance_report_oa_fact_id='legacy'");
            Map<String, Object> q = query();q.put("employeeIds", List.of());
            assertMissed(session, q, "a");
        }
    }

    @Test
    void previousMonthsApprovedSharesCannotReviveARevokedFormInTheSelectedMonth() throws Exception {
        try (SqlSession session = session()) {
            person(session, "a", "001");
            insert(session, "INSERT INTO attendance_report_projection VALUES ('august','company','2026-08-01','2026-09-01')");
            oa(session,"old","a","LEAVE","COMPENSATORY","APPROVED","2026-08-31 00:30:00",960);
            insert(session,"UPDATE attendance_report_oa_fact SET attendance_report_projection_id='august', interval_end='2026-09-01 10:00:00', recognized_minutes_by_date_json=? WHERE attendance_report_oa_fact_id='old'",
                    "{\"2026-08-31\":480,\"2026-09-01\":480}");
            oa(session,"new","a","LEAVE","COMPENSATORY","REVOKED","2026-08-31 00:30:00",960);
            insert(session,"UPDATE attendance_report_oa_fact SET interval_end='2026-09-01 10:00:00', recognized_minutes_by_date_json=? WHERE attendance_report_oa_fact_id='new'",
                    "{\"2026-08-31\":480,\"2026-09-01\":480}");
            Map<String,Object> q=query();q.put("accountType","TIME_OFF");q.put("projectionIds",List.of("august","projection"));
            List<QueryPageRows.OaUsageDocumentRow> rows=session.selectList(NS+"listLeaveUsageDocuments",q);
            assertThat(rows).hasSize(1);
            assertThat(com.szsemicon.hr.reporting.application.OaMonthlyUsageAssembler.aggregate(
                    rows,LocalDate.of(2026,9,1),LocalDate.of(2026,10,1))).isEmpty();
            assertThat(com.szsemicon.hr.reporting.application.OaMonthlyUsageAssembler.aggregate(
                    rows,LocalDate.of(2026,8,1),LocalDate.of(2026,10,1)))
                    .containsExactly(new QueryPageRows.MonthlyLeaveUsageRow("a",8,480,"TIME_OFF"));
        }
    }

    private static void assertMissed(SqlSession s, Map<String, Object> q, String... ids) {
        assertThat(((Number)s.selectOne(NS+"countMissedPunchStatEmployees", q)).longValue()).isEqualTo(ids.length);
        List<QueryPageRows.DirectoryEmployeeRow> rows = s.selectList(NS+"listMissedPunchStatEmployees", q);
        assertThat(rows).extracting(QueryPageRows.DirectoryEmployeeRow::employeeId).containsExactly(ids);
    }
    private static List<QueryPageRows.MonthlyLeaveUsageRow> usage(SqlSession s, String type) {
        Map<String, Object> q = query();q.put("accountType", type);
        return com.szsemicon.hr.reporting.application.OaMonthlyUsageAssembler.aggregate(
                s.selectList(NS+"listLeaveUsageDocuments", q), LocalDate.of(2026,9,1), LocalDate.of(2026,10,1));
    }
    private static Map<String, Object> query() {
        Map<String, Object> q = new HashMap<>();
        q.put("projectionIds", List.of("projection"));q.put("companyId", "company");
        q.put("fromDate", LocalDate.of(2026,9,1));q.put("toDateExclusive", LocalDate.of(2026,10,1));
        q.put("employeeIds", List.of("a"));q.put("offset",0);q.put("limit",100);q.put("exceptionType","MAKEUP");
        return q;
    }
    private static void person(SqlSession s, String id, String number) throws Exception {
        insert(s, "INSERT INTO employee_version VALUES (?,?,?)", id+"-version", number, id);
        insert(s, "INSERT INTO attendance_report_daily_fact VALUES ('projection','company',?,?, 'org','org-version','2026-09-28',0,NULL,NULL)", id,id+"-version");
    }
    private static void oa(SqlSession s, String id, String employee, String type, String leave, String status, String instant, int minutes) throws Exception {
        boolean point = type.equals("PUNCH_CORRECTION");
        insert(s, "INSERT INTO attendance_report_oa_fact (attendance_report_oa_fact_id,oa_attendance_document_id,attendance_report_projection_id,company_id,employee_id,employee_version_id,organization_id,document_type,leave_type_code,source_status,source_version,interval_start,interval_end,point_instant,recognized_minutes) VALUES (?,?,'projection','company',?,?,'org',?,?,?,'opaque',?,?,?,?)",
                id,id,employee,employee+"-version",type,leave,status,
                point?null:instant,point?null:instant.replace("00:30:00", "01:30:00").replace("11:30:00", "13:30:00").replace("16:00:00", "17:00:00"),point?instant:null,minutes);
    }
    private static void insert(SqlSession s, String sql, Object... args) throws Exception {
        try(var p=s.getConnection().prepareStatement(sql)){for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);p.executeUpdate();}
        s.clearCache();
    }
    private static SqlSession session() throws Exception {
        Configuration c = new Configuration();
        c.setEnvironment(new Environment("usage-missed",new JdbcTransactionFactory(),new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:usage-missed-"+UUID.randomUUID()+";MODE=MySQL", "sa", "")));
        String resource="mappers/AttendanceReportQueryPageMapper.xml";
        try(var in=Resources.getResourceAsStream(resource)){new XMLMapperBuilder(in,c,resource,c.getSqlFragments()).parse();}
        SqlSession s=new SqlSessionFactoryBuilder().build(c).openSession(true);
        for(String ddl:List.of("SET TIME ZONE 'UTC'",
                "CREATE TABLE attendance_report_projection(attendance_report_projection_id VARCHAR(64),company_id VARCHAR(64),period_start DATE,period_end_exclusive DATE)",
                "INSERT INTO attendance_report_projection VALUES('projection','company','2026-09-01','2026-10-01')",
                "CREATE TABLE employee_version(employee_version_id VARCHAR(64) PRIMARY KEY,employee_number VARCHAR(64),display_name VARCHAR(64))",
                "CREATE TABLE organization_version(organization_version_id VARCHAR(64) PRIMARY KEY,name VARCHAR(64))",
                "INSERT INTO organization_version VALUES('org-version','Department')",
                "CREATE TABLE attendance_report_daily_fact(attendance_report_projection_id VARCHAR(64),company_id VARCHAR(64),employee_id VARCHAR(64),employee_version_id VARCHAR(64),organization_id VARCHAR(64),organization_version_id VARCHAR(64),business_date DATE,missing_punch_count INT,first_punch_at TIMESTAMP,last_punch_at TIMESTAMP)",
                "CREATE TABLE attendance_report_oa_fact(attendance_report_oa_fact_id VARCHAR(64),oa_attendance_document_id VARCHAR(64),attendance_report_projection_id VARCHAR(64),company_id VARCHAR(64),employee_id VARCHAR(64),employee_version_id VARCHAR(64),organization_id VARCHAR(64),document_type VARCHAR(32),leave_type_code VARCHAR(32),source_status VARCHAR(32),source_version VARCHAR(160),interval_start TIMESTAMP,interval_end TIMESTAMP,point_instant TIMESTAMP,recognized_minutes BIGINT,recognized_minutes_by_date_json VARCHAR(8000), allocation_semantics VARCHAR(32))")) insert(s,ddl);
        return s;
    }
}
