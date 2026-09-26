package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.leavetimeaccount.domain.AnnualLeaveCalculator;
import com.szsemicon.hr.leavetimeaccount.domain.AnnualLeaveEntitlement;
import com.szsemicon.hr.leavetimeaccount.domain.AnnualLeavePolicy;
import com.szsemicon.hr.reporting.infrastructure.persistence.QueryPageRows.LeaveStatAccountRow;
import com.szsemicon.hr.reporting.infrastructure.persistence.QueryPageRows.MonthlyLeaveUsageRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Year;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Year-row presentation for 年假统计表 / 调休统计表. Pre-opening months render as
 * {@code /}; months on or after 2026-08-01 render numeric used amounts.
 */
final class LeaveStatAssembler {

    static final LocalDate OPENING_INCLUSIVE_FROM = LocalDate.of(2026, 8, 1);
    static final String PRE_OPENING = "/";
    private static final BigDecimal HOURS_PER_DAY = new BigDecimal("8");
    private static final AnnualLeavePolicy POLICY =
            AnnualLeavePolicy.defaults("ANNUAL_LEAVE_DEFAULT_V1");

    private LeaveStatAssembler() {
    }

    static Map<String, Object> annualRow(
            LeaveStatAccountRow account,
            List<MonthlyLeaveUsageRow> usage,
            int year,
            int sequence,
            String department) {
        List<MonthlyLeaveUsageRow> used = usedMovements(usage);
        Map<String, Object> row = identity(account, sequence, department);
        Tenure tenure = tenure(account.hireDate(), year, account.priorServiceDays());
        row.put("hireDate", account.hireDate() == null ? "" : account.hireDate().toString());
        row.put("companyTenureYears", tenure.companyYears);
        row.put("priorTenureYears", tenure.priorYears);
        row.put("cumulativeTenureYears", tenure.cumulativeYears);
        row.put("entitledDays", tenure.entitledDays);
        row.put("newHireCalendarDays", tenure.newHireDays);
        putBalances(row, account);
        row.put(
                "openingHours",
                scale(julyOpeningHours(account.remainingHours(), used, List.of(), year)));
        putMonthlyUsed(row, used, year, true);
        return row;
    }

    static Map<String, Object> timeOffRow(
            LeaveStatAccountRow account,
            List<MonthlyLeaveUsageRow> usage,
            int year,
            int sequence,
            String department) {
        List<MonthlyLeaveUsageRow> used = usedMovements(usage);
        List<MonthlyLeaveUsageRow> overtime = overtimeMovements(usage);
        Map<String, Object> row = identity(account, sequence, department);
        putBalances(row, account);
        row.put(
                "openingHours",
                scale(julyOpeningHours(account.remainingHours(), used, overtime, year)));
        row.put("overtimeCreditHours", scale(sumHours(overtime, year)));
        putMonthlyUsed(row, used, year, false);
        return row;
    }

    static Object usedCell(int year, int month, BigDecimal usedHours, boolean asDays) {
        LocalDate first = LocalDate.of(year, month, 1);
        if (first.isBefore(OPENING_INCLUSIVE_FROM)) {
            return PRE_OPENING;
        }
        BigDecimal hours = usedHours == null ? BigDecimal.ZERO : usedHours;
        if (asDays) {
            return scale(hoursToDays(hours));
        }
        return scale(hours);
    }

    private static Map<String, Object> identity(
            LeaveStatAccountRow account,
            int sequence,
            String department) {
        DepartmentPathNames.Levels levels =
                DepartmentPathNames.fromReportDepartment(department);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("employeeId", account.employeeId());
        row.put("sequence", sequence);
        row.put("levelOneDepartment", nullToEmpty(levels.levelOne()));
        row.put("levelTwoDepartment", nullToEmpty(levels.levelTwo()));
        row.put("employeeNumber", account.employeeNumber());
        row.put("employeeName", account.employeeName());
        row.put("department", department);
        return row;
    }

    private static void putBalances(Map<String, Object> row, LeaveStatAccountRow account) {
        row.put("remainingHours", scale(account.remainingHours()));
        row.put("remainingDays", scale(hoursToDays(account.remainingHours())));
        row.put("openingHours", scale(account.openingHours()));
        row.put("grantedHours", scale(account.grantedHours()));
        row.put("overtimeCreditHours", scale(account.overtimeCreditHours()));
        row.put("usedHours", scale(account.usedHours()));
    }

    private static void putMonthlyUsed(
            Map<String, Object> row,
            List<MonthlyLeaveUsageRow> usage,
            int year,
            boolean asDays) {
        BigDecimal[] hours = new BigDecimal[12];
        if (usage != null) {
            for (MonthlyLeaveUsageRow item : usage) {
                if (item == null
                        || item.usageMonth() == null
                        || item.usageMonth() < 1
                        || item.usageMonth() > 12) {
                    continue;
                }
                hours[item.usageMonth() - 1] = minutesToHours(item.recognizedMinutes());
            }
        }
        for (int month = 1; month <= 12; month++) {
            row.put("usedMonth" + month, usedCell(year, month, hours[month - 1], asDays));
        }
    }

    static BigDecimal julyOpeningHours(
            BigDecimal remainingHours,
            List<MonthlyLeaveUsageRow> used,
            List<MonthlyLeaveUsageRow> overtime,
            int year) {
        return scale(remainingHours)
                .add(sumHours(used, year))
                .subtract(sumHours(overtime, year));
    }

    private static List<MonthlyLeaveUsageRow> usedMovements(
            List<MonthlyLeaveUsageRow> usage) {
        if (usage == null) {
            return List.of();
        }
        return usage.stream().filter(LeaveStatAssembler::isUsed).toList();
    }

    private static List<MonthlyLeaveUsageRow> overtimeMovements(
            List<MonthlyLeaveUsageRow> usage) {
        if (usage == null) {
            return List.of();
        }
        return usage.stream().filter(LeaveStatAssembler::isOvertime).toList();
    }

    private static boolean isUsed(MonthlyLeaveUsageRow row) {
        if (row == null) {
            return false;
        }
        String kind = row.kind();
        return kind == null
                || kind.isBlank()
                || "USED".equals(kind)
                || "ANNUAL".equals(kind)
                || "TIME_OFF".equals(kind);
    }

    private static boolean isOvertime(MonthlyLeaveUsageRow row) {
        if (row == null || row.kind() == null) {
            return false;
        }
        return "COMPENSATORY_OT".equals(row.kind())
                || "OVERTIME_CREDIT".equals(row.kind());
    }

    private static BigDecimal sumHours(List<MonthlyLeaveUsageRow> rows, int year) {
        BigDecimal total = BigDecimal.ZERO;
        if (rows == null) {
            return total.setScale(2, RoundingMode.HALF_UP);
        }
        for (MonthlyLeaveUsageRow row : rows) {
            if (row == null
                    || row.usageMonth() == null
                    || row.usageMonth() < 1
                    || row.usageMonth() > 12
                    || LocalDate.of(year, row.usageMonth(), 1)
                            .isBefore(OPENING_INCLUSIVE_FROM)) {
                continue;
            }
            total = total.add(minutesToHours(row.recognizedMinutes()));
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    static Tenure tenure(LocalDate hireDate, int year, Integer priorServiceDays) {
        int priorDays = priorServiceDays == null ? 0 : Math.max(priorServiceDays, 0);
        int priorMonths = priorDays / 30;
        LocalDate asOf = LocalDate.of(year, 12, 31);
        if (hireDate == null || asOf.isBefore(hireDate)) {
            return new Tenure(
                    hireDate,
                    BigDecimal.ZERO,
                    years(priorMonths),
                    years(priorMonths),
                    0,
                    BigDecimal.ZERO);
        }
        AnnualLeaveEntitlement qualified = AnnualLeaveCalculator.assess(
                hireDate, asOf, priorMonths, POLICY);
        AnnualLeaveEntitlement statutory = AnnualLeaveCalculator.assess(
                hireDate,
                asOf,
                priorMonths,
                POLICY.withoutQualificationRequirement());
        return new Tenure(
                hireDate,
                years(qualified.currentEmploymentCompletedMonths()),
                years(priorMonths),
                years(qualified.cumulativeServiceMonths()),
                qualified.days(),
                newHireCalendarDays(hireDate, year, statutory.days()));
    }

    static BigDecimal newHireCalendarDays(LocalDate hireDate, int year, int statutoryDays) {
        if (hireDate == null || hireDate.getYear() != year || statutoryDays <= 0) {
            return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
        }
        LocalDate yearEnd = LocalDate.of(year, 12, 31);
        long remaining = ChronoUnit.DAYS.between(hireDate, yearEnd) + 1;
        int yearLength = Year.of(year).length();
        return BigDecimal.valueOf(statutoryDays)
                .multiply(BigDecimal.valueOf(remaining))
                .divide(BigDecimal.valueOf(yearLength), 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal years(int months) {
        return BigDecimal.valueOf(months)
                .divide(new BigDecimal("12"), 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal hoursToDays(BigDecimal hours) {
        if (hours == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return hours.divide(HOURS_PER_DAY, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal minutesToHours(long minutes) {
        return BigDecimal.valueOf(minutes)
                .divide(new BigDecimal("60"), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    record Tenure(
            LocalDate hireDate,
            BigDecimal companyYears,
            BigDecimal priorYears,
            BigDecimal cumulativeYears,
            int entitledDays,
            BigDecimal newHireDays) {
    }
}
