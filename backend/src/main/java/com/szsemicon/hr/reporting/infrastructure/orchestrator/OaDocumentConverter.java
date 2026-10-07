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

/**
 * Converts OA attendance documents to calculation engine evidence.
 *
 * <p>Approved leave revocations replace the original leave interval with the
 * revocation's actual start/end. Multiple revocations for the same leave are
 * unioned so overlapping minutes are not counted twice.
 */
final class OaDocumentConverter {

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
        Map<String, List<OaDocumentRow>> revocationsByKey = groupRevocations(
                revocations);
        List<IntervalEvidence> resolved = new ArrayList<>();
        for (OaDocumentRow leave : leaves) {
            List<OaDocumentRow> matched = matchingRevocations(
                    leave, revocationsByKey);
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

    private static Map<String, List<OaDocumentRow>> groupRevocations(
            List<OaDocumentRow> revocations) {
        Map<String, List<OaDocumentRow>> grouped = new LinkedHashMap<>();
        for (OaDocumentRow revocation : revocations) {
            grouped.computeIfAbsent(
                    revocationKey(revocation), ignored -> new ArrayList<>())
                    .add(revocation);
        }
        return grouped;
    }

    private static List<OaDocumentRow> matchingRevocations(
            OaDocumentRow leave,
            Map<String, List<OaDocumentRow>> revocationsByKey) {
        String serial = trimToNull(leave.leaveSerial());
        if (serial != null) {
            List<OaDocumentRow> bySerial = revocationsByKey.get(serialKey(serial));
            if (bySerial != null && !bySerial.isEmpty()) {
                return bySerial;
            }
        }
        String employee = trimToNull(leave.employeeNumber());
        if (employee == null) {
            return List.of();
        }
        return revocationsByKey.getOrDefault(employeeKey(employee), List.of());
    }

    private static String revocationKey(OaDocumentRow revocation) {
        String serial = trimToNull(revocation.originalLeaveSerial());
        if (serial != null) {
            return serialKey(serial);
        }
        String employee = trimToNull(revocation.employeeNumber());
        if (employee == null) {
            return "unmatched:" + revocation.sourceBusinessKey();
        }
        return employeeKey(employee);
    }

    private static String serialKey(String serial) {
        return "serial:" + serial;
    }

    private static String employeeKey(String employeeNumber) {
        return "employee:" + employeeNumber;
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
