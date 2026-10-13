package com.szsemicon.hr.evidenceingestion.infrastructure.oa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort.SourceStatus;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort.OaDocumentRecord;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import java.util.stream.Stream;

class OaMysqlAttendanceDocumentAdapterRefreshTest {
    private String url;
    private OaMysqlAttendanceDocumentAdapter adapter;
    private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-01T00:00:00Z");

    @BeforeEach
    void database() throws Exception {
        url = "jdbc:h2:mem:oa-refresh-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        adapter = new OaMysqlAttendanceDocumentAdapter(() -> DriverManager.getConnection(url), new OaMysqlProperties());
        execute("CREATE TABLE col_summary (form_recordid BIGINT, state INT, finish_date TIMESTAMP, start_date TIMESTAMP, create_date TIMESTAMP)");
        execute("CREATE TABLE ctp_enum_item (id BIGINT, showvalue VARCHAR(32))");
        execute("INSERT INTO ctp_enum_item VALUES (1, '事假')");
        execute("CREATE TABLE formmain_0170 (id BIGINT, field0083 VARCHAR(32), field0084 VARCHAR(32), field0097 VARCHAR(32), field0086 TIMESTAMP, field0087 TIMESTAMP, field0089 BIGINT)");
        execute("CREATE TABLE formmain_0370 (id BIGINT, field0083 VARCHAR(32), field0084 VARCHAR(32), field0097 VARCHAR(32), field0099 VARCHAR(32), field0086 TIMESTAMP, field0087 TIMESTAMP, field0100 BIGINT)");
        for (String main : List.of("0171", "0251", "0201", "0203")) { execute("CREATE TABLE formmain_" + main + " (id BIGINT)"); }
        execute("CREATE TABLE formson_0172 (id BIGINT, formmain_id BIGINT, field0093 VARCHAR(32), field0094 VARCHAR(32), field0100 TIMESTAMP, field0099 TIMESTAMP, field0096 BIGINT)");
        execute("CREATE TABLE formson_0252 (id BIGINT, formmain_id BIGINT, field0127 VARCHAR(32), field0131 VARCHAR(32), field0132 TIMESTAMP, field0135 TIMESTAMP)");
        execute("CREATE TABLE formson_0202 (id BIGINT, formmain_id BIGINT, field0127 VARCHAR(32), field0131 VARCHAR(32), field0132 TIMESTAMP, field0134 TIMESTAMP)");
        execute("CREATE TABLE formson_0204 (id BIGINT, formmain_id BIGINT, field0127 VARCHAR(32), field0131 VARCHAR(32), field0132 TIMESTAMP)");
    }

    @Test
    void readsCrossWindowLeaveAndLinkedZeroRevocationOutsideItsActualWindow() throws Exception {
        leave(1, "L1", "2026-08-31 08:30:00", "2026-09-02 11:30:00", 0);
        leave(2, "L2", "2026-09-09 08:30:00", "2026-09-09 11:30:00", 1);
        execute("INSERT INTO formmain_0370 (id, field0083, field0084, field0097, field0099, field0086, field0087) VALUES (3, 'member', 'E1', 'R1', 'L2', '2026-10-02 08:30:00', '2026-10-02 08:30:00')");
        summary(3, 3);
        var records = adapter.fetchOverlapping("source", FROM, TO);
        assertThat(records).extracting(row -> row.sourceBusinessKey()).containsExactly("LEAVE:1", "LEAVE:2", "LEAVE_REVOCATION:3");
        assertThat(records.get(0).sourceStatus()).isEqualTo(SourceStatus.PENDING);
        assertThat(records.get(1).sourceStatus()).isEqualTo(SourceStatus.UNKNOWN);
        assertThat(records.get(2).start()).isEqualTo(records.get(2).end());
    }

    @Test
    void rereadsKnownKeyAfterDatesOrAttributionDisappearWithoutMovingSourceTimestamp() throws Exception {
        leave(1, "L1", "2026-09-09 08:30:00", "2026-09-09 11:30:00", 3);
        var before = adapter.fetchByBusinessKeys("source", List.of("LEAVE:1")).getFirst();
        execute("UPDATE formmain_0170 SET field0087='2026-09-09 10:30:00' WHERE id=1");
        var changed = adapter.fetchByBusinessKeys("source", List.of("LEAVE:1")).getFirst();
        assertThat(changed.sourceVersion()).isEqualTo(before.sourceVersion());
        assertThat(changed.end()).isNotEqualTo(before.end());
        execute("UPDATE formmain_0170 SET field0083=NULL, field0084=NULL, field0086=NULL, field0087=NULL, field0089=NULL WHERE id=1");
        execute("UPDATE col_summary SET state=2 WHERE form_recordid=1");
        var cancelled = adapter.fetchByBusinessKeys("source", List.of("LEAVE:1")).getFirst();
        assertThat(cancelled.sourceStatus()).isEqualTo(SourceStatus.REVOKED);
        assertThat(cancelled.start()).isNull();
        assertThat(cancelled.externalPersonRef()).isNull();
        assertThat(cancelled.leaveType()).isNull();
        assertThat(cancelled.sourceVersion()).contains(":state=2:");
        assertThat(adapter.fetchOverlapping("source", FROM, TO)).isEmpty();
    }

    @Test
    void missingWorkflowAndMissingActualEndStayExplicitRatherThanInventingZeroLeave() throws Exception {
        leave(1, "L1", "2026-09-09 08:30:00", "2026-09-09 11:30:00", 3);
        execute("DELETE FROM col_summary WHERE form_recordid=1");
        var draft = adapter.fetchByBusinessKeys("source", List.of("LEAVE:1")).getFirst();
        assertThat(draft.sourceStatus()).isEqualTo(SourceStatus.DRAFT);
        assertThat(draft.sourceVersion()).contains("NO_TIMESTAMP:state=NULL");
        execute("INSERT INTO formmain_0370 (id, field0083, field0084, field0097, field0099, field0086, field0087) VALUES (3, 'member', 'E1', 'R1', 'L1', '2026-09-09 08:30:00', NULL)");
        summary(3, 3);
        var revocation = adapter.fetchByBusinessKeys("source", List.of("LEAVE_REVOCATION:3")).getFirst();
        assertThat(revocation.end()).isNull();
        assertThatThrownBy(() -> adapter.fetchByBusinessKeys("source", List.of("LEAVE:1 OR 1=1")))
                .isInstanceOf(OaReadOnlyQueryException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"LEAVE", "LEAVE_REVOCATION"})
    void missingMainWithExplicitRevokedWorkflowProducesStableSameKeyTombstone(String family) throws Exception {
        long id = 2535651520287166161L;
        summary(id, 2);
        String key = family + ":" + id;
        var records = adapter.fetchByBusinessKeys("source", List.of(key));
        assertThat(records).hasSize(1);
        var revoked = records.getFirst();
        assertThat(revoked.sourceBusinessKey()).isEqualTo(key);
        assertThat(revoked.documentType().name()).isEqualTo(family);
        assertThat(revoked.sourceStatus()).isEqualTo(SourceStatus.REVOKED);
        assertThat(revoked.sourceVersion()).startsWith("OA_MAIN_REVOKED:").hasSizeLessThanOrEqualTo(90);
        assertThat(revoked.effectiveCandidate()).isFalse();
        assertThat(revoked.start()).isNull();
        assertThat(revoked.end()).isNull();
        assertThat(revoked.externalPersonRef()).isNull();
        assertThat(revoked.employeeNumber()).isNull();
        assertThat(revoked.leaveType()).isNull();
        assertThat(revoked.leaveSerial()).isNull();
        assertThat(revoked.originalLeaveSerial()).isNull();
        assertThat(adapter.fetchByBusinessKeys("source", List.of(key, key))).containsExactly(revoked);
    }

    @ParameterizedTest
    @ValueSource(strings = {"LEAVE", "LEAVE_REVOCATION"})
    void absenceAndNonterminalOrConflictingWorkflowsNeverProveRevocation(String family) throws Exception {
        summary(1, 0);
        summary(2, 3);
        summary(3, 1);
        summary(4, 2);
        summary(4, 3);
        summary(5, 2);
        execute("INSERT INTO col_summary VALUES (5, NULL, NULL, NULL, NULL)");
        execute("INSERT INTO col_summary VALUES (6, NULL, NULL, NULL, NULL)");
        summary(7, 2);
        summary(7, 0);
        assertThat(adapter.fetchByBusinessKeys("source", java.util.stream.LongStream.rangeClosed(1, 8)
                .mapToObj(id -> family + ":" + id).toList())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"LEAVE", "LEAVE_REVOCATION"})
    void unanimouslyRevokedSummaryEvidenceIsIndependentOfRowOrderAndChangesWhenEvidenceChanges(String family) throws Exception {
        long id = -91;
        summary(id, 2);
        execute("INSERT INTO col_summary VALUES (-91, 2, '2026-09-30 12:00:00', NULL, NULL)");
        String key = family + ":" + id;
        var original = adapter.fetchByBusinessKeys("source", List.of(key)).getFirst();
        execute("DELETE FROM col_summary WHERE form_recordid=-91");
        execute("INSERT INTO col_summary VALUES (-91, 2, '2026-09-30 12:00:00', NULL, NULL)");
        summary(id, 2);
        assertThat(adapter.fetchByBusinessKeys("source", List.of(key))).containsExactly(original);
        execute("UPDATE col_summary SET finish_date='2026-10-01 12:00:00' WHERE finish_date IS NOT NULL");
        assertThat(adapter.fetchByBusinessKeys("source", List.of(key)).getFirst().sourceVersion())
                .isNotEqualTo(original.sourceVersion());
    }

    @Test
    void presentMainRowsRetainTheirOriginalIntervalsAndAreNeverReplacedByMissingRowTombstones() throws Exception {
        leave(1, "L1", "2026-09-09 08:30:00", "2026-09-09 11:30:00", 2);
        execute("INSERT INTO formmain_0370 (id, field0083, field0084, field0097, field0099, field0086, field0087) VALUES (3, 'member', 'E1', 'R1', 'L1', '2026-09-09 08:30:00', '2026-09-09 10:30:00')");
        summary(3, 2);
        var records = adapter.fetchByBusinessKeys("source", List.of("LEAVE:1", "LEAVE_REVOCATION:3"));
        assertThat(records).hasSize(2);
        assertThat(records).allSatisfy(record -> {
            assertThat(record.start()).isNotNull();
            assertThat(record.end()).isNotNull();
            assertThat(record.employeeNumber()).isEqualTo("E1");
            assertThat(record.sourceVersion()).doesNotStartWith("OA_MAIN_REVOKED:");
        });
        assertThat(records.get(1).originalLeaveSerial()).isEqualTo("L1");
    }

    @Test
    void missingDetailsCannotUseAnUnrelatedWorkflowWhoseMainIdHappensToEqualTheirDetailId() throws Exception {
        summary(40, 2);
        summary(41, 2);
        assertThat(adapter.fetchByBusinessKeys("source", List.of("OVERTIME:40", "PUNCH_CORRECTION:41")))
                .isEmpty();
    }

    @Test
    void fallbackReadFailureIsNotSilentlyTreatedAsAnEmptyOrRevokedDocument() throws Exception {
        summary(1, 2);
        var failingAdapter = new OaMysqlAttendanceDocumentAdapter(() -> {
            var connection = DriverManager.getConnection(url);
            return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {java.sql.Connection.class}, (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement") && args[0] instanceof String sql
                                && sql.contains("NOT EXISTS")) {
                            throw new java.sql.SQLException("simulated fallback read failure");
                        }
                        try { return method.invoke(connection, args); }
                        catch (java.lang.reflect.InvocationTargetException exception) { throw exception.getCause(); }
                    });
        }, new OaMysqlProperties());
        assertThatThrownBy(() -> failingAdapter.fetchByBusinessKeys("source", List.of("LEAVE:1")))
                .isInstanceOf(OaReadOnlyQueryException.class)
                .hasMessageContaining("OA identity refresh failed");
    }

    @Test
    void explicitCancelLeaveWithUniqueSameMemberOriginalUsesZeroActualIntervalInEveryReadPath() throws Exception {
        cancellation();
        var expected = Instant.parse("2026-09-14T00:30:00Z");
        var rows = cancellationReadPaths();
        assertThat(rows).hasSize(3).allSatisfy(row -> {
            assertThat(row.sourceBusinessKey()).isEqualTo("LEAVE_REVOCATION:12");
            assertThat(row.start()).isEqualTo(expected);
            assertThat(row.end()).isEqualTo(expected);
            assertThat(row.sourceStatus()).isEqualTo(SourceStatus.APPROVED);
            assertThat(row.originalLeaveSerial()).isEqualTo("L-CANCEL");
        });
        assertThat(cancellationReadPaths()).isEqualTo(rows);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2})
    void cancellationGeometryNeverPromotesPendingOrRevokedWorkflowToApproved(int state) throws Exception {
        cancellation();
        execute("UPDATE col_summary SET state=" + state + " WHERE form_recordid=12");
        assertThat(cancellationReadPaths()).hasSize(3).allSatisfy(row -> {
            assertThat(row.start()).isEqualTo(Instant.parse("2026-09-14T00:30:00Z"));
            assertThat(row.end()).isEqualTo(row.start());
            assertThat(row.sourceStatus()).isEqualTo(state == 2 ? SourceStatus.REVOKED : SourceStatus.PENDING);
            assertThat(row.effectiveCandidate()).isFalse();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"revocation", "both"})
    void missingMemberMayUseOnlyExactNonblankEmployeeCode(String missing) throws Exception {
        cancellation();
        execute("UPDATE formmain_0370 SET field0083=NULL WHERE id=12");
        if (missing.equals("both")) execute("UPDATE formmain_0170 SET field0083=NULL WHERE id=11");
        assertThat(cancellationReadPaths()).hasSize(3).allSatisfy(row -> {
            assertThat(row.start()).isEqualTo(Instant.parse("2026-09-14T00:30:00Z"));
            assertThat(row.end()).isEqualTo(row.start());
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unsafeCancellationEvidence")
    void incompleteOrAmbiguousCancellationProofKeepsNullActualDates(String description, String[] statements) throws Exception {
        cancellation();
        for (String sql : statements) execute(sql);
        // By-key and incremental reads always see this row; the window path may
        // omit it if the intentionally broken original no longer intersects.
        var rows = cancellationReadPaths();
        assertThat(rows).as(description).hasSizeBetween(2, 3).allSatisfy(row -> {
            assertThat(row.start()).isNull();
            assertThat(row.end()).isNull();
            assertThat(row.sourceStatus()).isEqualTo(SourceStatus.APPROVED);
        });
    }

    private static Stream<Arguments> unsafeCancellationEvidence() {
        return Stream.of(
                unsafe("unknown type id", "UPDATE formmain_0370 SET field0100=1"),
                unsafe("matching label on unverified enum id", "INSERT INTO ctp_enum_item VALUES (2, '取消请假')", "UPDATE formmain_0370 SET field0100=2"),
                unsafe("known id with changed label", "UPDATE ctp_enum_item SET showvalue='更改请假信息' WHERE id=5457595020900320516"),
                unsafe("label is not exact", "UPDATE ctp_enum_item SET showvalue='取消请假 ' WHERE id=5457595020900320516"),
                unsafe("missing enum label", "DELETE FROM ctp_enum_item WHERE id=5457595020900320516"),
                unsafe("empty original serial", "UPDATE formmain_0370 SET field0099=' '"),
                unsafe("unknown original serial", "UPDATE formmain_0370 SET field0099='NOT-FOUND'"),
                unsafe("serial differs only by case", "UPDATE formmain_0370 SET field0099='l-cancel'"),
                unsafe("serial differs by whitespace", "UPDATE formmain_0370 SET field0099='L-CANCEL '"),
                unsafe("no original", "DELETE FROM formmain_0170"),
                unsafe("duplicate serial for same person", "INSERT INTO formmain_0170 SELECT 13,field0083,field0084,field0097,field0086,field0087,field0089 FROM formmain_0170 WHERE id=11"),
                unsafe("duplicate serial for another person", "INSERT INTO formmain_0170 SELECT 13,'another-member','OTHER',field0097,field0086,field0087,field0089 FROM formmain_0170 WHERE id=11"),
                unsafe("present revocation member cannot fall back when original member is missing", "UPDATE formmain_0170 SET field0083=NULL"),
                unsafe("two known different members cannot fall back to equal code", "UPDATE formmain_0370 SET field0083='different-member'"),
                unsafe("member comparison is exact", "UPDATE formmain_0370 SET field0083='member '"),
                unsafe("missing member and mismatching code", "UPDATE formmain_0370 SET field0083=NULL,field0084='OTHER'"),
                unsafe("missing member and blank code", "UPDATE formmain_0370 SET field0083=NULL,field0084=' '"),
                unsafe("missing member and aliased code is not exact proof", "UPDATE formmain_0370 SET field0083=NULL,field0084='SZT0221'", "UPDATE formmain_0170 SET field0084='SZST0221'"),
                unsafe("missing original start", "UPDATE formmain_0170 SET field0086=NULL"),
                unsafe("missing original end", "UPDATE formmain_0170 SET field0087=NULL"),
                unsafe("zero original interval", "UPDATE formmain_0170 SET field0087=field0086"),
                unsafe("reversed original interval", "UPDATE formmain_0170 SET field0087='2026-09-14 07:00:00'")
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"start", "end"})
    void explicitCancelTypeCannotFillOnlyOneMissingActualEndpoint(String present) throws Exception {
        cancellation();
        execute("UPDATE formmain_0370 SET " + (present.equals("start") ? "field0086" : "field0087")
                + "='2026-09-14 09:00:00' WHERE id=12");
        assertThat(cancellationReadPaths()).hasSize(3).allSatisfy(row -> {
            if (present.equals("start")) {
                assertThat(row.start()).isEqualTo(Instant.parse("2026-09-14T01:00:00Z"));
                assertThat(row.end()).isNull();
            } else {
                assertThat(row.start()).isNull();
                assertThat(row.end()).isEqualTo(Instant.parse("2026-09-14T01:00:00Z"));
            }
        });
    }

    @Test
    void existingNonzeroActualIntervalRemainsExactEvenForCancelType() throws Exception {
        cancellation();
        execute("UPDATE formmain_0370 SET field0086='2026-09-14 09:00:00',field0087='2026-09-14 10:00:00' WHERE id=12");
        assertThat(cancellationReadPaths()).hasSize(3).allSatisfy(row -> {
            assertThat(row.start()).isEqualTo(Instant.parse("2026-09-14T01:00:00Z"));
            assertThat(row.end()).isEqualTo(Instant.parse("2026-09-14T02:00:00Z"));
        });
    }

    private static Arguments unsafe(String description, String... statements) {
        return Arguments.of(description, statements);
    }

    @Test
    void amendmentAbsenceQueryIncludesUnapprovedUndatedAndUnmatchedRowsWithoutSummary() throws Exception {
        assertThat(adapter.hasNoLeaveRevocationsForSerial("source", "L1")).isTrue();
        execute("INSERT INTO formmain_0370 (id, field0099) VALUES (99, ' L1 ')");
        assertThat(adapter.hasNoLeaveRevocationsForSerial("source", "L1")).isFalse();
        assertThat(adapter.hasNoLeaveRevocationsForSerial("source", "L2")).isTrue();
    }

    @Test
    void anyBlankOriginalSerialOrUnavailableQueryCannotProveNoAmendments() throws Exception {
        execute("INSERT INTO formmain_0370 (id) VALUES (99)");
        assertThat(adapter.hasNoLeaveRevocationsForSerial("source", "L1")).isFalse();
        execute("DELETE FROM formmain_0370");
        execute("INSERT INTO formmain_0370 (id, field0099) VALUES (100, '  ')");
        assertThat(adapter.hasNoLeaveRevocationsForSerial("source", "L1")).isFalse();
        execute("DROP TABLE formmain_0370");
        assertThatThrownBy(() -> adapter.hasNoLeaveRevocationsForSerial("source", "L1"))
                .isInstanceOf(OaReadOnlyQueryException.class);
    }

    @Test
    void nonExactOrAbsentCandidateSerialNeverRunsAnAbsenceInference() {
        assertThat(adapter.hasNoLeaveRevocationsForSerial("source", null)).isFalse();
        assertThat(adapter.hasNoLeaveRevocationsForSerial("source", "")).isFalse();
        assertThat(adapter.hasNoLeaveRevocationsForSerial("source", " L1")).isFalse();
        assertThat(adapter.hasNoLeaveRevocationsForSerial("source", "L1 ")).isFalse();
    }

    private void cancellation() throws Exception {
        leave(11, "L-CANCEL", "2026-09-14 08:30:00", "2026-09-14 12:00:00", 3);
        execute("INSERT INTO ctp_enum_item VALUES (5457595020900320516, '取消请假')");
        execute("INSERT INTO formmain_0370 VALUES (12, 'member', 'E1', 'R-CANCEL', 'L-CANCEL', NULL, NULL, 5457595020900320516)");
        summary(12, 3);
    }

    private List<OaDocumentRecord> cancellationReadPaths() {
        var rows = new java.util.ArrayList<OaDocumentRecord>();
        rows.addAll(adapter.fetchByBusinessKeys("source", List.of("LEAVE_REVOCATION:12")));
        rows.addAll(adapter.fetchOverlapping("source", FROM, TO).stream()
                .filter(row -> row.sourceBusinessKey().equals("LEAVE_REVOCATION:12")).toList());
        rows.addAll(adapter.fetchPage("source", null).records().stream()
                .filter(row -> row.sourceBusinessKey().equals("LEAVE_REVOCATION:12")).toList());
        return rows;
    }

    private void leave(long id, String serial, String start, String end, int state) throws Exception {
        execute("INSERT INTO formmain_0170 VALUES (" + id + ", 'member', 'E1', '" + serial + "', '" + start + "', '" + end + "', 1)");
        summary(id, state);
    }
    private void summary(long id, int state) throws Exception {
        execute("INSERT INTO col_summary VALUES (" + id + ", " + state + ", NULL, '2026-08-01 12:00:00', '2026-08-01 12:00:00')");
    }
    private void execute(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) { statement.execute(sql); }
    }
}
