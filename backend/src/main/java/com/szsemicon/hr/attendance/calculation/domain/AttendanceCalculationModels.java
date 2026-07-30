package com.szsemicon.hr.attendance.calculation.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class AttendanceCalculationModels {

    private AttendanceCalculationModels() {
    }

    public enum SegmentKind {
        SCHEDULED_WORK
    }

    public enum PunchDirection {
        ENTRY,
        EXIT,
        AUTO
    }

    public enum EvidenceKind {
        APPROVED_ADJUSTMENT(1),
        REVERSAL(2),
        LEAVE(3),
        TIME_OFF(3),
        OUTING(3),
        TRIP(3),
        EXEMPT_PUNCH(3),
        PUNCH_CORRECTION(3),
        OVERTIME(3),
        PUNCH(4),
        SYSTEM_FINDING(5);

        private final int priority;

        EvidenceKind(int priority) {
            this.priority = priority;
        }

        public int priority() {
            return priority;
        }
    }

    public enum EvidenceDecisionStatus {
        SELECTED,
        REJECTED,
        CONFLICT
    }

    public enum ResultCategory {
        SCHEDULED_WORK,
        LEAVE,
        TIME_OFF,
        OUTING_WORK,
        TRIP_WORK,
        EXEMPT_WORK,
        MISSING_PUNCH_PENDING,
        ABSENCE,
        LATE,
        EARLY_DEPARTURE,
        EXTENDED_PRESENCE,
        RECOGNIZED_OVERTIME,
        EVIDENCE_CONFLICT
    }

    public enum ExplanationNodeType {
        DAILY_RESULT,
        RESULT_ITEM,
        SCHEDULED_SEGMENT,
        RULE_HIT,
        EVIDENCE,
        ADJUSTMENT,
        EXCEPTION,
        REQUEST
    }

    public record TimeInterval(Instant start, Instant end) {

        public TimeInterval {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            if (!start.isBefore(end)) {
                throw new IllegalArgumentException("interval start must be before end");
            }
        }

        public long minutes() {
            return Duration.between(start, end).toMinutes();
        }

        public boolean overlaps(TimeInterval other) {
            return start.isBefore(other.end) && other.start.isBefore(end);
        }

        public TimeInterval intersection(TimeInterval other) {
            Instant intersectionStart = start.isAfter(other.start) ? start : other.start;
            Instant intersectionEnd = end.isBefore(other.end) ? end : other.end;
            return intersectionStart.isBefore(intersectionEnd)
                    ? new TimeInterval(intersectionStart, intersectionEnd)
                    : null;
        }

        public boolean contains(Instant instant) {
            return !instant.isBefore(start) && instant.isBefore(end);
        }
    }

    public record ScheduledWorkSegment(
            String segmentId,
            LocalDate businessDate,
            TimeInterval interval,
            TimeInterval arrivalWindow,
            TimeInterval departureWindow,
            SegmentKind kind) {

        public ScheduledWorkSegment {
            segmentId = requireText(segmentId, "segmentId");
            Objects.requireNonNull(businessDate, "businessDate");
            Objects.requireNonNull(interval, "interval");
            Objects.requireNonNull(arrivalWindow, "arrivalWindow");
            Objects.requireNonNull(departureWindow, "departureWindow");
            Objects.requireNonNull(kind, "kind");
            if (!arrivalWindow.overlaps(interval)
                    || !departureWindow.overlaps(interval)) {
                throw new IllegalArgumentException(
                        "punch windows must overlap their scheduled segment");
            }
        }
    }

    public record PunchEvent(
            String eventId,
            Instant instant,
            PunchDirection direction,
            String evidenceReference) {

        public PunchEvent {
            eventId = requireText(eventId, "eventId");
            Objects.requireNonNull(instant, "instant");
            Objects.requireNonNull(direction, "direction");
            evidenceReference = requireText(
                    evidenceReference, "evidenceReference");
        }
    }

    public record IntervalEvidence(
            String evidenceId,
            EvidenceKind kind,
            TimeInterval interval,
            String sourceReference,
            Instant firstSubmittedAt,
            boolean effective) {

        public IntervalEvidence {
            evidenceId = requireText(evidenceId, "evidenceId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(interval, "interval");
            sourceReference = requireText(sourceReference, "sourceReference");
            if (kind == EvidenceKind.PUNCH) {
                throw new IllegalArgumentException(
                        "point punches must use PunchEvent");
            }
        }
    }

    public record AdjustmentFact(
            String adjustmentId,
            TimeInterval interval,
            ResultCategory conclusion,
            String reason,
            String actorId,
            String authorizationDecisionReference,
            String approvalReference,
            String requestId,
            String correlationId,
            String periodToken,
            long version,
            String reversesAdjustmentId) {

        public AdjustmentFact {
            adjustmentId = requireText(adjustmentId, "adjustmentId");
            Objects.requireNonNull(interval, "interval");
            Objects.requireNonNull(conclusion, "conclusion");
            reason = requireText(reason, "reason");
            actorId = requireText(actorId, "actorId");
            authorizationDecisionReference = requireText(
                    authorizationDecisionReference,
                    "authorizationDecisionReference");
            approvalReference = requireText(
                    approvalReference, "approvalReference");
            requestId = requireText(requestId, "requestId");
            correlationId = requireText(correlationId, "correlationId");
            periodToken = requireText(periodToken, "periodToken");
            if (version < 0) {
                throw new IllegalArgumentException(
                        "adjustment version must be non-negative");
            }
            reversesAdjustmentId = optionalText(
                    reversesAdjustmentId, "reversesAdjustmentId");
        }
    }

    public record GraceConsumptionSnapshot(
            String employeeId,
            YearMonth month,
            int used,
            String snapshotDigest) {

        public GraceConsumptionSnapshot {
            employeeId = requireText(employeeId, "employeeId");
            Objects.requireNonNull(month, "month");
            if (used < 0) {
                throw new IllegalArgumentException(
                        "grace used count must be non-negative");
            }
            snapshotDigest = requireText(snapshotDigest, "snapshotDigest");
        }
    }

    public record MealDeductionRule(
            String ruleId,
            TimeInterval window,
            int deductionMinutes,
            int triggerMinutes,
            boolean requireFullCoverage) {

        public MealDeductionRule {
            ruleId = requireText(ruleId, "ruleId");
            Objects.requireNonNull(window, "window");
            if (deductionMinutes < 0 || triggerMinutes < 0) {
                throw new IllegalArgumentException(
                        "meal deduction values must be non-negative");
            }
        }

        public MealDeductionRule(
                String ruleId,
                TimeInterval window,
                int deductionMinutes,
                boolean requireFullCoverage) {
            this(
                    ruleId,
                    window,
                    deductionMinutes,
                    0,
                    requireFullCoverage);
        }
    }

    public record CalculationPolicy(
            int lateGraceMaxMinutes,
            int monthlyLateGraceUses,
            Instant correctionDeadline,
            boolean timelyPendingSubmission,
            Instant overtimeFirstSubmittedAt,
            int overtimeSubmissionDeadlineMinutes,
            List<MealDeductionRule> mealDeductions) {

        public CalculationPolicy {
            if (lateGraceMaxMinutes < 0
                    || monthlyLateGraceUses < 0
                    || overtimeSubmissionDeadlineMinutes < 0) {
                throw new IllegalArgumentException(
                        "policy minute/count values must be non-negative");
            }
            Objects.requireNonNull(correctionDeadline, "correctionDeadline");
            mealDeductions = immutableSorted(
                    mealDeductions,
                    Comparator.comparing(MealDeductionRule::ruleId));
            assertUniqueReferences(
                    mealDeductions.stream()
                            .map(MealDeductionRule::ruleId)
                            .toList(),
                    "meal deduction rule");
        }
    }

    public record CalculationInputSnapshot(
            String companyId,
            String employeeId,
            String employmentPeriodId,
            LocalDate businessDate,
            ZoneId businessZone,
            Instant knowledgeCutoff,
            List<ScheduledWorkSegment> segments,
            List<PunchEvent> punchEvents,
            List<IntervalEvidence> intervalEvidence,
            List<AdjustmentFact> adjustments,
            GraceConsumptionSnapshot graceConsumption,
            CalculationPolicy policy,
            String configurationSnapshotReference,
            String configurationDigest,
            String evidenceSnapshotReference,
            String evidenceDigest,
            String adjustmentDigest,
            String periodId,
            long periodVersion,
            String periodToken,
            String algorithmVersion,
            String requestId,
            String correlationId) {

        public CalculationInputSnapshot {
            companyId = requireText(companyId, "companyId");
            employeeId = requireText(employeeId, "employeeId");
            employmentPeriodId = requireText(
                    employmentPeriodId, "employmentPeriodId");
            Objects.requireNonNull(businessDate, "businessDate");
            Objects.requireNonNull(businessZone, "businessZone");
            Objects.requireNonNull(knowledgeCutoff, "knowledgeCutoff");
            segments = immutableSorted(
                    segments,
                    Comparator.comparing(
                                    (ScheduledWorkSegment value) ->
                                            value.interval().start())
                            .thenComparing(ScheduledWorkSegment::segmentId));
            punchEvents = immutableSorted(
                    punchEvents,
                    Comparator.comparing(PunchEvent::instant)
                            .thenComparing(value -> value.direction().name())
                            .thenComparing(PunchEvent::eventId));
            intervalEvidence = immutableSorted(
                    intervalEvidence,
                    Comparator.comparing(
                                    (IntervalEvidence value) ->
                                            value.interval().start())
                            .thenComparing(value -> value.kind().priority())
                            .thenComparing(IntervalEvidence::evidenceId));
            adjustments = immutableSorted(
                    adjustments,
                    Comparator.comparing(
                                    (AdjustmentFact value) ->
                                            value.interval().start())
                            .thenComparing(AdjustmentFact::adjustmentId));
            Objects.requireNonNull(graceConsumption, "graceConsumption");
            Objects.requireNonNull(policy, "policy");
            configurationSnapshotReference = requireText(
                    configurationSnapshotReference,
                    "configurationSnapshotReference");
            configurationDigest = requireText(
                    configurationDigest, "configurationDigest");
            evidenceSnapshotReference = requireText(
                    evidenceSnapshotReference, "evidenceSnapshotReference");
            evidenceDigest = requireText(evidenceDigest, "evidenceDigest");
            adjustmentDigest = requireText(adjustmentDigest, "adjustmentDigest");
            periodId = requireText(periodId, "periodId");
            if (periodVersion < 0) {
                throw new IllegalArgumentException(
                        "periodVersion must be non-negative");
            }
            periodToken = requireText(periodToken, "periodToken");
            algorithmVersion = requireText(
                    algorithmVersion, "algorithmVersion");
            requestId = requireText(requestId, "requestId");
            correlationId = requireText(correlationId, "correlationId");
            if (!employeeId.equals(graceConsumption.employeeId())
                    || !YearMonth.from(businessDate)
                            .equals(graceConsumption.month())) {
                throw new IllegalArgumentException(
                        "grace snapshot must match employee and month");
            }
            for (ScheduledWorkSegment segment : segments) {
                if (!businessDate.equals(segment.businessDate())) {
                    throw new IllegalArgumentException(
                            "all segments must belong to the business date");
                }
            }
            assertNoSegmentOverlap(segments);
            assertUniqueReferences(
                    punchEvents.stream().map(PunchEvent::eventId).toList(),
                    "punch event");
            assertUniqueReferences(
                    intervalEvidence.stream()
                            .map(IntervalEvidence::evidenceId)
                            .toList(),
                    "evidence");
            assertUniqueReferences(
                    adjustments.stream()
                            .map(AdjustmentFact::adjustmentId)
                            .toList(),
                    "adjustment");
        }
    }

    public record EvidenceDecision(
            String decisionId,
            TimeInterval interval,
            String evidenceId,
            EvidenceKind kind,
            EvidenceDecisionStatus status,
            String reasonCode) {

        public EvidenceDecision {
            decisionId = requireText(decisionId, "decisionId");
            Objects.requireNonNull(interval, "interval");
            evidenceId = requireText(evidenceId, "evidenceId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(status, "status");
            reasonCode = requireText(reasonCode, "reasonCode");
        }
    }

    public record RuleHit(
            String ruleHitId,
            String ruleReference,
            String segmentId,
            String ruleCode,
            long rawMinutes,
            long includedMinutes,
            List<String> evidenceIds) {

        public RuleHit {
            ruleHitId = requireText(ruleHitId, "ruleHitId");
            ruleReference = requireText(ruleReference, "ruleReference");
            segmentId = requireText(segmentId, "segmentId");
            ruleCode = requireText(ruleCode, "ruleCode");
            if (rawMinutes < 0 || includedMinutes < 0) {
                throw new IllegalArgumentException(
                        "rule minutes must be non-negative");
            }
            evidenceIds = immutableSorted(evidenceIds, Comparator.naturalOrder());
        }
    }

    public record ResultItem(
            String semanticKey,
            String segmentId,
            TimeInterval interval,
            ResultCategory category,
            long minutes,
            String reasonCode,
            List<String> evidenceIds,
            String exceptionFingerprint) {

        public ResultItem {
            semanticKey = requireText(semanticKey, "semanticKey");
            segmentId = requireText(segmentId, "segmentId");
            Objects.requireNonNull(interval, "interval");
            Objects.requireNonNull(category, "category");
            if (minutes < 0 || minutes > interval.minutes()) {
                throw new IllegalArgumentException(
                        "item minutes must fit its interval");
            }
            reasonCode = requireText(reasonCode, "reasonCode");
            evidenceIds = immutableSorted(evidenceIds, Comparator.naturalOrder());
            exceptionFingerprint = optionalText(
                    exceptionFingerprint, "exceptionFingerprint");
        }
    }

    public record AttendanceMetrics(
            long scheduledMinutes,
            long confirmedScheduledWorkMinutes,
            long extendedPresenceMinutes,
            long recognizedOvertimeMinutes,
            long leaveOrTimeOffMinutes,
            long absenceMinutes,
            long actualWorkMinutes) {

        public AttendanceMetrics {
            if (scheduledMinutes < 0
                    || confirmedScheduledWorkMinutes < 0
                    || extendedPresenceMinutes < 0
                    || recognizedOvertimeMinutes < 0
                    || leaveOrTimeOffMinutes < 0
                    || absenceMinutes < 0
                    || actualWorkMinutes < 0) {
                throw new IllegalArgumentException(
                        "attendance metrics must be non-negative");
            }
            if (actualWorkMinutes
                    != confirmedScheduledWorkMinutes
                            + recognizedOvertimeMinutes) {
                throw new IllegalArgumentException(
                        "actual work must equal W_in + O");
            }
        }
    }

    public record ExplanationNode(
            String nodeId,
            ExplanationNodeType type,
            String referenceId,
            Map<String, String> attributes) {

        public ExplanationNode {
            nodeId = requireText(nodeId, "nodeId");
            Objects.requireNonNull(type, "type");
            referenceId = requireText(referenceId, "referenceId");
            attributes = Map.copyOf(Objects.requireNonNull(
                    attributes, "attributes"));
        }
    }

    public record ExplanationEdge(
            String fromNodeId,
            String toNodeId,
            String relationship) {

        public ExplanationEdge {
            fromNodeId = requireText(fromNodeId, "fromNodeId");
            toNodeId = requireText(toNodeId, "toNodeId");
            relationship = requireText(relationship, "relationship");
        }
    }

    public record ExplanationGraph(
            List<ExplanationNode> nodes,
            List<ExplanationEdge> edges) {

        public ExplanationGraph {
            nodes = immutableSorted(
                    nodes, Comparator.comparing(ExplanationNode::nodeId));
            edges = immutableSorted(
                    edges,
                    Comparator.comparing(ExplanationEdge::fromNodeId)
                            .thenComparing(ExplanationEdge::toNodeId)
                            .thenComparing(ExplanationEdge::relationship));
            Set<String> nodeIds = nodes.stream()
                    .map(ExplanationNode::nodeId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            for (ExplanationEdge edge : edges) {
                if (!nodeIds.contains(edge.fromNodeId())
                        || !nodeIds.contains(edge.toNodeId())) {
                    throw new IllegalArgumentException(
                            "explanation edge must reference existing nodes");
                }
            }
        }
    }

    public record DailyAttendanceResult(
            String calculationVersionId,
            String inputDigest,
            String resultDigest,
            String algorithmVersion,
            AttendanceMetrics metrics,
            List<ResultItem> items,
            List<RuleHit> ruleHits,
            List<EvidenceDecision> evidenceDecisions,
            ExplanationGraph explanation,
            Set<String> consumedPunchEventIds,
            List<String> exceptionFingerprints) {

        public DailyAttendanceResult {
            calculationVersionId = requireText(
                    calculationVersionId, "calculationVersionId");
            inputDigest = requireText(inputDigest, "inputDigest");
            resultDigest = requireText(resultDigest, "resultDigest");
            algorithmVersion = requireText(
                    algorithmVersion, "algorithmVersion");
            Objects.requireNonNull(metrics, "metrics");
            items = immutableSorted(
                    items, Comparator.comparing(ResultItem::semanticKey));
            ruleHits = immutableSorted(
                    ruleHits, Comparator.comparing(RuleHit::ruleHitId));
            evidenceDecisions = immutableSorted(
                    evidenceDecisions,
                    Comparator.comparing(EvidenceDecision::decisionId));
            Objects.requireNonNull(explanation, "explanation");
            consumedPunchEventIds = Set.copyOf(Objects.requireNonNull(
                    consumedPunchEventIds, "consumedPunchEventIds"));
            exceptionFingerprints = immutableSorted(
                    exceptionFingerprints, Comparator.naturalOrder());
        }
    }

    static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    static String optionalText(String value, String field) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must be null or non-blank");
        }
        return value;
    }

    static <T> List<T> immutableSorted(
            List<T> values, Comparator<? super T> comparator) {
        Objects.requireNonNull(values, "values");
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("collection contains null");
        }
        return values.stream().sorted(comparator).toList();
    }

    private static void assertNoSegmentOverlap(
            List<ScheduledWorkSegment> segments) {
        for (int index = 1; index < segments.size(); index++) {
            if (segments.get(index - 1).interval()
                    .overlaps(segments.get(index).interval())) {
                throw new IllegalArgumentException(
                        "scheduled work segments must not overlap");
            }
        }
    }

    private static void assertUniqueReferences(
            List<String> values, String kind) {
        if (Set.copyOf(values).size() != values.size()) {
            throw new IllegalArgumentException(
                    kind + " references must be unique");
        }
    }
}
