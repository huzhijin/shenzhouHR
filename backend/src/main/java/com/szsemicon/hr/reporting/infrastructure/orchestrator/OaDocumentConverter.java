package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.EvidenceKind;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.IntervalEvidence;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.TimeInterval;
import com.szsemicon.hr.attendance.domain.LeaveType;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.OaDocumentRow;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converts OA attendance documents to calculation engine evidence.
 *
 * <p>Approved leave revocations replace the original leave interval with the
 * revocation's actual start/end. Multiple revocations for the same leave are
 * unioned so overlapping minutes are not counted twice.
 */
final class OaDocumentConverter {

    private static final Logger log = LoggerFactory.getLogger(OaDocumentConverter.class);

    static final String UNRESOLVED_LEAVE_REVOCATION =
            "OA_LEAVE_REVOCATION_UNRESOLVED";

    private OaDocumentConverter() {
    }

    static List<IntervalEvidence> toIntervalEvidence(
            List<OaDocumentRow> documents) {
        List<OaDocumentRow> effective = documents.stream()
                .filter(OaDocumentRow::effectiveCandidate)
                .toList();
        List<OaDocumentRow> leaves = new ArrayList<>();
        List<OaDocumentRow> revocations = new ArrayList<>();
        List<OaDocumentRow> others = new ArrayList<>();
        for (OaDocumentRow document : effective) {
            switch (document.documentType()) {
                case "LEAVE" -> leaves.add(document);
                case "LEAVE_REVOCATION" -> revocations.add(document);
                case "PUNCH_CORRECTION" -> {
                    // Point makeup punches are merged as PunchEvent rows in
                    // the orchestrator. A zero-length interval cannot snap.
                }
                default -> others.add(document);
            }
        }
        List<IntervalEvidence> evidence = new ArrayList<>();
        evidence.addAll(resolveLeaveEvidence(leaves, revocations));
        for (OaDocumentRow document : others) {
            IntervalEvidence converted = toEvidence(document);
            if (converted != null) {
                evidence.add(converted);
            }
        }
        return List.copyOf(evidence);
    }

    /**
     * Leave business keys whose approved 销假 replaces the original interval.
     * The replacement is the 销假 form's actual leave start/end. An empty list
     * means the leave was fully returned and must count as zero hours.
     */
    static Map<String, List<TimeInterval>> amendedLeaveIntervals(
            List<OaDocumentRow> documents) {
        if (documents == null || documents.isEmpty()) {
            return Map.of();
        }
        List<OaDocumentRow> leaves = new ArrayList<>();
        List<OaDocumentRow> revocations = new ArrayList<>();
        for (OaDocumentRow document : documents) {
            if (document == null || !document.effectiveCandidate()) {
                continue;
            }
            switch (document.documentType()) {
                case "LEAVE" -> leaves.add(document);
                case "LEAVE_REVOCATION" -> revocations.add(document);
                default -> {
                }
            }
        }
        if (revocations.isEmpty()) {
            return Map.of();
        }
        Map<OaDocumentRow, List<OaDocumentRow>> revocationsByLeave =
                associateRevocations(leaves, revocations);
        Map<String, List<TimeInterval>> amended = new LinkedHashMap<>();
        for (OaDocumentRow leave : leaves) {
            List<OaDocumentRow> matched = revocationsByLeave.getOrDefault(
                    leave, List.of());
            if (matched.isEmpty()) {
                continue;
            }
            // The report's legacy map is keyed by the business key. Never
            // silently reuse a correction if two source/employee identities
            // happen to publish that same key.
            long identitiesForKey = leaves.stream()
                    .filter(candidate -> Objects.equals(
                            candidate.sourceBusinessKey(), leave.sourceBusinessKey()))
                    .map(OaDocumentConverter::subject)
                    .distinct()
                    .count();
            if (identitiesForKey > 1) {
                throw unresolved("ambiguous report business key", leave);
            }
            amended.put(leave.sourceBusinessKey(), unionIntervals(matched));
        }
        return Map.copyOf(amended);
    }

    private static List<IntervalEvidence> resolveLeaveEvidence(
            List<OaDocumentRow> leaves,
            List<OaDocumentRow> revocations) {
        if (revocations.isEmpty()) {
            List<IntervalEvidence> expanded = new ArrayList<>();
            for (OaDocumentRow leave : leaves) {
                expanded.addAll(toLeaveEvidence(leave));
            }
            return List.copyOf(expanded);
        }
        Map<OaDocumentRow, List<OaDocumentRow>> revocationsByLeave =
                associateRevocations(leaves, revocations);
        List<IntervalEvidence> resolved = new ArrayList<>();
        for (OaDocumentRow leave : leaves) {
            List<OaDocumentRow> matched = revocationsByLeave.getOrDefault(
                    leave, List.of());
            if (matched.isEmpty()) {
                resolved.addAll(toLeaveEvidence(leave));
                continue;
            }
            LeaveType leaveType = requiredLeaveType(EvidenceKind.LEAVE, leave);
            for (TimeInterval interval : unionIntervals(matched)) {
                resolved.add(leaveEvidence(
                        leave.sourceBusinessKey() + ":" + interval.start(),
                        interval,
                        leave,
                        leaveType));
            }
        }
        return resolved;
    }

    private record Subject(String sourceId, String employeeId, String legacyNumber) {
    }

    private record LeaveReference(Subject subject, String serial) {
    }

    private static Map<OaDocumentRow, List<OaDocumentRow>> associateRevocations(
            List<OaDocumentRow> leaves,
            List<OaDocumentRow> revocations) {
        Map<LeaveReference, List<OaDocumentRow>> byReference = new LinkedHashMap<>();
        Map<Subject, List<OaDocumentRow>> bySubject = new LinkedHashMap<>();
        for (OaDocumentRow leave : leaves) {
            Subject subject = subject(leave);
            if (subject == null) {
                continue;
            }
            bySubject.computeIfAbsent(subject, ignored -> new ArrayList<>()).add(leave);
            String serial = trimToNull(leave.leaveSerial());
            if (serial != null) {
                byReference.computeIfAbsent(new LeaveReference(subject, serial),
                        ignored -> new ArrayList<>()).add(leave);
            }
        }
        Map<OaDocumentRow, List<OaDocumentRow>> matched = new LinkedHashMap<>();
        for (OaDocumentRow revocation : revocations) {
            Subject subject = subject(revocation);
            if (subject == null) {
                continue;
            }
            String serial = trimToNull(revocation.originalLeaveSerial());
            List<OaDocumentRow> candidates;
            if (serial != null) {
                candidates = byReference.getOrDefault(
                        new LeaveReference(subject, serial), List.of());
            } else {
                List<OaDocumentRow> employeeLeaves = bySubject.getOrDefault(
                        subject, List.of());
                // Historical forms can lack the original serial. Accept only
                // one containing original within this exact source/employee;
                // never apply one revocation to every leave of an employee.
                candidates = employeeLeaves.stream()
                        .filter(leave -> containsActualInterval(leave, revocation))
                        .toList();
            }
            candidates = candidates.stream().distinct().toList();
            if (serial == null && candidates.size() != 1) {
                log.warn("{}: missing original reference has {} containing candidates ({})",
                        UNRESOLVED_LEAVE_REVOCATION, candidates.size(),
                        revocation.sourceBusinessKey());
                continue;
            }
            if (candidates.size() > 1) {
                throw unresolved("ambiguous original leave reference", revocation);
            }
            if (candidates.isEmpty()) {
                continue;
            }
            OaDocumentRow leave = candidates.getFirst();
            if (leave.startInstant() == null || leave.endInstant() == null
                    || !leave.startInstant().isBefore(leave.endInstant())
                    || revocation.startInstant() == null || revocation.endInstant() == null
                    || revocation.endInstant().isBefore(revocation.startInstant())) {
                throw unresolved("invalid original or actual leave interval", revocation);
            }
            // An explicit, unique source/employee/original-serial reference is
            // authoritative for approved changes to leave information: the
            // actual interval can move to another day or extend the request.
            // Containment is only the safe association rule for historical
            // forms without an original serial (the fallback above).
            matched.computeIfAbsent(leave, ignored -> new ArrayList<>()).add(revocation);
        }
        return matched;
    }

    private static Subject subject(OaDocumentRow row) {
        String sourceId = trimToNull(row.sourceId());
        String employeeId = trimToNull(row.employeeId());
        if (sourceId != null || employeeId != null) {
            if (sourceId == null || employeeId == null) {
                throw unresolved("incomplete source/employee identity", row);
            }
            return new Subject(sourceId, employeeId, null);
        }
        String employeeNumber = trimToNull(row.employeeNumber());
        return employeeNumber == null ? null : new Subject(null, null, employeeNumber);
    }

    private static boolean containsActualInterval(
            OaDocumentRow leave, OaDocumentRow revocation) {
        return leave.startInstant() != null && leave.endInstant() != null
                && revocation.startInstant() != null && revocation.endInstant() != null
                && !revocation.endInstant().isBefore(revocation.startInstant())
                && !revocation.startInstant().isBefore(leave.startInstant())
                && !revocation.endInstant().isAfter(leave.endInstant());
    }

    private static IllegalStateException unresolved(String reason, OaDocumentRow row) {
        return new IllegalStateException(UNRESOLVED_LEAVE_REVOCATION
                + ": " + reason + " (" + row.sourceBusinessKey() + ")");
    }

    private static List<TimeInterval> unionIntervals(List<OaDocumentRow> rows) {
        List<TimeInterval> intervals = rows.stream()
                .map(row -> OaIntervalGrid.snapInterval(
                        row.startInstant(), row.endInstant()))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(TimeInterval::start)
                        .thenComparing(TimeInterval::end))
                .toList();
        List<TimeInterval> merged = new ArrayList<>();
        for (TimeInterval interval : intervals) {
            if (merged.isEmpty()) {
                merged.add(interval);
                continue;
            }
            TimeInterval last = merged.getLast();
            if (!interval.start().isAfter(last.end())) {
                Instant end = interval.end().isAfter(last.end())
                        ? interval.end()
                        : last.end();
                merged.set(merged.size() - 1, new TimeInterval(last.start(), end));
            } else {
                merged.add(interval);
            }
        }
        return merged;
    }

    private static List<IntervalEvidence> toLeaveEvidence(OaDocumentRow doc) {
        if (doc.leaveType() == LeaveType.BREASTFEEDING) {
            return breastfeedingSlices(doc);
        }
        IntervalEvidence converted = toEvidence(doc);
        return converted == null ? List.of() : List.of(converted);
    }

    private static List<IntervalEvidence> breastfeedingSlices(OaDocumentRow doc) {
        TimeInterval span = OaIntervalGrid.snapInterval(
                doc.startInstant(), doc.endInstant());
        if (span == null) {
            return List.of();
        }
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
        java.time.LocalDate from = span.start().atZone(zone).toLocalDate();
        java.time.LocalDate to = span.end().minusNanos(1).atZone(zone).toLocalDate();
        java.time.LocalTime clock = span.start().atZone(zone).toLocalTime();
        List<IntervalEvidence> slices = new ArrayList<>();
        for (java.time.LocalDate date = from;
                !date.isAfter(to);
                date = date.plusDays(1)) {
            DayOfWeek weekday = date.getDayOfWeek();
            if (weekday == DayOfWeek.SATURDAY || weekday == DayOfWeek.SUNDAY) {
                continue;
            }
            Instant dayStart = date.atTime(clock).atZone(zone).toInstant();
            Instant dayEnd = dayStart.plusSeconds(3600);
            TimeInterval interval = OaIntervalGrid.snapInterval(dayStart, dayEnd);
            if (interval == null) {
                continue;
            }
            slices.add(leaveEvidence(
                    doc.sourceBusinessKey() + ":" + date,
                    interval,
                    doc,
                    LeaveType.BREASTFEEDING));
        }
        return slices;
    }

    private static IntervalEvidence toEvidence(OaDocumentRow doc) {
        TimeInterval interval = OaIntervalGrid.snapInterval(
                doc.startInstant(), doc.endInstant());
        if (interval == null) {
            return null;
        }
        EvidenceKind kind = mapDocumentType(doc.documentType());
        return new IntervalEvidence(
                "oa:" + doc.sourceBusinessKey(),
                kind,
                interval,
                doc.sourceBusinessKey(),
                doc.firstSubmittedAt(),
                doc.effectiveCandidate(),
                doc.overtimeType(),
                requiredLeaveType(kind, doc));
    }

    private static IntervalEvidence leaveEvidence(
            String evidenceKey,
            TimeInterval interval,
            OaDocumentRow source,
            LeaveType leaveType) {
        return new IntervalEvidence(
                "oa:" + evidenceKey,
                EvidenceKind.LEAVE,
                interval,
                source.sourceBusinessKey(),
                source.firstSubmittedAt(),
                true,
                null,
                leaveType);
    }

    private static LeaveType requiredLeaveType(
            EvidenceKind kind, OaDocumentRow doc) {
        if (kind != EvidenceKind.LEAVE) {
            return null;
        }
        if (doc.leaveType() == null) {
            throw new IllegalArgumentException(
                    "LEAVE document has no classified leave type: "
                            + doc.sourceBusinessKey());
        }
        return doc.leaveType();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static EvidenceKind mapDocumentType(String documentType) {
        return switch (documentType) {
            case "LEAVE" -> EvidenceKind.LEAVE;
            case "OVERTIME" -> EvidenceKind.OVERTIME;
            case "OUTING" -> EvidenceKind.OUTING;
            case "TRIP" -> EvidenceKind.TRIP;
            case "TIME_OFF" -> EvidenceKind.TIME_OFF;
            case "EXEMPT_PUNCH" -> EvidenceKind.EXEMPT_PUNCH;
            case "PUNCH_CORRECTION" -> EvidenceKind.PUNCH_CORRECTION;
            default -> throw new IllegalArgumentException(
                    "Unknown OA document type: " + documentType);
        };
    }
}
