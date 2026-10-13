package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.reporting.application.OaEvidenceExclusionWriteService.ExclusionIntent;
import com.szsemicon.hr.reporting.application.OaEvidenceExclusionWriteService.ExclusionReceipt;
import com.szsemicon.hr.reporting.application.OaOvertimeRecognitionWriteService.RecognitionIntent;
import com.szsemicon.hr.reporting.application.OaOvertimeRecognitionWriteService.RecognitionReceipt;
import com.szsemicon.hr.reporting.application.PunchExemptionRepairService.RepairIntent;
import com.szsemicon.hr.reporting.application.PunchExemptionRepairService.RepairReceipt;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MySQL isolation integration tests — Phase 2.
 *
 * Class name ends with "IT" so the default Surefire configuration skips it.
 * Run explicitly from the backend directory:
 *
 *   if ./mvnw -Dtest=MysqlIsolationRepairIT test > "$MYSQL_REPAIR_TEST_DIR/it.log" 2>&1;
 *   then rc=0; else rc=$?; fi
 *   echo $rc > "$MYSQL_REPAIR_TEST_DIR/it.exit"
 *
 * FK CHECKS: oa_attendance_document_context has a deep FK chain to
 * oa_runtime_contract_revision. Rather than bypassing FKs, we insert a
 * minimal DRAFT oa_runtime_contract_revision row (the service queries only
 * read the document itself, not the contract revision).
 *
 * SEEDED DATA: Flyway migrations V2/V5/V14 already seed HR_ADMIN role
 * (10000000-0000-0000-0000-000000000001), EMPLOYEE:EDIT capability
 * (23000000-0000-0000-0000-000000000016), and ATTENDANCE_REPORT:REFRESH
 * capability (2a000000-0000-0000-0000-000000000009). We reuse those IDs
 * and only insert the role-assignment and data-scope rows needed for the
 * test actor.
 *
 * SHARED OA SOURCE: SHARED_SOURCE_ID hardcoded in both write services:
 *   e05866a7-97b0-11f1-8924-00155d09fd0e
 */
class MysqlIsolationRepairIT extends MysqlIsolationTestBase {

    // ---- seeded IDs (already inserted by Flyway migrations) --------------

    /** V2: HR_ADMIN role */
    private static final String ROLE_HR_ADMIN           = "10000000-0000-0000-0000-000000000001";
    /** V14: ATTENDANCE_REPORT:REFRESH capability */
    private static final String CAP_REPORT_REFRESH      = "2a000000-0000-0000-0000-000000000009";
    /** V5: EMPLOYEE:EDIT capability */
    private static final String CAP_EMPLOYEE_EDIT       = "23000000-0000-0000-0000-000000000016";

    // ---- test-local IDs --------------------------------------------------

    private static final String COMPANY_ID      = "c1000001-0000-0000-0000-000000000001";
    private static final String EMPLOYEE_ID     = "e1000001-0000-0000-0000-000000000001";
    private static final String ACTOR_ID        = "a1000001-0000-0000-0000-000000000001";
    private static final String SCOPE_ID        = "d1000001-0000-0000-0000-000000000001";
    private static final String EMP_VERSION_ID  = "f1000001-0000-0000-0000-000000000001";
    private static final String ASSIGN_ID       = "b1000001-0000-0000-0000-000000000001";
    private static final String ORG_ID          = "91000001-0000-0000-0000-000000000001";

    /** Must match SHARED_SOURCE_ID in both OA write services. */
    private static final String OA_SOURCE_ID    = "e05866a7-97b0-11f1-8924-00155d09fd0e";

    // OA document chain IDs — all RFC 4122 compliant UUIDs
    private static final String RAW_FACT_OT     = "aa000001-0000-4000-8000-000000000001";
    private static final String RAW_FACT_LEAVE  = "aa000002-0000-4000-8000-000000000002";
    private static final String NORM_REC_OT     = "bb000001-0000-4000-8000-000000000001";
    private static final String NORM_REC_LEAVE  = "bb000002-0000-4000-8000-000000000002";
    private static final String MATCH_DEC_OT    = "cc000001-0000-4000-8000-000000000001";
    private static final String MATCH_DEC_LEAVE = "cc000002-0000-4000-8000-000000000002";
    private static final String OA_DOC_OT       = "dd000001-0000-4000-8000-000000000001";
    private static final String OA_DOC_LEAVE    = "dd000002-0000-4000-8000-000000000002";
    private static final String OA_CTX_OT       = "ee000001-0000-4000-8000-000000000001";
    private static final String CONTRACT_REV_ID = "ff000001-0000-4000-8000-000000000001";

    private static final String EXEMPTION_ID_1  = "11000001-0000-4000-8000-000000000001";
    private static final String EXEMPTION_ID_2  = "11000002-0000-4000-8000-000000000002";

    // Overtime: 2026-10-08 09:00–11:00 CST = 01:00–03:00 UTC, 30-min grid
    private static final Instant OT_START = Instant.parse("2026-10-08T01:00:00Z");
    private static final Instant OT_END   = Instant.parse("2026-10-08T03:00:00Z");
    private static final LocalDate OT_DATE = LocalDate.of(2026, 10, 8);

    // Leave: 2026-10-09 09:00–17:00 CST = 01:00–09:00 UTC
    private static final Instant LEAVE_START = Instant.parse("2026-10-09T01:00:00Z");
    private static final Instant LEAVE_END   = Instant.parse("2026-10-09T09:00:00Z");
    private static final LocalDate LEAVE_DATE = LocalDate.of(2026, 10, 9);

    private static final String OT_BIZ_KEY    = "OT-2026100801-IT";
    private static final String OT_VERSION    = "2026-10-08T01:00:00:state=3:Asia/Shanghai";
    private static final String LEAVE_BIZ_KEY = "LV-2026100901-IT";
    private static final String LEAVE_VERSION = "2026-10-09T01:00:00:state=3:Asia/Shanghai";

    // Valid lowercase hex-64 digests for test inputs
    private static final String DIGEST_A = PunchExemptionRepairService.stableDigest("ev-a");
    private static final String DIGEST_B = PunchExemptionRepairService.stableDigest("ev-b");
    private static final String DIGEST_C = PunchExemptionRepairService.stableDigest("ev-c");
    private static final String DIGEST_D = PunchExemptionRepairService.stableDigest("ev-d");
    private static final String DIGEST_E = PunchExemptionRepairService.stableDigest("ev-e");
    private static final String DIGEST_F = PunchExemptionRepairService.stableDigest("ev-f");
    private static final String DIGEST_G = PunchExemptionRepairService.stableDigest("ev-g");
    private static final String DIGEST_H = PunchExemptionRepairService.stableDigest("ev-h");
    private static final String DIGEST_I = PunchExemptionRepairService.stableDigest("ev-i");
    private static final String DIGEST_J = PunchExemptionRepairService.stableDigest("ev-j");
    // Distinct digest for changed-payload tests (must differ from above)
    private static final String DIGEST_ALT = PunchExemptionRepairService.stableDigest("ev-alt");

    // ---- lifecycle -------------------------------------------------------

    @BeforeEach
    void setUp() {
        cleanAll();
        insertBaseFixtures();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        ACTOR_ID, "test", List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        cleanAll();
    }

    // ---- flyway ----------------------------------------------------------

    @Test
    void flyway_migratesCleanSchema_toV76() {
        String highest = jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history" +
                " ORDER BY installed_rank DESC LIMIT 1",
                String.class);
        assertThat(highest)
                .as("Flyway must reach V76 "
                        + "(V74=attendance_report_oa_evidence_exclusion, "
                        + "V75=attendance_report_oa_overtime_recognition, "
                        + "V76=punch_exemption_repair_receipt)")
                .isEqualTo("76");
    }

    // ---- OaOvertimeRecognitionWriteService -------------------------------

    @Test
    void oaOvertime_happyPath_writesRowAndAudit() {
        insertOtimeDocument();
        String reqId = UUID.randomUUID().toString();
        String recId = UUID.randomUUID().toString();
        String confirmText = "Confirm OT recognition for " + recId;
        String confirmSha  = PunchExemptionRepairService.stableDigest(confirmText);

        RecognitionReceipt receipt = overtimeRecognitionWriteService.recognize(
                new RecognitionIntent(reqId, recId, COMPANY_ID, EMPLOYEE_ID,
                        OT_BIZ_KEY, OT_VERSION, OA_DOC_OT, OT_DATE,
                        OT_START, OT_END, 120,
                        "Compensatory OT recognised by HR",
                        confirmText, confirmSha, DIGEST_A));

        assertThat(receipt.recognitionId()).isEqualTo(recId);
        assertThat(receipt.reversedAt()).isNull();

        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_report_oa_overtime_recognition" +
                " WHERE recognition_id = ?", Long.class, recId);
        assertThat(count).isEqualTo(1);

        Long auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE resource_id_ref = ?",
                Long.class, recId);
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    void oaOvertime_exactReplay_doesNotDuplicateAudit() {
        insertOtimeDocument();
        String reqId = UUID.randomUUID().toString();
        String recId = UUID.randomUUID().toString();
        String confirmText = "Replay OT " + recId;
        String confirmSha  = PunchExemptionRepairService.stableDigest(confirmText);

        RecognitionIntent intent = new RecognitionIntent(
                reqId, recId, COMPANY_ID, EMPLOYEE_ID,
                OT_BIZ_KEY, OT_VERSION, OA_DOC_OT, OT_DATE,
                OT_START, OT_END, 120,
                "OT recognised",
                confirmText, confirmSha, DIGEST_B);

        RecognitionReceipt first  = overtimeRecognitionWriteService.recognize(intent);
        RecognitionReceipt second = overtimeRecognitionWriteService.recognize(intent);

        assertThat(second.recognitionId()).isEqualTo(first.recognitionId());
        assertThat(second.knowledgeAt()).isEqualTo(first.knowledgeAt());

        Long rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_report_oa_overtime_recognition" +
                " WHERE request_id = ?", Long.class, reqId);
        assertThat(rowCount).isEqualTo(1);

        Long auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE resource_id_ref = ?",
                Long.class, recId);
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    void oaOvertime_changedPayload_isRejected() {
        insertOtimeDocument();
        String reqId = UUID.randomUUID().toString();
        String recId = UUID.randomUUID().toString();
        String confirmText = "OT confirm " + recId;
        String confirmSha  = PunchExemptionRepairService.stableDigest(confirmText);

        overtimeRecognitionWriteService.recognize(new RecognitionIntent(
                reqId, recId, COMPANY_ID, EMPLOYEE_ID,
                OT_BIZ_KEY, OT_VERSION, OA_DOC_OT, OT_DATE,
                OT_START, OT_END, 120, "reason A",
                confirmText, confirmSha, DIGEST_C));

        // Same requestId+docId but different recognizedMinutes — replay check rejects
        assertThatThrownBy(() -> overtimeRecognitionWriteService.recognize(
                new RecognitionIntent(
                        reqId, recId, COMPANY_ID, EMPLOYEE_ID,
                        OT_BIZ_KEY, OT_VERSION, OA_DOC_OT, OT_DATE,
                        OT_START, OT_END, 90 /* changed */, "reason A",
                        confirmText, confirmSha, DIGEST_C)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void oaOvertime_auditFailure_rollsBackRecognitionRow() {
        insertOtimeDocument();
        String reqId = UUID.randomUUID().toString();
        String recId = UUID.randomUUID().toString();
        String confirmText = "OT rollback test " + recId;
        String confirmSha  = PunchExemptionRepairService.stableDigest(confirmText);

        // Verify that a clean call (no constraint) succeeds — so we know the
        // fixture is valid and the service reaches the audit INSERT.
        String probeReqId = UUID.randomUUID().toString();
        String probeRecId = UUID.randomUUID().toString();
        String probeText  = "OT probe " + probeRecId;
        String probeSha   = PunchExemptionRepairService.stableDigest(probeText);

        // This must succeed to confirm our fixture reaches the audit step
        overtimeRecognitionWriteService.recognize(new RecognitionIntent(
                probeReqId, probeRecId, COMPANY_ID, EMPLOYEE_ID,
                OT_BIZ_KEY, OT_VERSION, OA_DOC_OT, OT_DATE,
                OT_START, OT_END, 120, "probe reason",
                probeText, probeSha, DIGEST_D));

        Long probeAudit = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE resource_id_ref = ?",
                Long.class, probeRecId);
        assertThat(probeAudit).as("probe call must reach audit INSERT").isEqualTo(1);

        // Clean up probe row so the unique constraints don't block the rollback test
        jdbc.update("DELETE FROM attendance_report_oa_overtime_recognition" +
                " WHERE recognition_id = ?", probeRecId);
        jdbc.update("DELETE FROM audit_event WHERE resource_id_ref = ?", probeRecId);

        // Now add a CHECK constraint that will cause the audit INSERT to fail for recId
        String cname = "ck_test_audit_ot_" + System.currentTimeMillis();
        boolean constraintCreated = false;
        try {
            jdbc.execute(
                    "ALTER TABLE audit_event ADD CONSTRAINT " + cname +
                    " CHECK (NOT (resource_id_ref = '" + recId + "'" +
                    " AND action_code = 'CREATE_OA_OVERTIME_RECOGNITION'))");
            constraintCreated = true;

            assertThatThrownBy(() -> overtimeRecognitionWriteService.recognize(
                    new RecognitionIntent(
                            reqId, recId, COMPANY_ID, EMPLOYEE_ID,
                            OT_BIZ_KEY, OT_VERSION, OA_DOC_OT, OT_DATE,
                            OT_START, OT_END, 120, "rollback reason",
                            confirmText, confirmSha, DIGEST_E)))
                    .cause()
                    .hasMessageContaining(cname);

            Long rowCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM attendance_report_oa_overtime_recognition" +
                    " WHERE recognition_id = ?", Long.class, recId);
            assertThat(rowCount).as("recognition row must be rolled back").isEqualTo(0);

            Long auditCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM audit_event WHERE resource_id_ref = ?",
                    Long.class, recId);
            assertThat(auditCount).as("audit row must be rolled back").isEqualTo(0);

        } finally {
            if (constraintCreated) {
                // MySQL 8.0: DROP CHECK <name>, no IF EXISTS
                jdbc.execute("ALTER TABLE audit_event DROP CHECK " + cname);
            }
        }
    }

    @Test
    void oaOvertime_concurrentSameRequest_exactlyOneRowIdempotent() throws Exception {
        insertOtimeDocument();
        String reqId = UUID.randomUUID().toString();
        String recId = UUID.randomUUID().toString();
        String confirmText = "OT concurrent " + recId;
        String confirmSha  = PunchExemptionRepairService.stableDigest(confirmText);

        RecognitionIntent intent = new RecognitionIntent(
                reqId, recId, COMPANY_ID, EMPLOYEE_ID,
                OT_BIZ_KEY, OT_VERSION, OA_DOC_OT, OT_DATE,
                OT_START, OT_END, 120, "concurrent reason",
                confirmText, confirmSha, DIGEST_F);

        // Each thread explicitly sets READ_COMMITTED on the TransactionTemplate so
        // that the service's PROPAGATION_REQUIRED call joins a READ_COMMITTED outer
        // transaction rather than MySQL's default REPEATABLE_READ. Without this,
        // both threads read the same snapshot and both attempt to INSERT, producing
        // a DuplicateKeyException. With READ_COMMITTED, the second thread's re-check
        // after the source lock sees the first thread's committed row and takes the
        // idempotent replay path.
        CyclicBarrier connBarrier = new CyclicBarrier(2);
        CyclicBarrier callBarrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicLong connId0 = new AtomicLong();
        AtomicLong connId1 = new AtomicLong();
        List<AtomicReference<RecognitionReceipt>> results = List.of(
                new AtomicReference<>(), new AtomicReference<>());

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            final AtomicLong connIdRef = (idx == 0) ? connId0 : connId1;
            futures.add(pool.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                                ACTOR_ID, "test", List.of()));
                try {
                    txTemplate.setIsolationLevel(
                            org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
                    txTemplate.executeWithoutResult(status -> {
                        Long cid = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                        connIdRef.set(cid);
                        // Verify isolation level actually took effect
                        String iso = jdbc.queryForObject(
                                "SELECT @@transaction_isolation", String.class);
                        if (!"READ-COMMITTED".equals(iso)) {
                            throw new IllegalStateException(
                                    "Expected READ-COMMITTED isolation, got: " + iso);
                        }
                        try {
                            connBarrier.await(5, TimeUnit.SECONDS);
                            callBarrier.await(5, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                        RecognitionReceipt r = overtimeRecognitionWriteService.recognize(intent);
                        results.get(idx).set(r);
                    });
                } finally {
                    SecurityContextHolder.clearContext();
                }
                return null;
            }));
        }

        for (Future<?> f : futures) f.get(15, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(connId0.get()).isNotZero();
        assertThat(connId1.get()).isNotZero();
        assertThat(connId0.get()).isNotEqualTo(connId1.get());

        RecognitionReceipt r0 = results.get(0).get();
        RecognitionReceipt r1 = results.get(1).get();
        assertThat(r0).isNotNull();
        assertThat(r1).isNotNull();
        assertThat(r0.recognitionId()).isEqualTo(r1.recognitionId());
        assertThat(r0.knowledgeAt()).isEqualTo(r1.knowledgeAt());
        assertThat(r0.recognitionDigest()).isEqualTo(r1.recognitionDigest());

        Long rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_report_oa_overtime_recognition" +
                " WHERE recognition_id = ?", Long.class, recId);
        assertThat(rowCount).isEqualTo(1);

        Long auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE resource_id_ref = ?",
                Long.class, recId);
        assertThat(auditCount).isEqualTo(1);
    }

    // ---- OaEvidenceExclusionWriteService ---------------------------------

    @Test
    void oaExclusion_happyPath_writesRowAndAudit() {
        insertLeaveDocument();
        String reqId  = UUID.randomUUID().toString();
        String exclId = UUID.randomUUID().toString();
        String confirmText = "Confirm exclusion " + exclId;
        String confirmSha  = PunchExemptionRepairService.stableDigest(confirmText);

        ExclusionReceipt receipt = evidenceExclusionWriteService.exclude(
                new ExclusionIntent(reqId, exclId, COMPANY_ID, EMPLOYEE_ID,
                        LEAVE_BIZ_KEY, LEAVE_VERSION, OA_DOC_LEAVE, LEAVE_DATE,
                        LEAVE_START, LEAVE_END,
                        "Annual leave excluded by HR correction",
                        confirmText, confirmSha, DIGEST_A));

        assertThat(receipt.exclusionId()).isEqualTo(exclId);
        assertThat(receipt.reversedAt()).isNull();

        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_report_oa_evidence_exclusion" +
                " WHERE exclusion_id = ?", Long.class, exclId);
        assertThat(count).isEqualTo(1);

        Long auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE resource_id_ref = ?",
                Long.class, exclId);
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    void oaExclusion_exactReplay_doesNotDuplicateAudit() {
        insertLeaveDocument();
        String reqId  = UUID.randomUUID().toString();
        String exclId = UUID.randomUUID().toString();
        String confirmText = "Replay exclusion " + exclId;
        String confirmSha  = PunchExemptionRepairService.stableDigest(confirmText);

        ExclusionIntent intent = new ExclusionIntent(
                reqId, exclId, COMPANY_ID, EMPLOYEE_ID,
                LEAVE_BIZ_KEY, LEAVE_VERSION, OA_DOC_LEAVE, LEAVE_DATE,
                LEAVE_START, LEAVE_END, "replay exclusion",
                confirmText, confirmSha, DIGEST_B);

        ExclusionReceipt first  = evidenceExclusionWriteService.exclude(intent);
        ExclusionReceipt second = evidenceExclusionWriteService.exclude(intent);

        assertThat(second.exclusionId()).isEqualTo(first.exclusionId());
        assertThat(second.knowledgeAt()).isEqualTo(first.knowledgeAt());

        Long rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_report_oa_evidence_exclusion" +
                " WHERE request_id = ?", Long.class, reqId);
        assertThat(rowCount).isEqualTo(1);

        Long auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE resource_id_ref = ?",
                Long.class, exclId);
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    void oaExclusion_changedPayload_isRejected() {
        insertLeaveDocument();
        String reqId  = UUID.randomUUID().toString();
        String exclId = UUID.randomUUID().toString();
        String confirmText = "Exclusion confirm " + exclId;
        String confirmSha  = PunchExemptionRepairService.stableDigest(confirmText);

        evidenceExclusionWriteService.exclude(new ExclusionIntent(
                reqId, exclId, COMPANY_ID, EMPLOYEE_ID,
                LEAVE_BIZ_KEY, LEAVE_VERSION, OA_DOC_LEAVE, LEAVE_DATE,
                LEAVE_START, LEAVE_END, "reason first",
                confirmText, confirmSha, DIGEST_C));

        // Different evidenceSha256 on same request+doc — replay check must reject
        assertThatThrownBy(() -> evidenceExclusionWriteService.exclude(
                new ExclusionIntent(
                        reqId, exclId, COMPANY_ID, EMPLOYEE_ID,
                        LEAVE_BIZ_KEY, LEAVE_VERSION, OA_DOC_LEAVE, LEAVE_DATE,
                        LEAVE_START, LEAVE_END, "reason first",
                        confirmText, confirmSha, DIGEST_ALT /* changed */)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void oaExclusion_auditFailure_rollsBackExclusionRow() {
        insertLeaveDocument();
        String reqId  = UUID.randomUUID().toString();
        String exclId = UUID.randomUUID().toString();
        String confirmText = "Exclusion rollback " + exclId;
        String confirmSha  = PunchExemptionRepairService.stableDigest(confirmText);

        // Verify fixture reaches audit step with a probe call
        String probeReqId = UUID.randomUUID().toString();
        String probeExclId = UUID.randomUUID().toString();
        String probeText  = "Exclusion probe " + probeExclId;
        String probeSha   = PunchExemptionRepairService.stableDigest(probeText);

        evidenceExclusionWriteService.exclude(new ExclusionIntent(
                probeReqId, probeExclId, COMPANY_ID, EMPLOYEE_ID,
                LEAVE_BIZ_KEY, LEAVE_VERSION, OA_DOC_LEAVE, LEAVE_DATE,
                LEAVE_START, LEAVE_END, "probe reason",
                probeText, probeSha, DIGEST_D));

        Long probeAudit = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE resource_id_ref = ?",
                Long.class, probeExclId);
        assertThat(probeAudit).as("probe call must reach audit INSERT").isEqualTo(1);

        jdbc.update("DELETE FROM attendance_report_oa_evidence_exclusion" +
                " WHERE exclusion_id = ?", probeExclId);
        jdbc.update("DELETE FROM audit_event WHERE resource_id_ref = ?", probeExclId);

        String cname = "ck_test_audit_ex_" + System.currentTimeMillis();
        boolean constraintCreated = false;
        try {
            jdbc.execute(
                    "ALTER TABLE audit_event ADD CONSTRAINT " + cname +
                    " CHECK (NOT (resource_id_ref = '" + exclId + "'" +
                    " AND action_code = 'CREATE_OA_EVIDENCE_EXCLUSION'))");
            constraintCreated = true;

            assertThatThrownBy(() -> evidenceExclusionWriteService.exclude(
                    new ExclusionIntent(
                            reqId, exclId, COMPANY_ID, EMPLOYEE_ID,
                            LEAVE_BIZ_KEY, LEAVE_VERSION, OA_DOC_LEAVE, LEAVE_DATE,
                            LEAVE_START, LEAVE_END, "rollback reason",
                            confirmText, confirmSha, DIGEST_E)))
                    .cause()
                    .hasMessageContaining(cname);

            Long rowCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM attendance_report_oa_evidence_exclusion" +
                    " WHERE exclusion_id = ?", Long.class, exclId);
            assertThat(rowCount).as("exclusion row must be rolled back").isEqualTo(0);

            Long auditCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM audit_event WHERE resource_id_ref = ?",
                    Long.class, exclId);
            assertThat(auditCount).as("audit row must be rolled back").isEqualTo(0);

        } finally {
            if (constraintCreated) {
                jdbc.execute("ALTER TABLE audit_event DROP CHECK " + cname);
            }
        }
    }

    @Test
    void oaExclusion_concurrentSameRequest_exactlyOneRowIdempotent() throws Exception {
        insertLeaveDocument();
        String reqId  = UUID.randomUUID().toString();
        String exclId = UUID.randomUUID().toString();
        String confirmText = "Exclusion concurrent " + exclId;
        String confirmSha  = PunchExemptionRepairService.stableDigest(confirmText);

        ExclusionIntent intent = new ExclusionIntent(
                reqId, exclId, COMPANY_ID, EMPLOYEE_ID,
                LEAVE_BIZ_KEY, LEAVE_VERSION, OA_DOC_LEAVE, LEAVE_DATE,
                LEAVE_START, LEAVE_END, "concurrent exclusion",
                confirmText, confirmSha, DIGEST_F);

        CyclicBarrier connBarrier = new CyclicBarrier(2);
        CyclicBarrier callBarrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicLong connId0 = new AtomicLong();
        AtomicLong connId1 = new AtomicLong();
        List<AtomicReference<ExclusionReceipt>> results = List.of(
                new AtomicReference<>(), new AtomicReference<>());

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            final AtomicLong connIdRef = (idx == 0) ? connId0 : connId1;
            futures.add(pool.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                                ACTOR_ID, "test", List.of()));
                try {
                    txTemplate.setIsolationLevel(
                            org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
                    txTemplate.executeWithoutResult(status -> {
                        Long cid = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                        connIdRef.set(cid);
                        String iso = jdbc.queryForObject(
                                "SELECT @@transaction_isolation", String.class);
                        if (!"READ-COMMITTED".equals(iso)) {
                            throw new IllegalStateException(
                                    "Expected READ-COMMITTED isolation, got: " + iso);
                        }
                        try {
                            connBarrier.await(5, TimeUnit.SECONDS);
                            callBarrier.await(5, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                        ExclusionReceipt r = evidenceExclusionWriteService.exclude(intent);
                        results.get(idx).set(r);
                    });
                } finally {
                    SecurityContextHolder.clearContext();
                }
                return null;
            }));
        }

        for (Future<?> f : futures) f.get(15, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(connId0.get()).isNotZero();
        assertThat(connId1.get()).isNotZero();
        assertThat(connId0.get()).isNotEqualTo(connId1.get());

        ExclusionReceipt r0 = results.get(0).get();
        ExclusionReceipt r1 = results.get(1).get();
        assertThat(r0).isNotNull();
        assertThat(r1).isNotNull();
        assertThat(r0.exclusionId()).isEqualTo(r1.exclusionId());
        assertThat(r0.knowledgeAt()).isEqualTo(r1.knowledgeAt());
        assertThat(r0.exclusionDigest()).isEqualTo(r1.exclusionDigest());

        Long rowCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_report_oa_evidence_exclusion" +
                " WHERE exclusion_id = ?", Long.class, exclId);
        assertThat(rowCount).isEqualTo(1);

        Long auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE resource_id_ref = ?",
                Long.class, exclId);
        assertThat(auditCount).isEqualTo(1);
    }

    // ---- PunchExemptionRepairService -------------------------------------

    @Test
    void repair_happyPath_writesReceiptAndAudit() {
        Instant original = Instant.parse("2026-10-01T00:00:00Z");
        Instant target   = Instant.parse("2026-10-05T00:00:00Z");
        insertExemption(EXEMPTION_ID_1, original);
        String reqId = UUID.randomUUID().toString();

        RepairReceipt receipt = punchExemptionRepairService.repair(repairIntent(
                reqId, EXEMPTION_ID_1, original, target, DIGEST_G));

        assertThat(receipt.requestId()).isEqualTo(reqId);
        assertThat(receipt.exemptionId()).isEqualTo(EXEMPTION_ID_1);
        assertThat(receipt.operation()).isEqualTo("ADJUST_VALID_FROM");

        Long receiptCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM punch_exemption_repair_receipt" +
                " WHERE receipt_id = ?", Long.class, receipt.receiptId());
        assertThat(receiptCount).isEqualTo(1);

        Instant updatedFrom = jdbc.queryForObject(
                "SELECT valid_from FROM punch_exemption_assignment" +
                " WHERE exemption_id = ?",
                Timestamp.class, EXEMPTION_ID_1).toInstant();
        assertThat(updatedFrom).isEqualTo(target);

        Long auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE resource_id_ref = ?",
                Long.class, EXEMPTION_ID_1);
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    void repair_exactReplay_doesNotDuplicateReceipt() {
        Instant original = Instant.parse("2026-10-01T00:00:00Z");
        Instant target   = Instant.parse("2026-10-05T00:00:00Z");
        insertExemption(EXEMPTION_ID_1, original);
        String reqId = UUID.randomUUID().toString();

        RepairIntent intent = repairIntent(reqId, EXEMPTION_ID_1, original, target, DIGEST_H);

        RepairReceipt first  = punchExemptionRepairService.repair(intent);
        RepairReceipt second = punchExemptionRepairService.repair(intent);

        assertThat(second.receiptId()).isEqualTo(first.receiptId());
        assertThat(second.repairedAt()).isEqualTo(first.repairedAt());
        assertThat(second.beforeDigest()).isEqualTo(first.beforeDigest());
        assertThat(second.afterDigest()).isEqualTo(first.afterDigest());

        Long receiptCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM punch_exemption_repair_receipt" +
                " WHERE exemption_id = ?", Long.class, EXEMPTION_ID_1);
        assertThat(receiptCount).isEqualTo(1);

        Long auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE resource_id_ref = ?",
                Long.class, EXEMPTION_ID_1);
        assertThat(auditCount).isEqualTo(1);
    }

    @Test
    void repair_changedApprovalDigest_isRejected() {
        Instant original = Instant.parse("2026-10-01T00:00:00Z");
        Instant target   = Instant.parse("2026-10-05T00:00:00Z");
        insertExemption(EXEMPTION_ID_1, original);
        String reqId = UUID.randomUUID().toString();

        punchExemptionRepairService.repair(
                repairIntent(reqId, EXEMPTION_ID_1, original, target, DIGEST_I));

        assertThatThrownBy(() -> punchExemptionRepairService.repair(
                repairIntent(reqId, EXEMPTION_ID_1, original, target, DIGEST_ALT)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重放冲突")
                .hasMessageContaining("approvalEvidenceDigest");
    }

    @Test
    void repair_auditFailure_rollsBackReceiptAndExemption() {
        Instant original = Instant.parse("2026-10-01T12:00:00Z");
        Instant target   = Instant.parse("2026-10-05T12:00:00Z");
        insertExemption(EXEMPTION_ID_1, original);
        String reqId = UUID.randomUUID().toString();

        // Probe: confirm a clean call reaches the audit INSERT
        String probeReqId = UUID.randomUUID().toString();
        insertExemption(EXEMPTION_ID_2, original);
        punchExemptionRepairService.repair(
                repairIntent(probeReqId, EXEMPTION_ID_2, original, target, DIGEST_J));

        Long probeAudit = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE resource_id_ref = ?",
                Long.class, EXEMPTION_ID_2);
        assertThat(probeAudit).as("probe must reach audit INSERT").isEqualTo(1);

        String cname = "ck_test_audit_rp_" + System.currentTimeMillis();
        boolean constraintCreated = false;
        try {
            jdbc.execute(
                    "ALTER TABLE audit_event ADD CONSTRAINT " + cname +
                    " CHECK (NOT (resource_id_ref = '" + EXEMPTION_ID_1 + "'" +
                    " AND action_code = 'PUNCH_EXEMPTION_REPAIR'))");
            constraintCreated = true;

            assertThatThrownBy(() -> punchExemptionRepairService.repair(
                    repairIntent(reqId, EXEMPTION_ID_1, original, target, DIGEST_G)))
                    .cause()
                    .hasMessageContaining(cname);

            Long receiptCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM punch_exemption_repair_receipt" +
                    " WHERE exemption_id = ?", Long.class, EXEMPTION_ID_1);
            assertThat(receiptCount).as("receipt must be rolled back").isEqualTo(0);

            Instant currentFrom = jdbc.queryForObject(
                    "SELECT valid_from FROM punch_exemption_assignment" +
                    " WHERE exemption_id = ?",
                    Timestamp.class, EXEMPTION_ID_1).toInstant();
            assertThat(currentFrom).as("exemption must be unchanged").isEqualTo(original);

        } finally {
            if (constraintCreated) {
                jdbc.execute("ALTER TABLE audit_event DROP CHECK " + cname);
            }
        }
    }

    @Test
    void repair_batchSecondFails_rollsBackBoth() {
        Instant from1 = Instant.parse("2026-10-01T10:00:00Z");
        Instant from2 = Instant.parse("2026-10-02T10:00:00Z");
        insertExemption(EXEMPTION_ID_1, from1);
        insertExemption(EXEMPTION_ID_2, from2);

        RepairIntent intent1 = repairIntent(
                UUID.randomUUID().toString(), EXEMPTION_ID_1,
                from1, Instant.parse("2026-10-05T10:00:00Z"), DIGEST_H);

        // Wrong expectedValidFrom causes CAS failure on intent2
        RepairIntent intent2 = repairIntent(
                UUID.randomUUID().toString(), EXEMPTION_ID_2,
                Instant.parse("2026-09-01T10:00:00Z"), // wrong
                Instant.parse("2026-10-06T10:00:00Z"), DIGEST_I);

        assertThatThrownBy(() ->
                punchExemptionRepairService.batchRepair(List.of(intent1, intent2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CAS")
                .hasMessageContaining(EXEMPTION_ID_2);

        Long receiptCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM punch_exemption_repair_receipt" +
                " WHERE exemption_id IN (?, ?)", Long.class,
                EXEMPTION_ID_1, EXEMPTION_ID_2);
        assertThat(receiptCount).as("both receipts must be rolled back").isEqualTo(0);

        Instant ex1From = jdbc.queryForObject(
                "SELECT valid_from FROM punch_exemption_assignment" +
                " WHERE exemption_id = ?",
                Timestamp.class, EXEMPTION_ID_1).toInstant();
        assertThat(ex1From).as("first exemption must be unchanged").isEqualTo(from1);
    }

    @Test
    void repair_concurrentSameRequest_exactlyOneReceiptIdempotent() throws Exception {
        Instant original = Instant.parse("2026-10-01T08:00:00Z");
        Instant target   = Instant.parse("2026-10-03T08:00:00Z");
        insertExemption(EXEMPTION_ID_1, original);
        String reqId = UUID.randomUUID().toString();

        RepairIntent intent = repairIntent(reqId, EXEMPTION_ID_1, original, target, DIGEST_J);

        CyclicBarrier connBarrier = new CyclicBarrier(2);
        CyclicBarrier callBarrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicLong connId0 = new AtomicLong();
        AtomicLong connId1 = new AtomicLong();
        List<AtomicReference<RepairReceipt>> results = List.of(
                new AtomicReference<>(), new AtomicReference<>());

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final int idx = i;
            final AtomicLong connIdRef = (idx == 0) ? connId0 : connId1;
            futures.add(pool.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                                ACTOR_ID, "test", List.of()));
                try {
                    txTemplate.setIsolationLevel(
                            org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
                    txTemplate.executeWithoutResult(status -> {
                        Long cid = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                        connIdRef.set(cid);
                        String iso = jdbc.queryForObject(
                                "SELECT @@transaction_isolation", String.class);
                        if (!"READ-COMMITTED".equals(iso)) {
                            throw new IllegalStateException(
                                    "Expected READ-COMMITTED isolation, got: " + iso);
                        }
                        try {
                            connBarrier.await(5, TimeUnit.SECONDS);
                            callBarrier.await(5, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                        RepairReceipt r = punchExemptionRepairService.repair(intent);
                        results.get(idx).set(r);
                    });
                } finally {
                    SecurityContextHolder.clearContext();
                }
                return null;
            }));
        }

        for (Future<?> f : futures) f.get(15, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(connId0.get()).isNotZero();
        assertThat(connId1.get()).isNotZero();
        assertThat(connId0.get()).isNotEqualTo(connId1.get());

        RepairReceipt r0 = results.get(0).get();
        RepairReceipt r1 = results.get(1).get();
        assertThat(r0).isNotNull();
        assertThat(r1).isNotNull();
        assertThat(r0.receiptId()).isEqualTo(r1.receiptId());
        assertThat(r0.repairedAt()).isEqualTo(r1.repairedAt());
        assertThat(r0.beforeDigest()).isEqualTo(r1.beforeDigest());
        assertThat(r0.afterDigest()).isEqualTo(r1.afterDigest());

        Long receiptCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM punch_exemption_repair_receipt" +
                " WHERE exemption_id = ?", Long.class, EXEMPTION_ID_1);
        assertThat(receiptCount).isEqualTo(1);

        Instant finalFrom = jdbc.queryForObject(
                "SELECT valid_from FROM punch_exemption_assignment" +
                " WHERE exemption_id = ?",
                Timestamp.class, EXEMPTION_ID_1).toInstant();
        assertThat(finalFrom).isEqualTo(target);

        Long auditCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE resource_id_ref = ?",
                Long.class, EXEMPTION_ID_1);
        assertThat(auditCount).isEqualTo(1);
    }

    // ---- fixture helpers -------------------------------------------------

    private void cleanAll() {
        jdbc.update("DELETE FROM attendance_report_oa_overtime_recognition" +
                " WHERE company_id = ?", COMPANY_ID);
        jdbc.update("DELETE FROM attendance_report_oa_evidence_exclusion" +
                " WHERE company_id = ?", COMPANY_ID);
        jdbc.update("DELETE FROM punch_exemption_repair_receipt" +
                " WHERE company_id = ?", COMPANY_ID);
        jdbc.update("DELETE FROM audit_event WHERE actor_id_ref = ?", ACTOR_ID);
        jdbc.update("DELETE FROM punch_exemption_assignment WHERE exemption_id IN (?, ?)",
                EXEMPTION_ID_1, EXEMPTION_ID_2);
        jdbc.update("DELETE FROM oa_attendance_document_context" +
                " WHERE oa_attendance_document_context_id = ?", OA_CTX_OT);
        jdbc.update("DELETE FROM oa_attendance_document" +
                " WHERE oa_attendance_document_id IN (?, ?)",
                OA_DOC_OT, OA_DOC_LEAVE);
        jdbc.update("DELETE FROM employee_match_decision" +
                " WHERE employee_match_decision_id IN (?, ?)",
                MATCH_DEC_OT, MATCH_DEC_LEAVE);
        jdbc.update("DELETE FROM normalized_attendance_record" +
                " WHERE normalized_attendance_record_id IN (?, ?)",
                NORM_REC_OT, NORM_REC_LEAVE);
        jdbc.update("DELETE FROM raw_attendance_fact" +
                " WHERE raw_attendance_fact_id IN (?, ?)",
                RAW_FACT_OT, RAW_FACT_LEAVE);
        // Delete contract revision we inserted (bypass-FK chain)
        jdbc.update("DELETE FROM oa_runtime_contract_revision" +
                " WHERE oa_runtime_contract_revision_id = ?", CONTRACT_REV_ID);
        jdbc.update("DELETE FROM employment_assignment WHERE assignment_id = ?", ASSIGN_ID);
        // attendance_source must be deleted before auth_principal:
        // V8 fk_att_source_actor references auth_principal(principal_id) via created_by.
        jdbc.update("DELETE FROM attendance_source" +
                " WHERE attendance_source_id = ?", OA_SOURCE_ID);
        // Delete only the role assignment we added for ACTOR_ID; never delete seeded rows
        jdbc.update("DELETE FROM auth_principal_role_assignment" +
                " WHERE principal_id = ?", ACTOR_ID);
        jdbc.update("DELETE FROM auth_data_scope WHERE scope_id = ?", SCOPE_ID);
        jdbc.update("DELETE FROM employee_current_projection WHERE employee_id = ?", EMPLOYEE_ID);
        jdbc.update("DELETE FROM employee_version WHERE employee_version_id = ?", EMP_VERSION_ID);
        jdbc.update("DELETE FROM auth_principal WHERE principal_id = ?", ACTOR_ID);
        jdbc.update("DELETE FROM employee WHERE employee_id = ?", EMPLOYEE_ID);
        jdbc.update("DELETE FROM organization_identity WHERE organization_id = ?", ORG_ID);
        jdbc.update("DELETE FROM company WHERE company_id = ?", COMPANY_ID);
    }

    private void insertBaseFixtures() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        jdbc.update("INSERT INTO company (company_id, code, name, status, created_at)" +
                " VALUES (?, 'SZTEST-IT', '深圳IT测试公司', 'ACTIVE', ?)",
                COMPANY_ID, Timestamp.from(now));

        // organization_identity: real columns are organization_id, company_id,
        // identity_status, created_at (V1). No code/name columns.
        jdbc.update("INSERT INTO organization_identity" +
                " (organization_id, company_id, identity_status, created_at)" +
                " VALUES (?, ?, 'ACTIVE', ?)",
                ORG_ID, COMPANY_ID, Timestamp.from(now));

        jdbc.update("INSERT INTO employee" +
                " (employee_id, company_id, employee_number, display_name, employment_status," +
                " created_at, updated_at)" +
                " VALUES (?, ?, 'EMP-IT01', '测试IT员工', 'ACTIVE', ?, ?)",
                EMPLOYEE_ID, COMPANY_ID, Timestamp.from(now), Timestamp.from(now));

        // auth_principal must be inserted before employee_version (FK on created_by)
        jdbc.update("INSERT INTO auth_principal" +
                " (principal_id, employee_id, status, created_at, row_version)" +
                " VALUES (?, ?, 'ACTIVE', ?, 0)",
                ACTOR_ID, EMPLOYEE_ID, Timestamp.from(now));

        // source_authority CHECK: IN ('INITIAL_EXCEL', 'LOCAL')
        jdbc.update("INSERT INTO employee_version" +
                " (employee_version_id, employee_id, employee_number," +
                " display_name, status, effective_from, effective_to," +
                " source_authority, created_at, created_by)" +
                " VALUES (?, ?, 'EMP-IT01', '测试IT员工', 'ACTIVE'," +
                " ?, NULL, 'LOCAL', ?, ?)",
                EMP_VERSION_ID, EMPLOYEE_ID,
                java.sql.Date.valueOf("2026-01-01"), Timestamp.from(now), ACTOR_ID);

        jdbc.update("INSERT INTO employee_current_projection" +
                " (employee_id, current_version_id, projected_at) VALUES (?, ?, ?)",
                EMPLOYEE_ID, EMP_VERSION_ID, Timestamp.from(now));

        // employment_assignment: employment_period_id NOT NULL after V5 ALTER.
        // Use the same value as the assignment_id (the V5 backfill pattern).
        // version_valid_to IS NULL and record_status = 'ACTIVE' required by
        // canAccessEmployee CTE (EmployeeReadMapper.xml).
        jdbc.update("INSERT INTO employment_assignment" +
                " (assignment_id, employee_id, organization_id," +
                " effective_from, effective_to," +
                " employment_period_id, version_valid_to, record_status)" +
                " VALUES (?, ?, ?, ?, NULL, ?, NULL, 'ACTIVE')",
                ASSIGN_ID, EMPLOYEE_ID, ORG_ID,
                Timestamp.from(now), ASSIGN_ID /* employment_period_id = assignment_id */);

        // Re-use seeded scope+role from Flyway; only insert the role assignment
        // and data scope needed for ACTOR_ID.
        jdbc.update("INSERT INTO auth_data_scope" +
                " (scope_id, scope_type, company_id, organization_id," +
                " include_descendants, valid_from, valid_to)" +
                " VALUES (?, 'COMPANY', ?, NULL, TRUE, ?, NULL)",
                SCOPE_ID, COMPANY_ID, Timestamp.from(now));

        // Seeded role: ROLE_HR_ADMIN (10000000-0000-0000-0000-000000000001)
        // Already has ATTENDANCE_REPORT:REFRESH and EMPLOYEE:EDIT from V5/V14.
        jdbc.update("INSERT INTO auth_principal_role_assignment" +
                " (assignment_id, principal_id, role_id, data_scope_id," +
                " valid_from, valid_to, assigned_by, reason, row_version)" +
                " VALUES (?, ?, ?, ?, ?, NULL, 'SYSTEM', 'IT setup', 0)",
                UUID.randomUUID().toString(), ACTOR_ID, ROLE_HR_ADMIN, SCOPE_ID,
                Timestamp.from(now));

        // Shared OA attendance source — lockActiveOaSource queries this UUID
        jdbc.update("INSERT INTO attendance_source" +
                " (attendance_source_id, company_id, source_code, source_type," +
                " display_name, status, created_by, created_at)" +
                " VALUES (?, ?, 'OA_MAIN_IT', 'OA_ATTENDANCE'," +
                " 'OA主源IT', 'ACTIVE', ?, ?)",
                OA_SOURCE_ID, COMPANY_ID, ACTOR_ID, Timestamp.from(now));

        // Minimal DRAFT oa_runtime_contract_revision needed by
        // oa_attendance_document_context FK. FK_CHECKS stay ON.
        // contract_digest: any valid hex-64 string.
        jdbc.update("INSERT INTO oa_runtime_contract_revision" +
                " (oa_runtime_contract_revision_id, attendance_source_id," +
                " revision_number, dictionary_version, contract_status," +
                " source_time_zone, date_range_end_rule," +
                " contract_digest, change_reason, row_version," +
                " created_by, created_at)" +
                " VALUES (?, ?, 1, 'v1-it', 'DRAFT'," +
                " 'Asia/Shanghai', 'NOT_VERIFIED'," +
                " ?, 'IT test fixture', 0, ?, ?)",
                CONTRACT_REV_ID, OA_SOURCE_ID,
                PunchExemptionRepairService.stableDigest("contract-it"),
                ACTOR_ID, Timestamp.from(now));
    }

    /**
     * Inserts the full OA overtime document chain required by
     * OaOvertimeRecognitionWriteService: raw_attendance_fact,
     * normalized_attendance_record, oa_attendance_document (OVERTIME/APPROVED),
     * employee_match_decision, oa_attendance_document_context (ACTIVATED/COMPENSATORY).
     */
    private void insertOtimeDocument() {
        Instant now = Instant.parse("2026-10-08T00:00:00Z");

        jdbc.update("INSERT INTO raw_attendance_fact" +
                " (raw_attendance_fact_id, attendance_source_id, company_id," +
                " fact_kind, source_business_key, source_version," +
                " source_time_text, source_time_zone," +
                " interval_start, interval_end," +
                " canonical_payload_digest, request_id, received_at, created_by)" +
                " VALUES (?, ?, ?, 'OA_DOCUMENT', ?, ?," +
                " '2026-10-08', 'Asia/Shanghai', ?, ?, ?, ?, ?, ?)",
                RAW_FACT_OT, OA_SOURCE_ID, COMPANY_ID,
                OT_BIZ_KEY, OT_VERSION,
                Timestamp.from(OT_START), Timestamp.from(OT_END),
                PunchExemptionRepairService.stableDigest("raw-ot"),
                UUID.randomUUID().toString(),
                Timestamp.from(now), ACTOR_ID);

        jdbc.update("INSERT INTO normalized_attendance_record" +
                " (normalized_attendance_record_id, raw_attendance_fact_id," +
                " normalization_revision, schema_version, record_kind," +
                " interval_start, interval_end, validation_status," +
                " canonical_digest, created_at)" +
                " VALUES (?, ?, 1, 'v1', 'OA_INTERVAL', ?, ?, 'VALID', ?, ?)",
                NORM_REC_OT, RAW_FACT_OT,
                Timestamp.from(OT_START), Timestamp.from(OT_END),
                PunchExemptionRepairService.stableDigest("norm-ot"),
                Timestamp.from(now));

        jdbc.update("INSERT INTO oa_attendance_document" +
                " (oa_attendance_document_id, attendance_source_id," +
                " source_business_key, source_version," +
                " document_type, source_status," +
                " normalized_attendance_record_id, knowledge_rank, created_at)" +
                " VALUES (?, ?, ?, ?, 'OVERTIME', 'APPROVED', ?, 1, ?)",
                OA_DOC_OT, OA_SOURCE_ID,
                OT_BIZ_KEY, OT_VERSION,
                NORM_REC_OT, Timestamp.from(now));

        jdbc.update("INSERT INTO employee_match_decision" +
                " (employee_match_decision_id, normalized_attendance_record_id," +
                " match_status, match_reason, employee_id, employment_period_id," +
                " resolver_snapshot_digest, created_at)" +
                " VALUES (?, ?, 'MATCHED', 'STANDING_LIST', ?, ?, ?, ?)",
                MATCH_DEC_OT, NORM_REC_OT,
                EMPLOYEE_ID, ASSIGN_ID,
                PunchExemptionRepairService.stableDigest("match-ot"),
                Timestamp.from(now));

        // oa_attendance_document_context: overtime_type = 'COMPENSATORY' required
        // by V75 service gate. FK to CONTRACT_REV_ID (DRAFT row inserted above).
        jdbc.update("INSERT INTO oa_attendance_document_context" +
                " (oa_attendance_document_context_id, oa_attendance_document_id," +
                " oa_runtime_contract_revision_id, activation_decision," +
                " raw_status_value, overtime_treatment, overtime_type," +
                " authorized_context_json, context_digest, created_at)" +
                " VALUES (?, ?, ?, 'ACTIVATED', '3', 'NORMAL', 'COMPENSATORY'," +
                " '{}', ?, ?)",
                OA_CTX_OT, OA_DOC_OT, CONTRACT_REV_ID,
                PunchExemptionRepairService.stableDigest("ctx-ot"),
                Timestamp.from(now));
    }

    /**
     * Inserts the full OA leave document chain required by
     * OaEvidenceExclusionWriteService: document_type=LEAVE, leave_type=ANNUAL,
     * source_status=APPROVED, validation_status=VALID, match_status=MATCHED.
     * No oa_attendance_document_context needed for V74 (it uses findDocumentGeometry
     * which does not join the context table).
     */
    private void insertLeaveDocument() {
        Instant now = Instant.parse("2026-10-09T00:00:00Z");

        jdbc.update("INSERT INTO raw_attendance_fact" +
                " (raw_attendance_fact_id, attendance_source_id, company_id," +
                " fact_kind, source_business_key, source_version," +
                " source_time_text, source_time_zone," +
                " interval_start, interval_end," +
                " canonical_payload_digest, request_id, received_at, created_by)" +
                " VALUES (?, ?, ?, 'OA_DOCUMENT', ?, ?," +
                " '2026-10-09', 'Asia/Shanghai', ?, ?, ?, ?, ?, ?)",
                RAW_FACT_LEAVE, OA_SOURCE_ID, COMPANY_ID,
                LEAVE_BIZ_KEY, LEAVE_VERSION,
                Timestamp.from(LEAVE_START), Timestamp.from(LEAVE_END),
                PunchExemptionRepairService.stableDigest("raw-lv"),
                UUID.randomUUID().toString(),
                Timestamp.from(now), ACTOR_ID);

        jdbc.update("INSERT INTO normalized_attendance_record" +
                " (normalized_attendance_record_id, raw_attendance_fact_id," +
                " normalization_revision, schema_version, record_kind," +
                " interval_start, interval_end, validation_status," +
                " canonical_digest, created_at)" +
                " VALUES (?, ?, 1, 'v1', 'OA_INTERVAL', ?, ?, 'VALID', ?, ?)",
                NORM_REC_LEAVE, RAW_FACT_LEAVE,
                Timestamp.from(LEAVE_START), Timestamp.from(LEAVE_END),
                PunchExemptionRepairService.stableDigest("norm-lv"),
                Timestamp.from(now));

        jdbc.update("INSERT INTO oa_attendance_document" +
                " (oa_attendance_document_id, attendance_source_id," +
                " source_business_key, source_version," +
                " document_type, source_status, leave_type," +
                " normalized_attendance_record_id, knowledge_rank, created_at)" +
                " VALUES (?, ?, ?, ?, 'LEAVE', 'APPROVED', 'ANNUAL', ?, 1, ?)",
                OA_DOC_LEAVE, OA_SOURCE_ID,
                LEAVE_BIZ_KEY, LEAVE_VERSION,
                NORM_REC_LEAVE, Timestamp.from(now));

        jdbc.update("INSERT INTO employee_match_decision" +
                " (employee_match_decision_id, normalized_attendance_record_id," +
                " match_status, match_reason, employee_id, employment_period_id," +
                " resolver_snapshot_digest, created_at)" +
                " VALUES (?, ?, 'MATCHED', 'STANDING_LIST', ?, ?, ?, ?)",
                MATCH_DEC_LEAVE, NORM_REC_LEAVE,
                EMPLOYEE_ID, ASSIGN_ID,
                PunchExemptionRepairService.stableDigest("match-lv"),
                Timestamp.from(now));
    }

    private void insertExemption(String exemptionId, Instant validFrom) {
        jdbc.update("INSERT INTO punch_exemption_assignment" +
                " (exemption_id, employee_id, listed_employee_number," +
                " source, valid_from, valid_to, created_at)" +
                " VALUES (?, ?, 'EMP-IT01', 'STANDING_LIST', ?, NULL, ?)",
                exemptionId, EMPLOYEE_ID,
                Timestamp.from(validFrom),
                Timestamp.from(Instant.parse("2026-09-01T00:00:00Z")));
    }

    private static RepairIntent repairIntent(String reqId, String exemptionId,
            Instant expectedFrom, Instant targetFrom, String approvalDigest) {
        return new RepairIntent(EMPLOYEE_ID, "EMP-IT01", COMPANY_ID, exemptionId,
                expectedFrom, targetFrom, reqId, approvalDigest);
    }
}
