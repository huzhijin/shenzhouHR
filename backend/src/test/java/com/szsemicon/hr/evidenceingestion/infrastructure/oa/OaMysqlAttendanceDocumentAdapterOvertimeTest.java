package com.szsemicon.hr.evidenceingestion.infrastructure.oa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.attendance.domain.OvertimeType;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort.OaPage;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class OaMysqlAttendanceDocumentAdapterOvertimeTest {

    private static final Instant START =
            Instant.parse("2026-08-15T10:00:00Z");
    private static final Instant END =
            Instant.parse("2026-08-15T12:30:00Z");
    private static final Instant MODIFIED =
            Instant.parse("2026-08-15T13:00:00Z");

    @Test
    void keepsSignedSeeyonFormIdInOvertimeBusinessKey() throws Exception {
        long negativeId = -388371210472787123L;
        Fixture fixture = fixture(-6539634143789166714L, negativeId);

        OaPage page = fixture.adapter().fetchPage("oa-source", null);

        assertThat(page.records()).singleElement().satisfies(record -> {
            assertThat(record.sourceBusinessKey())
                    .isEqualTo("OVERTIME:-388371210472787123");
            assertThat(record.overtimeType()).isEqualTo(OvertimeType.PAID);
        });
    }

    @Test
    void readsField0096AndMapsKnownOvertimeType() throws Exception {
        Fixture fixture = fixture(-6539634143789166714L);

        OaPage page = fixture.adapter().fetchPage("oa-source", null);

        assertThat(page.records()).singleElement().satisfies(record -> {
            assertThat(record.sourceBusinessKey()).isEqualTo("OVERTIME:172");
            assertThat(record.sourceVersion())
                    .isNotEqualTo("1")
                    .endsWith(":state=3:Asia/Shanghai");
            assertThat(record.overtimeType()).isEqualTo(OvertimeType.PAID);
            assertThat(record.effectiveCandidate()).isTrue();
        });
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(
                        fixture.connection(),
                        org.mockito.Mockito.atLeastOnce())
                .prepareStatement(sql.capture());
        assertThat(sql.getAllValues())
                .anySatisfy(statement -> assertThat(statement)
                        .contains(
                                "s.field0096         AS overtime_type_id",
                                "FROM formson_0172 s",
                                "COALESCE(cs.finish_date, cs.start_date, cs.create_date)")
                        .doesNotContain("lastmodifydate"));
    }

    @Test
    void sourceVersionChangesWithApprovalStateAndRejectsMissingInputs() {
        Timestamp modified = Timestamp.valueOf("2026-08-15 12:00:00");

        assertThat(OaMysqlAttendanceDocumentAdapter.sourceVersion(modified, 0))
                .isEqualTo("2026-08-15T12:00:00:state=0:Asia/Shanghai");
        assertThat(OaMysqlAttendanceDocumentAdapter.sourceVersion(modified, 3))
                .isEqualTo("2026-08-15T12:00:00:state=3:Asia/Shanghai");
        assertThat(OaMysqlAttendanceDocumentAdapter.sourceVersion(null, 3))
                .isNull();
        assertThat(OaMysqlAttendanceDocumentAdapter.sourceVersion(modified, null))
                .isNull();
    }

    @Test
    void nullOvertimeTypeIsWarnedAndMarkedIneligible(
            CapturedOutput output) throws Exception {
        Fixture fixture = fixture(null);

        OaPage page = fixture.adapter().fetchPage("oa-source", null);

        assertThat(page.records()).singleElement().satisfies(record -> {
            assertThat(record.overtimeType()).isNull();
            assertThat(record.hasValidOvertimeClassification()).isFalse();
            assertThat(record.effectiveCandidate()).isFalse();
        });
        assertThat(output.getOut() + output.getErr())
                .contains("加班记录类型为空，已隔离: recordId=172");
    }

    @Test
    void unknownOvertimeTypeIsErroredAndMarkedIneligible(
            CapturedOutput output) throws Exception {
        Fixture fixture = fixture(42L);

        OaPage page = fixture.adapter().fetchPage("oa-source", null);

        assertThat(page.records()).singleElement().satisfies(record -> {
            assertThat(record.overtimeType()).isNull();
            assertThat(record.effectiveCandidate()).isFalse();
        });
        assertThat(output.getOut() + output.getErr())
                .contains("未知加班类型: enumId=42, recordId=172");
    }

    @Test
    void overlappingWindowReadsOvertimeByStartWithoutCursor() throws Exception {
        Fixture fixture = fixture(-6539634143789166714L);
        var records = fixture.adapter().fetchOverlapping(
                "oa-source",
                Instant.parse("2026-07-31T16:00:00Z"),
                Instant.parse("2026-08-31T16:00:00Z"));
        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.sourceBusinessKey()).isEqualTo("OVERTIME:172");
            assertThat(record.employeeNumber()).isEqualTo("E001");
            assertThat(record.overtimeType()).isEqualTo(OvertimeType.PAID);
        });
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(
                        fixture.connection(),
                        org.mockito.Mockito.atLeastOnce())
                .prepareStatement(sql.capture());
        assertThat(sql.getAllValues())
                .anySatisfy(statement -> assertThat(statement)
                        .contains("s.field0100 >= ? AND s.field0100 < ?")
                        .doesNotContain("LIMIT ?"));
    }

    private static Fixture fixture(Long overtimeTypeId) throws Exception {
        return fixture(overtimeTypeId, 172L);
    }

    private static Fixture fixture(Long overtimeTypeId, long formId) throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement emptyStatement = mock(PreparedStatement.class);
        ResultSet emptyResult = mock(ResultSet.class);
        when(emptyResult.next()).thenReturn(false);
        when(emptyStatement.executeQuery()).thenReturn(emptyResult);

        PreparedStatement overtimeStatement = mock(PreparedStatement.class);
        ResultSet overtimeResult = mock(ResultSet.class);
        when(overtimeResult.next()).thenReturn(true, false);
        when(overtimeResult.getString("member_id")).thenReturn("member-172");
        when(overtimeResult.getString("employee_code")).thenReturn("E001");
        when(overtimeResult.getTimestamp("start_dt"))
                .thenReturn(Timestamp.from(START));
        when(overtimeResult.getTimestamp("end_dt"))
                .thenReturn(Timestamp.from(END));
        when(overtimeResult.getInt("approval_state")).thenReturn(3);
        when(overtimeResult.getTimestamp("last_modified"))
                .thenReturn(Timestamp.from(MODIFIED));
        when(overtimeResult.getLong("form_id")).thenReturn(formId);
        when(overtimeResult.getLong("overtime_type_id"))
                .thenReturn(overtimeTypeId != null ? overtimeTypeId : 0L);
        when(overtimeResult.wasNull())
                .thenReturn(false, overtimeTypeId == null);
        when(overtimeStatement.executeQuery()).thenReturn(overtimeResult);

        when(connection.prepareStatement(anyString()))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    return sql.contains("FROM formson_0172 s")
                            ? overtimeStatement
                            : emptyStatement;
                });

        OaMysqlProperties properties = new OaMysqlProperties();
        OaMysqlAttendanceDocumentAdapter adapter =
                new OaMysqlAttendanceDocumentAdapter(
                        () -> connection,
                        properties);
        return new Fixture(adapter, connection);
    }

    private record Fixture(
            OaMysqlAttendanceDocumentAdapter adapter,
            Connection connection) {
    }
}
