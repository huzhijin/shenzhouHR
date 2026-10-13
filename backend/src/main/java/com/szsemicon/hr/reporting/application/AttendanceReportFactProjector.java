package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.AttendanceMetrics;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.DailyAttendanceResult;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.ResultCategory;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.ResultItem;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.RuleHit;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.TimeInterval;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceExceptionModels.AttendanceExceptionCase;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceExceptionModels.ExceptionFinding;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceExceptionModels.ExceptionTransitionType;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceExceptionModels.ExceptionType;
import com.szsemicon.hr.attendance.domain.LeaveType;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DayType;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionSeverity;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Converts a validated Wave 5 daily result into the minimum formal-report
 * read model. It never copies raw punch payloads, OA reasons, coordinates, or
 * device identifiers into reporting facts.
 */
public final class AttendanceReportFactProjector {

    private static final Pattern SAFE_REASON_CODE =
            Pattern.compile("[A-Z0-9_:-]{1,64}");
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalTime MORNING_START = LocalTime.of(8, 30);
    private static final LocalTime MORNING_END = LocalTime.of(12, 0);
    private static final LocalTime AFTERNOON_START = LocalTime.of(13, 0);
    private static final LocalTime AFTERNOON_END = LocalTime.of(17, 30);

    public ProjectionFacts project(
            DailyAttendanceResult result, ProjectionContext context) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(context, "context");
        long rawLate = result.ruleHits().stream()
                .filter(this::lateRule)
                .mapToLong(RuleHit::rawMinutes)
                .sum();
        long penalizedLate = result.ruleHits().stream()
                .filter(this::lateRule)
                .mapToLong(RuleHit::includedMinutes)
                .sum();
        long early = result.ruleHits().stream()
                .filter(value -> "EARLY_DEPARTURE_CHARGEABLE"
                        .equals(value.ruleCode()))
                .mapToLong(RuleHit::includedMinutes)
                .sum();
        var metrics = result.metrics();
        int missing = resolvedMissingPunchCount(result, context, metrics);

        int scheduledAttendanceDays = metrics.scheduledMinutes() > 0 ? 1 : 0;
        double actualAttendanceDays;
        boolean lateConvertedToAbsence = result.ruleHits().stream()
                .anyMatch(value -> "LATE_CONVERTED_TO_ABSENCE"
                        .equals(value.ruleCode()));
        boolean paidLeave = metrics.leaveOrTimeOffMinutes() > 0
                && (context.leaveType() == null
                        || context.leaveType().countsAsAttendance());
        boolean unpaidLeave = metrics.leaveOrTimeOffMinutes() > 0
                && context.leaveType() != null
                && !context.leaveType().countsAsAttendance();
        if (scheduledAttendanceDays == 0 || lateConvertedToAbsence) {
            actualAttendanceDays = 0;
        } else if (metrics.confirmedScheduledWorkMinutes() > 0 && unpaidLeave) {
            actualAttendanceDays = 0.5;
        } else if (metrics.confirmedScheduledWorkMinutes() > 0 || paidLeave) {
            actualAttendanceDays = 1;
        } else {
            actualAttendanceDays = 0;
        }

        DailyFact daily = new DailyFact(
                context.factId(),
                context.companyId(),
                context.employeeId(),
                context.employeeNumber(),
                context.employeeName(),
                context.organizationId(),
                context.organizationVersionId(),
                context.organizationName(),
                context.businessDate(),
                context.dayType(),
                context.shiftLabel(),
                metrics.scheduledMinutes(),
                metrics.confirmedScheduledWorkMinutes(),
                metrics.recognizedOvertimeMinutes(),
                metrics.paidOvertimeMinutes(),
                metrics.compensatoryOvertimeMinutes(),
                metrics.voluntaryOvertimeMinutes(),
                metrics.totalOvertimeMinutes(),
                metrics.leaveOrTimeOffMinutes(),
                metrics.absenceMinutes(),
                metrics.actualWorkMinutes(),
                scheduledAttendanceDays,
                actualAttendanceDays,
                rawLate,
                penalizedLate,
                early,
                missing,
                context.firstPunchAt(),
                context.lastPunchAt(),
                result.calculationVersionId(),
                result.resultDigest(),
                context.leaveType());
        return new ProjectionFacts(
                daily,
                exceptionFacts(result, context, penalizedLate > 0, missing));
    }

    /**
     * Projects the current state of a reconciled exception case. This is the
     * trusted path for exception types that can block calculation before a
     * daily result exists (for example missing configuration or ambiguous
     * punch matching).
     */
    public ExceptionFact projectCurrentException(
            AttendanceExceptionCase exceptionCase,
            CurrentExceptionProjectionContext context) {
        Objects.requireNonNull(exceptionCase, "exceptionCase");
        Objects.requireNonNull(context, "context");
        ExceptionFinding initial = exceptionCase.initialFinding();
        List<ExceptionFinding> observations =
                exceptionCase.observations().isEmpty()
                        ? List.of(initial)
                        : exceptionCase.observations();
        for (ExceptionFinding finding : observations) {
            boolean sameStableCase =
                    finding.fingerprint().equals(initial.fingerprint())
                            && finding.companyId().equals(
                                    initial.companyId())
                            && finding.employeeId().equals(
                                    initial.employeeId())
                            && finding.businessDate().equals(
                                    initial.businessDate())
                            && finding.type() == initial.type();
            if (!sameStableCase) {
                throw new IllegalArgumentException(
                        "exception case observations changed stable identity");
            }
        }
        ExceptionFinding current = observations.getLast();
        if (!context.companyId().equals(current.companyId())
                || !context.employeeId().equals(current.employeeId())
                || !context.businessDate().equals(current.businessDate())) {
            throw new IllegalArgumentException(
                    "exception projection context does not match the case");
        }
        var lastTransition = exceptionCase.transitions().isEmpty()
                ? null
                : exceptionCase.transitions().getLast();
        String reasonCode = lastTransition == null
                ? current.reasonCode()
                : lastTransition.reasonCode();
        if (!SAFE_REASON_CODE.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException(
                    "exception reason code is not report-safe");
        }
        String calculationVersionId = lastTransition == null
                ? current.calculationVersionId()
                : lastTransition.calculationVersionId();
        return new ExceptionFact(
                exceptionCase.caseId(),
                current.employeeId(),
                context.employeeNumber(),
                context.employeeName(),
                context.organizationId(),
                context.organizationName(),
                current.businessDate(),
                current.type().name(),
                ExceptionSeverity.valueOf(current.severity().name()),
                currentState(lastTransition == null
                        ? null
                        : lastTransition.type()),
                context.exceptionMinutes(),
                "原因码=" + reasonCode
                        + "；证据数量="
                        + current.evidenceReferences().size(),
                calculationVersionId);
    }

    private List<ExceptionFact> exceptionFacts(
            DailyAttendanceResult result,
            ProjectionContext context,
            boolean hasChargeableLate,
            int missingCount) {
        Map<String, ExceptionAccumulator> findings = new LinkedHashMap<>();
        result.items().stream()
                .sorted(Comparator.comparing(ResultItem::semanticKey))
                .forEach(item -> {
                    ExceptionDescriptor descriptor = descriptor(item);
                    if (descriptor == null) {
                        return;
                    }
                    if ("LATE".equals(descriptor.type())
                            && !hasChargeableLate
                            && !exactStartLate(result)) {
                        return;
                    }
                    String caseId = item.exceptionFingerprint() == null
                            ? scopedFingerprint(
                                    context,
                                    result.calculationVersionId(),
                                    item.semanticKey(),
                                    descriptor.type())
                            : item.exceptionFingerprint();
                    findings.computeIfAbsent(
                                    caseId,
                                    ignored -> new ExceptionAccumulator(
                                            caseId, descriptor))
                            .add(item);
                });
        result.ruleHits().stream()
                .filter(value -> "OVERTIME_DOCUMENT_MISSING_OR_LATE"
                        .equals(value.ruleCode()))
                .forEach(hit -> {
                    String caseId = scopedFingerprint(
                            context,
                            result.calculationVersionId(),
                            hit.ruleHitId(),
                            "OVERTIME_DOCUMENT_MISSING_OR_LATE");
                    findings.computeIfAbsent(
                                    caseId,
                                    ignored -> new ExceptionAccumulator(
                                            caseId,
                                            new ExceptionDescriptor(
                                                    "OVERTIME_DOCUMENT_MISSING_OR_LATE",
                                                    ExceptionSeverity.WARNING,
                                                    ExceptionState.PENDING_EVIDENCE)))
                            .add(hit.includedMinutes(), hit.evidenceIds().size());
                });
        // An approved outing or trip that runs past the last scheduled
        // off-time is surfaced for review so the employee can file the
        // matching overtime document. It carries the observed overrun
        // minutes rather than the recognised minutes, which are zero until
        // an overtime document is approved.
        result.ruleHits().stream()
                .filter(value -> "OUTING_OVERTIME_UNDECLARED"
                        .equals(value.ruleCode()))
                .forEach(hit -> {
                    String caseId = scopedFingerprint(
                            context,
                            result.calculationVersionId(),
                            hit.ruleHitId(),
                            "OUTING_OVERTIME_UNDECLARED");
                    findings.computeIfAbsent(
                                    caseId,
                                    ignored -> new ExceptionAccumulator(
                                            caseId,
                                            new ExceptionDescriptor(
                                                    "OUTING_OVERTIME_UNDECLARED",
                                                    ExceptionSeverity.WARNING,
                                                    ExceptionState.PENDING_REVIEW)))
                            .add(hit.rawMinutes(), hit.evidenceIds().size());
                });
        long penalizedLateMinutes = result.ruleHits().stream()
                .filter(this::lateRule)
                .mapToLong(RuleHit::includedMinutes)
                .sum();
        long earlyMinutes = result.ruleHits().stream()
                .filter(value -> "EARLY_DEPARTURE_CHARGEABLE"
                        .equals(value.ruleCode()))
                .mapToLong(RuleHit::includedMinutes)
                .sum();
        addMetricFallback(
                findings,
                result.calculationVersionId(),
                context,
                missingCount,
                penalizedLateMinutes,
                earlyMinutes,
                result.metrics().absenceMinutes());
        SlotCoverage coverage = slotCoverage(result, context);
        if (restDayOvertimeMissingOffDuty(context, result.metrics())
                && !coverage.afternoonCovered()
                && !coverage.fullDayLeave(result.metrics())
                && noneMatch(findings, "MISSING_OFF")) {
            addFallback(
                    findings,
                    result.calculationVersionId(),
                    context,
                    "MISSING_OFF_DUTY",
                    ExceptionSeverity.ERROR,
                    ExceptionState.OPEN,
                    0);
        }
        if (exactStartLate(result) && noneMatch(findings, "LATE")) {
            addFallback(
                    findings,
                    result.calculationVersionId(),
                    context,
                    "LATE",
                    ExceptionSeverity.WARNING,
                    ExceptionState.OPEN,
                    0);
        }
        return collapseExceptions(
                findings, result, context, coverage);
    }

    private List<ExceptionFact> collapseExceptions(
            Map<String, ExceptionAccumulator> findings,
            DailyAttendanceResult result,
            ProjectionContext context,
            SlotCoverage coverage) {
        boolean morningCovered = coverage.morningCovered();
        boolean afternoonCovered = coverage.afternoonCovered();
        boolean fullDayLeave = coverage.fullDayLeave(result.metrics());
        Instant morningPunch = morningPunch(
                context.firstPunchAt(), context.lastPunchAt());
        Instant afternoonPunch = afternoonPunch(
                context.firstPunchAt(), context.lastPunchAt());
        boolean noPunches = context.firstPunchAt() == null
                && context.lastPunchAt() == null;
        Map<String, ExceptionAccumulator> unique = new LinkedHashMap<>();
        for (ExceptionAccumulator finding : findings.values()) {
            String type = finding.descriptor.type();
            if (fullDayLeave
                    && (type.startsWith("MISSING")
                            || "ABSENCE".equals(type)
                            || "LATE".equals(type)
                            || "EARLY_DEPARTURE".equals(type))) {
                continue;
            }
            if (type.startsWith("MISSING_PUNCH")) {
                if (morningPunch != null && afternoonPunch != null) {
                    continue;
                }
                if (noPunches) {
                    if (!morningCovered) {
                        putUnique(
                                unique,
                                remap(
                                        finding,
                                        "MISSING_ON_DUTY",
                                        context,
                                        result.calculationVersionId()));
                    }
                    if (!afternoonCovered) {
                        putUnique(
                                unique,
                                remap(
                                        finding,
                                        "MISSING_OFF_DUTY",
                                        context,
                                        result.calculationVersionId()));
                    }
                    continue;
                }
                if (morningPunch == null && !morningCovered) {
                    type = "MISSING_ON_DUTY";
                } else if (afternoonPunch == null && !afternoonCovered) {
                    type = "MISSING_OFF_DUTY";
                } else {
                    continue;
                }
                finding = remap(
                        finding, type, context, result.calculationVersionId());
            }
            if ("LATE".equals(type) && morningCovered) {
                continue;
            }
            if ("EARLY_DEPARTURE".equals(type) && afternoonCovered) {
                continue;
            }
            if ("ABSENCE".equals(type)
                    && (morningCovered && afternoonCovered
                            || noPunches && morningCovered
                            || noPunches && afternoonCovered)) {
                continue;
            }
            if ("ABSENCE".equals(type)
                    && (unique.containsKey("MISSING_ON_DUTY")
                            || unique.containsKey("MISSING_OFF_DUTY")
                            || type.startsWith("MISSING"))) {
                continue;
            }
            putUnique(unique, finding);
        }
        if (unique.containsKey("ABSENCE")
                && (unique.containsKey("MISSING_ON_DUTY")
                        || unique.containsKey("MISSING_OFF_DUTY"))) {
            unique.remove("ABSENCE");
        }
        return unique.values().stream()
                .map(value -> new ExceptionFact(
                        value.caseId,
                        context.employeeId(),
                        context.employeeNumber(),
                        context.employeeName(),
                        context.organizationId(),
                        context.organizationName(),
                        context.businessDate(),
                        value.descriptor.type(),
                        value.descriptor.severity(),
                        value.descriptor.state(),
                        value.minutes,
                        evidenceSummary(value),
                        result.calculationVersionId()))
                .sorted(Comparator.comparing(ExceptionFact::caseId))
                .toList();
    }

    private static void putUnique(
            Map<String, ExceptionAccumulator> unique,
            ExceptionAccumulator finding) {
        unique.putIfAbsent(finding.descriptor.type(), finding);
    }

    private ExceptionAccumulator remap(
            ExceptionAccumulator original,
            String type,
            ProjectionContext context,
            String calculationVersionId) {
        ExceptionAccumulator remapped = new ExceptionAccumulator(
                scopedFingerprint(context, calculationVersionId, type),
                new ExceptionDescriptor(
                        type,
                        original.descriptor.severity(),
                        original.descriptor.state()));
        remapped.add(original.minutes, original.evidenceCount);
        remapped.reasonCode = original.reasonCode;
        return remapped;
    }

    private boolean slotCovered(
            DailyAttendanceResult result,
            LocalDate businessDate,
            LocalTime start,
            LocalTime end) {
        Instant slotStart = businessDate.atTime(start)
                .atZone(BUSINESS_ZONE)
                .toInstant();
        Instant slotEnd = businessDate.atTime(end)
                .atZone(BUSINESS_ZONE)
                .toInstant();
        TimeInterval slot = new TimeInterval(slotStart, slotEnd);
        return result.items().stream()
                .filter(item -> coveringCategory(item.category()))
                .anyMatch(item -> item.interval().overlaps(slot));
    }

    private static boolean coveringCategory(ResultCategory category) {
        return category == ResultCategory.LEAVE
                || category == ResultCategory.TIME_OFF
                || category == ResultCategory.OUTING_WORK
                || category == ResultCategory.TRIP_WORK
                || category == ResultCategory.EXEMPT_WORK;
    }

    private static Instant morningPunch(Instant first, Instant last) {
        if (first == null && last == null) {
            return null;
        }
        if (last == null || last.equals(first)) {
            Instant only = first != null ? first : last;
            return only.atZone(BUSINESS_ZONE).toLocalTime().isBefore(LocalTime.NOON)
                    ? only
                    : null;
        }
        return first;
    }

    private static Instant afternoonPunch(Instant first, Instant last) {
        if (first == null && last == null) {
            return null;
        }
        Instant closing = last != null ? last : first;
        if (last == null || last.equals(first)) {
            return closing.atZone(BUSINESS_ZONE).toLocalTime().isBefore(LocalTime.NOON)
                    ? null
                    : closing;
        }
        return last;
    }

    private static String evidenceSummary(ExceptionAccumulator value) {
        if ("FAKE_OVERTIME".equals(value.descriptor.type())) {
            return "加班时段进入正常上班且未请假；原因码="
                    + value.reasonCode
                    + "；证据数量="
                    + value.evidenceCount;
        }
        return "原因码=" + value.reasonCode
                + "；证据数量="
                + value.evidenceCount;
    }

    private void addMetricFallback(
            Map<String, ExceptionAccumulator> findings,
            String calculationVersionId,
            ProjectionContext context,
            int missing,
            long penalizedLate,
            long early,
            long absence) {
        if (missing > 0
                && noneMatch(findings, "MISSING_PUNCH")
                && noneMatch(findings, "MISSING_ON")
                && noneMatch(findings, "MISSING_OFF")) {
            addFallback(
                    findings,
                    calculationVersionId,
                    context,
                    "MISSING_PUNCH_OVERDUE",
                    ExceptionSeverity.ERROR,
                    ExceptionState.OPEN,
                    0);
        }
        if (penalizedLate > 0 && noneMatch(findings, "LATE")) {
            addFallback(
                    findings,
                    calculationVersionId,
                    context,
                    "LATE",
                    ExceptionSeverity.WARNING,
                    ExceptionState.OPEN,
                    penalizedLate);
        }
        if (early > 0 && noneMatch(findings, "EARLY_DEPARTURE")) {
            addFallback(
                    findings,
                    calculationVersionId,
                    context,
                    "EARLY_DEPARTURE",
                    ExceptionSeverity.WARNING,
                    ExceptionState.OPEN,
                    early);
        }
        if (absence > 0
                && noneMatch(findings, "ABSENCE")
                && noneMatch(findings, "MISSING_PUNCH")
                && noneMatch(findings, "AMBIGUOUS_PUNCH_MATCH")
                && noneMatch(findings, "LATE_CONVERTED_TO_ABSENCE")) {
            addFallback(
                    findings,
                    calculationVersionId,
                    context,
                    "ABSENCE",
                    ExceptionSeverity.ERROR,
                    ExceptionState.OPEN,
                    absence);
        }
    }

    private boolean noneMatch(
            Map<String, ExceptionAccumulator> findings, String typePrefix) {
        return findings.values().stream().noneMatch(value ->
                value.descriptor.type().startsWith(typePrefix));
    }

    private void addFallback(
            Map<String, ExceptionAccumulator> findings,
            String calculationVersionId,
            ProjectionContext context,
            String type,
            ExceptionSeverity severity,
            ExceptionState state,
            long minutes) {
        String caseId = scopedFingerprint(
                context, calculationVersionId, type);
        ExceptionAccumulator accumulator = findings.computeIfAbsent(
                caseId,
                ignored -> new ExceptionAccumulator(
                        caseId,
                        new ExceptionDescriptor(type, severity, state)));
        accumulator.add(minutes, 0);
    }

    private int resolvedMissingPunchCount(
            DailyAttendanceResult result,
            ProjectionContext context,
            AttendanceMetrics metrics) {
        SlotCoverage coverage = slotCoverage(result, context);
        if (coverage.fullDayLeave(metrics)) {
            return 0;
        }
        Instant morning = morningPunch(
                context.firstPunchAt(), context.lastPunchAt());
        Instant afternoon = afternoonPunch(
                context.firstPunchAt(), context.lastPunchAt());
        int uncovered = 0;
        if (metrics.scheduledMinutes() > 0) {
            if (!coverage.morningCovered() && morning == null) {
                uncovered++;
            }
            if (!coverage.afternoonCovered() && afternoon == null) {
                uncovered++;
            }
        }
        if (coverage.morningCovered()
                || coverage.afternoonCovered()
                || metrics.leaveOrTimeOffMinutes() > 0) {
            if (restDayOvertimeMissingOffDuty(context, metrics)
                    && !coverage.afternoonCovered()
                    && afternoon == null) {
                return Math.max(uncovered, 1);
            }
            return uncovered;
        }
        int missing = (int) result.items().stream()
                .filter(value -> isExplicitMissingPunch(value.reasonCode()))
                .map(ResultItem::exceptionFingerprint)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        missing = Math.max(missing, noonSlotMissing(context, result, metrics));
        if (restDayOvertimeMissingOffDuty(context, metrics)) {
            missing = Math.max(missing, 1);
        }
        return missing;
    }

    private SlotCoverage slotCoverage(
            DailyAttendanceResult result, ProjectionContext context) {
        return new SlotCoverage(
                slotCovered(
                        result,
                        context.businessDate(),
                        MORNING_START,
                        MORNING_END),
                slotCovered(
                        result,
                        context.businessDate(),
                        AFTERNOON_START,
                        AFTERNOON_END));
    }

    private static boolean restDayOvertimeMissingOffDuty(
            ProjectionContext context, AttendanceMetrics metrics) {
        if (metrics.leaveOrTimeOffMinutes() > 0) {
            return false;
        }
        if (metrics.recognizedOvertimeMinutes() <= 0) {
            return false;
        }
        DayType dayType = context.dayType();
        if (dayType != DayType.SATURDAY
                && dayType != DayType.SUNDAY
                && dayType != DayType.PUBLIC_HOLIDAY) {
            return false;
        }
        return afternoonPunch(
                context.firstPunchAt(), context.lastPunchAt()) == null;
    }

    private static int noonSlotMissing(
            ProjectionContext context,
            DailyAttendanceResult result,
            AttendanceMetrics metrics) {
        if (metrics.scheduledMinutes() <= 0
                || metrics.leaveOrTimeOffMinutes() > 0
                || result.items().stream().anyMatch(item ->
                        item.category() == ResultCategory.EXEMPT_WORK
                                || item.category() == ResultCategory.OUTING_WORK
                                || item.category() == ResultCategory.TRIP_WORK
                                || item.category() == ResultCategory.LEAVE
                                || item.category() == ResultCategory.TIME_OFF)) {
            return 0;
        }
        int missing = 0;
        if (context.firstPunchAt() == null) {
            missing++;
        }
        if (context.lastPunchAt() == null) {
            missing++;
        }
        return missing;
    }

    private static boolean isExplicitMissingPunch(String reasonCode) {
        return reasonCode != null
                && reasonCode.startsWith("MISSING_PUNCH_");
    }

    private ExceptionDescriptor descriptor(ResultItem item) {
        ExceptionDescriptor byReasonCode = descriptor(item.reasonCode());
        if (byReasonCode != null) {
            return byReasonCode;
        }
        return switch (item.category()) {
            case LATE -> new ExceptionDescriptor(
                    "LATE",
                    ExceptionSeverity.WARNING,
                    ExceptionState.OPEN);
            case EARLY_DEPARTURE -> new ExceptionDescriptor(
                    "EARLY_DEPARTURE",
                    ExceptionSeverity.WARNING,
                    ExceptionState.OPEN);
            case MISSING_PUNCH_PENDING -> new ExceptionDescriptor(
                    "MISSING_PUNCH_PENDING",
                    ExceptionSeverity.WARNING,
                    ExceptionState.PENDING_EVIDENCE);
            case ABSENCE -> new ExceptionDescriptor(
                    "ABSENCE",
                    ExceptionSeverity.ERROR,
                    ExceptionState.OPEN);
            case EVIDENCE_CONFLICT -> new ExceptionDescriptor(
                    "EVIDENCE_CONFLICT",
                    ExceptionSeverity.ERROR,
                    ExceptionState.PENDING_REVIEW);
            default -> null;
        };
    }

    private ExceptionDescriptor descriptor(String reasonCode) {
        if (reasonCode == null) {
            return null;
        }
        String normalizedReason = reasonCode.startsWith(
                "AMBIGUOUS_PUNCH_MATCH_")
                        ? "AMBIGUOUS_PUNCH_MATCH"
                        : reasonCode;
        if (normalizedReason.startsWith("MISSING_PUNCH_PENDING")) {
            normalizedReason = "MISSING_PUNCH_PENDING";
        } else if (normalizedReason.startsWith("MISSING_PUNCH_OVERDUE")) {
            normalizedReason = "MISSING_PUNCH_OVERDUE";
        } else if (normalizedReason.startsWith("LATE_CONVERTED_TO_ABSENCE")) {
            normalizedReason = "LATE_CONVERTED_TO_ABSENCE";
        }
        ExceptionType type;
        try {
            type = ExceptionType.valueOf(normalizedReason);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        ExceptionSeverity severity = switch (type) {
            case EARLY_RETURN_CANDIDATE -> ExceptionSeverity.INFO;
            case LATE, EARLY_DEPARTURE, MISSING_PUNCH_PENDING,
                    OVERTIME_DOCUMENT_MISSING_OR_LATE,
                    OVERTIME_FORM_BEYOND_LAST_PUNCH,
                    LONG_PUNCH_SPAN_REVIEW,
                    OUTING_OVERTIME_UNDECLARED,
                    LEAVE_PUNCH_CONFLICT -> ExceptionSeverity.WARNING;
            default -> ExceptionSeverity.ERROR;
        };
        ExceptionState state = switch (type) {
            case MISSING_PUNCH_PENDING,
                    OVERTIME_DOCUMENT_MISSING_OR_LATE ->
                ExceptionState.PENDING_EVIDENCE;
            case EVIDENCE_CONFLICT, AMBIGUOUS_PUNCH_MATCH,
                    CROSS_MIDNIGHT_REVIEW_REQUIRED,
                    LONG_PUNCH_SPAN_REVIEW,
                    OVERTIME_FORM_BEYOND_LAST_PUNCH,
                    OUTING_OR_TRIP_INCOMPLETE,
                    OUTING_OVERTIME_UNDECLARED,
                    OA_APPROVAL_STATUS_UNKNOWN,
                    OA_PERSON_REFERENCE_INVALID,
                    EMPLOYEE_UNMATCHED,
                    DUPLICATE_SOURCE_RECORD,
                    SOURCE_SCHEMA_CHANGED,
                    SOURCE_SYNC_STALE,
                    NO_ATTENDANCE_GROUP,
                    NO_SHIFT_OR_CALENDAR,
                    POST_CLOSE_SOURCE_CHANGE,
                    INPUT_INTEGRITY_ERROR ->
                ExceptionState.PENDING_REVIEW;
            default -> ExceptionState.OPEN;
        };
        return new ExceptionDescriptor(type.name(), severity, state);
    }

    private ExceptionState currentState(ExceptionTransitionType transition) {
        if (transition == null) {
            return ExceptionState.OPEN;
        }
        return switch (transition) {
            case PENDING_EVIDENCE -> ExceptionState.PENDING_EVIDENCE;
            case PENDING_REVIEW -> ExceptionState.PENDING_REVIEW;
            case RESOLVED_BY_RECALCULATION -> ExceptionState.RESOLVED;
            case OPENED, OBSERVED_BY_RECALCULATION,
                    REOPENED_BY_RECALCULATION -> ExceptionState.OPEN;
        };
    }

    private boolean lateRule(RuleHit value) {
        return "MONTHLY_LATE_GRACE_CONSUMED".equals(value.ruleCode())
                || "LATE_CHARGEABLE".equals(value.ruleCode())
                || "LATE_AT_SHIFT_START".equals(value.ruleCode());
    }

    private static boolean exactStartLate(DailyAttendanceResult result) {
        return result.ruleHits().stream()
                .anyMatch(value -> "LATE_AT_SHIFT_START"
                        .equals(value.ruleCode()));
    }

    private static String scopedFingerprint(
            ProjectionContext context, String... values) {
        String[] scoped = new String[values.length + 2];
        scoped[0] = context.employeeId();
        scoped[1] = context.businessDate().toString();
        System.arraycopy(values, 0, scoped, 2, values.length);
        return fingerprint(scoped);
    }

    private static String fingerprint(String... values) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "unable to fingerprint exception projection", exception);
        }
    }

    public record ProjectionContext(
            String factId,
            String companyId,
            String employeeId,
            String employeeNumber,
            String employeeName,
            String organizationId,
            String organizationVersionId,
            String organizationName,
            LocalDate businessDate,
            DayType dayType,
            String shiftLabel,
            Instant firstPunchAt,
            Instant lastPunchAt,
            LeaveType leaveType) {

        public ProjectionContext {
            require(factId, "factId");
            require(companyId, "companyId");
            require(employeeId, "employeeId");
            require(employeeNumber, "employeeNumber");
            require(employeeName, "employeeName");
            require(organizationId, "organizationId");
            require(organizationVersionId, "organizationVersionId");
            require(organizationName, "organizationName");
            Objects.requireNonNull(businessDate, "businessDate");
            Objects.requireNonNull(dayType, "dayType");
            require(shiftLabel, "shiftLabel");
            if (firstPunchAt != null
                    && lastPunchAt != null
                    && lastPunchAt.isBefore(firstPunchAt)) {
                throw new IllegalArgumentException(
                        "last punch cannot precede first punch");
            }
        }

        public ProjectionContext(
                String factId,
                String companyId,
                String employeeId,
                String employeeNumber,
                String employeeName,
                String organizationId,
                String organizationVersionId,
                String organizationName,
                LocalDate businessDate,
                DayType dayType,
                String shiftLabel,
                Instant firstPunchAt,
                Instant lastPunchAt) {
            this(
                    factId,
                    companyId,
                    employeeId,
                    employeeNumber,
                    employeeName,
                    organizationId,
                    organizationVersionId,
                    organizationName,
                    businessDate,
                    dayType,
                    shiftLabel,
                    firstPunchAt,
                    lastPunchAt,
                    null);
        }
    }

    public record ProjectionFacts(
            DailyFact dailyFact,
            List<ExceptionFact> exceptionFacts) {

        public ProjectionFacts {
            Objects.requireNonNull(dailyFact, "dailyFact");
            exceptionFacts = List.copyOf(exceptionFacts);
        }
    }

    public record CurrentExceptionProjectionContext(
            String companyId,
            String employeeId,
            String employeeNumber,
            String employeeName,
            String organizationId,
            String organizationName,
            LocalDate businessDate,
            long exceptionMinutes) {

        public CurrentExceptionProjectionContext {
            require(companyId, "companyId");
            require(employeeId, "employeeId");
            require(employeeNumber, "employeeNumber");
            require(employeeName, "employeeName");
            require(organizationId, "organizationId");
            require(organizationName, "organizationName");
            Objects.requireNonNull(businessDate, "businessDate");
            if (exceptionMinutes < 0
                    || exceptionMinutes > 4_294_967_295L) {
                throw new IllegalArgumentException(
                        "exceptionMinutes is out of range");
            }
        }
    }

    private record SlotCoverage(
            boolean morningCovered, boolean afternoonCovered) {

        private boolean fullDayLeave(AttendanceMetrics metrics) {
            if (morningCovered && afternoonCovered) {
                return true;
            }
            return metrics.leaveOrTimeOffMinutes() > 0
                    && metrics.scheduledMinutes() > 0
                    && metrics.leaveOrTimeOffMinutes()
                            >= metrics.scheduledMinutes();
        }
    }

    private record ExceptionDescriptor(
            String type,
            ExceptionSeverity severity,
            ExceptionState state) {
    }

    private static final class ExceptionAccumulator {

        private final String caseId;
        private final ExceptionDescriptor descriptor;
        private long minutes;
        private int evidenceCount;
        private String reasonCode;

        private ExceptionAccumulator(
                String caseId, ExceptionDescriptor descriptor) {
            this.caseId = caseId;
            this.descriptor = descriptor;
        }

        private void add(ResultItem item) {
            add(item.minutes(), item.evidenceIds().size());
            reasonCode = reasonCode == null
                    ? item.reasonCode()
                    : reasonCode;
        }

        private void add(long value, int evidence) {
            minutes += value;
            evidenceCount += evidence;
            if (reasonCode == null) {
                reasonCode = descriptor.type();
            }
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
