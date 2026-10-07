package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.reporting.application.AttendanceReportFactProjector.ProjectionFacts;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PeriodState;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PublishCommand;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.VerifiedCalculatedFacts;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.VerifiedProjectionMetadata;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DayType;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionSeverity;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionState;
import com.szsemicon.hr.reporting.infrastructure.persistence.AttendanceDashboardWorkbenchMapper;
import com.szsemicon.hr.reporting.infrastructure.persistence.DashboardWorkbenchRows.PunchRow;
import com.szsemicon.hr.reporting.infrastructure.persistence.DashboardWorkbenchRows.RosterRow;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class RealtimeReportProvisionalAssembler {

    private static final ZoneId ZONE = AttendanceDashboardService.BUSINESS_ZONE;
    private static final LocalTime LATE_AFTER = LocalTime.of(9, 0);
    private static final String FORMULA =
            "FULL_CALCULATION_OA_FORM_HOURS_V8";

    private RealtimeReportProvisionalAssembler() {
    }

    static PublishCommand assemble(
            AttendanceDashboardWorkbenchMapper mapper,
            String companyId,
            YearMonth period,
            String principalId,
            Instant dataAsOf) {
        Objects.requireNonNull(mapper, "mapper");
        LocalDate periodStart = period.atDay(1);
        LocalDate today = dataAsOf.atZone(ZONE).toLocalDate();
        LocalDate periodEndExclusive = period.plusMonths(1).atDay(1);
        LocalDate through = today.isBefore(periodEndExclusive)
                ? today.plusDays(1)
                : periodEndExclusive;
        if (!through.isAfter(periodStart)) {
            return command(companyId, period, principalId, dataAsOf, List.of());
        }
        LocalDate rosterDate = through.minusDays(1);
        List<RosterRow> roster = nullToEmpty(mapper.listRoster(
                companyId, rosterDate));
        Instant windowStart = periodStart.atStartOfDay(ZONE).toInstant();
        Instant windowEnd = through.atStartOfDay(ZONE).toInstant();
        Map<String, List<Instant>> punches = punchesByEmployee(
                roster, nullToEmpty(mapper.listPunchPoints(windowStart, windowEnd)));
        List<VerifiedCalculatedFacts> facts = new ArrayList<>();
        for (RosterRow row : roster) {
            String employeeId = safe(row.employeeId(), "unknown-employee");
            String number = safe(row.employeeNumber(), employeeId);
            String name = safe(row.employeeName(), "未命名");
            String organizationId = safe(row.organizationId(), "unassigned");
            String organizationName = safe(row.organizationName(), "未分配组织");
            List<Instant> employeePunches = punches.getOrDefault(
                    employeeId, List.of());
            for (LocalDate day = periodStart;
                    day.isBefore(through);
                    day = day.plusDays(1)) {
                facts.add(dayFacts(
                        companyId,
                        employeeId,
                        number,
                        name,
                        organizationId,
                        organizationName,
                        day,
                        punchesOn(employeePunches, day)));
            }
        }
        return command(companyId, period, principalId, dataAsOf, facts);
    }

    private static VerifiedCalculatedFacts dayFacts(
            String companyId,
            String employeeId,
            String number,
            String name,
            String organizationId,
            String organizationName,
            LocalDate day,
            List<Instant> punches) {
        boolean weekend = day.getDayOfWeek() == DayOfWeek.SATURDAY
                || day.getDayOfWeek() == DayOfWeek.SUNDAY;
        DayType dayType = weekend ? weekendType(day) : DayType.WEEKDAY;
        long scheduled = weekend ? 0 : 480;
        Instant first = punches.isEmpty() ? null : punches.getFirst();
        Instant last = punches.isEmpty() ? null : punches.getLast();
        int missing = 0;
        long late = 0;
        long confirmed = 0;
        int actualDays = 0;
        List<ExceptionFact> exceptions = new ArrayList<>();
        if (!weekend) {
            if (punches.isEmpty()) {
                missing = 2;
                exceptions.add(exception(
                        employeeId,
                        number,
                        name,
                        organizationId,
                        organizationName,
                        day,
                        "MISSING_PUNCH_OVERDUE",
                        ExceptionSeverity.ERROR,
                        scheduled,
                        "当日尚无有效打卡"));
            } else if (punches.size() == 1) {
                missing = 1;
                exceptions.add(exception(
                        employeeId,
                        number,
                        name,
                        organizationId,
                        organizationName,
                        day,
                        "MISSING_PUNCH_PENDING",
                        ExceptionSeverity.WARNING,
                        0,
                        "仅有 1 次有效打卡"));
            } else {
                confirmed = scheduled;
                actualDays = 1;
                LocalTime firstTime = first.atZone(ZONE).toLocalTime();
                if (firstTime.isAfter(LATE_AFTER)) {
                    late = java.time.Duration.between(
                            LATE_AFTER, firstTime).toMinutes();
                    exceptions.add(exception(
                            employeeId,
                            number,
                            name,
                            organizationId,
                            organizationName,
                            day,
                            "LATE",
                            ExceptionSeverity.WARNING,
                            late,
                            "首次打卡晚于 09:00"));
                }
            }
        }
        String factId = employeeId + ":" + day;
        if (factId.length() > 128) {
            factId = factId.substring(0, 128);
        }
        DailyFact daily = new DailyFact(
                factId,
                companyId,
                employeeId,
                number,
                name,
                organizationId,
                clipped(organizationId, "org-version"),
                organizationName,
                day,
                dayType,
                weekend ? "休息" : "标准白班",
                scheduled,
                confirmed,
                0,
                0,
                0,
                0,
                0,
                0,
                weekend ? 0 : scheduled - confirmed,
                confirmed,
                weekend ? 0 : 1,
                actualDays,
                late,
                late,
                0,
                missing,
                first,
                last,
                "ATTENDANCE.PROVISIONAL:V1",
                "c".repeat(64),
                null);
        return new VerifiedCalculatedFacts(
                clipped(employeeId, "employee-version"),
                clipped(employeeId, "assignment"),
                new ProjectionFacts(daily, exceptions));
    }

    private static ExceptionFact exception(
            String employeeId,
            String number,
            String name,
            String organizationId,
            String organizationName,
            LocalDate day,
            String type,
            ExceptionSeverity severity,
            long minutes,
            String evidence) {
        String reference = day + ":" + employeeId + ":" + type;
        if (reference.length() > 128) {
            reference = reference.substring(0, 128);
        }
        return new ExceptionFact(
                reference,
                employeeId,
                number,
                name,
                organizationId,
                organizationName,
                day,
                type,
                severity,
                ExceptionState.OPEN,
                minutes,
                evidence,
                "WORKBENCH-PUNCH:V1");
    }

    private static Map<String, List<Instant>> punchesByEmployee(
            List<RosterRow> roster, List<PunchRow> punches) {
        Set<String> rosterIds = new HashSet<>();
        Map<String, String> byNumber = new HashMap<>();
        for (RosterRow row : roster) {
            rosterIds.add(row.employeeId());
            if (row.employeeNumber() != null) {
                byNumber.putIfAbsent(row.employeeNumber(), row.employeeId());
            }
        }
        Map<String, List<Instant>> byEmployee = new HashMap<>();
        for (PunchRow punch : punches) {
            String matched = rosterIds.contains(punch.employeeId())
                    ? punch.employeeId()
                    : byNumber.get(punch.employeeNumber());
            if (matched == null) {
                continue;
            }
            byEmployee.computeIfAbsent(matched, ignored -> new ArrayList<>())
                    .add(punch.pointInstant());
        }
        byEmployee.values().forEach(list -> list.sort(Comparator.naturalOrder()));
        return byEmployee;
    }

    private static List<Instant> punchesOn(
            List<Instant> punches, LocalDate day) {
        List<Instant> onDay = new ArrayList<>();
        for (Instant punch : punches) {
            if (day.equals(punch.atZone(ZONE).toLocalDate())) {
                onDay.add(punch);
            }
        }
        return onDay;
    }

    private static PublishCommand command(
            String companyId,
            YearMonth period,
            String principalId,
            Instant dataAsOf,
            List<VerifiedCalculatedFacts> facts) {
        return new PublishCommand(
                new VerifiedProjectionMetadata(
                        companyId,
                        period,
                        PeriodState.OPEN,
                        FORMULA,
                        List.of("WORKBENCH-PUNCH:V1"),
                        digest(companyId + "|" + period + "|" + facts.size()),
                        dataAsOf,
                        principalId),
                facts,
                List.of(),
                List.of());
    }

    private static DayType weekendType(LocalDate day) {
        return day.getDayOfWeek() == DayOfWeek.SATURDAY
                ? DayType.SATURDAY
                : DayType.SUNDAY;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String clipped(String value, String fallback) {
        String candidate = value == null || value.isBlank() ? fallback : value.trim();
        if (candidate.length() > 36) {
            candidate = candidate.substring(0, 36);
        }
        if (candidate.isBlank()) {
            return fallback;
        }
        return candidate;
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }
}
