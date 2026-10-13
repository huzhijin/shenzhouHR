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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Independent execution of the production SQL; no query text is copied into the tests. */
class LegacyLeaveDayEvidenceSqlTest {
    private static final String NS = AttendanceReportQueryPageMapper.class.getName() + ".listLegacyLeaveDayEvidence";

    @Test
    void approvedCrossMonthCandidateReadsOnlyItsOwnPublishedSeptemberDays() throws Exception {
        try (SqlSession s = session()) {
            candidate(s);
            daily(s,"projection","company","a","2026-08-31",480,"COMPENSATORY");
            daily(s,"projection","company","a","2026-09-01",480,"COMPENSATORY");
            daily(s,"projection","company","a","2026-09-02",0,null);
            daily(s,"projection","company","a","2026-09-03",480,"COMPENSATORY");
            daily(s,"projection","company","a","2026-09-04",480,"COMPENSATORY");
            var rows = rows(s,params());
            assertThat(rows).extracting(QueryPageRows.LegacyLeaveDayEvidenceRow::businessDate)
                    .containsExactly(LocalDate.of(2026,9,1),LocalDate.of(2026,9,2),LocalDate.of(2026,9,3),LocalDate.of(2026,9,4));
            assertThat(rows).allSatisfy(r -> {
                assertThat(r.documentId()).isEqualTo("form");
                assertThat(r.employeeId()).isEqualTo("a");
                assertThat(r.projectionStart()).isEqualTo(LocalDate.of(2026,9,1));
                assertThat(r.effectiveDocumentCount()).isEqualTo(1);
            });
            assertThat(rows.get(1).leaveMinutes()).isZero();
            assertThat(rows.get(1).leaveType()).isNull();
        }
    }

    @ParameterizedTest
    @CsvSource({"LEAVE,PERSONAL,APPROVED,opaque", "LEAVE,ANNUAL,PENDING,opaque",
            "LEAVE,COMPENSATORY,MODIFIED,opaque", "TIME_OFF,COMPENSATORY,SUPPLEMENTED,opaque",
            "LEAVE,SICK,UNKNOWN,2026-09-01T08:30:00:state=0:Asia/Shanghai"})
    void everyEffectiveCompetingFormCountsEvenWhenZeroMinutesAndNotInCandidateOrAccountFilter(
            String type,String leave,String status,String version) throws Exception {
        try (SqlSession s = session()) {
            oneDay(s);
            oa(s,"competing","projection","company","a",type,leave,status,version,
                    "2026-09-01 00:30:00","2026-09-01 04:00:00",0,null);
            Map<String,Object> p=params();query(p).put("accountType","TIME_OFF");
            query(p).put("organizationIds",List.of("irrelevant-narrow-filter"));
            assertThat(rows(s,p)).singleElement().satisfies(r -> assertThat(r.effectiveDocumentCount()).isEqualTo(2));
        }
    }

    @ParameterizedTest
    @CsvSource({"REVOKED,opaque", "REJECTED,opaque", "DRAFT,opaque", "CANCELLED,opaque",
            "UNKNOWN,opaque", "UNKNOWN,2026-09-01T08:30:00:state=2:Asia/Shanghai",
            "UNKNOWN,prefix-2026-09-01T08:30:00:state=0:Asia/Shanghai"})
    void ineffectiveOrNonExactLegacyFormsCannotBecomeACompetitor(String status,String version) throws Exception {
        try (SqlSession s = session()) {
            oneDay(s);
            oa(s,"competing","projection","company","a","LEAVE","PERSONAL",status,version,
                    "2026-09-01 00:30:00","2026-09-01 04:00:00",300,null);
            assertThat(rows(s,params())).singleElement().satisfies(r -> assertThat(r.effectiveDocumentCount()).isEqualTo(1));
        }
    }

    @Test
    void otherEmployeeCompanyAndOldPinCannotSupplyOrCompeteForThisEmployeesDay() throws Exception {
        try (SqlSession s = session()) {
            oneDay(s);
            for (String which:List.of("employee","company","pin")) {
                String pin=which.equals("pin")?"old":"projection";
                String company=which.equals("company")?"other-company":"company";
                String employee=which.equals("employee")?"b":"a";
                oa(s,which,pin,company,employee,"LEAVE","ANNUAL","APPROVED","opaque",
                        "2026-09-01 00:30:00","2026-09-01 04:00:00",999,null);
                daily(s,pin,company,employee,"2026-09-01",999,"ANNUAL");
            }
            assertThat(rows(s,params())).singleElement().satisfies(r -> {
                assertThat(r.leaveMinutes()).isEqualTo(480);
                assertThat(r.effectiveDocumentCount()).isEqualTo(1);
            });
        }
    }

    @Test
    void shanghaiMidnightHalfOpenBoundsCountTheCorrectDayOnly() throws Exception {
        try (SqlSession s = session()) {
            candidate(s);
            daily(s,"projection","company","a","2026-09-01",480,"COMPENSATORY");
            daily(s,"projection","company","a","2026-09-02",480,"COMPENSATORY");
            oa(s,"midnight","projection","company","a","LEAVE","PERSONAL","PENDING","opaque",
                    "2026-08-31 16:00:00","2026-09-01 16:00:00",0,null);
            assertThat(rows(s,params())).extracting(QueryPageRows.LegacyLeaveDayEvidenceRow::effectiveDocumentCount)
                    .containsExactly(2L,1L);
        }
    }

    @Test
    void missingDailyFactsRemainMissingRatherThanBeingManufacturedAsZero() throws Exception {
        try (SqlSession s = session()) {
            candidate(s);
            daily(s,"projection","company","a","2026-09-01",480,"COMPENSATORY");
            daily(s,"projection","company","a","2026-09-03",480,"COMPENSATORY");
            assertThat(rows(s,params())).extracting(QueryPageRows.LegacyLeaveDayEvidenceRow::businessDate)
                    .containsExactly(LocalDate.of(2026,9,1),LocalDate.of(2026,9,3));
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"employeeIds","projectionIds","documentIds","companyId"})
    void absentAuthorizationOrCandidatesCannotExpandIntoOtherFacts(String field) throws Exception {
        try (SqlSession s = session()) {
            oneDay(s);
            Map<String,Object> p=params();
            if(field.equals("documentIds")) p.put(field,List.of());
            else query(p).put(field,field.equals("companyId")?"other-company":List.of());
            assertThat(rows(s,p)).isEmpty();
            if(!field.equals("companyId")) {
                if(field.equals("documentIds"))p.put(field,null);else query(p).put(field,null);
                assertThat(rows(s,p)).isEmpty();
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"PENDING,300,NULL", "REVOKED,300,NULL", "APPROVED,0,NULL", "APPROVED,300,{}"})
    void proofCannotBeRequestedForNonApprovedZeroOrAlreadyAllocatedRows(String status,int minutes,String json) throws Exception {
        try (SqlSession s = session()) {
            oneDay(s);
            exec(s,"UPDATE attendance_report_oa_fact SET source_status=?,recognized_minutes=?,recognized_minutes_by_date_json=? WHERE oa_attendance_document_id='form'",
                    status,minutes,json.equals("NULL")?null:json);
            assertThat(rows(s,params())).isEmpty();
        }
    }

    @Test
    void dailyTypeIsReturnedVerbatimForTheIndependentProofToRejectAMismatch() throws Exception {
        try (SqlSession s = session()) {
            candidate(s);daily(s,"projection","company","a","2026-09-01",480,"ANNUAL");
            assertThat(rows(s,params())).singleElement().satisfies(r -> {
                assertThat(r.leaveType()).isEqualTo("ANNUAL");assertThat(r.effectiveDocumentCount()).isEqualTo(1);
            });
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> query(Map<String,Object> p){return (Map<String,Object>)p.get("query");}
    private static Map<String,Object> params(){
        Map<String,Object> q=new HashMap<>();q.put("projectionIds",List.of("projection"));q.put("companyId","company");
        q.put("employeeIds",List.of("a"));q.put("fromDate",LocalDate.of(2026,9,1));q.put("toDateExclusive",LocalDate.of(2026,10,1));
        Map<String,Object> p=new HashMap<>();p.put("query",q);p.put("documentIds",List.of("form"));return p;
    }
    private static List<QueryPageRows.LegacyLeaveDayEvidenceRow> rows(SqlSession s,Map<String,Object> p){return s.selectList(NS,p);}
    private static void candidate(SqlSession s)throws Exception{oa(s,"form","projection","company","a","LEAVE","COMPENSATORY","APPROVED","opaque","2026-08-31 01:00:00","2026-09-04 10:00:00",2400,null);}
    private static void oneDay(SqlSession s)throws Exception{candidate(s);daily(s,"projection","company","a","2026-09-01",480,"COMPENSATORY");}
    private static void daily(SqlSession s,String pin,String company,String employee,String day,int minutes,String type)throws Exception{
        exec(s,"INSERT INTO attendance_report_daily_fact VALUES(?,?,?,?,?,?)",pin,company,employee,day,minutes,type);
    }
    private static void oa(SqlSession s,String id,String pin,String company,String employee,String type,String leave,String status,String version,String start,String end,int minutes,String json)throws Exception{
        exec(s,"INSERT INTO attendance_report_oa_fact VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,id,pin,company,employee,type,leave,status,version,start,end,minutes,json,"org");
    }
    private static void exec(SqlSession s,String sql,Object... args)throws Exception{
        try(var p=s.getConnection().prepareStatement(sql)){for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);p.executeUpdate();}s.clearCache();
    }
    private static SqlSession session()throws Exception{
        Configuration c=new Configuration();
        c.setEnvironment(new Environment("legacy-proof",new JdbcTransactionFactory(),new UnpooledDataSource("org.h2.Driver","jdbc:h2:mem:legacy-proof-"+UUID.randomUUID()+";MODE=MySQL","sa","")));
        String resource="mappers/AttendanceReportQueryPageMapper.xml";
        try(var in=Resources.getResourceAsStream(resource)){new XMLMapperBuilder(in,c,resource,c.getSqlFragments()).parse();}
        SqlSession s=new SqlSessionFactoryBuilder().build(c).openSession(true);
        for(String ddl:List.of("SET TIME ZONE 'UTC'",
                "CREATE TABLE attendance_report_projection(attendance_report_projection_id VARCHAR(64),company_id VARCHAR(64),period_start DATE,period_end_exclusive DATE)",
                "INSERT INTO attendance_report_projection VALUES('projection','company','2026-09-01','2026-10-01')",
                "CREATE TABLE attendance_report_daily_fact(attendance_report_projection_id VARCHAR(64),company_id VARCHAR(64),employee_id VARCHAR(64),business_date DATE,leave_or_time_off_minutes BIGINT,leave_type VARCHAR(64))",
                "CREATE TABLE attendance_report_oa_fact(attendance_report_oa_fact_id VARCHAR(64),oa_attendance_document_id VARCHAR(64),attendance_report_projection_id VARCHAR(64),company_id VARCHAR(64),employee_id VARCHAR(64),document_type VARCHAR(32),leave_type_code VARCHAR(64),source_status VARCHAR(32),source_version VARCHAR(160),interval_start TIMESTAMP,interval_end TIMESTAMP,recognized_minutes BIGINT,recognized_minutes_by_date_json VARCHAR(8000),organization_id VARCHAR(64))"))exec(s,ddl);
        return s;
    }
}
