package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import com.szsemicon.hr.reporting.application.AttendanceReportDependencyPlan;
import com.szsemicon.hr.reporting.application.OaRecognizedDayMinutes;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.OaDocumentRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.ProjectedLeaveUsageRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.ShiftSegmentRow;
import java.time.*;
import java.util.*;

/** Computes a fixed point of changed document contributions, never of every long interval. */
final class OaReportDependencyPlanner {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private OaReportDependencyPlanner() { }

    static AttendanceReportDependencyPlan plan(String companyId, YearMonth period,
            LocalDate from, LocalDate to, Collection<String> employeeIds,
            List<OaDocumentRow> pinned, List<OaDocumentRow> current,
            List<ShiftSegmentRow> shifts, Set<String> historicallyAmendedKeys) {
        return plan(companyId, period, from, to, employeeIds, pinned, current, shifts,
                historicallyAmendedKeys, List.of());
    }

    static AttendanceReportDependencyPlan plan(String companyId, YearMonth period,
            LocalDate from, LocalDate to, Collection<String> employeeIds,
            List<OaDocumentRow> pinned, List<OaDocumentRow> current,
            List<ShiftSegmentRow> shifts, Set<String> historicallyAmendedKeys,
            List<ProjectedLeaveUsageRow> pinnedLeaveUsage) {
        Map<Key, Set<OaDocumentRow>> before = grouped(pinned);
        Map<Key, Set<OaDocumentRow>> after = grouped(current);
        Set<Key> keys = new LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        List<List<OaDocumentRow>> changes = new ArrayList<>();
        for (Key key : keys) {
            Set<OaDocumentRow> oldRows = before.getOrDefault(key, Set.of());
            Set<OaDocumentRow> newRows = after.getOrDefault(key, Set.of());
            if (!oldRows.equals(newRows)) {
                List<OaDocumentRow> affected = new ArrayList<>(oldRows);
                affected.addAll(newRows);
                changes.add(affected);
            }
        }
        // The old schema retains whole original intervals/hours, not the
        // revocation version used to compute copied daily rows. An approved
        // amendment therefore requires its whole related original scope;
        // equal total hours cannot prove equal business-day contributions.
        List<OaDocumentRow> amendmentCandidates = new ArrayList<>(current);
        amendmentCandidates.addAll(pinned);
        for (OaDocumentRow row : amendmentCandidates) {
            if (historicallyAmendedKeys.contains(row.sourceId() + ":" + row.sourceBusinessKey())
                    && "LEAVE".equals(row.documentType())) {
                List<OaDocumentRow> related = new ArrayList<>(
                        before.getOrDefault(key(row), Set.of()));
                related.addAll(after.getOrDefault(key(row), Set.of()));
                changes.add(related);
            }
        }
        List<List<OaDocumentRow>> amendmentGroups = amendmentGroups(before, after, current);
        changes.addAll(amendmentGroups);
        for (ProjectedLeaveUsageRow usage : pinnedLeaveUsage) {
            Key usageKey = new Key(usage.sourceId(), usage.sourceBusinessKey());
            List<OaDocumentRow> originals = before.getOrDefault(usageKey, Set.of()).stream()
                    .filter(row -> "LEAVE".equals(row.documentType()) && Objects.equals(row.employeeId(), usage.employeeId()))
                    .toList();
            if (originals.size() != 1) throw new IllegalStateException("OA_PINNED_LEAVE_DEPENDENCY_AMBIGUOUS");
            Map<LocalDate, Long> days = OaRecognizedDayMinutes.decode(
                    usage.recognizedMinutesByDateJson(), usage.recognizedMinutes());
            boolean amended = historicallyAmendedKeys.contains(usage.sourceId() + ":" + usage.sourceBusinessKey())
                    || amendmentGroups.stream().anyMatch(group -> group.stream().anyMatch(row -> key(row).equals(usageKey)));
            if (days == null) {
                // Legacy pins do not identify a previous amendment's actual
                // dates. Do not invent those dates from the original request.
                OaDocumentRow original = originals.getFirst();
                if (!overlaps(original, period.atDay(1).atStartOfDay(ZONE).toInstant(),
                        period.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant())) {
                    // Even a whole-month employee-window copy cannot remove
                    // this legacy row: neither its trace interval nor absent
                    // day map intersects the month's copy predicate.
                    throw new IllegalStateException("OA_LEGACY_OUTSIDE_MONTH_LEAVE_REQUIRES_FULL_REBUILD");
                }
                if (amended
                        && (!from.equals(period.atDay(1)) || !to.equals(period.plusMonths(1).atDay(1)))) {
                    throw new IllegalStateException("OA_LEGACY_AMENDED_LEAVE_REQUIRES_FULL_MONTH");
                }
                continue;
            }
            if (!amended && before.getOrDefault(usageKey, Set.of()).equals(after.getOrDefault(usageKey, Set.of()))) continue;
            OaDocumentRow original = originals.getFirst();
            List<OaDocumentRow> related = new ArrayList<>(before.getOrDefault(usageKey, Set.of()));
            related.addAll(after.getOrDefault(usageKey, Set.of()));
            // Associate the pinned day shares with the same original and its
            // current approved amendment. This survives cancellation/removal
            // of the amendment itself, which is never an OA report fact.
            for (List<OaDocumentRow> group : amendmentGroups) {
                if (group.stream().anyMatch(row -> key(row).equals(usageKey)
                        && Objects.equals(row.employeeId(), usage.employeeId()))) related.addAll(group);
            }
            for (LocalDate day : days.keySet()) {
                related.add(new OaDocumentRow(original.sourceBusinessKey(), "LEAVE_DAY_DEPENDENCY", null,
                        original.leaveType(), original.employeeNumber(), day.atStartOfDay(ZONE).toInstant(),
                        day.plusDays(1).atStartOfDay(ZONE).toInstant(), "Asia/Shanghai", original.firstSubmittedAt(),
                        true, original.leaveSerial(), null, original.sourceId(), original.employeeId()));
            }
            changes.add(related);
            amendmentGroups.add(related);
        }
        // LEAVE/TIME_OFF re-emits the whole month for in-scope employees (shares
        // depend on WORK inputs across the month, not just OA changes). Windowed
        // recalc (not full-month) replaces the whole month's LEAVE/TIME_OFF, so
        // plan must cover the whole month to keep daily/monthly consistent.
        boolean isWindowedRecalc = !from.equals(period.atDay(1)) || !to.equals(period.plusMonths(1).atDay(1));
        LocalDate writeFrom = isWindowedRecalc ? period.atDay(1) : from;
        LocalDate writeTo = isWindowedRecalc ? period.plusMonths(1).atDay(1) : to;
        boolean changed;
        do {
            changed = false;
            Instant start = writeFrom.atStartOfDay(ZONE).toInstant();
            Instant end = writeTo.atStartOfDay(ZONE).toInstant();
            for (List<OaDocumentRow> group : changes) {
                if (group.stream().noneMatch(row -> overlaps(row, start, end))) continue;
                for (OaDocumentRow row : group) {
                    // A zero-contribution old OA interval already intersecting
                    // this write window is removed by the publisher's copy
                    // predicate. Its original span need not recalculate daily.
                    // Keep its identity, however: if it moved into the window
                    // from outside, that old OA row must be removed as well.
                    if (!row.effectiveCandidate() && overlaps(row, start, end)) continue;
                    LocalDate a = row.startInstant().atZone(ZONE).toLocalDate();
                    LocalDate b = row.endInstant().isAfter(row.startInstant())
                            ? row.endInstant().minusNanos(1).atZone(ZONE).toLocalDate().plusDays(1)
                            : a.plusDays(1);
                    if (a.isBefore(period.atDay(1))) a = period.atDay(1);
                    if (b.isAfter(period.plusMonths(1).atDay(1))) b = period.plusMonths(1).atDay(1);
                    if (!a.isBefore(b)) continue; // Related evidence in another month is read, never written here.
                    if (a.isBefore(writeFrom)) { writeFrom = a; changed = true; }
                    if (b.isAfter(writeTo)) { writeTo = b; changed = true; }
                }
            }
        } while (changed);
        Instant evidenceFrom = writeFrom.minusDays(1).atStartOfDay(ZONE).toInstant();
        Instant evidenceTo = writeTo.atTime(12, 0).atZone(ZONE).toInstant();
        // Writes remain clipped to this month. Read the associated original
        // and actual intervals in full, including a cross-month move, so the
        // converter never receives an orphan amendment at a window edge.
        Instant writeStart = writeFrom.atStartOfDay(ZONE).toInstant();
        Instant writeLimit = writeTo.atStartOfDay(ZONE).toInstant();
        for (List<OaDocumentRow> group : amendmentGroups) {
            if (group.stream().noneMatch(row -> overlaps(row, writeStart, writeLimit))) continue;
            for (OaDocumentRow row : group) {
                if (row.startInstant().isBefore(evidenceFrom)) evidenceFrom = row.startInstant();
                Instant end = row.endInstant().isAfter(row.startInstant())
                        ? row.endInstant() : row.endInstant().plusNanos(1);
                if (end.isAfter(evidenceTo)) evidenceTo = end;
            }
        }
        for (ShiftSegmentRow row : shifts) {
            if (row.businessDate().isBefore(writeFrom.minusDays(1))
                    || !row.businessDate().isBefore(writeTo)) continue;
            for (Instant value : List.of(row.segmentStart(), row.arrivalWindowStart(), row.departureWindowStart())) {
                if (value.isBefore(evidenceFrom)) evidenceFrom = value;
            }
            for (Instant value : List.of(row.segmentEnd(), row.arrivalWindowEnd(), row.departureWindowEnd())) {
                if (value.isAfter(evidenceTo)) evidenceTo = value;
            }
        }
        Instant punchFrom = evidenceFrom;
        Instant punchTo = evidenceTo;
        Instant writeAt = writeFrom.atStartOfDay(ZONE).toInstant();
        Instant writeEnd = writeTo.atStartOfDay(ZONE).toInstant();
        // Whole-document overtime recognition still needs its original
        // start-day/off-duty punches, independently from the daily write range.
        for (OaDocumentRow row : current) {
            if (!"OVERTIME".equals(row.documentType()) || !overlaps(row, writeAt, writeEnd)) continue;
            Instant a = row.startInstant().atZone(ZONE).toLocalDate().atStartOfDay(ZONE).toInstant();
            Instant b = row.startInstant().atZone(ZONE).toLocalDate().plusDays(1)
                    .atTime(6, 0).atZone(ZONE).toInstant();
            if (row.endInstant().isAfter(b)) b = row.endInstant();
            if (a.isBefore(punchFrom)) punchFrom = a;
            if (b.isAfter(punchTo)) punchTo = b;
        }
        // Makeup OA points and exemption intervals on the original overtime
        // start day are recognition dependencies too, not just physical punches.
        if (punchFrom.isBefore(evidenceFrom)) evidenceFrom = punchFrom;
        if (punchTo.isAfter(evidenceTo)) evidenceTo = punchTo;
        return new AttendanceReportDependencyPlan(companyId, period, writeFrom, writeTo,
                evidenceFrom, evidenceTo, punchFrom, punchTo,
                employeeIds == null ? List.of() : List.copyOf(employeeIds));
    }

    private record Key(String sourceId, String businessKey) { }
    private record LeaveReference(String sourceId, String employeeId, String serial) { }

    private static List<List<OaDocumentRow>> amendmentGroups(Map<Key, Set<OaDocumentRow>> before,
            Map<Key, Set<OaDocumentRow>> after, List<OaDocumentRow> current) {
        Map<LeaveReference, Set<Key>> originals = new LinkedHashMap<>();
        List<OaDocumentRow> allOriginals = new ArrayList<>();
        // Match current amendments against current originals, exactly as the
        // converter does. A retired different key must not make that current
        // reference ambiguous; the chosen key's old facts are added below.
        after.values().forEach(allOriginals::addAll);
        for (OaDocumentRow row : allOriginals) {
            if (!"LEAVE".equals(row.documentType()) || blank(row.sourceId()) || blank(row.employeeId()) || blank(row.leaveSerial())) continue;
            originals.computeIfAbsent(new LeaveReference(row.sourceId(), row.employeeId(), row.leaveSerial().trim()),
                    ignored -> new LinkedHashSet<>()).add(key(row));
        }
        List<List<OaDocumentRow>> groups = new ArrayList<>();
        for (OaDocumentRow row : current) {
            if (!"LEAVE_REVOCATION".equals(row.documentType()) || !row.effectiveCandidate()
                    || blank(row.sourceId()) || blank(row.employeeId()) || blank(row.originalLeaveSerial())) continue;
            Set<Key> matches = originals.getOrDefault(new LeaveReference(row.sourceId(), row.employeeId(),
                    row.originalLeaveSerial().trim()), Set.of());
            if (matches.size() != 1) continue; // No guessing from employee number or geometry.
            if (row.startInstant() == null || row.endInstant() == null || row.endInstant().isBefore(row.startInstant()))
                throw new IllegalStateException("OA_LEAVE_REVOCATION_UNRESOLVED: invalid actual interval");
            Key original = matches.iterator().next();
            List<OaDocumentRow> group = new ArrayList<>();
            before.getOrDefault(original, Set.of()).stream().filter(o -> Objects.equals(o.employeeId(), row.employeeId())).forEach(group::add);
            after.getOrDefault(original, Set.of()).stream().filter(o -> Objects.equals(o.employeeId(), row.employeeId())).forEach(group::add);
            group.add(contribution(row));
            groups.add(group);
        }
        return groups;
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static Key key(OaDocumentRow row) {
        return new Key(row.sourceId(), row.sourceBusinessKey());
    }
    private static Map<Key, Set<OaDocumentRow>> grouped(List<OaDocumentRow> rows) {
        Map<Key, Set<OaDocumentRow>> result = new LinkedHashMap<>();
        for (OaDocumentRow row : rows) {
            if (!"LEAVE_REVOCATION".equals(row.documentType())) {
                result.computeIfAbsent(key(row), ignored -> new LinkedHashSet<>()).add(contribution(row));
            }
        }
        return result;
    }
    private static OaDocumentRow contribution(OaDocumentRow row) {
        if ("PUNCH_CORRECTION".equals(row.documentType())) return row;
        // Persisted OA facts and the calculator use this same half-hour grid.
        // Comparing raw seconds against snapped facts would falsely treat an
        // unchanged long document as a changed contribution every time.
        return new OaDocumentRow(row.sourceBusinessKey(), row.documentType(), row.overtimeType(),
                row.leaveType(), row.employeeNumber(), OaIntervalGrid.snap(row.startInstant()),
                OaIntervalGrid.snap(row.endInstant()), row.sourceTimeZone(), row.firstSubmittedAt(),
                row.effectiveCandidate(), row.leaveSerial(), row.originalLeaveSerial(),
                row.sourceId(), row.employeeId());
    }

    private static boolean overlaps(OaDocumentRow row, Instant from, Instant to) {
        return row.endInstant().isAfter(row.startInstant())
                ? row.startInstant().isBefore(to) && row.endInstant().isAfter(from)
                : !row.startInstant().isBefore(from) && row.startInstant().isBefore(to);
    }
}
