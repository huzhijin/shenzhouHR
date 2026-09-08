package com.szsemicon.hr.evidenceingestion.infrastructure.oa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.szsemicon.hr.attendance.domain.LeaveType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OaMysqlAttendanceDocumentAdapterLeaveTypeTest {

    @Test
    void mapsConfirmedSickAndPersonalLeaveLabels() throws Exception {
        assertThat(fetch("病假").records()).singleElement().satisfies(record -> {
            assertThat(record.leaveType()).isEqualTo(LeaveType.SICK);
            assertThat(record.effectiveCandidate()).isTrue();
        });
        assertThat(fetch("事假").records()).singleElement().satisfies(record -> {
            assertThat(record.leaveType()).isEqualTo(LeaveType.PERSONAL);
            assertThat(record.effectiveCandidate()).isTrue();
        });
        assertThat(fetch("计生假").records()).singleElement().satisfies(record -> {
            assertThat(record.leaveType()).isEqualTo(LeaveType.FAMILY_PLANNING);
            assertThat(record.effectiveCandidate()).isTrue();
        });
        assertThat(fetch("其他").records()).singleElement().satisfies(record -> {
            assertThat(record.leaveType()).isEqualTo(LeaveType.OTHER);
            assertThat(record.effectiveCandidate()).isTrue();
        });
    }

    @Test
    void unknownOrMissingLeaveTypeIsFailClosed() throws Exception {
        assertThat(fetch("探亲假").records()).singleElement().satisfies(record -> {
            assertThat(record.leaveType()).isNull();
            assertThat(record.hasValidLeaveClassification()).isFalse();
            assertThat(record.effectiveCandidate()).isFalse();
        });
    }

    private static com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort.OaPage
            fetch(String label) throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement emptyStatement = mock(PreparedStatement.class);
        ResultSet emptyResult = mock(ResultSet.class);
        when(emptyResult.next()).thenReturn(false);
        when(emptyStatement.executeQuery()).thenReturn(emptyResult);

        PreparedStatement leaveStatement = mock(PreparedStatement.class);
        ResultSet leaveResult = mock(ResultSet.class);
        when(leaveResult.next()).thenReturn(true, false);
        when(leaveResult.getString("member_id")).thenReturn("member-172");
        when(leaveResult.getString("employee_code")).thenReturn("E001");
        when(leaveResult.getString("leave_type_label")).thenReturn(label);
        when(leaveResult.getObject("start_dt", java.time.LocalDateTime.class))
                .thenReturn(java.time.LocalDateTime.parse("2026-08-15T09:00:00"));
        when(leaveResult.getObject("end_dt", java.time.LocalDateTime.class))
                .thenReturn(java.time.LocalDateTime.parse("2026-08-15T18:00:00"));
        when(leaveResult.getInt("approval_state")).thenReturn(3);
        when(leaveResult.getTimestamp("last_modified"))
                .thenReturn(Timestamp.from(Instant.parse("2026-08-15T10:30:00Z")));
        when(leaveResult.getLong("form_id")).thenReturn(172L);
        when(leaveStatement.executeQuery()).thenReturn(leaveResult);
        when(connection.prepareStatement(anyString())).thenAnswer(invocation ->
                invocation.<String>getArgument(0).contains("FROM formmain_0170 m")
                        ? leaveStatement : emptyStatement);

        return new OaMysqlAttendanceDocumentAdapter(
                () -> connection, new OaMysqlProperties()).fetchPage("oa", null);
    }
}
