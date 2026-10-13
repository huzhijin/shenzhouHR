package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class PunchLocationMapperTest {
    @Test
    void currentOwnerSurvivesIdentityReplayAndRetractionRemovesLocation() throws Exception {
        Configuration config = new Configuration();
        String resource = "mappers/PunchLocationMapper.xml";
        try (var input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        try (var db = DriverManager.getConnection("jdbc:h2:mem:punch-location;MODE=MySQL")) {
            var ddl = db.createStatement();
            ddl.execute("CREATE TABLE raw_attendance_fact(raw_attendance_fact_id VARCHAR, company_id VARCHAR, source_instant TIMESTAMP, verification_method VARCHAR, location_summary VARCHAR, source_coordinate_system VARCHAR, coordinate_validation_status VARCHAR, coordinate_conversion_status VARCHAR, map_longitude DECIMAL, map_latitude DECIMAL, map_coordinate_system VARCHAR)");
            ddl.execute("CREATE TABLE effective_attendance_event(effective_attendance_event_id VARCHAR, employee_id VARCHAR, point_instant TIMESTAMP, company_id VARCHAR)");
            ddl.execute("CREATE TABLE evidence_link(raw_attendance_fact_id VARCHAR, effective_attendance_event_id VARCHAR, employee_match_decision_id VARCHAR, link_type VARCHAR, evidence_link_id VARCHAR)");
            ddl.execute("CREATE TABLE employee_match_decision(employee_match_decision_id VARCHAR, employee_id VARCHAR)");
            ddl.execute("CREATE TABLE effective_event_lifecycle_fact(effective_attendance_event_id VARCHAR, lifecycle_type VARCHAR, knowledge_at TIMESTAMP, effective_event_lifecycle_fact_id VARCHAR)");
            ddl.execute("INSERT INTO raw_attendance_fact VALUES('raw','company',TIMESTAMP '2026-09-15 01:00:00','gps','address','UNKNOWN','UNKNOWN_SYSTEM','NOT_APPLICABLE',NULL,NULL,NULL)");
            ddl.execute("INSERT INTO effective_attendance_event VALUES('old','wrong',TIMESTAMP '2026-09-15 01:00:00','company'),('new','correct',TIMESTAMP '2026-09-15 01:00:00','company')");
            ddl.execute("INSERT INTO evidence_link VALUES('raw','old','match-old','PRIMARY','000'),('raw','new','match-new','PRIMARY','999')");
            ddl.execute("INSERT INTO effective_event_lifecycle_fact VALUES('old','ACTIVATED',TIMESTAMP '2026-09-15 01:00:00','1'),('old','SUPERSEDED',TIMESTAMP '2026-09-15 02:00:00','2'),('new','ACTIVATED',TIMESTAMP '2026-09-15 02:00:00','3')");
            var location = config.getMappedStatement(PunchLocationMapper.class.getName() + ".findLocation");
            var params = Map.of("rawFactId", "raw");
            var sql = location.getBoundSql(params);
            try (var query = db.prepareStatement(sql.getSql())) {
                new DefaultParameterHandler(location, params, sql).setParameters(query);
                try (var rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString("employeeId")).isEqualTo("correct");
                    assertThat(rows.next()).isFalse();
                }
                ddl.execute("INSERT INTO effective_event_lifecycle_fact VALUES('new','RETRACTED',TIMESTAMP '2026-09-15 03:00:00','4')");
                try (var rows = query.executeQuery()) {
                    assertThat(rows.next()).isFalse();
                }
            }
            var list = config.getMappedStatement(PunchLocationMapper.class.getName() + ".listDayPunches");
            var listParams = Map.of("companyId", "company", "employeeId", "wrong",
                    "windowStart", Instant.parse("2026-09-14T16:00:00Z"),
                    "windowEnd", Instant.parse("2026-09-15T16:00:00Z"));
            var listSql = list.getBoundSql(listParams);
            try (var query = db.prepareStatement(listSql.getSql())) {
                new DefaultParameterHandler(list, listParams, listSql).setParameters(query);
                assertThat(query.executeQuery().next()).isFalse();
            }
        }
    }
}
