package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import com.szsemicon.hr.attendance.domain.OvertimeType;
import com.szsemicon.hr.reporting.application.AttendanceReportFactProjector.ProjectionFacts;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.VerifiedCalculatedFacts;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.VerifiedOaDocumentFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.OaOvertimeRecognitionRow;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.OaReportFactRow;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Audited recognition changes two projections together; it never creates evidence or exemptions. */
final class OaOvertimeRecognition {
    static final String MARKER_PREFIX = com.szsemicon.hr.reporting.application.
            AttendanceReportDecisionVersionProvider.OVERTIME_RECOGNITION_PREFIX;
    private static final Set<String> EFFECTIVE = Set.of("APPROVED", "MODIFIED", "SUPPLEMENTED", "PENDING");

    private OaOvertimeRecognition() { }

    record Applied(List<VerifiedCalculatedFacts> dailyFacts, List<VerifiedOaDocumentFact> oaFacts,
            List<String> versionMarkers) { }
    private record Day(String employeeId, LocalDate date) { }

    static List<String> markers(List<OaOvertimeRecognitionRow> rows, Instant asOf) {
        return rows.stream().filter(row -> !row.knowledgeAt().isAfter(asOf))
                .map(row -> marker(row, asOf)).sorted().toList();
    }

    private static String marker(OaOvertimeRecognitionRow row, Instant asOf) {
        Instant reversal = row.reversedAt() != null && !row.reversedAt().isAfter(asOf)
                ? row.reversedAt() : null;
        return MARKER_PREFIX + row.recognitionId() + ":" + digest(
                row.recognitionId(), row.companyId(), row.employeeId(), row.sourceId(),
                row.sourceBusinessKey(), row.sourceVersion(), row.documentId(), row.businessDate().toString(),
                row.expectedIntervalStart().toString(), row.expectedIntervalEnd().toString(),
                row.overtimeType().name(), Integer.toString(row.recognizedMinutes()),
                row.knowledgeAt().toString(), Objects.toString(reversal, "ACTIVE"),
                row.confirmationSha256(), row.evidenceSha256(), row.requestId());
    }

    static Applied apply(String companyId, YearMonth period, Instant asOf,
            List<OaOvertimeRecognitionRow> decisions, List<String> priorMarkers,
            List<VerifiedCalculatedFacts> daily, List<VerifiedOaDocumentFact> oa,
            List<OaReportFactRow> currentSources) {
        Map<Day, VerifiedCalculatedFacts> days = new LinkedHashMap<>();
        for (var value : daily) {
            var fact = value.facts().dailyFact();
            require(days.put(new Day(fact.employeeId(), fact.businessDate()), value) == null,
                    "DUPLICATE_DAILY_SUBJECT");
        }
        Map<String, VerifiedOaDocumentFact> documents = new LinkedHashMap<>();
        for (var fact : oa) require(documents.put(fact.oaAttendanceDocumentId(), fact) == null,
                "DUPLICATE_OA_DOCUMENT");
        Map<String, String> previous = new HashMap<>();
        for (String value : priorMarkers) {
            require(value.startsWith(MARKER_PREFIX) && value.lastIndexOf(':') > MARKER_PREFIX.length(),
                    "INVALID_PRIOR_DECISION_MARKER");
            String id = value.substring(MARKER_PREFIX.length(), value.lastIndexOf(':'));
            require(previous.put(id, value) == null, "DUPLICATE_PRIOR_DECISION_MARKER");
        }
        Map<String, String> consumed = new LinkedHashMap<>();
        Set<String> ids = new java.util.HashSet<>();
        Set<String> changedDocuments = new java.util.HashSet<>();
        for (var decision : decisions) {
            if (decision.knowledgeAt().isAfter(asOf)) continue;
            validateDecision(companyId, period, decision);
            require(ids.add(decision.recognitionId()), "DUPLICATE_DECISION");
            Day key = new Day(decision.employeeId(), decision.businessDate());
            VerifiedCalculatedFacts targetDay = days.get(key);
            if (targetDay == null) {
                // Partial recalculation must not acknowledge a new/reversed decision
                // for an inherited employee-day that it did not actually compute.
                if (previous.containsKey(decision.recognitionId())) {
                    consumed.put(decision.recognitionId(), previous.get(decision.recognitionId()));
                }
                continue;
            }
            consumed.put(decision.recognitionId(), marker(decision, asOf));
            if (decision.reversedAt() != null && !decision.reversedAt().isAfter(asOf)) continue;
            List<OaReportFactRow> current = currentSources.stream()
                    .filter(row -> decision.documentId().equals(row.oaAttendanceDocumentId())
                            && decision.employeeId().equals(row.employeeId())
                            && decision.sourceBusinessKey().equals(row.sourceBusinessKey())
                            && decision.sourceVersion().equals(row.sourceVersion())
                            && "OVERTIME".equals(row.documentType())
                            && "APPROVED".equals(row.sourceStatus()))
                    .toList();
            // Current sources already enforce latest observation, identity matching,
            // source activity and exact-version exclusions. Never resurrect a stale row.
            if (current.isEmpty()) continue;
            require(current.size() == 1, "AMBIGUOUS_CURRENT_RECOGNITION_DOCUMENT");
            OaReportFactRow source = current.getFirst();
            require(source.startInstant().equals(decision.expectedIntervalStart())
                            && source.endInstant().equals(decision.expectedIntervalEnd())
                            && source.overtimeType() == decision.overtimeType(),
                    "RECOGNITION_SOURCE_GEOMETRY_OR_TYPE_CHANGED");
            VerifiedOaDocumentFact fact = documents.get(decision.documentId());
            require(fact != null && fact.companyId().equals(companyId)
                            && fact.employeeId().equals(decision.employeeId())
                            && fact.sourceVersion().equals(decision.sourceVersion())
                            && "APPROVED".equals(fact.sourceStatus())
                            && "OVERTIME".equals(fact.documentType())
                            && decision.overtimeType().name().equals(fact.leaveTypeCode())
                            && decision.expectedIntervalStart().equals(fact.intervalStart())
                            && decision.expectedIntervalEnd().equals(fact.intervalEndExclusive()),
                    "RECOGNITION_FACT_DOES_NOT_MATCH_EXACT_SOURCE");
            require(changedDocuments.add(decision.documentId()), "MULTIPLE_DECISIONS_FOR_DOCUMENT");
            require(currentSources.stream().noneMatch(other ->
                            !other.oaAttendanceDocumentId().equals(decision.documentId())
                            && decision.employeeId().equals(other.employeeId())
                            && "OVERTIME".equals(other.documentType())
                            && EFFECTIVE.contains(other.sourceStatus())
                            && OaIntervalGrid.snap(other.startInstant()).isBefore(decision.expectedIntervalEnd())
                            && OaIntervalGrid.snap(other.endInstant()).isAfter(decision.expectedIntervalStart())),
                    "RECOGNITION_OVERLAPS_ANOTHER_EFFECTIVE_FORM");
            verifyDailyAndOaAgree(targetDay.facts().dailyFact(), documents.values().stream().toList(), key);
            long before = minutesOn(fact, key.date());
            require(before == fact.recognizedMinutes(), "RECOGNITION_MUST_BE_SINGLE_DAY");
            long delta = Math.subtractExact(decision.recognizedMinutes(), before);
            DailyFact adjusted = adjust(targetDay.facts().dailyFact(), decision.overtimeType(), delta,
                    marker(decision, asOf));
            String clearedCase = adjusted.factId() + ":OT_FORM_BEYOND:oa:" + decision.sourceBusinessKey();
            days.put(key, new VerifiedCalculatedFacts(targetDay.employeeVersionId(), targetDay.employmentAssignmentId(),
                    new ProjectionFacts(adjusted, targetDay.facts().exceptionFacts().stream()
                            .filter(exception -> !("OVERTIME_FORM_BEYOND_LAST_PUNCH".equals(exception.exceptionType())
                                    && clearedCase.equals(exception.caseId())))
                            .toList())));
            documents.put(decision.documentId(), new VerifiedOaDocumentFact(fact.companyId(), fact.oaAttendanceDocumentId(),
                    fact.employeeId(), fact.employeeVersionId(), fact.employmentAssignmentId(), fact.organizationId(),
                    fact.organizationVersionId(), fact.documentType(), fact.leaveTypeCode(), fact.temporalShape(),
                    fact.pointInstant(), fact.intervalStart(), fact.intervalEndExclusive(), decision.recognizedMinutes(),
                    fact.sourceStatus(), fact.sourceVersion(), fact.sourceOrigin(), Map.of(key.date(), (long) decision.recognizedMinutes())));
            verifyDailyAndOaAgree(adjusted, documents.values().stream().toList(), key);
        }
        return new Applied(List.copyOf(days.values()), List.copyOf(documents.values()),
                consumed.values().stream().sorted().toList());
    }

    private static void validateDecision(String companyId, YearMonth period, OaOvertimeRecognitionRow row) {
        require(companyId.equals(row.companyId()) && period.equals(YearMonth.from(row.businessDate())),
                "RECOGNITION_SCOPE_MISMATCH");
        require(row.expectedIntervalStart().isBefore(row.expectedIntervalEnd())
                        && row.expectedIntervalStart().equals(OaIntervalGrid.snap(row.expectedIntervalStart()))
                        && row.expectedIntervalEnd().equals(OaIntervalGrid.snap(row.expectedIntervalEnd()))
                        && row.expectedIntervalStart().atZone(OaIntervalGrid.BUSINESS_ZONE).toLocalDate().equals(row.businessDate())
                        && row.expectedIntervalEnd().minusNanos(1).atZone(OaIntervalGrid.BUSINESS_ZONE).toLocalDate().equals(row.businessDate()),
                "RECOGNITION_REQUIRES_ONE_NORMALIZED_BUSINESS_DAY");
        require(row.recognizedMinutes() > 0 && row.recognizedMinutes() <= 1440
                        && row.recognizedMinutes() % 30 == 0
                        && row.recognizedMinutes() <= java.time.Duration.between(
                                row.expectedIntervalStart(), row.expectedIntervalEnd()).toMinutes(),
                "INVALID_RECOGNITION_MINUTES");
    }

    private static long minutesOn(VerifiedOaDocumentFact fact, LocalDate day) {
        require(fact.recognizedMinutesByDate() != null, "RECOGNITION_REQUIRES_OA_DAY_ALLOCATION");
        return fact.recognizedMinutesByDate().getOrDefault(day, 0L);
    }

    private static void verifyDailyAndOaAgree(DailyFact daily, List<VerifiedOaDocumentFact> documents, Day key) {
        long paid = 0, comp = 0, voluntary = 0;
        for (var fact : documents) {
            if (!key.employeeId().equals(fact.employeeId()) || !"OVERTIME".equals(fact.documentType())) continue;
            long minutes = minutesOn(fact, key.date());
            if (minutes == 0) continue;
            switch (OvertimeType.valueOf(fact.leaveTypeCode())) {
                case PAID -> paid = Math.addExact(paid, minutes);
                case COMPENSATORY -> comp = Math.addExact(comp, minutes);
                case VOLUNTARY -> voluntary = Math.addExact(voluntary, minutes);
            }
        }
        long total = Math.addExact(Math.addExact(paid, comp), voluntary);
        require(daily.paidOvertimeMinutes() == paid && daily.compensatoryOvertimeMinutes() == comp
                        && daily.voluntaryOvertimeMinutes() == voluntary && daily.totalOvertimeMinutes() == total
                        && daily.recognizedOvertimeMinutes() == total,
                "RECOGNITION_DAILY_OA_ALLOCATION_MISMATCH");
    }

    private static DailyFact adjust(DailyFact d, OvertimeType type, long delta, String marker) {
        long paid = Math.addExact(d.paidOvertimeMinutes(), type == OvertimeType.PAID ? delta : 0);
        long comp = Math.addExact(d.compensatoryOvertimeMinutes(), type == OvertimeType.COMPENSATORY ? delta : 0);
        long voluntary = Math.addExact(d.voluntaryOvertimeMinutes(), type == OvertimeType.VOLUNTARY ? delta : 0);
        long total = Math.addExact(Math.addExact(paid, comp), voluntary);
        return new DailyFact(d.factId(), d.companyId(), d.employeeId(), d.employeeNumber(), d.employeeName(),
                d.organizationId(), d.organizationVersionId(), d.organizationName(), d.businessDate(), d.dayType(), d.shiftLabel(),
                d.scheduledMinutes(), d.confirmedScheduledWorkMinutes(), total, paid, comp, voluntary, total,
                d.leaveOrTimeOffMinutes(), d.absenceMinutes(), Math.addExact(d.confirmedScheduledWorkMinutes(), total),
                d.scheduledAttendanceDays(), d.actualAttendanceDays(), d.lateMinutes(), d.penalizedLateMinutes(),
                d.earlyDepartureMinutes(), d.missingPunchCount(), d.firstPunchAt(), d.lastPunchAt(), d.calculationVersionId(),
                digest(d.resultDigest(), marker), d.leaveType());
    }

    private static String digest(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = Objects.requireNonNull(value).getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
