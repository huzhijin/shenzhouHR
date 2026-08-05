package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.reporting.application.AttendanceMonthMatrixPage.BadgeCode;
import com.szsemicon.hr.reporting.application.AttendanceMonthMatrixPage.DayCell;
import com.szsemicon.hr.reporting.application.AttendanceMonthMatrixPage.EmployeeRow;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DayType;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionState;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.OaDocumentFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportSourceSnapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class AttendanceMonthMatrixAssembler {

    static final String FORMULA_VERSION = "ATTENDANCE_MONTH_MATRIX_V1";
    // ReportSourceSnapshot currently has no per-projection zone. The formal
    // reporting contract is therefore explicitly limited to the published
    // China business zone, which is also returned by the REST metadata.
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> EFFECTIVE_OA_STATUSES = Set.of(
            "APPROVED", "MODIFIED", "SUPPLEMENTED");

    private AttendanceMonthMatrixAssembler() {
    }

    static MatrixData assemble(ReportSourceSnapshot snapshot) {
        LocalDate periodStart = snapshot.filter().period().atDay(1);
        LocalDate periodEnd = snapshot.filter().period().atEndOfMonth();
        List<LocalDate> dates = periodStart.datesUntil(
                        periodEnd.plusDays(1))
                .toList();
        Map<String, EmployeeAccumulator> employees = new HashMap<>();
        Map<EmployeeDate, DayAccumulator> cells = new HashMap<>();

        snapshot.dailyFacts().stream()
                .sorted(Comparator.comparing(DailyFact::businessDate)
                        .thenComparing(DailyFact::factId))
                .forEach(fact -> {
                    employee(employees, fact.employeeId()).update(
                            fact.employeeNumber(),
                            fact.employeeName(),
                            fact.organizationId(),
                            fact.organizationName(),
                            fact.businessDate());
                    DayAccumulator cell = cell(
                            cells, fact.employeeId(), fact.businessDate());
                    cell.addDailyFact(fact);
                });

        snapshot.oaDocumentFacts().stream()
                .filter(fact -> EFFECTIVE_OA_STATUSES.contains(
                        fact.sourceStatus().toUpperCase(Locale.ROOT)))
                .sorted(Comparator.comparing(OaDocumentFact::start)
                        .thenComparing(OaDocumentFact::documentId))
                .forEach(fact -> {
                    LocalDate firstDate = fact.start()
                            .atZone(BUSINESS_ZONE)
                            .toLocalDate();
                    employee(employees, fact.employeeId()).update(
                            fact.employeeNumber(),
                            fact.employeeName(),
                            fact.organizationId(),
                            fact.organizationName(),
                            firstDate);
                    LocalDate from = later(periodStart, firstDate);
                    LocalDate through = earlier(
                            periodEnd,
                            fact.endExclusive()
                                    .minusNanos(1)
                                    .atZone(BUSINESS_ZONE)
                                    .toLocalDate());
                    if (from.isAfter(through)) {
                        return;
                    }
                    for (LocalDate date = from;
                            !date.isAfter(through);
                            date = date.plusDays(1)) {
                        cell(cells, fact.employeeId(), date)
                                .badges.add(oaBadge(fact));
                    }
                });

        snapshot.exceptionFacts().stream()
                .filter(fact -> fact.state() != ExceptionState.RESOLVED)
                .sorted(Comparator.comparing(ExceptionFact::businessDate)
                        .thenComparing(ExceptionFact::caseId))
                .forEach(fact -> {
                    employee(employees, fact.employeeId()).update(
                            fact.employeeNumber(),
                            fact.employeeName(),
                            fact.organizationId(),
                            fact.organizationName(),
                            fact.businessDate());
                    DayAccumulator cell = cell(
                            cells, fact.employeeId(), fact.businessDate());
                    BadgeCode badge = exceptionBadge(fact.exceptionType());
                    if (badge == BadgeCode.LATE
                            && cell.hasDailyFact
                            && cell.penalizedLateMinutes == 0) {
                        return;
                    }
                    cell.badges.add(badge);
                });

        List<EmployeeRow> rows = employees.values().stream()
                .sorted(Comparator.comparing(
                                EmployeeAccumulator::employeeNumber)
                        .thenComparing(EmployeeAccumulator::employeeName)
                        .thenComparing(EmployeeAccumulator::employeeId))
                .map(employee -> new EmployeeRow(
                        employee.employeeId,
                        employee.employeeNumber,
                        employee.employeeName,
                        employee.organizationId,
                        employee.organizationName,
                        dates.stream()
                                .map(date -> cellValue(cells.get(
                                        new EmployeeDate(
                                                employee.employeeId,
                                                date)), date))
                                .toList()))
                .toList();
        return new MatrixData(dates, rows);
    }

    private static DayCell cellValue(
            DayAccumulator accumulator, LocalDate date) {
        if (accumulator == null) {
            return new DayCell(date, null, null, null, null, List.of());
        }
        if (accumulator.leaveOrTimeOffMinutes > 0
                && accumulator.badges.stream().noneMatch(
                        AttendanceMonthMatrixAssembler::isLeaveBadge)) {
            accumulator.badges.add(BadgeCode.OTHER_LEAVE);
        }
        return new DayCell(
                date,
                join(accumulator.organizationNames),
                join(accumulator.shiftLabels),
                accumulator.firstPunchAt,
                accumulator.lastPunchAt,
                accumulator.badges.stream()
                        .sorted(Comparator.comparingInt(Enum::ordinal))
                        .toList());
    }

    private static boolean isLeaveBadge(BadgeCode code) {
        return switch (code) {
            case TIME_OFF,
                    PERSONAL_LEAVE,
                    SICK_LEAVE,
                    ANNUAL_LEAVE,
                    OTHER_LEAVE -> true;
            default -> false;
        };
    }

    private static String join(Set<String> values) {
        if (values.isEmpty()) {
            return null;
        }
        return String.join(" / ", values);
    }

    private static EmployeeAccumulator employee(
            Map<String, EmployeeAccumulator> employees, String employeeId) {
        return employees.computeIfAbsent(
                employeeId, EmployeeAccumulator::new);
    }

    private static DayAccumulator cell(
            Map<EmployeeDate, DayAccumulator> cells,
            String employeeId,
            LocalDate date) {
        return cells.computeIfAbsent(
                new EmployeeDate(employeeId, date),
                ignored -> new DayAccumulator());
    }

    private static BadgeCode oaBadge(OaDocumentFact fact) {
        String documentType = fact.documentType().toUpperCase(Locale.ROOT);
        return switch (documentType) {
            case "LEAVE" -> leaveBadge(fact.leaveType());
            case "TIME_OFF" -> BadgeCode.TIME_OFF;
            case "OUTING" -> BadgeCode.OUTING;
            case "TRIP" -> BadgeCode.TRIP;
            case "PUNCH_CORRECTION" -> BadgeCode.PUNCH_CORRECTION;
            case "LEAVE_REVOCATION" -> BadgeCode.LEAVE_REVOCATION;
            case "OVERTIME" -> BadgeCode.OVERTIME_APPLICATION;
            case "EXEMPT_PUNCH" -> BadgeCode.EXEMPT_PUNCH;
            default -> BadgeCode.OTHER_ATTENDANCE_DOCUMENT;
        };
    }

    private static BadgeCode leaveBadge(String leaveType) {
        if (leaveType == null) {
            return BadgeCode.OTHER_LEAVE;
        }
        return switch (leaveType.toUpperCase(Locale.ROOT)) {
            case "PERSONAL_LEAVE" -> BadgeCode.PERSONAL_LEAVE;
            case "SICK_LEAVE" -> BadgeCode.SICK_LEAVE;
            case "ANNUAL_LEAVE" -> BadgeCode.ANNUAL_LEAVE;
            case "TIME_OFF" -> BadgeCode.TIME_OFF;
            default -> BadgeCode.OTHER_LEAVE;
        };
    }

    private static BadgeCode exceptionBadge(String exceptionType) {
        String normalized = exceptionType.toUpperCase(Locale.ROOT);
        if ("LATE".equals(normalized)) {
            return BadgeCode.LATE;
        }
        if ("EARLY_DEPARTURE".equals(normalized)
                || normalized.startsWith("EARLY_DEPARTURE_")) {
            return BadgeCode.EARLY_DEPARTURE;
        }
        if (normalized.startsWith("MISSING_PUNCH")) {
            return BadgeCode.MISSING_PUNCH;
        }
        if ("ABSENCE".equals(normalized)) {
            return BadgeCode.ABSENCE;
        }
        return BadgeCode.OTHER_EXCEPTION;
    }

    private static LocalDate earlier(LocalDate first, LocalDate second) {
        return first.isBefore(second) ? first : second;
    }

    private static LocalDate later(LocalDate first, LocalDate second) {
        return first.isAfter(second) ? first : second;
    }

    record MatrixData(List<LocalDate> dates, List<EmployeeRow> rows) {

        MatrixData {
            dates = List.copyOf(dates);
            rows = List.copyOf(rows);
        }
    }

    private record EmployeeDate(String employeeId, LocalDate date) {
    }

    private static final class EmployeeAccumulator {

        private final String employeeId;
        private String employeeNumber;
        private String employeeName;
        private String organizationId;
        private String organizationName;
        private LocalDate metadataDate;

        private EmployeeAccumulator(String employeeId) {
            this.employeeId = employeeId;
        }

        private void update(
                String number,
                String name,
                String organization,
                String organizationLabel,
                LocalDate date) {
            if (metadataDate == null || !date.isBefore(metadataDate)) {
                employeeNumber = number;
                employeeName = name;
                organizationId = organization;
                organizationName = organizationLabel;
                metadataDate = date;
            }
        }

        private String employeeId() {
            return employeeId;
        }

        private String employeeNumber() {
            return employeeNumber;
        }

        private String employeeName() {
            return employeeName;
        }
    }

    private static final class DayAccumulator {

        private final Set<String> organizationNames =
                new java.util.LinkedHashSet<>();
        private final Set<String> shiftLabels =
                new java.util.LinkedHashSet<>();
        private final EnumSet<BadgeCode> badges =
                EnumSet.noneOf(BadgeCode.class);
        private Instant firstPunchAt;
        private Instant lastPunchAt;
        private long leaveOrTimeOffMinutes;
        private long penalizedLateMinutes;
        private boolean hasDailyFact;

        private void addDailyFact(DailyFact fact) {
            hasDailyFact = true;
            organizationNames.add(fact.organizationName());
            shiftLabels.add(fact.shiftLabel());
            leaveOrTimeOffMinutes += fact.leaveOrTimeOffMinutes();
            penalizedLateMinutes += fact.penalizedLateMinutes();
            if (fact.penalizedLateMinutes() > 0) {
                badges.add(BadgeCode.LATE);
            }
            if (fact.earlyDepartureMinutes() > 0) {
                badges.add(BadgeCode.EARLY_DEPARTURE);
            }
            if (fact.missingPunchCount() > 0) {
                badges.add(BadgeCode.MISSING_PUNCH);
            }
            if (fact.absenceMinutes() > 0) {
                badges.add(BadgeCode.ABSENCE);
            }
            if (fact.recognizedOvertimeMinutes() > 0) {
                badges.add(BadgeCode.RECOGNIZED_OVERTIME);
            }
            if (fact.scheduledMinutes() == 0
                    && Set.of(
                                    DayType.SATURDAY,
                                    DayType.SUNDAY,
                                    DayType.PUBLIC_HOLIDAY)
                            .contains(fact.dayType())) {
                badges.add(BadgeCode.REST_DAY);
            }
            if (fact.firstPunchAt() != null
                    && (firstPunchAt == null
                            || fact.firstPunchAt().isBefore(firstPunchAt))) {
                firstPunchAt = fact.firstPunchAt();
            }
            if (fact.lastPunchAt() != null
                    && (lastPunchAt == null
                            || fact.lastPunchAt().isAfter(lastPunchAt))) {
                lastPunchAt = fact.lastPunchAt();
            }
        }
    }
}
