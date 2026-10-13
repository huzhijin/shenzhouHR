package com.szsemicon.hr.leavetimeaccount.domain;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Year;
import java.util.Objects;

final class AnnualLeaveCalendar {

    private AnnualLeaveCalendar() {
    }

    static int completedMonths(
            LocalDate startDate,
            LocalDate asOfDate,
            LeapDayAnniversaryRule leapDayRule) {
        Objects.requireNonNull(startDate, "最新入职日期不能为空");
        Objects.requireNonNull(asOfDate, "评估日期不能为空");
        Objects.requireNonNull(leapDayRule, "闰日周年策略不能为空");
        if (asOfDate.isBefore(startDate)) {
            throw new IllegalArgumentException("评估日期不得早于最新入职日期");
        }

        long candidateMonths = Math.addExact(
                Math.multiplyExact((long) asOfDate.getYear() - startDate.getYear(), 12L),
                asOfDate.getMonthValue() - startDate.getMonthValue());
        LocalDate candidateDate = addMonths(startDate, candidateMonths, leapDayRule);
        if (candidateDate.isAfter(asOfDate)) {
            candidateMonths--;
        }
        return Math.toIntExact(candidateMonths);
    }

    static LocalDate addMonths(
            LocalDate anchor,
            long months,
            LeapDayAnniversaryRule leapDayRule) {
        Objects.requireNonNull(anchor, "日历锚点不能为空");
        Objects.requireNonNull(leapDayRule, "闰日周年策略不能为空");
        if (months < 0) {
            throw new IllegalArgumentException("日历月增量不得为负数");
        }
        try {
            LocalDate result = anchor.plusMonths(months);
            if (isLeapDay(anchor)
                    && months > 0
                    && months % 12 == 0
                    && !Year.isLeap(result.getYear())
                    && leapDayRule == LeapDayAnniversaryRule.MARCH_1) {
                return result.plusDays(1);
            }
            return result;
        } catch (DateTimeException | ArithmeticException exception) {
            throw new IllegalArgumentException("日历日期超出支持范围", exception);
        }
    }

    static LocalDate anniversary(
            LocalDate startDate,
            int anniversaryNumber,
            LeapDayAnniversaryRule leapDayRule) {
        if (anniversaryNumber < 1) {
            throw new IllegalArgumentException("周年序号必须大于零");
        }
        return addMonths(startDate, Math.multiplyExact((long) anniversaryNumber, 12L), leapDayRule);
    }

    static int anniversaryNumberOn(
            LocalDate startDate,
            LocalDate candidateDate,
            LeapDayAnniversaryRule leapDayRule) {
        Objects.requireNonNull(startDate, "最新入职日期不能为空");
        Objects.requireNonNull(candidateDate, "候选周年日期不能为空");
        if (!candidateDate.isAfter(startDate)) {
            return 0;
        }
        int possibleNumber = candidateDate.getYear() - startDate.getYear();
        if (possibleNumber < 1) {
            return 0;
        }
        return anniversary(startDate, possibleNumber, leapDayRule).equals(candidateDate)
                ? possibleNumber
                : 0;
    }

    private static boolean isLeapDay(LocalDate date) {
        return date.getMonthValue() == 2 && date.getDayOfMonth() == 29;
    }
}
