package com.szsemicon.hr.evidenceingestion.infrastructure.oa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort.DocumentType;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort.OaPage;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort.SourceStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OaMysqlAttendanceDocumentAdapterExemptionOutingTest {

    @Test
    void usesSignedOutingAndExemptionTablesAndNeverQueriesTrip() throws Exception {
        Fixture fixture = fixture(row(3), null);

        OaPage page = fixture.adapter().fetchPage("oa-source", null);

        assertThat(page.records()).singleElement().satisfies(record -> {
            assertThat(record.documentType()).isEqualTo(DocumentType.OUTING);
            assertThat(record.effectiveCandidate()).isTrue();
        });
        assertThat(fixture.sql())
                .anySatisfy(statement -> assertThat(statement)
                        .contains(
                                "FROM formson_0252 s",
                                "JOIN formmain_0251 m",
                                "AND cs.state IN (0, 2, 3)"))
                .anySatisfy(statement -> assertThat(statement)
                        .contains(
                                "FROM formson_0202 s",
                                "JOIN formmain_0201 m",
                                "AND cs.state IN (0, 2, 3)",
                                "DATE(s.field0132) <= DATE(s.field0134)"))
                .allSatisfy(statement -> assertThat(statement)
                        .doesNotContain("formmain_0265"));
    }

    @Test
    void capturesPendingAndCancelledTransitionsButNeverMarksThemEffective()
            throws Exception {
        for (Integer state : new Integer[] {0, 2}) {
            Fixture fixture = fixture(row(state), row(state));

            OaPage page = fixture.adapter().fetchPage("oa-source", null);

            assertThat(page.records()).hasSize(2).allSatisfy(record -> {
                assertThat(record.effectiveCandidate()).isFalse();
                assertThat(record.sourceStatus())
                        .isIn(SourceStatus.UNKNOWN, SourceStatus.REVOKED);
            });
        }

        Fixture draft = fixture(row(null), row(null));
        assertThat(draft.adapter().fetchPage("oa-source", null).records())
                .isEmpty();
    }

    @Test
    void exemptionEndDateIsConvertedToNextDayExclusive() throws Exception {
        Fixture fixture = fixture(
                null,
                new SourceRow(
                        3,
                        LocalDateTime.parse("2026-08-01T12:34:56"),
                        LocalDateTime.parse("2026-08-05T23:59:59")));

        OaPage page = fixture.adapter().fetchPage("oa-source", null);

        assertThat(page.records()).singleElement().satisfies(record -> {
            assertThat(record.documentType())
                    .isEqualTo(DocumentType.EXEMPT_PUNCH);
            assertThat(record.start())
                    .isEqualTo(Instant.parse("2026-07-31T16:00:00Z"));
            assertThat(record.end())
                    .isEqualTo(Instant.parse("2026-08-05T16:00:00Z"));
            Instant augustFifthStart =
                    Instant.parse("2026-08-04T16:00:00Z");
            assertThat(record.start()).isBeforeOrEqualTo(augustFifthStart);
            assertThat(record.end()).isAfter(augustFifthStart);
        });
    }

    private static SourceRow row(Integer state) {
        return new SourceRow(
                state,
                LocalDateTime.parse("2026-08-15T09:00:00"),
                LocalDateTime.parse("2026-08-15T18:00:00"));
    }

    private static Fixture fixture(
            SourceRow outingRow,
            SourceRow exemptionRow) throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement emptyStatement = mock(PreparedStatement.class);
        ResultSet emptyResult = mock(ResultSet.class);
        when(emptyResult.next()).thenReturn(false);
        when(emptyStatement.executeQuery()).thenReturn(emptyResult);

        PreparedStatement outingStatement = statement(
                outingRow, "start_dt", "end_dt", 252L);
        PreparedStatement exemptionStatement = statement(
                exemptionRow, "start_date", "end_date", 202L);
        List<String> preparedSql = new ArrayList<>();
        when(connection.prepareStatement(anyString()))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    preparedSql.add(sql);
                    if (sql.contains("FROM formson_0252 s")) {
                        return outingStatement;
                    }
                    if (sql.contains("FROM formson_0202 s")) {
                        return exemptionStatement;
                    }
                    return emptyStatement;
                });

        OaMysqlAttendanceDocumentAdapter adapter =
                new OaMysqlAttendanceDocumentAdapter(
                        () -> connection,
                        new OaMysqlProperties());
        return new Fixture(adapter, preparedSql);
    }

    private static PreparedStatement statement(
            SourceRow row,
            String startColumn,
            String endColumn,
            long formId) throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(result.next()).thenReturn(row != null, false);
        if (row != null) {
            when(result.getString("member_id")).thenReturn("member-1");
            when(result.getString("employee_code")).thenReturn("E001");
            when(result.getTimestamp(startColumn))
                    .thenReturn(Timestamp.valueOf(row.start()));
            when(result.getTimestamp(endColumn))
                    .thenReturn(Timestamp.valueOf(row.end()));
            when(result.getInt("approval_state"))
                    .thenReturn(row.state() != null ? row.state() : 0);
            when(result.wasNull()).thenReturn(row.state() == null);
            when(result.getTimestamp("last_modified"))
                    .thenReturn(Timestamp.valueOf("2026-08-16 09:00:00"));
            when(result.getLong("form_id")).thenReturn(formId);
        }
        when(statement.executeQuery()).thenReturn(result);
        return statement;
    }

    private record SourceRow(
            Integer state,
            LocalDateTime start,
            LocalDateTime end) {
    }

    private record Fixture(
            OaMysqlAttendanceDocumentAdapter adapter,
            List<String> sql) {
    }
}
