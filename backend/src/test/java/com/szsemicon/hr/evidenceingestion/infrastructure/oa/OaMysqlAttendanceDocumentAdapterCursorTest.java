package com.szsemicon.hr.evidenceingestion.infrastructure.oa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort.OaPage;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class OaMysqlAttendanceDocumentAdapterCursorTest {

    @Test
    void compositeCursorKeepsIndependentFormPositionsAndUsesRealDigest()
            throws Exception {
        Instant outingModified = Instant.parse("2026-08-20T01:00:00Z");
        Instant exemptionModified = Instant.parse("2026-08-10T01:00:00Z");
        Connection connection = mock(Connection.class);
        PreparedStatement emptyStatement = emptyStatement();
        PreparedStatement outing = intervalStatement(
                "start_dt", "end_dt", 252L, outingModified);
        PreparedStatement exemption = intervalStatement(
                "start_date", "end_date", 202L, exemptionModified);
        when(connection.prepareStatement(anyString()))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    if (sql.contains("FROM formson_0252 s")) {
                        return outing;
                    }
                    if (sql.contains("FROM formson_0202 s")) {
                        return exemption;
                    }
                    return emptyStatement;
                });
        OaMysqlAttendanceDocumentAdapter adapter =
                new OaMysqlAttendanceDocumentAdapter(
                        () -> connection, new OaMysqlProperties());

        OaPage page = adapter.fetchPage("oa-source", null);

        assertThat(page.records()).hasSize(2);
        assertThat(page.pageDigest()).matches("[0-9a-f]{64}");
        assertThat(page.nextCursor()).startsWith("oa3|");
        var cursor = OaMysqlAttendanceDocumentAdapter.MultiCursor.parse(
                page.nextCursor());
        assertThat(cursor.outing().lastModifiedMillis())
                .isEqualTo(outingModified.toEpochMilli());
        assertThat(cursor.outing().lastId()).isEqualTo(252L);
        assertThat(cursor.exemptPunch().lastModifiedMillis())
                .isEqualTo(exemptionModified.toEpochMilli());
        assertThat(cursor.exemptPunch().lastId()).isEqualTo(202L);
        assertThat(cursor.leave().lastModifiedMillis()).isZero();
        assertThat(cursor.overtime().lastModifiedMillis()).isZero();

        OaPage terminal = adapter.fetchPage("oa-source", page.nextCursor());
        assertThat(terminal.records()).isEmpty();
        assertThat(terminal.inputCursor()).isEqualTo(page.nextCursor());
        assertThat(terminal.nextCursor()).isEqualTo(page.nextCursor());
        assertThat(terminal.pageDigest())
                .matches("[0-9a-f]{64}")
                .isNotEqualTo(page.pageDigest());
    }

    @Test
    void legacyCursorIsAppliedToEveryStreamDuringUpgrade() {
        var cursor = OaMysqlAttendanceDocumentAdapter.MultiCursor.parse(
                "1723564800000:12345678");

        assertThat(cursor.leave()).isEqualTo(cursor.leaveRevocation());
        assertThat(cursor.leave()).isEqualTo(cursor.overtime());
        assertThat(cursor.leave()).isEqualTo(cursor.outing());
        assertThat(cursor.leave()).isEqualTo(cursor.exemptPunch());
        assertThat(cursor.leave()).isEqualTo(cursor.punchCorrection());
        assertThat(cursor.leave().lastModifiedMillis())
                .isEqualTo(1_723_564_800_000L);
        assertThat(cursor.leave().lastId()).isEqualTo(12_345_678L);
    }

    @Test
    void compositeCursorPreservesSubMillisecondDatabasePrecision() {
        Timestamp sourceTimestamp = Timestamp.valueOf(
                LocalDateTime.parse("2026-08-15T12:34:56.123456"));
        var initial = OaMysqlAttendanceDocumentAdapter.MultiCursor.parse(null);
        var advanced = initial.withOuting(
                initial.outing().advance(sourceTimestamp, 252L));

        var replayed = OaMysqlAttendanceDocumentAdapter.MultiCursor.parse(
                advanced.encode());

        assertThat(replayed).isEqualTo(advanced);
        assertThat(replayed.outing().asTimestamp())
                .isEqualTo(sourceTimestamp);
    }

    @Test
    void malformedCursorFailsClosedInsteadOfRestartingFromEpoch() {
        OaMysqlAttendanceDocumentAdapter adapter =
                new OaMysqlAttendanceDocumentAdapter(
                        () -> {
                            throw new AssertionError(
                                    "invalid cursor must fail before database access");
                        },
                        new OaMysqlProperties());

        OaReadOnlyQueryException failure = catchThrowableOfType(
                () -> adapter.fetchPage("oa-source", "not-a-cursor"),
                OaReadOnlyQueryException.class);

        assertThat(failure.safeCode()).isEqualTo("OA_CURSOR_INVALID");
    }

    private static PreparedStatement emptyStatement() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(result.next()).thenReturn(false);
        when(statement.executeQuery()).thenReturn(result);
        return statement;
    }

    private static PreparedStatement intervalStatement(
            String startColumn,
            String endColumn,
            long formId,
            Instant modified) throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(result.next()).thenReturn(true, false);
        when(result.getString("member_id")).thenReturn("member-1");
        when(result.getString("employee_code")).thenReturn("E001");
        when(result.getTimestamp(startColumn))
                .thenReturn(Timestamp.from(Instant.parse(
                        "2026-08-15T01:00:00Z")));
        when(result.getTimestamp(endColumn))
                .thenReturn(Timestamp.from(Instant.parse(
                        "2026-08-15T10:00:00Z")));
        when(result.getInt("approval_state")).thenReturn(3);
        when(result.wasNull()).thenReturn(false);
        when(result.getTimestamp("last_modified"))
                .thenReturn(Timestamp.from(modified));
        when(result.getLong("form_id")).thenReturn(formId);
        when(statement.executeQuery()).thenReturn(result);
        return statement;
    }
}
