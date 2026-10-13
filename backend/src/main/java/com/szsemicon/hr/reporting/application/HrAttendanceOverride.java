package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionFact;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class HrAttendanceOverride {

    private HrAttendanceOverride() {
    }

    public static Set<String> parseClearedTypes(String raw) {
        Set<String> types = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return types;
        }
        for (String token : raw.split("[,;\\s]+")) {
            if (token.isBlank()) {
                continue;
            }
            types.add(token.trim().toUpperCase(Locale.ROOT));
        }
        return types;
    }

    public static DailyFact apply(
            DailyFact fact,
            Integer overtimeMinutesOverride,
            Set<String> clearedTypes) {
        if (fact == null) {
            return null;
        }
        Set<String> cleared = clearedTypes == null ? Set.of() : clearedTypes;
        long recognized = fact.recognizedOvertimeMinutes();
        long paid = fact.paidOvertimeMinutes();
        long compensatory = fact.compensatoryOvertimeMinutes();
        long voluntary = fact.voluntaryOvertimeMinutes();
        long total = fact.totalOvertimeMinutes();
        if (overtimeMinutesOverride != null) {
            recognized = Math.max(0, overtimeMinutesOverride);
            if (recognized == 0) {
                paid = 0;
                compensatory = 0;
                voluntary = 0;
            } else {
                long classified = paid + compensatory + voluntary;
                if (classified <= 0) {
                    paid = recognized;
                    compensatory = 0;
                    voluntary = 0;
                } else {
                    paid = Math.round(paid * (double) recognized / classified);
                    compensatory = Math.round(
                            compensatory * (double) recognized / classified);
                    voluntary = Math.max(0, recognized - paid - compensatory);
                }
            }
            total = paid + compensatory + voluntary;
        }
        long late = cleared.contains("LATE") ? 0 : fact.lateMinutes();
        long penalizedLate = cleared.contains("LATE") ? 0 : fact.penalizedLateMinutes();
        long early = cleared.contains("EARLY_DEPARTURE") ? 0 : fact.earlyDepartureMinutes();
        int missing = (cleared.contains("MISSING_PUNCH")
                || cleared.contains("MISSING_ON_DUTY")
                || cleared.contains("MISSING_OFF_DUTY"))
                ? 0
                : fact.missingPunchCount();
        long absence = (cleared.contains("ABSENCE")
                || cleared.contains("LATE_CONVERTED_TO_ABSENCE"))
                ? 0
                : fact.absenceMinutes();
        return new DailyFact(
                fact.factId(),
                fact.companyId(),
                fact.employeeId(),
                fact.employeeNumber(),
                fact.employeeName(),
                fact.organizationId(),
                fact.organizationVersionId(),
                fact.organizationName(),
                fact.businessDate(),
                fact.dayType(),
                fact.shiftLabel(),
                fact.scheduledMinutes(),
                fact.confirmedScheduledWorkMinutes(),
                recognized,
                paid,
                compensatory,
                voluntary,
                total,
                fact.leaveOrTimeOffMinutes(),
                absence,
                fact.confirmedScheduledWorkMinutes() + recognized,
                fact.scheduledAttendanceDays(),
                fact.actualAttendanceDays(),
                late,
                penalizedLate,
                early,
                missing,
                fact.firstPunchAt(),
                fact.lastPunchAt(),
                fact.calculationVersionId(),
                fact.resultDigest(),
                fact.leaveType());
    }

    public static List<ExceptionFact> filter(
            List<ExceptionFact> facts, Set<String> clearedTypes) {
        if (facts == null || facts.isEmpty()
                || clearedTypes == null || clearedTypes.isEmpty()) {
            return facts == null ? List.of() : facts;
        }
        List<ExceptionFact> kept = new ArrayList<>();
        for (ExceptionFact fact : facts) {
            if (!cleared(fact.exceptionType(), clearedTypes)) {
                kept.add(fact);
            }
        }
        return List.copyOf(kept);
    }

    static boolean cleared(String exceptionType, Set<String> clearedTypes) {
        String type = exceptionType == null ? "" : exceptionType.toUpperCase(Locale.ROOT);
        if (clearedTypes.contains(type)) {
            return true;
        }
        if (clearedTypes.contains("LATE")
                && (type.equals("LATE") || type.equals("LATE_CONVERTED_TO_ABSENCE"))) {
            return true;
        }
        if (clearedTypes.contains("EARLY_DEPARTURE") && type.contains("EARLY")) {
            return true;
        }
        if ((clearedTypes.contains("MISSING_PUNCH")
                || clearedTypes.contains("MISSING_ON_DUTY")
                || clearedTypes.contains("MISSING_OFF_DUTY"))
                && type.contains("MISSING")) {
            return true;
        }
        if ((clearedTypes.contains("ABSENCE")
                || clearedTypes.contains("LATE_CONVERTED_TO_ABSENCE"))
                && (type.equals("ABSENCE") || type.equals("LATE_CONVERTED_TO_ABSENCE"))) {
            return true;
        }
        return false;
    }
}
