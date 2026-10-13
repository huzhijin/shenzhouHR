package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.reporting.application.QueryPageRows.LeaveStatAccountRow;
import com.szsemicon.hr.reporting.application.QueryPageRows.MonthlyLeaveUsageRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LeaveStatAssemblerTest {

    @Test
    void preOpeningMonthsAreSlashAndAugustUnusedIsZero() {
        assertThat(LeaveStatAssembler.usedCell(2026, 7, BigDecimal.ZERO, true))
                .isEqualTo("/");
        assertThat((BigDecimal) LeaveStatAssembler.usedCell(2026, 8, null, true))
                .isEqualByComparingTo("0.00");
        assertThat((BigDecimal) LeaveStatAssembler.usedCell(2026, 8, new BigDecimal("8.00"), true))
                .isEqualByComparingTo("1.00");
    }

    @Test
    void newHireCalendarDaysAreZeroWhenNotHiredThisYear() {
        assertThat(LeaveStatAssembler.newHireCalendarDays(
                        LocalDate.of(2020, 3, 1), 2026, 5))
                .isEqualByComparingTo("0.0");
        assertThat(LeaveStatAssembler.newHireCalendarDays(null, 2026, 5))
                .isEqualByComparingTo("0.0");
    }

    @Test
    void annualRowKeepsSeniorityColumnsAndTimeOffOmitsThem() {
        LeaveStatAccountRow account = new LeaveStatAccountRow(
                "employee-1",
                "SZST0001",
                "张三",
                "org-1",
                "工程一部",
                "ANNUAL_LEAVE",
                new BigDecimal("40.00"),
                new BigDecimal("40.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("40.00"),
                LocalDate.of(2020, 3, 1),
                0,
                0,
                null);
        Map<String, Object> annual = LeaveStatAssembler.annualRow(
                account,
                List.of(new MonthlyLeaveUsageRow("employee-1", 8, 0)),
                2026,
                1,
                DepartmentPathNames.fromRootToLeaf(List.of("服务中心", "工程一部"))
                        .reportDepartment());
        assertThat(annual.get("usedMonth7")).isEqualTo("/");
        assertThat((BigDecimal) annual.get("usedMonth8")).isEqualByComparingTo("0.00");
        assertThat((BigDecimal) annual.get("newHireCalendarDays")).isEqualByComparingTo("0.0");
        assertThat(annual.get("entitledDays")).isEqualTo(5);
        assertThat(annual).containsKeys(
                "hireDate", "companyTenureYears", "priorTenureYears", "cumulativeTenureYears");

        Map<String, Object> timeOff = LeaveStatAssembler.timeOffRow(
                account, List.of(), 2026, 1, "工程一部");
        assertThat((BigDecimal) timeOff.get("openingHours")).isEqualByComparingTo("40.00");
        assertThat(timeOff).doesNotContainKeys(
                "hireDate", "entitledDays", "newHireCalendarDays", "companyTenureYears");
        assertThat(timeOff.get("usedMonth1")).isEqualTo("/");
    }

    @Test
    void julyOpeningBacksOutAugustUsedAndOvertimeFromRemaining() {
        LeaveStatAccountRow account = new LeaveStatAccountRow(
                "employee-1",
                "SZST0001",
                "张三",
                "org-1",
                "工程一部",
                "TIME_OFF",
                new BigDecimal("381.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("72.00"),
                LocalDate.of(2015, 6, 2),
                0,
                0,
                null);
        List<MonthlyLeaveUsageRow> usage = List.of(
                new MonthlyLeaveUsageRow("employee-1", 8, 480, "TIME_OFF"),
                new MonthlyLeaveUsageRow("employee-1", 8, 960, "COMPENSATORY_OT"));
        Map<String, Object> timeOff = LeaveStatAssembler.timeOffRow(
                account, usage, 2026, 1, "工程一部");
        assertThat((BigDecimal) timeOff.get("remainingHours")).isEqualByComparingTo("72.00");
        assertThat((BigDecimal) timeOff.get("usedMonth8")).isEqualByComparingTo("8.00");
        assertThat((BigDecimal) timeOff.get("overtimeCreditHours")).isEqualByComparingTo("16.00");
        assertThat((BigDecimal) timeOff.get("openingHours")).isEqualByComparingTo("64.00");
        Map<String, Object> annual = LeaveStatAssembler.annualRow(
                account,
                List.of(new MonthlyLeaveUsageRow("employee-1", 8, 480, "ANNUAL")),
                2026,
                1,
                "工程一部");
        assertThat((BigDecimal) annual.get("openingHours")).isEqualByComparingTo("80.00");
        assertThat((BigDecimal) annual.get("usedMonth8")).isEqualByComparingTo("1.00");
        assertThat((BigDecimal) annual.get("remainingHours")).isEqualByComparingTo("72.00");
    }

    @Test
    void tenure_0_01_external_years_plus_0_internal_months_displays_correctly() {
        LeaveStatAccountRow account = new LeaveStatAccountRow(
                "employee-1",
                "SZST0001",
                "张三",
                "org-1",
                "工程一部",
                "ANNUAL_LEAVE",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                LocalDate.of(2026, 12, 1),
                4,
                0,
                new BigDecimal("0.01"));
        LeaveStatAssembler.Tenure tenure = LeaveStatAssembler.tenure(
                account.hireDate(), 2026, account.priorServiceDays(),
                account.priorServiceMonths(), account.priorServiceYears());
        assertThat(tenure.companyYears()).isEqualByComparingTo("0.00");
        assertThat(tenure.priorYears()).isEqualByComparingTo("0.01");
        assertThat(tenure.cumulativeYears()).isEqualByComparingTo("0.01");
        assertThat(tenure.entitledDays()).isEqualTo(0);
    }

    @Test
    void tenure_8_25_external_years_plus_21_internal_months_displays_10_00_and_entitled_10_days() {
        LeaveStatAccountRow account = new LeaveStatAccountRow(
                "employee-2",
                "SZST0002",
                "李四",
                "org-2",
                "工程二部",
                "ANNUAL_LEAVE",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                LocalDate.of(2025, 3, 31),
                2963,
                99,
                new BigDecimal("8.25"));
        LeaveStatAssembler.Tenure tenure = LeaveStatAssembler.tenure(
                account.hireDate(), 2026, account.priorServiceDays(),
                account.priorServiceMonths(), account.priorServiceYears());
        assertThat(tenure.companyYears()).isEqualByComparingTo("1.75");
        assertThat(tenure.priorYears()).isEqualByComparingTo("8.25");
        assertThat(tenure.cumulativeYears()).isEqualByComparingTo("10.00");
        assertThat(tenure.entitledDays()).isEqualTo(10);
    }
}
