package com.szsemicon.hr.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.attendance.domain.LeaveType;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ReportRowsLeaveTypeMappingTest {

    @Test
    void mapsDatabaseLeaveCodeInsteadOfEnumConstantName() {
        var row = new ReportRows.DailyRow(
                "fact-1",
                "company-a",
                "employee-1",
                "SZST0001",
                "张三",
                "org-1",
                "org-version-1",
                "研发部",
                LocalDate.of(2026, 8, 15),
                "WEEKDAY",
                "白班",
                480,
                480,
                0,
                0,
                0,
                0,
                0,
                480,
                "ANNUAL_LEAVE",
                0,
                480,
                1,
                1.0,
                0,
                0,
                0,
                0,
                null,
                null,
                "calc-1",
                "digest-1");

        assertThat(row.toDomain().leaveType()).isEqualTo(LeaveType.ANNUAL);
    }

    @Test
    void storedWorkHourMismatchStillReads() {
        var row = new ReportRows.DailyRow(
                "fact-2",
                "company-a",
                "employee-1",
                "SZST0001",
                "张三",
                "org-1",
                "org-version-1",
                "研发部",
                LocalDate.of(2026, 8, 15),
                "WEEKDAY",
                "白班",
                480,
                480,
                30,
                0,
                0,
                0,
                0,
                0,
                "ANNUAL_LEAVE",
                0,
                480,
                1,
                0.5000001,
                0,
                0,
                0,
                0,
                null,
                null,
                "calc-1",
                "digest-1");

        var fact = row.toDomain();
        assertThat(fact.actualWorkMinutes()).isEqualTo(510);
        assertThat(fact.recognizedOvertimeMinutes()).isEqualTo(30);
        assertThat(fact.actualAttendanceDays()).isEqualTo(0.5d);
    }
}
