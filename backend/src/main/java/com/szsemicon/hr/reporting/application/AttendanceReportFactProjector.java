package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.DailyAttendanceResult;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.ResultCategory;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.ResultItem;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.RuleHit;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceExceptionModels.AttendanceExceptionCase;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceExceptionModels.ExceptionFinding;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceExceptionModels.ExceptionTransitionType;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceExceptionModels.ExceptionType;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DayType;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionSeverity;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
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
        int missing = (int) result.items().stream()
                .filter(value -> value.category()
                                == ResultCategory.MISSING_PUNCH_PENDING
                        || value.category() == ResultCategory.ABSENCE)
                .map(ResultItem::exceptionFingerprint)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        var metrics = result.metrics();
        DailyFact daily = new DailyFact(
                context.factId(),
                context.legalEntityId(),
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
                metrics.leaveOrTimeOffMinutes(),
                metrics.absenceMinutes(),
                metrics.actualWorkMinutes(),
                rawLate,
                penalizedLate,
                early,
                missing,
                context.firstPunchAt(),
                context.lastPunchAt(),
                result.calculationVersionId(),
                result.resultDigest());
        return new ProjectionFacts(daily, exceptionFacts(result, context));
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
                            && finding.legalEntityId().equals(
                                    initial.legalEntityId())
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
        if (!context.legalEntityId().equals(current.legalEntityId())
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
            DailyAttendanceResult result, ProjectionContext context) {
        Map<String, ExceptionAccumulator> findings = new LinkedHashMap<>();
        result.items().stream()
                .sorted(Comparator.comparing(ResultItem::semanticKey))
                .forEach(item -> {
                    ExceptionDescriptor descriptor = descriptor(item);
                    if (descriptor == null) {
                        return;
                    }
                    String caseId = item.exceptionFingerprint() == null
                            ? fingerprint(
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
                    String caseId = fingerprint(
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
        return findings.values().stream()
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
                        "原因码=" + value.reasonCode
                                + "；证据数量=" + value.evidenceCount,
                        result.calculationVersionId()))
                .sorted(Comparator.comparing(ExceptionFact::caseId))
                .toList();
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
                    "MISSING_PUNCH_OVERDUE",
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
                    LEAVE_PUNCH_CONFLICT -> ExceptionSeverity.WARNING;
            default -> ExceptionSeverity.ERROR;
        };
        ExceptionState state = switch (type) {
            case MISSING_PUNCH_PENDING ->
                ExceptionState.PENDING_EVIDENCE;
            case EVIDENCE_CONFLICT, AMBIGUOUS_PUNCH_MATCH,
                    CROSS_MIDNIGHT_REVIEW_REQUIRED,
                    OUTING_OR_TRIP_INCOMPLETE,
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
                || "LATE_CHARGEABLE".equals(value.ruleCode());
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
            String legalEntityId,
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

        public ProjectionContext {
            require(factId, "factId");
            require(legalEntityId, "legalEntityId");
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
            String legalEntityId,
            String employeeId,
            String employeeNumber,
            String employeeName,
            String organizationId,
            String organizationName,
            LocalDate businessDate,
            long exceptionMinutes) {

        public CurrentExceptionProjectionContext {
            require(legalEntityId, "legalEntityId");
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
