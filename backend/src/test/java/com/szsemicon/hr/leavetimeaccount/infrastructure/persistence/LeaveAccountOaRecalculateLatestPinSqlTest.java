package com.szsemicon.hr.leavetimeaccount.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.leavetimeaccount.infrastructure.persistence.LeaveAccountOaRecalculateMapper.OaHourRow;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Real H2 mapper tests for LeaveAccountOaRecalculateMapper.listOaHours.
 *
 * Uses SqlSession.getMapper() so Instant TypeHandler and OaHourRow result
 * mapping go through real MyBatis machinery — not hand-rolled JDBC.
 *
 * Verifies:
 *   (a) latest PUBLISHED V8 pin per company+period is selected,
 *   (b) V74 attendance_report_oa_evidence_exclusion filter is applied with
 *       correct asOf semantics, and
 *   (c) facts from older pins are never resurrected once a newer pin exists.
 */
class LeaveAccountOaRecalculateLatestPinSqlTest {

    private static final Path LEAVE_MAPPER = Path.of(
            "src/main/resources/mappers/LeaveAccountOaRecalculateMapper.xml");

    private static final String COMPANY_A = "company-a";
    private static final String COMPANY_B = "company-b";
    private static final String EMPLOYEE_1 = "employee-1";
    private static final String SOURCE_ID = "oa-source-1";

    // Report period: September 2026
    private static final Timestamp PERIOD_SEP_START =
            Timestamp.valueOf("2026-09-01 00:00:00");
    private static final Timestamp PERIOD_SEP_END =
            Timestamp.valueOf("2026-10-01 00:00:00");

    // Query window: full year 2026
    private static final Instant YEAR_START =
            Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant YEAR_END_EXCLUSIVE =
            Instant.parse("2027-01-01T00:00:00Z");

    // A fact whose interval_start falls inside September
    private static final Timestamp FACT_SEP_7 =
            Timestamp.valueOf("2026-09-07 09:00:00");

    // Two distinct publication times for pins in the same period
    private static final Timestamp PUB_EARLIER =
            Timestamp.valueOf("2026-09-20 10:00:00");
    private static final Timestamp PUB_LATER =
            Timestamp.valueOf("2026-09-25 10:00:00");

    // asOf is after both pins were published
    private static final Instant AS_OF = Instant.parse("2026-10-08T04:00:00Z");

    private SqlSessionFactory sessionFactory;
    private Connection rawConnection;

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:h2:mem:leave-pin-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE";
        rawConnection = DriverManager.getConnection(url);

        UnpooledDataSource ds = new UnpooledDataSource(
                "org.h2.Driver", url, "", "");
        Environment env = new Environment(
                "test", new JdbcTransactionFactory(), ds);
        Configuration cfg = new Configuration(env);
        try (var reader = Files.newBufferedReader(LEAVE_MAPPER)) {
            new XMLMapperBuilder(reader, cfg, LEAVE_MAPPER.toString(),
                    cfg.getSqlFragments()).parse();
        }
        sessionFactory = new SqlSessionFactoryBuilder().build(cfg);

        execute("""
                CREATE TABLE attendance_report_projection (
                    attendance_report_projection_id VARCHAR(64) PRIMARY KEY,
                    company_id VARCHAR(36) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    formula_catalog_version VARCHAR(64) NOT NULL,
                    published_at TIMESTAMP NULL,
                    period_start TIMESTAMP NOT NULL,
                    period_end_exclusive TIMESTAMP NOT NULL
                )
                """);
        execute("""
                CREATE TABLE attendance_report_oa_fact (
                    attendance_report_oa_fact_id VARCHAR(64) PRIMARY KEY,
                    attendance_report_projection_id VARCHAR(64) NOT NULL,
                    company_id VARCHAR(36) NOT NULL,
                    employee_id VARCHAR(36) NOT NULL,
                    oa_attendance_document_id VARCHAR(64) NOT NULL,
                    document_type VARCHAR(32) NOT NULL,
                    leave_type_code VARCHAR(64),
                    source_status VARCHAR(32) NOT NULL,
                    interval_start TIMESTAMP NULL,
                    point_instant TIMESTAMP NULL,
                    recognized_minutes BIGINT NOT NULL
                )
                """);
        execute("""
                CREATE TABLE oa_attendance_document (
                    oa_attendance_document_id VARCHAR(64) PRIMARY KEY,
                    attendance_source_id VARCHAR(64) NOT NULL,
                    source_business_key VARCHAR(191) NOT NULL,
                    source_version VARCHAR(128) NOT NULL
                )
                """);
        execute("""
                CREATE TABLE oa_attendance_document_context (
                    oa_attendance_document_id VARCHAR(64) PRIMARY KEY,
                    overtime_type VARCHAR(20)
                )
                """);
        execute("""
                CREATE TABLE attendance_report_oa_evidence_exclusion (
                    exclusion_id VARCHAR(36) PRIMARY KEY,
                    company_id VARCHAR(36) NOT NULL,
                    employee_id VARCHAR(36) NOT NULL,
                    oa_attendance_document_id VARCHAR(64) NOT NULL,
                    attendance_source_id VARCHAR(64) NOT NULL,
                    source_business_key VARCHAR(191) NOT NULL,
                    source_version VARCHAR(128) NOT NULL,
                    knowledge_at TIMESTAMP NOT NULL,
                    reversed_at TIMESTAMP NULL
                )
                """);
        insertDocument("doc-a", SOURCE_ID, "LEAVE:annual-2026-09-07", "v1");
    }

    @AfterEach
    void tearDown() throws Exception {
        rawConnection.close();
    }

    // -----------------------------------------------------------------------
    // Test cases
    // -----------------------------------------------------------------------

    @Test
    void oldPinHasDoc_newPinDoesNot_returnsZeroForThatDocument() throws Exception {
        insertProjection("pin-old", COMPANY_A, PERIOD_SEP_START, PERIOD_SEP_END,
                "PUBLISHED", PUB_EARLIER);
        insertFact("f-old", "pin-old", COMPANY_A, EMPLOYEE_1, "doc-a",
                "LEAVE", "ANNUAL", "APPROVED", FACT_SEP_7, 480);

        // New pin published later has no fact for doc-a
        insertProjection("pin-new", COMPANY_A, PERIOD_SEP_START, PERIOD_SEP_END,
                "PUBLISHED", PUB_LATER);

        assertThat(sumMinutes(COMPANY_A, EMPLOYEE_1, "ANNUAL"))
                .as("new pin has no doc-a; old pin must not be consulted")
                .isZero();
    }

    @Test
    void newPinHasReducedMinutes_returnsNewNotHistoric480() throws Exception {
        insertProjection("pin-old", COMPANY_A, PERIOD_SEP_START, PERIOD_SEP_END,
                "PUBLISHED", PUB_EARLIER);
        insertFact("f-old", "pin-old", COMPANY_A, EMPLOYEE_1, "doc-a",
                "LEAVE", "ANNUAL", "APPROVED", FACT_SEP_7, 480);

        insertProjection("pin-new", COMPANY_A, PERIOD_SEP_START, PERIOD_SEP_END,
                "PUBLISHED", PUB_LATER);
        insertFact("f-new", "pin-new", COMPANY_A, EMPLOYEE_1, "doc-a",
                "LEAVE", "ANNUAL", "APPROVED", FACT_SEP_7, 240);

        assertThat(sumMinutes(COMPANY_A, EMPLOYEE_1, "ANNUAL"))
                .as("new pin has 240; historic 480 must not surface")
                .isEqualTo(240);
    }

    @Test
    void unpublishedDraftPin_notSelected() throws Exception {
        // DRAFT rows have NULL published_at in the production V10 schema
        insertProjection("pin-draft", COMPANY_A, PERIOD_SEP_START, PERIOD_SEP_END,
                "DRAFT", null);
        insertFact("f-draft", "pin-draft", COMPANY_A, EMPLOYEE_1, "doc-a",
                "LEAVE", "ANNUAL", "APPROVED", FACT_SEP_7, 480);

        assertThat(sumMinutes(COMPANY_A, EMPLOYEE_1, "ANNUAL")).isZero();
    }

    @Test
    void pinWithFuturePublishedAtBeyondAsOf_notSelected() throws Exception {
        Timestamp futurePublished = Timestamp.valueOf("2026-11-01 00:00:00");
        insertProjection("pin-future", COMPANY_A, PERIOD_SEP_START, PERIOD_SEP_END,
                "PUBLISHED", futurePublished);
        insertFact("f-future", "pin-future", COMPANY_A, EMPLOYEE_1, "doc-a",
                "LEAVE", "ANNUAL", "APPROVED", FACT_SEP_7, 480);

        assertThat(sumMinutes(COMPANY_A, EMPLOYEE_1, "ANNUAL")).isZero();
    }

    @Test
    void oldPinOnly_futurePinNotYetPublished_oldPinSelectedNotFuturePin()
            throws Exception {
        // Old pin (published before asOf) has 480; future pin (published after
        // asOf) has a different doc with 999. The query must return 480 from
        // the old pin, not skip it because a future pin exists in the same
        // company+period.
        insertProjection("pin-old", COMPANY_A, PERIOD_SEP_START, PERIOD_SEP_END,
                "PUBLISHED", PUB_EARLIER);
        insertFact("f-old", "pin-old", COMPANY_A, EMPLOYEE_1, "doc-a",
                "LEAVE", "ANNUAL", "APPROVED", FACT_SEP_7, 480);

        insertDocument("doc-future", SOURCE_ID, "LEAVE:annual-2026-09-08", "v1");
        Timestamp futurePublished = Timestamp.valueOf("2026-11-01 00:00:00");
        insertProjection("pin-future", COMPANY_A, PERIOD_SEP_START, PERIOD_SEP_END,
                "PUBLISHED", futurePublished);
        insertFact("f-future", "pin-future", COMPANY_A, EMPLOYEE_1, "doc-future",
                "LEAVE", "ANNUAL", "APPROVED", FACT_SEP_7, 999);

        assertThat(sumMinutes(COMPANY_A, EMPLOYEE_1, "ANNUAL"))
                .as("future pin excluded by asOf; old pin with 480 must be selected")
                .isEqualTo(480);
    }

    @Test
    void samePubTimeTiebreak_higherProjectionIdWins() throws Exception {
        // pin-zzz > pin-aaa lexicographically; ORDER BY projection_id DESC
        // picks pin-zzz. pin-aaa has doc-a (480); pin-zzz has doc-b (300).
        insertProjection("pin-aaa", COMPANY_A, PERIOD_SEP_START, PERIOD_SEP_END,
                "PUBLISHED", PUB_EARLIER);
        insertFact("f-aaa", "pin-aaa", COMPANY_A, EMPLOYEE_1, "doc-a",
                "LEAVE", "ANNUAL", "APPROVED", FACT_SEP_7, 480);

        insertDocument("doc-b", SOURCE_ID, "LEAVE:annual-2026-09-08", "v1");
        insertProjection("pin-zzz", COMPANY_A, PERIOD_SEP_START, PERIOD_SEP_END,
                "PUBLISHED", PUB_EARLIER);
        insertFact("f-zzz", "pin-zzz", COMPANY_A, EMPLOYEE_1, "doc-b",
                "LEAVE", "ANNUAL", "APPROVED", FACT_SEP_7, 300);

        assertThat(sumMinutes(COMPANY_A, EMPLOYEE_1, "ANNUAL"))
                .as("pin-zzz selected by ID tiebreak; must see 300 not 480")
                .isEqualTo(300);
    }

    @Test
    void crossCompanyIsolation_companyADoesNotAffectCompanyBQuery()
            throws Exception {
        insertProjection("pin-a", COMPANY_A, PERIOD_SEP_START, PERIOD_SEP_END,
                "PUBLISHED", PUB_LATER);
        insertFact("f-a", "pin-a", COMPANY_A, EMPLOYEE_1, "doc-a",
                "LEAVE", "ANNUAL", "APPROVED", FACT_SEP_7, 480);

        insertProjection("pin-b", COMPANY_B, PERIOD_SEP_START, PERIOD_SEP_END,
                "PUBLISHED", PUB_LATER);

        assertThat(sumMinutes(COMPANY_B, EMPLOYEE_1, "ANNUAL"))
                .as("company-B query must not see company-A facts")
                .isZero();
    }

    @Test
    void v74ExclusionActive_returnsZeroForExcludedDocument() throws Exception {
        insertProjection("pin-new", COMPANY_A, PERIOD_SEP_START, PERIOD_SEP_END,
                "PUBLISHED", PUB_LATER);
        insertFact("f-new", "pin-new", COMPANY_A, EMPLOYEE_1, "doc-a",
                "LEAVE", "ANNUAL", "APPROVED", FACT_SEP_7, 480);

        insertExclusion("excl-1", COMPANY_A, EMPLOYEE_1, "doc-a",
                SOURCE_ID, "LEAVE:annual-2026-09-07", "v1",
                Timestamp.valueOf("2026-10-01 09:00:00"), null);

        assertThat(sumMinutes(COMPANY_A, EMPLOYEE_1, "ANNUAL"))
                .as("active exclusion (reversed_at IS NULL); must return 0")
                .isZero();
    }

    @Test
    void v74ExclusionReversedBeforeAsOf_exclusionLapsed_documentCounts()
            throws Exception {
        // reversed_at < asOf: the exclusion's own reversal happened before
        // the cutoff, so the exclusion is no longer in effect at asOf.
        // SQL: reversed_at IS NULL OR reversed_at > asOf — reversed_at <
        // asOf does not satisfy the condition; the document is included.
        insertProjection("pin-new", COMPANY_A, PERIOD_SEP_START, PERIOD_SEP_END,
                "PUBLISHED", PUB_LATER);
        insertFact("f-new", "pin-new", COMPANY_A, EMPLOYEE_1, "doc-a",
                "LEAVE", "ANNUAL", "APPROVED", FACT_SEP_7, 480);

        Timestamp reversedBeforeAsOf = Timestamp.valueOf("2026-10-07 08:00:00");
        insertExclusion("excl-rev", COMPANY_A, EMPLOYEE_1, "doc-a",
                SOURCE_ID, "LEAVE:annual-2026-09-07", "v1",
                Timestamp.valueOf("2026-10-01 09:00:00"), reversedBeforeAsOf);

        assertThat(sumMinutes(COMPANY_A, EMPLOYEE_1, "ANNUAL"))
                .as("exclusion reversed before asOf; document should count")
                .isEqualTo(480);
    }

    @Test
    void v74ExclusionReversedAfterAsOf_documentStillExcluded() throws Exception {
        // reversed_at > asOf: the exclusion had not yet been reversed at
        // asOf, so the exclusion is still in effect.
        insertProjection("pin-new", COMPANY_A, PERIOD_SEP_START, PERIOD_SEP_END,
                "PUBLISHED", PUB_LATER);
        insertFact("f-new", "pin-new", COMPANY_A, EMPLOYEE_1, "doc-a",
                "LEAVE", "ANNUAL", "APPROVED", FACT_SEP_7, 480);

        Timestamp reversedAfterAsOf = Timestamp.valueOf("2026-10-09 08:00:00");
        insertExclusion("excl-future-rev", COMPANY_A, EMPLOYEE_1, "doc-a",
                SOURCE_ID, "LEAVE:annual-2026-09-07", "v1",
                Timestamp.valueOf("2026-10-01 09:00:00"), reversedAfterAsOf);

        assertThat(sumMinutes(COMPANY_A, EMPLOYEE_1, "ANNUAL"))
                .as("exclusion reversed after asOf; document must still be excluded")
                .isZero();
    }

    @Test
    void v74ExclusionForDifferentSourceVersion_doesNotExcludeCurrentVersion()
            throws Exception {
        // Exclusion targets source_version "v0"; the document record is "v1".
        insertProjection("pin-new", COMPANY_A, PERIOD_SEP_START, PERIOD_SEP_END,
                "PUBLISHED", PUB_LATER);
        insertFact("f-new", "pin-new", COMPANY_A, EMPLOYEE_1, "doc-a",
                "LEAVE", "ANNUAL", "APPROVED", FACT_SEP_7, 480);

        insertExclusion("excl-wrong-ver", COMPANY_A, EMPLOYEE_1, "doc-a",
                SOURCE_ID, "LEAVE:annual-2026-09-07", "v0",
                Timestamp.valueOf("2026-10-01 09:00:00"), null);

        assertThat(sumMinutes(COMPANY_A, EMPLOYEE_1, "ANNUAL"))
                .as("exclusion is for v0; current doc is v1; must not be excluded")
                .isEqualTo(480);
    }

    @Test
    void v74ExclusionWrongCompany_doesNotExcludeDocumentForTargetCompany()
            throws Exception {
        insertProjection("pin-new", COMPANY_A, PERIOD_SEP_START, PERIOD_SEP_END,
                "PUBLISHED", PUB_LATER);
        insertFact("f-new", "pin-new", COMPANY_A, EMPLOYEE_1, "doc-a",
                "LEAVE", "ANNUAL", "APPROVED", FACT_SEP_7, 480);

        // Exclusion row belongs to company-b, not company-a
        insertExclusion("excl-wrong-co", COMPANY_B, EMPLOYEE_1, "doc-a",
                SOURCE_ID, "LEAVE:annual-2026-09-07", "v1",
                Timestamp.valueOf("2026-10-01 09:00:00"), null);

        assertThat(sumMinutes(COMPANY_A, EMPLOYEE_1, "ANNUAL"))
                .as("exclusion is for company-b; company-a document must not be excluded")
                .isEqualTo(480);
    }

    @Test
    void v74ExclusionWrongEmployee_doesNotExcludeDocumentForTargetEmployee()
            throws Exception {
        insertProjection("pin-new", COMPANY_A, PERIOD_SEP_START, PERIOD_SEP_END,
                "PUBLISHED", PUB_LATER);
        insertFact("f-new", "pin-new", COMPANY_A, EMPLOYEE_1, "doc-a",
                "LEAVE", "ANNUAL", "APPROVED", FACT_SEP_7, 480);

        // Exclusion row is for employee-2, not employee-1
        insertExclusion("excl-wrong-emp", COMPANY_A, "employee-2", "doc-a",
                SOURCE_ID, "LEAVE:annual-2026-09-07", "v1",
                Timestamp.valueOf("2026-10-01 09:00:00"), null);

        assertThat(sumMinutes(COMPANY_A, EMPLOYEE_1, "ANNUAL"))
                .as("exclusion is for employee-2; employee-1 document must not be excluded")
                .isEqualTo(480);
    }

    @Test
    void v74ExclusionSameBusinessKeyNewSourceVersion_notExcluded()
            throws Exception {
        // An exclusion targets the old document (doc-a-v1) by source_version v1.
        // A new immutable document record doc-a-v2 has the same business_key
        // but source_version v2 and its own document_id "doc-a-v2".
        // The exclusion must not affect the newer independent document version.
        insertDocument("doc-a-v2", SOURCE_ID, "LEAVE:annual-2026-09-07", "v2");

        insertProjection("pin-new", COMPANY_A, PERIOD_SEP_START, PERIOD_SEP_END,
                "PUBLISHED", PUB_LATER);
        // The fact references the new doc record
        insertFact("f-v2", "pin-new", COMPANY_A, EMPLOYEE_1, "doc-a-v2",
                "LEAVE", "ANNUAL", "APPROVED", FACT_SEP_7, 480);

        // Exclusion targets the old doc-a (source_version v1)
        insertExclusion("excl-old-ver", COMPANY_A, EMPLOYEE_1, "doc-a",
                SOURCE_ID, "LEAVE:annual-2026-09-07", "v1",
                Timestamp.valueOf("2026-10-01 09:00:00"), null);

        assertThat(sumMinutes(COMPANY_A, EMPLOYEE_1, "ANNUAL"))
                .as("exclusion is for v1/doc-a; new doc-a-v2 must not be excluded")
                .isEqualTo(480);
    }

    @Test
    void v74ExclusionWithFutureKnowledgeAt_doesNotExcludeAtEarlierAsOf()
            throws Exception {
        // Exclusion has knowledge_at 60 seconds after AS_OF, so it was not
        // yet known at the time of AS_OF. The SQL condition knowledge_at <=
        // asOf must exclude this row; the document should count normally.
        insertProjection("pin-new", COMPANY_A, PERIOD_SEP_START, PERIOD_SEP_END,
                "PUBLISHED", PUB_LATER);
        insertFact("f-new", "pin-new", COMPANY_A, EMPLOYEE_1, "doc-a",
                "LEAVE", "ANNUAL", "APPROVED", FACT_SEP_7, 480);

        Timestamp futureKnowledge = Timestamp.from(AS_OF.plusSeconds(60));
        insertExclusion("excl-future-k", COMPANY_A, EMPLOYEE_1, "doc-a",
                SOURCE_ID, "LEAVE:annual-2026-09-07", "v1",
                futureKnowledge, null);

        assertThat(sumMinutes(COMPANY_A, EMPLOYEE_1, "ANNUAL"))
                .as("exclusion knowledge_at is after AS_OF; must not apply")
                .isEqualTo(480);
    }

    // -----------------------------------------------------------------------
    // Query runner — uses SqlSession.getMapper() for real MyBatis binding
    // -----------------------------------------------------------------------

    private long sumMinutes(String companyId, String employeeId, String kind)
            throws Exception {
        try (SqlSession session = sessionFactory.openSession()) {
            LeaveAccountOaRecalculateMapper mapper =
                    session.getMapper(LeaveAccountOaRecalculateMapper.class);
            List<OaHourRow> rows = mapper.listOaHours(
                    companyId, YEAR_START, YEAR_END_EXCLUSIVE, AS_OF);
            return rows.stream()
                    .filter(r -> employeeId.equals(r.employeeId())
                            && kind.equals(r.kind()))
                    .mapToLong(OaHourRow::recognizedMinutes)
                    .sum();
        }
    }

    // -----------------------------------------------------------------------
    // Fixture helpers
    // -----------------------------------------------------------------------

    private void insertProjection(
            String id,
            String companyId,
            Timestamp periodStart,
            Timestamp periodEnd,
            String status,
            Timestamp publishedAt) throws Exception {
        try (PreparedStatement stmt = rawConnection.prepareStatement("""
                INSERT INTO attendance_report_projection (
                    attendance_report_projection_id, company_id, status,
                    formula_catalog_version, published_at,
                    period_start, period_end_exclusive
                ) VALUES (?, ?, ?, 'FULL_CALCULATION_OA_FORM_HOURS_V8', ?, ?, ?)
                """)) {
            stmt.setString(1, id);
            stmt.setString(2, companyId);
            stmt.setString(3, status);
            stmt.setTimestamp(4, publishedAt);
            stmt.setTimestamp(5, periodStart);
            stmt.setTimestamp(6, periodEnd);
            stmt.executeUpdate();
        }
    }

    private void insertFact(
            String factId,
            String projectionId,
            String companyId,
            String employeeId,
            String docId,
            String documentType,
            String leaveTypeCode,
            String sourceStatus,
            Timestamp intervalStart,
            long recognizedMinutes) throws Exception {
        try (PreparedStatement stmt = rawConnection.prepareStatement("""
                INSERT INTO attendance_report_oa_fact (
                    attendance_report_oa_fact_id,
                    attendance_report_projection_id,
                    company_id,
                    employee_id,
                    oa_attendance_document_id,
                    document_type,
                    leave_type_code,
                    source_status,
                    interval_start,
                    point_instant,
                    recognized_minutes
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?)
                """)) {
            stmt.setString(1, factId);
            stmt.setString(2, projectionId);
            stmt.setString(3, companyId);
            stmt.setString(4, employeeId);
            stmt.setString(5, docId);
            stmt.setString(6, documentType);
            stmt.setString(7, leaveTypeCode);
            stmt.setString(8, sourceStatus);
            stmt.setTimestamp(9, intervalStart);
            stmt.setLong(10, recognizedMinutes);
            stmt.executeUpdate();
        }
    }

    private void insertDocument(
            String docId,
            String sourceId,
            String businessKey,
            String sourceVersion) throws Exception {
        try (PreparedStatement stmt = rawConnection.prepareStatement("""
                INSERT INTO oa_attendance_document (
                    oa_attendance_document_id,
                    attendance_source_id,
                    source_business_key,
                    source_version
                ) VALUES (?, ?, ?, ?)
                """)) {
            stmt.setString(1, docId);
            stmt.setString(2, sourceId);
            stmt.setString(3, businessKey);
            stmt.setString(4, sourceVersion);
            stmt.executeUpdate();
        }
    }

    private void insertExclusion(
            String exclusionId,
            String companyId,
            String employeeId,
            String docId,
            String sourceId,
            String businessKey,
            String sourceVersion,
            Timestamp knowledgeAt,
            Timestamp reversedAt) throws Exception {
        try (PreparedStatement stmt = rawConnection.prepareStatement("""
                INSERT INTO attendance_report_oa_evidence_exclusion (
                    exclusion_id, company_id, employee_id,
                    oa_attendance_document_id,
                    attendance_source_id, source_business_key, source_version,
                    knowledge_at, reversed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            stmt.setString(1, exclusionId);
            stmt.setString(2, companyId);
            stmt.setString(3, employeeId);
            stmt.setString(4, docId);
            stmt.setString(5, sourceId);
            stmt.setString(6, businessKey);
            stmt.setString(7, sourceVersion);
            stmt.setTimestamp(8, knowledgeAt);
            stmt.setTimestamp(9, reversedAt);
            stmt.executeUpdate();
        }
    }

    private void execute(String sql) throws Exception {
        try (var stmt = rawConnection.createStatement()) {
            stmt.execute(sql);
        }
    }
}
