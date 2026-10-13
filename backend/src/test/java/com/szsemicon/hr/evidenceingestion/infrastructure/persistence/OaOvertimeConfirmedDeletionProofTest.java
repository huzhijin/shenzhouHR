package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.evidenceingestion.application.MissingOaReportScopeProof;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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

/**
 * Tests OVERTIME confirmed source deletion exclusions work correctly with employee/company proof.
 * Unlike PUNCH_CORRECTION which always needs exclusion, OVERTIME without ACTIVATED context is already provable.
 * Exclusions are needed when OVERTIME has ACTIVATED context (requires current source) but source was deleted.
 */
class OaOvertimeConfirmedDeletionProofTest {
    private static final String COMPANY = "target-company";
    private static final List<String> TARGETS = List.of("target");
    private static final String KEY = "OVERTIME:100";
    private static final Instant AS_OF = Instant.parse("2026-10-06T00:00:00Z");

    @Test
    void activatedOvertimeWithoutExclusionBlocksEmployeeProof() throws Exception {
        try (SqlSession session = session()) {
            observation(session, "old", "shared", KEY, "OVERTIME", "target");
            execute(session, "UPDATE oa_attendance_document SET source_status='UNKNOWN'");
            insert(session, "INSERT INTO oa_attendance_document_context VALUES ('oa-old','ACTIVATED','PAID')");
            session.clearCache();

            assertThat(proven(session, KEY)).isEmpty();
        }
    }

    @Test
    void exactExclusionResolvesActivatedOvertimeEmployeeBlocking() throws Exception {
        try (SqlSession session = session()) {
            observation(session, "old", "shared", KEY, "OVERTIME", "target");
            execute(session, "UPDATE oa_attendance_document SET source_status='UNKNOWN'");
            insert(session, "INSERT INTO oa_attendance_document_context VALUES ('oa-old','ACTIVATED','PAID')");
            session.clearCache();

            exclusion(session, KEY, "v-old");
            assertThat(proven(session, KEY)).containsExactly(KEY);
        }
    }

    @Test
    void newVersionExclusionDoesNotInheritFromOldVersion() throws Exception {
        try (SqlSession session = session()) {
            observation(session, "old", "shared", KEY, "OVERTIME", "target");
            execute(session, "UPDATE oa_attendance_document SET source_status='UNKNOWN'");
            insert(session, "INSERT INTO oa_attendance_document_context VALUES ('oa-old','ACTIVATED','PAID')");
            exclusion(session, KEY, "v-old");

            observation(session, "new", "shared", KEY, "OVERTIME", "target");
            execute(session, "UPDATE oa_attendance_document SET knowledge_rank=2,source_status='UNKNOWN',source_version='v-new' WHERE oa_attendance_document_id='oa-new'");
            execute(session, "UPDATE raw_attendance_fact SET source_version='v-new' WHERE raw_attendance_fact_id='raw-new'");
            insert(session, "INSERT INTO oa_attendance_document_context VALUES ('oa-new','ACTIVATED','PAID')");
            session.clearCache();

            assertThat(proven(session, KEY)).isEmpty();
        }
    }

    @Test
    void futureKnowledgeAtDoesNotApplyToCurrentProof() throws Exception {
        try (SqlSession session = session()) {
            observation(session, "old", "shared", KEY, "OVERTIME", "target");
            execute(session, "UPDATE oa_attendance_document SET source_status='UNKNOWN'");
            insert(session, "INSERT INTO oa_attendance_document_context VALUES ('oa-old','ACTIVATED','PAID')");

            insert(session, "INSERT INTO attendance_report_oa_evidence_exclusion " +
                    "VALUES ('exclude','target-company','target','shared',?,?,'oa-old','2026-10-07 00:00:00',NULL)",
                    KEY, "v-old");
            session.clearCache();

            assertThat(proven(session, TARGETS, KEY, AS_OF)).isEmpty();
        }
    }

    @Test
    void wrongEmployeeExclusionDoesNotApply() throws Exception {
        try (SqlSession session = session()) {
            observation(session, "old", "shared", KEY, "OVERTIME", "target");
            execute(session, "UPDATE oa_attendance_document SET source_status='UNKNOWN'");
            insert(session, "INSERT INTO oa_attendance_document_context VALUES ('oa-old','ACTIVATED','PAID')");

            execute(session, "INSERT INTO employee VALUES ('peer','target-company','E002')");
            insert(session, "INSERT INTO attendance_report_oa_evidence_exclusion " +
                    "VALUES ('exclude','target-company','peer','shared',?,?,'oa-old','2026-10-06 00:00:00',NULL)",
                    KEY, "v-old");
            session.clearCache();

            assertThat(proven(session, KEY)).isEmpty();
        }
    }

    @Test
    void revokedOvertimeWithoutExclusionIsProvenForCompany() throws Exception {
        try (SqlSession session = session()) {
            observation(session, "old", "shared", KEY, "OVERTIME", "target");
            execute(session, "UPDATE oa_attendance_document SET source_status='REVOKED'");

            assertThat(provenCompany(session, KEY)).containsExactly(KEY);
        }
    }

    @Test
    void exactExclusionResolvesActivatedOvertimeCompanyBlocking() throws Exception {
        try (SqlSession session = session()) {
            observation(session, "old", "shared", KEY, "OVERTIME", "target");
            execute(session, "UPDATE oa_attendance_document SET source_status='PENDING'");
            insert(session, "INSERT INTO oa_attendance_document_context VALUES ('oa-old','ACTIVATED','PAID')");
            session.clearCache();

            assertThat(provenCompany(session, KEY)).isEmpty();

            exclusion(session, KEY, "v-old");
            assertThat(provenCompany(session, KEY)).containsExactly(KEY);
        }
    }

    @Test
    void companyProofNewVersionDoesNotInheritExclusion() throws Exception {
        try (SqlSession session = session()) {
            observation(session, "old", "shared", KEY, "OVERTIME", "target");
            execute(session, "UPDATE oa_attendance_document SET source_status='PENDING'");
            insert(session, "INSERT INTO oa_attendance_document_context VALUES ('oa-old','ACTIVATED','PAID')");
            exclusion(session, KEY, "v-old");

            observation(session, "new", "shared", KEY, "OVERTIME", "target");
            execute(session, "UPDATE oa_attendance_document SET knowledge_rank=2,source_status='PENDING',source_version='v-new' WHERE oa_attendance_document_id='oa-new'");
            execute(session, "UPDATE raw_attendance_fact SET source_version='v-new' WHERE raw_attendance_fact_id='raw-new'");
            insert(session, "INSERT INTO oa_attendance_document_context VALUES ('oa-new','ACTIVATED','PAID')");
            session.clearCache();

            assertThat(provenCompany(session, KEY)).isEmpty();
        }
    }

    @Test
    void companyProofFutureKnowledgeDoesNotApply() throws Exception {
        try (SqlSession session = session()) {
            observation(session, "old", "shared", KEY, "OVERTIME", "target");
            execute(session, "UPDATE oa_attendance_document SET source_status='PENDING'");
            insert(session, "INSERT INTO oa_attendance_document_context VALUES ('oa-old','ACTIVATED','PAID')");

            insert(session, "INSERT INTO attendance_report_oa_evidence_exclusion " +
                    "VALUES ('exclude','target-company','target','shared',?,?,'oa-old','2026-10-07 00:00:00',NULL)",
                    KEY, "v-old");
            session.clearCache();

            assertThat(provenCompany(session, KEY)).isEmpty();
        }
    }

    @Test
    void companyProofReversedExclusionDoesNotApply() throws Exception {
        try (SqlSession session = session()) {
            observation(session, "old", "shared", KEY, "OVERTIME", "target");
            execute(session, "UPDATE oa_attendance_document SET source_status='PENDING'");
            insert(session, "INSERT INTO oa_attendance_document_context VALUES ('oa-old','ACTIVATED','PAID')");

            insert(session, "INSERT INTO attendance_report_oa_evidence_exclusion " +
                    "VALUES ('exclude','target-company','target','shared',?,?,'oa-old','2026-10-06 00:00:00','2026-10-06 00:00:00')",
                    KEY, "v-old");
            session.clearCache();

            assertThat(provenCompany(session, KEY)).isEmpty();
        }
    }

    private static Set<String> provenCompany(SqlSession session, String key) {
        var mapper = session.getMapper(AttendanceEvidenceMapper.class);
        return MissingOaReportScopeProof.provenNoncontributingCompanyKeys(COMPANY, List.of(key),
                mapper.findMissingOaKeyScopeRows("shared", COMPANY, List.of(key)),
                mapper.findCompanyEmployeeNumbers(COMPANY),
                mapper.findMissingOaCompanyCurrentEligibilityRows("shared", COMPANY, List.of(key), AS_OF), AS_OF);
    }

    private static void exclusion(SqlSession session, String key, String version) throws Exception {
        insert(session, "INSERT INTO attendance_report_oa_evidence_exclusion " +
                "VALUES ('exclude','target-company','target','shared',?,?,'oa-old','2026-10-06 00:00:00',NULL)",
                key, version);
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
        insert(session, "INSERT INTO oa_attendance_document (oa_attendance_document_id, normalized_attendance_record_id, attendance_source_id, source_business_key, source_version, document_type, source_status, knowledge_rank, created_at) VALUES (?, ?, ?, ?, ?, ?, 'APPROVED', 1, '2026-10-01 00:00:00')",
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
        configuration.setEnvironment(new Environment("overtime-deletion-proof", new JdbcTransactionFactory(),
                new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:overtime-proof-" + UUID.randomUUID()
                        + ";MODE=MySQL", "sa", "")));
        String resource = "mappers/AttendanceEvidenceMapper.xml";
        try (var input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        execute(session,
                "CREATE TABLE attendance_source (attendance_source_id VARCHAR(36) PRIMARY KEY, company_id VARCHAR(36))",
                "CREATE TABLE company (company_id VARCHAR(36) PRIMARY KEY)",
                "CREATE TABLE employee (employee_id VARCHAR(36) PRIMARY KEY, company_id VARCHAR(36), employee_number VARCHAR(64))",
                "CREATE TABLE employee_version (employee_version_id VARCHAR(36) PRIMARY KEY, employee_id VARCHAR(36), employee_number VARCHAR(64))",
                "CREATE TABLE raw_attendance_fact (raw_attendance_fact_id VARCHAR(64) PRIMARY KEY, attendance_source_id VARCHAR(64), source_business_key VARCHAR(191), source_version VARCHAR(128), fact_kind VARCHAR(32))",
                "CREATE TABLE normalized_attendance_record (normalized_attendance_record_id VARCHAR(64) PRIMARY KEY, raw_attendance_fact_id VARCHAR(64), normalization_revision INT, validation_status VARCHAR(32), record_kind VARCHAR(32))",
                "CREATE TABLE oa_attendance_document (oa_attendance_document_id VARCHAR(64) PRIMARY KEY, normalized_attendance_record_id VARCHAR(64), attendance_source_id VARCHAR(64), source_business_key VARCHAR(191), source_version VARCHAR(128), document_type VARCHAR(32), source_status VARCHAR(32), knowledge_rank BIGINT, created_at TIMESTAMP)",
                "CREATE TABLE oa_attendance_document_context (oa_attendance_document_id VARCHAR(64) PRIMARY KEY, activation_decision VARCHAR(64), overtime_type VARCHAR(20))",
                "CREATE TABLE employee_match_decision (normalized_attendance_record_id VARCHAR(64) PRIMARY KEY, employee_id VARCHAR(36), match_status VARCHAR(32))",
                "CREATE TABLE attendance_report_oa_evidence_exclusion (exclusion_id VARCHAR(36) PRIMARY KEY, company_id VARCHAR(36), employee_id VARCHAR(36), attendance_source_id VARCHAR(64), source_business_key VARCHAR(191), source_version VARCHAR(128), oa_attendance_document_id VARCHAR(64), knowledge_at TIMESTAMP, reversed_at TIMESTAMP NULL)",
                "CREATE TABLE evidence_link (evidence_link_id VARCHAR(36) PRIMARY KEY, effective_attendance_event_id VARCHAR(36), raw_attendance_fact_id VARCHAR(64), normalized_attendance_record_id VARCHAR(64), employee_match_decision_id VARCHAR(64), link_type VARCHAR(32), created_at TIMESTAMP)",
                "CREATE TABLE attendance_report_oa_fact (attendance_report_oa_fact_id VARCHAR(36) PRIMARY KEY, attendance_report_projection_id VARCHAR(36), company_id VARCHAR(36), oa_attendance_document_id VARCHAR(36), employee_id VARCHAR(36), created_at TIMESTAMP)",
                "INSERT INTO attendance_source VALUES ('shared', 'target-company')",
                "INSERT INTO company VALUES ('target-company')",
                "INSERT INTO employee VALUES ('target', 'target-company', 'E001')",
                "INSERT INTO employee_version VALUES ('v1', 'target', 'E001')");
        return session;
    }
}
