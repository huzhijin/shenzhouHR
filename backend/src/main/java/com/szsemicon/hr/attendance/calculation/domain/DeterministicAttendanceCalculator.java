package com.szsemicon.hr.attendance.calculation.domain;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.AdjustmentFact;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.AttendanceMetrics;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.CalculationInputSnapshot;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.DailyAttendanceResult;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.EvidenceDecision;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.EvidenceDecisionStatus;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.EvidenceKind;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.ExplanationEdge;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.ExplanationGraph;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.ExplanationNode;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.ExplanationNodeType;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.IntervalEvidence;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.MealDeductionRule;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.PunchDirection;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.PunchEvent;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.ResultCategory;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.ResultItem;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.RuleHit;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.ScheduledWorkSegment;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.TimeInterval;
import com.szsemicon.hr.attendance.domain.OvertimeType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public final class DeterministicAttendanceCalculator {

    public DailyAttendanceResult calculate(
            String calculationVersionId,
            CalculationInputSnapshot snapshot) {
        AttendanceCalculationModels.requireText(
                calculationVersionId, "calculationVersionId");
        Objects.requireNonNull(snapshot, "snapshot");
        String inputDigest = CanonicalAttendanceDigests.inputDigest(snapshot);
        Accumulator accumulator = new Accumulator();
        Set<String> consumedPunchIds = new LinkedHashSet<>();
        List<Candidate> candidates = candidates(snapshot);
        for (ScheduledWorkSegment segment : snapshot.segments()) {
            calculateSegment(
                    snapshot,
                    segment,
                    candidates,
                    consumedPunchIds,
                    accumulator);
        }
        calculateAuthorizedOvertime(
                snapshot, consumedPunchIds, accumulator);
        addUndeclaredOutingOvertime(snapshot, accumulator);
        AttendanceMetrics metrics = metrics(snapshot, accumulator.items);
        ExplanationGraph explanation = explanation(
                calculationVersionId, snapshot, accumulator);
        DailyAttendanceResult draft = new DailyAttendanceResult(
                calculationVersionId,
                inputDigest,
                "PENDING",
                snapshot.algorithmVersion(),
                metrics,
                accumulator.items,
                accumulator.ruleHits,
                accumulator.decisions,
                explanation,
                consumedPunchIds,
                accumulator.exceptionFingerprints);
        String resultDigest =
                CanonicalAttendanceDigests.resultDigest(draft);
        return new DailyAttendanceResult(
                calculationVersionId,
                inputDigest,
                resultDigest,
                snapshot.algorithmVersion(),
                metrics,
                accumulator.items,
                accumulator.ruleHits,
                accumulator.decisions,
                explanation,
                consumedPunchIds,
                accumulator.exceptionFingerprints);
    }

    private void calculateSegment(
            CalculationInputSnapshot snapshot,
            ScheduledWorkSegment segment,
            List<Candidate> allCandidates,
            Set<String> consumedPunchIds,
            Accumulator accumulator) {
        if (snapshot.punchExempt()) {
            accumulator.items.add(item(
                    segment,
                    segment.interval(),
                    ResultCategory.EXEMPT_WORK,
                    segment.interval().minutes(),
                    "PUNCH_EXEMPT_ROLE",
                    List.of(),
                    null,
                    "punch-exempt-role"));
            return;
        }
        List<Candidate> segmentCandidates = allCandidates.stream()
                .filter(candidate ->
                        candidate.interval().overlaps(segment.interval()))
                .toList();
        TreeSet<Instant> boundaries = new TreeSet<>();
        boundaries.add(segment.interval().start());
        boundaries.add(segment.interval().end());
        for (Candidate candidate : segmentCandidates) {
            TimeInterval intersection =
                    candidate.interval().intersection(segment.interval());
            if (intersection != null) {
                boundaries.add(intersection.start());
                boundaries.add(intersection.end());
            }
        }
        List<Instant> ordered = List.copyOf(boundaries);
        List<TimeInterval> uncovered = new ArrayList<>();
        for (int index = 1; index < ordered.size(); index++) {
            TimeInterval slice =
                    new TimeInterval(ordered.get(index - 1), ordered.get(index));
            List<Candidate> covering = segmentCandidates.stream()
                    .filter(candidate -> covers(candidate.interval(), slice))
                    .sorted(Candidate.ORDER)
                    .toList();
            if (covering.isEmpty()) {
                uncovered.add(slice);
                continue;
            }
            int highestPriority = covering.getFirst().kind().priority();
            List<Candidate> highest = covering.stream()
                    .filter(value -> value.kind().priority() == highestPriority)
                    .toList();
            boolean exemptionPrecedence = highest.stream().anyMatch(value ->
                            value.kind() == EvidenceKind.EXEMPT_PUNCH)
                    && highest.stream().anyMatch(value ->
                            value.kind() == EvidenceKind.OUTING);
            if (exemptionPrecedence) {
                highest = highest.stream()
                        .filter(value -> value.kind() != EvidenceKind.OUTING)
                        .toList();
            }
            boolean conflict = mutuallyExclusive(highest);
            if (conflict) {
                String fingerprint = exceptionFingerprint(
                        snapshot,
                        segment.segmentId(),
                        slice,
                        "EVIDENCE_CONFLICT",
                        highest.stream().map(Candidate::id).toList());
                accumulator.exceptionFingerprints.add(fingerprint);
                accumulator.items.add(item(
                        segment,
                        slice,
                        ResultCategory.EVIDENCE_CONFLICT,
                        0,
                        "EVIDENCE_CONFLICT",
                        highest.stream().map(Candidate::id).toList(),
                        fingerprint,
                        "evidence-conflict"));
                for (Candidate candidate : covering) {
                    accumulator.decisions.add(decision(
                            segment,
                            slice,
                            candidate,
                            candidate.kind().priority() == highestPriority
                                    ? EvidenceDecisionStatus.CONFLICT
                                    : EvidenceDecisionStatus.REJECTED,
                            candidate.kind().priority() == highestPriority
                                    ? "SAME_PRIORITY_MUTUALLY_EXCLUSIVE"
                                    : "LOWER_PRIORITY"));
                }
                continue;
            }
            Candidate selected = highest.getFirst();
            for (Candidate candidate : covering) {
                accumulator.decisions.add(decision(
                        segment,
                        slice,
                        candidate,
                        candidate == selected
                                ? EvidenceDecisionStatus.SELECTED
                                : EvidenceDecisionStatus.REJECTED,
                        exemptionPrecedence
                                        && candidate.kind()
                                                == EvidenceKind.OUTING
                                ? "EXEMPTION_PRECEDENCE"
                                : candidate == selected
                                ? "HIGHEST_PRIORITY"
                                : "LOWER_OR_DUPLICATE_PRIORITY"));
            }
            if (selected.category() == null) {
                uncovered.add(slice);
                continue;
            }
            accumulator.items.add(item(
                    segment,
                    slice,
                    selected.category(),
                    slice.minutes(),
                    "SELECTED_" + selected.kind().name(),
                    List.of(selected.id()),
                    null,
                    "evidence"));
        }
        if (uncovered.isEmpty()) {
            return;
        }
        List<IntervalEvidence> outingEvidence = outingEvidence(snapshot);
        if (!outingEvidence.isEmpty()) {
            List<PunchEvent> validPunches = businessDatePunches(snapshot);
            if (validPunches.isEmpty()) {
                addOutingWithoutPunch(
                        snapshot,
                        segment,
                        uncovered,
                        outingEvidence,
                        accumulator);
                return;
            }
            List<String> evidenceIds = new ArrayList<>();
            outingEvidence.stream()
                    .map(IntervalEvidence::evidenceId)
                    .sorted()
                    .forEach(evidenceIds::add);
            validPunches.stream()
                    .map(PunchEvent::eventId)
                    .sorted()
                    .forEach(evidenceIds::add);
            validPunches.stream()
                    .map(PunchEvent::eventId)
                    .forEach(consumedPunchIds::add);
            for (TimeInterval slice : uncovered) {
                accumulator.items.add(item(
                        segment,
                        slice,
                        ResultCategory.OUTING_WORK,
                        slice.minutes(),
                        "OUTING_APPROVED_WITH_PUNCH",
                        evidenceIds,
                        null,
                        "outing-work"));
            }
            return;
        }
        PunchSelection selection = selectPunches(
                snapshot.punchEvents(), segment, consumedPunchIds);
        if (selection.ambiguous()) {
            addMissingOrAmbiguous(
                    snapshot,
                    segment,
                    uncovered,
                    selection,
                    "AMBIGUOUS_PUNCH_MATCH",
                    accumulator);
            return;
        }
        selection.selectedIds().forEach(consumedPunchIds::add);
        if (selection.arrival() == null || selection.departure() == null) {
            if (!selection.selectedIds().isEmpty()
                    && missingPunchSides(segment, uncovered, selection)
                            .isEmpty()) {
                for (TimeInterval slice : uncovered) {
                    accumulator.items.add(item(
                            segment,
                            slice,
                            ResultCategory.SCHEDULED_WORK,
                            slice.minutes(),
                            "PUNCH_WITH_EXEMPTION_CONFIRMED",
                            selection.selectedIds(),
                            null,
                            "work-with-exemption"));
                }
                return;
            }
            addMissingOrAmbiguous(
                    snapshot,
                    segment,
                    uncovered,
                    selection,
                    "MISSING_PUNCH",
                    accumulator);
            return;
        }
        boolean convertedToAbsence = addLateOrConvertToAbsence(
                snapshot,
                segment,
                uncovered,
                selection,
                accumulator);
        if (!convertedToAbsence) {
            for (TimeInterval slice : uncovered) {
                accumulator.items.add(item(
                        segment,
                        slice,
                        ResultCategory.SCHEDULED_WORK,
                        slice.minutes(),
                        "PUNCH_PAIR_CONFIRMED",
                        selection.selectedIds(),
                        null,
                        "work"));
            }
            addEarlyDeparture(
                    snapshot,
                    segment,
                    uncovered,
                    selection,
                    accumulator);
        }
        if (selection.departure().instant()
                .isAfter(segment.interval().end())) {
            TimeInterval extended = new TimeInterval(
                    segment.interval().end(),
                    selection.departure().instant());
            accumulator.items.add(item(
                    segment,
                    extended,
                    ResultCategory.EXTENDED_PRESENCE,
                    extended.minutes(),
                    "PUNCH_AFTER_SCHEDULE",
                    List.of(selection.departure().eventId()),
                    null,
                    "extended"));
        }
    }

    private boolean addLateOrConvertToAbsence(
            CalculationInputSnapshot snapshot,
            ScheduledWorkSegment segment,
            List<TimeInterval> uncovered,
            PunchSelection selection,
            Accumulator accumulator) {
        if (selection.arrival().instant().isAfter(segment.interval().start())) {
            TimeInterval rawInterval = new TimeInterval(
                    segment.interval().start(),
                    selection.arrival().instant());
            long rawMinutes = intersectionsMinutes(rawInterval, uncovered);
            if (rawMinutes > 0) {
                boolean graceAvailable = snapshot.graceConsumption().used()
                        < snapshot.policy().monthlyLateGraceUses();
                long graceMinutes = graceAvailable
                        ? Math.min(
                                rawMinutes,
                                snapshot.policy().lateGraceMaxMinutes())
                        : 0;
                long includedMinutes = rawMinutes - graceMinutes;
                boolean convertedToAbsence = includedMinutes >= 30;
                String ruleCode = convertedToAbsence
                        ? "LATE_CONVERTED_TO_ABSENCE"
                        : includedMinutes == 0
                                ? "MONTHLY_LATE_GRACE_CONSUMED"
                                : "LATE_CHARGEABLE";
                accumulator.ruleHits.add(new RuleHit(
                        ruleId(segment, "LATE"),
                        snapshot.configurationSnapshotReference(),
                        segment.segmentId(),
                        ruleCode,
                        rawMinutes,
                        convertedToAbsence ? 0 : includedMinutes,
                        List.of(selection.arrival().eventId())));
                if (convertedToAbsence) {
                    String fingerprint = exceptionFingerprint(
                            snapshot,
                            segment.segmentId(),
                            segment.interval(),
                            ruleCode,
                            List.of(selection.arrival().eventId()));
                    accumulator.exceptionFingerprints.add(fingerprint);
                    accumulator.items.add(item(
                            segment,
                            segment.interval(),
                            ResultCategory.ABSENCE,
                            segment.interval().minutes(),
                            ruleCode,
                            List.of(selection.arrival().eventId()),
                            fingerprint,
                            "late-absence"));
                    return true;
                }
                accumulator.items.add(item(
                        segment,
                        rawInterval,
                        ResultCategory.LATE,
                        includedMinutes,
                        ruleCode,
                        List.of(selection.arrival().eventId()),
                        null,
                        "late"));
            }
        }
        return false;
    }

    private void addEarlyDeparture(
            CalculationInputSnapshot snapshot,
            ScheduledWorkSegment segment,
            List<TimeInterval> uncovered,
            PunchSelection selection,
            Accumulator accumulator) {
        if (selection.departure().instant()
                .isBefore(segment.interval().end())) {
            TimeInterval rawInterval = new TimeInterval(
                    selection.departure().instant(),
                    segment.interval().end());
            long rawMinutes = intersectionsMinutes(rawInterval, uncovered);
            if (rawMinutes > 0) {
                accumulator.ruleHits.add(new RuleHit(
                        ruleId(segment, "EARLY"),
                        snapshot.configurationSnapshotReference(),
                        segment.segmentId(),
                        "EARLY_DEPARTURE_CHARGEABLE",
                        rawMinutes,
                        rawMinutes,
                        List.of(selection.departure().eventId())));
                accumulator.items.add(item(
                        segment,
                        rawInterval,
                        ResultCategory.EARLY_DEPARTURE,
                        rawMinutes,
                        "EARLY_DEPARTURE_CHARGEABLE",
                        List.of(selection.departure().eventId()),
                        null,
                        "early"));
            }
        }
    }

    private void addMissingOrAmbiguous(
            CalculationInputSnapshot snapshot,
            ScheduledWorkSegment segment,
            List<TimeInterval> uncovered,
            PunchSelection selection,
            String reason,
            Accumulator accumulator) {
        boolean pending = !snapshot.knowledgeCutoff()
                        .isAfter(snapshot.policy().correctionDeadline())
                || snapshot.policy().timelyPendingSubmission();
        if ("AMBIGUOUS_PUNCH_MATCH".equals(reason)) {
            String fingerprint = exceptionFingerprint(
                    snapshot,
                    segment.segmentId(),
                    segment.interval(),
                    reason,
                    selection.selectedIds());
            accumulator.exceptionFingerprints.add(fingerprint);
            for (TimeInterval slice : uncovered) {
                accumulator.items.add(item(
                        segment,
                        slice,
                        pending
                                ? ResultCategory.MISSING_PUNCH_PENDING
                                : ResultCategory.ABSENCE,
                        pending ? 0 : slice.minutes(),
                        reason + (pending ? "_PENDING" : "_OVERDUE"),
                        selection.selectedIds(),
                        fingerprint,
                        "ambiguous"));
            }
            return;
        }

        List<String> missingSides = missingPunchSides(
                segment, uncovered, selection);
        String exceptionType = pending
                ? "MISSING_PUNCH_PENDING"
                : "MISSING_PUNCH_OVERDUE";
        for (int sideIndex = 0; sideIndex < missingSides.size(); sideIndex++) {
            String side = missingSides.get(sideIndex);
            String reasonCode = exceptionType + "_" + side;
            String fingerprint = exceptionFingerprint(
                    snapshot,
                    segment.segmentId(),
                    segment.interval(),
                    reasonCode,
                    selection.selectedIds());
            accumulator.exceptionFingerprints.add(fingerprint);
            for (TimeInterval slice : uncovered) {
                accumulator.items.add(item(
                        segment,
                        slice,
                        pending
                                ? ResultCategory.MISSING_PUNCH_PENDING
                                : ResultCategory.ABSENCE,
                        !pending && sideIndex == 0 ? slice.minutes() : 0,
                        reasonCode,
                        selection.selectedIds(),
                        fingerprint,
                        "missing-" + side.toLowerCase()));
            }
        }
    }

    private List<String> missingPunchSides(
            ScheduledWorkSegment segment,
            List<TimeInterval> uncovered,
            PunchSelection selection) {
        boolean entryExpected = uncovered.stream().anyMatch(value ->
                value.start().equals(segment.interval().start()));
        boolean exitExpected = uncovered.stream().anyMatch(value ->
                value.end().equals(segment.interval().end()));
        List<String> missingSides = new ArrayList<>(2);
        if (entryExpected && selection.arrival() == null) {
            missingSides.add("ENTRY");
        }
        if (exitExpected && selection.departure() == null) {
            missingSides.add("EXIT");
        }
        return List.copyOf(missingSides);
    }

    private List<IntervalEvidence> outingEvidence(
            CalculationInputSnapshot snapshot) {
        Instant dayStart = snapshot.businessDate()
                .atStartOfDay(snapshot.businessZone())
                .toInstant();
        Instant dayEnd = snapshot.businessDate()
                .plusDays(1)
                .atStartOfDay(snapshot.businessZone())
                .toInstant();
        return snapshot.intervalEvidence().stream()
                .filter(IntervalEvidence::effective)
                .filter(value -> value.kind() == EvidenceKind.OUTING)
                .filter(value -> value.interval().start().isBefore(dayEnd))
                .filter(value -> value.interval().end().isAfter(dayStart))
                .sorted(Comparator.comparing(IntervalEvidence::evidenceId))
                .toList();
    }

    private List<PunchEvent> businessDatePunches(
            CalculationInputSnapshot snapshot) {
        Instant dayStart = snapshot.businessDate()
                .atStartOfDay(snapshot.businessZone())
                .toInstant();
        Instant dayEnd = snapshot.businessDate()
                .plusDays(1)
                .atStartOfDay(snapshot.businessZone())
                .toInstant();
        return snapshot.punchEvents().stream()
                .filter(value -> !value.instant().isBefore(dayStart))
                .filter(value -> value.instant().isBefore(dayEnd))
                .sorted(Comparator.comparing(PunchEvent::instant)
                        .thenComparing(PunchEvent::eventId))
                .toList();
    }

    private void addOutingWithoutPunch(
            CalculationInputSnapshot snapshot,
            ScheduledWorkSegment segment,
            List<TimeInterval> uncovered,
            List<IntervalEvidence> outingEvidence,
            Accumulator accumulator) {
        List<String> evidenceIds = outingEvidence.stream()
                .map(IntervalEvidence::evidenceId)
                .sorted()
                .toList();
        List<String> missingSides = missingPunchSides(
                segment,
                uncovered,
                new PunchSelection(null, null, false));
        if (missingSides.isEmpty()) {
            for (TimeInterval slice : uncovered) {
                accumulator.items.add(item(
                        segment,
                        slice,
                        ResultCategory.ABSENCE,
                        slice.minutes(),
                        "OUTING_APPROVED_WITHOUT_PUNCH",
                        evidenceIds,
                        null,
                        "outing-absence"));
            }
            return;
        }
        for (int sideIndex = 0;
                sideIndex < missingSides.size();
                sideIndex++) {
            String side = missingSides.get(sideIndex);
            String reasonCode = "MISSING_PUNCH_OUTING_" + side;
            String fingerprint = exceptionFingerprint(
                    snapshot,
                    segment.segmentId(),
                    segment.interval(),
                    reasonCode,
                    evidenceIds);
            accumulator.exceptionFingerprints.add(fingerprint);
            for (TimeInterval slice : uncovered) {
                accumulator.items.add(item(
                        segment,
                        slice,
                        ResultCategory.ABSENCE,
                        sideIndex == 0 ? slice.minutes() : 0,
                        reasonCode,
                        evidenceIds,
                        fingerprint,
                        "outing-missing-" + side.toLowerCase()));
            }
        }
    }

    private void calculateAuthorizedOvertime(
            CalculationInputSnapshot snapshot,
            Set<String> consumedPunchIds,
            Accumulator accumulator) {
        List<IntervalEvidence> overtimeEvidence =
                snapshot.intervalEvidence().stream()
                        .filter(IntervalEvidence::effective)
                        .filter(value ->
                                value.kind() == EvidenceKind.OVERTIME)
                        .filter(value -> value.overtimeType() != null)
                        .toList();
        for (IntervalEvidence authorization : overtimeEvidence) {
            List<PunchEvent> available = snapshot.punchEvents().stream()
                    .filter(value -> !consumedPunchIds.contains(value.eventId()))
                    .filter(value -> inclusiveContains(
                            authorization.interval(), value.instant()))
                    .sorted(Comparator.comparing(PunchEvent::instant)
                            .thenComparing(PunchEvent::eventId))
                    .toList();
            List<PresenceSpan> presence = pairPresence(available).stream()
                    .map(span -> span.intersection(authorization.interval()))
                    .filter(Objects::nonNull)
                    .toList();
            if (presence.isEmpty()) {
                continue;
            }
            presence.stream()
                    .flatMap(span -> span.punchIds().stream())
                    .forEach(consumedPunchIds::add);
            TimeInterval eligible = new TimeInterval(
                    presence.getFirst().interval().start(),
                    presence.getLast().interval().end());
            long actualMinutes = presence.stream()
                    .mapToLong(span -> span.interval().minutes())
                    .sum();
            List<String> punchIds = presence.stream()
                    .flatMap(span -> span.punchIds().stream())
                    .distinct()
                    .sorted()
                    .toList();
            String segmentId = overtimeSegmentId(authorization);
            ScheduledWorkSegment synthetic = new ScheduledWorkSegment(
                    segmentId,
                    snapshot.businessDate(),
                    eligible,
                    eligible,
                    eligible,
                    AttendanceCalculationModels.SegmentKind.SCHEDULED_WORK);
            for (PresenceSpan span : presence) {
                accumulator.items.add(item(
                        synthetic,
                        span.interval(),
                        ResultCategory.EXTENDED_PRESENCE,
                        span.interval().minutes(),
                        "OFF_SCHEDULE_PRESENCE",
                        span.punchIds(),
                        null,
                        "overtime-presence"));
            }
            boolean timely = overtimeTimely(
                    snapshot, authorization, eligible.end());
            long recognized = timely ? actualMinutes : 0;
            if (timely) {
                for (MealDeductionRule rule :
                        snapshot.policy().mealDeductions()) {
                    boolean applies = rule.requireFullCoverage()
                            ? presence.stream().anyMatch(span ->
                                    covers(span.interval(), rule.window()))
                            : presence.stream().anyMatch(span ->
                                    span.interval().overlaps(rule.window()));
                    if (applies
                            && actualMinutes >= rule.triggerMinutes()) {
                        recognized = Math.max(
                                0, recognized - rule.deductionMinutes());
                    }
                }
            }
            accumulator.ruleHits.add(new RuleHit(
                    ruleId(synthetic, "OVERTIME"),
                    authorization.sourceReference(),
                    segmentId,
                    timely
                            ? "OVERTIME_AUTHORIZED_AND_TIMELY"
                            : "OVERTIME_DOCUMENT_MISSING_OR_LATE",
                    actualMinutes,
                    recognized,
                    evidenceReferences(
                            authorization.evidenceId(), punchIds)));
            accumulator.items.add(item(
                    synthetic,
                    eligible,
                    ResultCategory.RECOGNIZED_OVERTIME,
                    recognized,
                    timely
                            ? "OVERTIME_AUTHORIZED_AND_TIMELY"
                            : "OVERTIME_DOCUMENT_MISSING_OR_LATE",
                    evidenceReferences(
                            authorization.evidenceId(), punchIds),
                    null,
                    "recognized-overtime"));
        }
    }

    /**
     * Flags an approved outing whose interval runs past the
     * last scheduled work segment of the day without a matching approved
     * overtime document.
     *
     * <p>The employee demonstrably worked past scheduled off-time, but the
     * overtime-recognition policy pays zero minutes without an approved
     * document. Emitting a rule hit surfaces this as a reviewable exception so
     * the overtime form can be filed, while deliberately recognising zero
     * minutes so payroll is never changed by inference.</p>
     */
    private void addUndeclaredOutingOvertime(
            CalculationInputSnapshot snapshot,
            Accumulator accumulator) {
        if (snapshot.segments().isEmpty()) {
            return;
        }
        Instant scheduledOffTime = snapshot.segments().stream()
                .map(segment -> segment.interval().end())
                .max(Comparator.naturalOrder())
                .orElse(null);
        if (scheduledOffTime == null) {
            return;
        }
        List<IntervalEvidence> declaredOvertime =
                snapshot.intervalEvidence().stream()
                        .filter(IntervalEvidence::effective)
                        .filter(value -> value.kind() == EvidenceKind.OVERTIME)
                        .filter(value -> value.overtimeType() != null)
                        .toList();
        List<IntervalEvidence> overruns = snapshot.intervalEvidence().stream()
                .filter(IntervalEvidence::effective)
                .filter(value -> value.kind() == EvidenceKind.OUTING)
                .filter(value ->
                        value.interval().end().isAfter(scheduledOffTime))
                .sorted(Comparator.comparing(IntervalEvidence::evidenceId))
                .toList();
        for (IntervalEvidence overrun : overruns) {
            Instant overrunStart =
                    overrun.interval().start().isAfter(scheduledOffTime)
                            ? overrun.interval().start()
                            : scheduledOffTime;
            TimeInterval window =
                    new TimeInterval(overrunStart, overrun.interval().end());
            boolean alreadyDeclared = declaredOvertime.stream()
                    .anyMatch(value -> covers(value.interval(), window));
            if (alreadyDeclared || window.minutes() <= 0) {
                continue;
            }
            String segmentId = outingOverrunSegmentId(overrun);
            accumulator.ruleHits.add(new RuleHit(
                    "rule-" + CanonicalAttendanceDigests.digestStrings(
                                    "W5_RULE_HIT_ID_V1",
                                    List.of(
                                            segmentId,
                                            "OUTING_OVERTIME_UNDECLARED"))
                            .substring(0, 24),
                    overrun.sourceReference(),
                    segmentId,
                    "OUTING_OVERTIME_UNDECLARED",
                    window.minutes(),
                    0,
                    List.of(overrun.evidenceId())));
        }
    }

    private String outingOverrunSegmentId(IntervalEvidence evidence) {
        return "outing-overrun-"
                + CanonicalAttendanceDigests.digestStrings(
                                "W5_OUTING_OVERRUN_SEGMENT_V1",
                                List.of(
                                        evidence.evidenceId(),
                                        evidence.interval().start().toString(),
                                        evidence.interval().end().toString()))
                        .substring(0, 20);
    }

    private List<PresenceSpan> pairPresence(List<PunchEvent> orderedPunches) {
        if (orderedPunches.size() < 2 || orderedPunches.size() % 2 != 0) {
            return List.of();
        }
        boolean allAuto = orderedPunches.stream()
                .allMatch(value -> value.direction() == PunchDirection.AUTO);
        boolean noAuto = orderedPunches.stream()
                .noneMatch(value -> value.direction() == PunchDirection.AUTO);
        if (!allAuto && !noAuto) {
            return List.of();
        }
        List<PresenceSpan> result = new ArrayList<>();
        if (allAuto) {
            for (int index = 1; index < orderedPunches.size(); index += 2) {
                addPresencePair(
                        result,
                        orderedPunches.get(index - 1),
                        orderedPunches.get(index));
            }
            return List.copyOf(result);
        }
        for (int index = 1; index < orderedPunches.size(); index += 2) {
            PunchEvent entry = orderedPunches.get(index - 1);
            PunchEvent exit = orderedPunches.get(index);
            if (entry.direction() != PunchDirection.ENTRY
                    || exit.direction() != PunchDirection.EXIT) {
                return List.of();
            }
            addPresencePair(result, entry, exit);
        }
        return List.copyOf(result);
    }

    private void addPresencePair(
            List<PresenceSpan> target,
            PunchEvent entry,
            PunchEvent exit) {
        if (entry.instant().isBefore(exit.instant())) {
            target.add(new PresenceSpan(
                    new TimeInterval(entry.instant(), exit.instant()),
                    List.of(entry.eventId(), exit.eventId())));
        }
    }

    private List<String> evidenceReferences(
            String authorizationId, List<String> punchIds) {
        List<String> references = new ArrayList<>();
        references.add(authorizationId);
        references.addAll(punchIds);
        return List.copyOf(references);
    }

    private boolean overtimeTimely(
            CalculationInputSnapshot snapshot,
            IntervalEvidence authorization,
            Instant actualEnd) {
        Instant submitted = authorization.firstSubmittedAt() != null
                ? authorization.firstSubmittedAt()
                : snapshot.policy().overtimeFirstSubmittedAt();
        if (submitted == null || !authorization.effective()) {
            return false;
        }
        if (!submitted.isAfter(actualEnd)) {
            return true;
        }
        return Duration.between(actualEnd, submitted).toMinutes()
                <= snapshot.policy().overtimeSubmissionDeadlineMinutes();
    }

    private AttendanceMetrics metrics(
            CalculationInputSnapshot snapshot, List<ResultItem> items) {
        long scheduled = snapshot.segments().stream()
                .mapToLong(value -> value.interval().minutes())
                .sum();
        long confirmed = sum(
                items,
                ResultCategory.SCHEDULED_WORK,
                ResultCategory.OUTING_WORK,
                ResultCategory.EXEMPT_WORK);
        long extended = sum(items, ResultCategory.EXTENDED_PRESENCE);
        long overtime = sum(items, ResultCategory.RECOGNIZED_OVERTIME);
        long paidOvertime = overtimeByType(
                snapshot, items, OvertimeType.PAID);
        long compensatoryOvertime = overtimeByType(
                snapshot, items, OvertimeType.COMPENSATORY);
        long voluntaryOvertime = overtimeByType(
                snapshot, items, OvertimeType.VOLUNTARY);
        long totalOvertime = Math.addExact(
                Math.addExact(paidOvertime, compensatoryOvertime),
                voluntaryOvertime);
        long leave = sum(
                items, ResultCategory.LEAVE, ResultCategory.TIME_OFF);
        long absence = sum(items, ResultCategory.ABSENCE);
        return new AttendanceMetrics(
                scheduled,
                confirmed,
                extended,
                overtime,
                paidOvertime,
                compensatoryOvertime,
                voluntaryOvertime,
                totalOvertime,
                leave,
                absence,
                confirmed + overtime);
    }

    private long overtimeByType(
            CalculationInputSnapshot snapshot,
            List<ResultItem> items,
            OvertimeType overtimeType) {
        Map<String, OvertimeType> typeByEvidenceId =
                snapshot.intervalEvidence().stream()
                        .filter(value -> value.kind() == EvidenceKind.OVERTIME)
                        .filter(value -> value.overtimeType() != null)
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                IntervalEvidence::evidenceId,
                                IntervalEvidence::overtimeType));
        return items.stream()
                .filter(value ->
                        value.category() == ResultCategory.RECOGNIZED_OVERTIME)
                .filter(value -> value.evidenceIds().stream().anyMatch(
                        evidenceId -> overtimeType.equals(
                                typeByEvidenceId.get(evidenceId))))
                .mapToLong(ResultItem::minutes)
                .sum();
    }

    private long sum(
            List<ResultItem> items, ResultCategory... categories) {
        Set<ResultCategory> included = Set.of(categories);
        return items.stream()
                .filter(value -> included.contains(value.category()))
                .mapToLong(ResultItem::minutes)
                .sum();
    }

    private ExplanationGraph explanation(
            String calculationVersionId,
            CalculationInputSnapshot snapshot,
            Accumulator accumulator) {
        Map<String, ExplanationNode> nodes = new LinkedHashMap<>();
        List<ExplanationEdge> edges = new ArrayList<>();
        String resultNodeId = "result:" + calculationVersionId;
        String requestNodeId = "request:" + snapshot.requestId();
        addNode(nodes, new ExplanationNode(
                resultNodeId,
                ExplanationNodeType.DAILY_RESULT,
                calculationVersionId,
                Map.of(
                        "employeeId", snapshot.employeeId(),
                        "businessDate", snapshot.businessDate().toString())));
        addNode(nodes, new ExplanationNode(
                requestNodeId,
                ExplanationNodeType.REQUEST,
                snapshot.requestId(),
                Map.of("correlationId", snapshot.correlationId())));
        edges.add(new ExplanationEdge(
                resultNodeId, requestNodeId, "CALCULATED_BY_REQUEST"));
        for (ScheduledWorkSegment segment : snapshot.segments()) {
            String segmentNodeId = "segment:" + segment.segmentId();
            addNode(nodes, new ExplanationNode(
                    segmentNodeId,
                    ExplanationNodeType.SCHEDULED_SEGMENT,
                    segment.segmentId(),
                    Map.of(
                            "start", segment.interval().start().toString(),
                            "end", segment.interval().end().toString())));
            edges.add(new ExplanationEdge(
                    resultNodeId, segmentNodeId, "HAS_SEGMENT"));
        }
        for (ResultItem item : accumulator.items) {
            String itemNodeId = "item:" + item.semanticKey();
            String segmentNodeId = "segment:" + item.segmentId();
            if (!nodes.containsKey(segmentNodeId)) {
                addNode(nodes, new ExplanationNode(
                        segmentNodeId,
                        ExplanationNodeType.SCHEDULED_SEGMENT,
                        item.segmentId(),
                        Map.of(
                                "start", item.interval().start().toString(),
                                "end", item.interval().end().toString())));
                edges.add(new ExplanationEdge(
                        resultNodeId, segmentNodeId, "HAS_SEGMENT"));
            }
            addNode(nodes, new ExplanationNode(
                    itemNodeId,
                    ExplanationNodeType.RESULT_ITEM,
                    item.semanticKey(),
                    Map.of(
                            "category", item.category().name(),
                            "minutes", Long.toString(item.minutes()),
                            "reasonCode", item.reasonCode())));
            edges.add(new ExplanationEdge(
                    resultNodeId, itemNodeId, "HAS_RESULT_ITEM"));
            edges.add(new ExplanationEdge(
                    itemNodeId, segmentNodeId, "DERIVED_FROM_SEGMENT"));
            for (String evidenceId : item.evidenceIds()) {
                String evidenceNodeId = "evidence:" + evidenceId;
                addNode(nodes, new ExplanationNode(
                        evidenceNodeId,
                        ExplanationNodeType.EVIDENCE,
                        evidenceId,
                        Map.of()));
                edges.add(new ExplanationEdge(
                        itemNodeId, evidenceNodeId, "SUPPORTED_BY"));
            }
            if (item.exceptionFingerprint() != null) {
                String exceptionNodeId =
                        "exception:" + item.exceptionFingerprint();
                addNode(nodes, new ExplanationNode(
                        exceptionNodeId,
                        ExplanationNodeType.EXCEPTION,
                        item.exceptionFingerprint(),
                        Map.of("reasonCode", item.reasonCode())));
                edges.add(new ExplanationEdge(
                        itemNodeId, exceptionNodeId, "RAISED_EXCEPTION"));
            }
        }
        for (RuleHit hit : accumulator.ruleHits) {
            String ruleNodeId = "rule:" + hit.ruleHitId();
            addNode(nodes, new ExplanationNode(
                    ruleNodeId,
                    ExplanationNodeType.RULE_HIT,
                    hit.ruleHitId(),
                    Map.of(
                            "ruleCode", hit.ruleCode(),
                            "rawMinutes", Long.toString(hit.rawMinutes()),
                            "includedMinutes",
                                    Long.toString(hit.includedMinutes()))));
            edges.add(new ExplanationEdge(
                    resultNodeId, ruleNodeId, "HAS_RULE_HIT"));
        }
        for (AdjustmentFact adjustment : snapshot.adjustments()) {
            String adjustmentNodeId =
                    "adjustment:" + adjustment.adjustmentId();
            addNode(nodes, new ExplanationNode(
                    adjustmentNodeId,
                    ExplanationNodeType.ADJUSTMENT,
                    adjustment.adjustmentId(),
                    Map.of(
                            "reason", adjustment.reason(),
                            "approvalReference",
                                    adjustment.approvalReference())));
            edges.add(new ExplanationEdge(
                    resultNodeId, adjustmentNodeId, "CONSIDERED_ADJUSTMENT"));
        }
        return new ExplanationGraph(
                List.copyOf(nodes.values()), edges);
    }

    private void addNode(
            Map<String, ExplanationNode> nodes, ExplanationNode node) {
        ExplanationNode existing = nodes.putIfAbsent(
                node.nodeId(), node);
        if (existing != null && !existing.equals(node)) {
            throw new IllegalArgumentException(
                    "conflicting explanation node identity");
        }
    }

    private List<Candidate> candidates(CalculationInputSnapshot snapshot) {
        List<Candidate> result = new ArrayList<>();
        for (AdjustmentFact adjustment : snapshot.adjustments()) {
            result.add(new Candidate(
                    adjustment.adjustmentId(),
                    EvidenceKind.APPROVED_ADJUSTMENT,
                    adjustment.interval(),
                    adjustment.conclusion()));
        }
        for (IntervalEvidence evidence : snapshot.intervalEvidence()) {
            if (!evidence.effective()) {
                continue;
            }
            if (evidence.kind() == EvidenceKind.TRIP) {
                continue;
            }
            result.add(new Candidate(
                    evidence.evidenceId(),
                    evidence.kind(),
                    evidence.interval(),
                    category(evidence.kind())));
        }
        return result.stream().sorted(Candidate.ORDER).toList();
    }

    private ResultCategory category(EvidenceKind kind) {
        return switch (kind) {
            case APPROVED_ADJUSTMENT ->
                    throw new IllegalArgumentException(
                            "adjustments have an explicit conclusion");
            case REVERSAL -> null;
            case LEAVE -> ResultCategory.LEAVE;
            case TIME_OFF -> ResultCategory.TIME_OFF;
            case OUTING, TRIP -> null;
            case EXEMPT_PUNCH, PUNCH_CORRECTION ->
                    ResultCategory.EXEMPT_WORK;
            case OVERTIME, PUNCH, SYSTEM_FINDING -> null;
        };
    }

    private boolean mutuallyExclusive(List<Candidate> candidates) {
        if (candidates.size() < 2) {
            return false;
        }
        return candidates.stream()
                        .map(Candidate::category)
                        .distinct()
                        .count()
                > 1;
    }

    private EvidenceDecision decision(
            ScheduledWorkSegment segment,
            TimeInterval slice,
            Candidate candidate,
            EvidenceDecisionStatus status,
            String reasonCode) {
        String decisionId = CanonicalAttendanceDigests.digestStrings(
                        "W5_EVIDENCE_DECISION_V1",
                        List.of(
                                segment.segmentId(),
                                slice.start().toString(),
                                slice.end().toString(),
                                candidate.id(),
                                status.name()))
                .substring(0, 24);
        return new EvidenceDecision(
                "decision-" + decisionId,
                slice,
                candidate.id(),
                candidate.kind(),
                status,
                reasonCode);
    }

    private ResultItem item(
            ScheduledWorkSegment segment,
            TimeInterval interval,
            ResultCategory category,
            long minutes,
            String reasonCode,
            List<String> evidenceIds,
            String exceptionFingerprint,
            String role) {
        String semanticKey = String.join(
                "|",
                segment.segmentId(),
                interval.start().toString(),
                interval.end().toString(),
                role);
        return new ResultItem(
                semanticKey,
                segment.segmentId(),
                interval,
                category,
                minutes,
                reasonCode,
                evidenceIds,
                exceptionFingerprint);
    }

    private PunchSelection selectPunches(
            List<PunchEvent> punches,
            ScheduledWorkSegment segment,
            Set<String> consumedPunchIds) {
        List<PunchEvent> arrivals = punches.stream()
                .filter(value -> !consumedPunchIds.contains(value.eventId()))
                .filter(value -> value.direction() != PunchDirection.EXIT)
                .filter(value -> inclusiveContains(
                        segment.arrivalWindow(), value.instant()))
                .sorted(Comparator.comparing(PunchEvent::instant)
                        .thenComparing(PunchEvent::eventId))
                .toList();
        List<PunchEvent> departures = punches.stream()
                .filter(value -> !consumedPunchIds.contains(value.eventId()))
                .filter(value -> value.direction() != PunchDirection.ENTRY)
                .filter(value -> inclusiveContains(
                        segment.departureWindow(), value.instant()))
                .sorted(Comparator.comparing(PunchEvent::instant)
                        .thenComparing(PunchEvent::eventId))
                .toList();
        PunchEvent arrival = arrivals.isEmpty() ? null : arrivals.getFirst();
        PunchEvent departure =
                departures.isEmpty() ? null : departures.getLast();
        boolean ambiguous = sameInstantCount(arrivals, arrival) > 1
                || sameInstantCount(departures, departure) > 1
                || (arrival != null
                        && departure != null
                        && arrival.eventId().equals(departure.eventId()));
        return new PunchSelection(arrival, departure, ambiguous);
    }

    private long sameInstantCount(
            List<PunchEvent> candidates, PunchEvent selected) {
        if (selected == null) {
            return 0;
        }
        return candidates.stream()
                .filter(value ->
                        value.instant().equals(selected.instant()))
                .count();
    }

    private boolean covers(TimeInterval outer, TimeInterval inner) {
        return !outer.start().isAfter(inner.start())
                && !outer.end().isBefore(inner.end());
    }

    private boolean inclusiveContains(
            TimeInterval interval, Instant instant) {
        return !instant.isBefore(interval.start())
                && !instant.isAfter(interval.end());
    }

    private long intersectionsMinutes(
            TimeInterval interval, List<TimeInterval> candidates) {
        return candidates.stream()
                .map(value -> value.intersection(interval))
                .filter(Objects::nonNull)
                .mapToLong(TimeInterval::minutes)
                .sum();
    }

    private String exceptionFingerprint(
            CalculationInputSnapshot snapshot,
            String segmentId,
            TimeInterval interval,
            String type,
            List<String> references) {
        List<String> parts = new ArrayList<>(List.of(
                snapshot.companyId(),
                snapshot.employeeId(),
                snapshot.businessDate().toString(),
                segmentId,
                interval.start().toString(),
                interval.end().toString(),
                type));
        references.stream().sorted().forEach(parts::add);
        return CanonicalAttendanceDigests.digestStrings(
                "W5_EXCEPTION_FINGERPRINT_V1", parts);
    }

    private String ruleId(
            ScheduledWorkSegment segment, String ruleCode) {
        return "rule-" + CanonicalAttendanceDigests.digestStrings(
                        "W5_RULE_HIT_ID_V1",
                        List.of(segment.segmentId(), ruleCode))
                .substring(0, 24);
    }

    private String overtimeSegmentId(IntervalEvidence evidence) {
        return "overtime-" + CanonicalAttendanceDigests.digestStrings(
                        "W5_OVERTIME_SEGMENT_V1",
                        List.of(
                                evidence.evidenceId(),
                                evidence.interval().start().toString(),
                                evidence.interval().end().toString()))
                .substring(0, 20);
    }

    private record Candidate(
            String id,
            EvidenceKind kind,
            TimeInterval interval,
            ResultCategory category) {

        private static final Comparator<Candidate> ORDER =
                Comparator.comparingInt(
                                (Candidate value) ->
                                        value.kind().priority())
                        .thenComparing(value -> value.interval().start())
                        .thenComparing(value -> value.interval().end())
                        .thenComparing(Candidate::id);
    }

    private record PunchSelection(
            PunchEvent arrival,
            PunchEvent departure,
            boolean ambiguous) {

        private List<String> selectedIds() {
            Set<String> result = new HashSet<>();
            if (arrival != null) {
                result.add(arrival.eventId());
            }
            if (departure != null) {
                result.add(departure.eventId());
            }
            return result.stream().sorted().toList();
        }
    }

    private record PresenceSpan(
            TimeInterval interval,
            List<String> punchIds) {

        private PresenceSpan {
            Objects.requireNonNull(interval, "interval");
            punchIds = List.copyOf(punchIds);
        }

        private PresenceSpan intersection(TimeInterval other) {
            TimeInterval intersection = interval.intersection(other);
            return intersection == null
                    ? null
                    : new PresenceSpan(intersection, punchIds);
        }
    }

    private static final class Accumulator {

        private final List<ResultItem> items = new ArrayList<>();
        private final List<RuleHit> ruleHits = new ArrayList<>();
        private final List<EvidenceDecision> decisions = new ArrayList<>();
        private final List<String> exceptionFingerprints =
                new ArrayList<>();
    }
}
