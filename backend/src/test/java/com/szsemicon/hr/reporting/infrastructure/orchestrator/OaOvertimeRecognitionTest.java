package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.szsemicon.hr.attendance.domain.OvertimeType;
import com.szsemicon.hr.reporting.application.AttendanceReportDependencyPlan;
import com.szsemicon.hr.reporting.application.AttendanceReportFactProjector.ProjectionFacts;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.*;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.*;
import com.szsemicon.hr.reporting.infrastructure.orchestrator.AttendanceReportCalculationRows.*;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class OaOvertimeRecognitionTest {
    static final String COMPANY = "company", EMPLOYEE = "employee", DOC = "document", KEY = "OVERTIME:1";
    static final YearMonth PERIOD = YearMonth.of(2026, 9);
    static final LocalDate DAY = PERIOD.atDay(5);
    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final Instant KNOWN = Instant.parse("2026-10-06T10:00:00Z");
    static final Instant LATER = KNOWN.plusSeconds(3600);

    @ParameterizedTest(name = "{0}")
    @MethodSource("fiveApprovedForms")
    void confirmedFiveExactFormsUpdateDailyAndOaTogetherWithoutExemptionOrFabricatedCards(Approved approved) {
        var mapper = mapper(approved);
        var decision = decision(approved, KNOWN, null);
        when(mapper.findOaOvertimeRecognitions(COMPANY, PERIOD.atDay(1), PERIOD.plusMonths(1).atDay(1), KNOWN))
                .thenReturn(List.of(decision));
        var result = assemble(mapper, KNOWN);
        var daily = result.calculatedFacts().getFirst().facts().dailyFact();
        assertThat(daily.recognizedOvertimeMinutes()).isEqualTo(approved.minutes());
        assertThat(daily.totalOvertimeMinutes()).isEqualTo(approved.minutes());
        assertThat(daily.paidOvertimeMinutes()).isEqualTo(approved.type() == OvertimeType.PAID ? approved.minutes() : 0);
        assertThat(daily.voluntaryOvertimeMinutes()).isEqualTo(approved.type() == OvertimeType.VOLUNTARY ? approved.minutes() : 0);
        assertThat(daily.compensatoryOvertimeMinutes()).isZero();
        assertThat(daily.actualWorkMinutes()).isEqualTo(daily.confirmedScheduledWorkMinutes() + approved.minutes());
        assertThat(daily.firstPunchAt()).isNull();
        assertThat(daily.lastPunchAt()).isNull();
        assertThat(result.oaDocumentFacts()).singleElement().satisfies(oa -> {
            assertThat(oa.recognizedMinutes()).isEqualTo(approved.minutes());
            assertThat(oa.recognizedMinutesByDate()).containsExactlyEntriesOf(Map.of(DAY, (long) approved.minutes()));
            assertThat(oa.leaveTypeCode()).isEqualTo(approved.type().name());
            assertThat(oa.sourceStatus()).isEqualTo("APPROVED");
            assertThat(oa.intervalStart()).isEqualTo(approved.start());
            assertThat(oa.intervalEndExclusive()).isEqualTo(approved.end());
        });
        assertThat(result.calculatedFacts().getFirst().facts().exceptionFacts())
                .noneMatch(e -> e.exceptionType().equals("OVERTIME_FORM_BEYOND_LAST_PUNCH"));
        assertThat(result.metadata().sourceVersions()).containsAll(OaOvertimeRecognition.markers(List.of(decision), KNOWN));
        var baseline = assemble(mapper(approved), KNOWN);
        assertThat(result.metadata().sourceSnapshotDigest()).isNotEqualTo(baseline.metadata().sourceSnapshotDigest());
        assertThat(baseline.calculatedFacts().getFirst().facts().dailyFact().recognizedOvertimeMinutes()).isZero();
    }

    @Test void preservesNormalLeaveAttendanceCardsAndOtherExceptionsAndAddsToNonoverlappingCompTime() {
        var f = fixture();
        var d = f.day().facts().dailyFact();
        Instant first = at("07:30"), last = at("22:00");
        var before = new DailyFact(d.factId(), d.companyId(), d.employeeId(), d.employeeNumber(), d.employeeName(),
                d.organizationId(), d.organizationVersionId(), d.organizationName(), DAY, d.dayType(), d.shiftLabel(),
                480, 420, 60, 0, 60, 0, 60, 30, 30, 480, 1, .5, 15, 10, 20, 1, first, last,
                d.calculationVersionId(), d.resultDigest(), com.szsemicon.hr.attendance.domain.LeaveType.PERSONAL);
        var originalWarning = f.day().facts().exceptionFacts().getFirst();
        var otherWarning = new ExceptionFact("unrelated-zero", EMPLOYEE, "SZST0648", "巨铭铭", "org", "org", DAY,
                "OVERTIME_FORM_BEYOND_LAST_PUNCH", originalWarning.severity(), originalWarning.state(), 0,
                "another source form", d.calculationVersionId());
        var unrelated = new VerifiedOaDocumentFact(COMPANY, "other", EMPLOYEE, "version", "assignment", "org", "org-version",
                "OVERTIME", "COMPENSATORY", OaTemporalShape.INTERVAL, null, at("21:00"), at("22:00"), 60,
                "APPROVED", "v2", "OA", Map.of(DAY, 60L));
        var applied = OaOvertimeRecognition.apply(COMPANY, PERIOD, KNOWN, List.of(f.decision()), List.of(),
                List.of(new VerifiedCalculatedFacts("version", "assignment", new ProjectionFacts(before, List.of(originalWarning, otherWarning)))),
                List.of(f.oa(), unrelated), List.of(f.source(), source("other", "other", "v2", "APPROVED", OvertimeType.COMPENSATORY, at("21:00"), at("22:00"))));
        var after = applied.dailyFacts().getFirst().facts().dailyFact();
        assertThat(after).usingRecursiveComparison().ignoringFields("recognizedOvertimeMinutes", "paidOvertimeMinutes", "totalOvertimeMinutes", "actualWorkMinutes", "resultDigest")
                .isEqualTo(before);
        assertThat(after.paidOvertimeMinutes()).isEqualTo(510);
        assertThat(after.compensatoryOvertimeMinutes()).isEqualTo(60);
        assertThat(after.totalOvertimeMinutes()).isEqualTo(570);
        assertThat(after.actualWorkMinutes()).isEqualTo(990);
        assertThat(applied.dailyFacts().getFirst().facts().exceptionFacts()).containsExactly(otherWarning);
        assertThat(applied.oaFacts()).contains(unrelated);
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "new-document", "new-version", "PENDING", "REVOKED", "wrong-employee", "wrong-key"})
    void invalidOrExcludedCurrentSourceCannotBeResurrected(String change) {
        var f = fixture();
        var s = f.source();
        List<OaReportFactRow> sources = change.equals("missing") ? List.of() : List.of(new OaReportFactRow(
                change.equals("new-document") ? "new-doc" : DOC, change.equals("wrong-key") ? "OVERTIME:other" : KEY, "OVERTIME", null,
                change.equals("wrong-employee") ? "other" : EMPLOYEE, "SZST0648", "assignment", s.startInstant(), s.endInstant(),
                Set.of("PENDING", "REVOKED").contains(change) ? change : "APPROVED",
                change.equals("new-version") ? "v2" : "v1", OvertimeType.PAID));
        var result = apply(f, KNOWN, f.decision(), sources);
        assertThat(result.dailyFacts()).containsExactly(f.day());
        assertThat(result.oaFacts()).containsExactly(f.oa());
    }

    @Test void changedTypeOrGeometryAndOverlapFailClosedInsteadOfGuessing() {
        var f = fixture();
        for (var bad : List.of(source(DOC, KEY, "v1", "APPROVED", OvertimeType.VOLUNTARY, f.source().startInstant(), f.source().endInstant()),
                source(DOC, KEY, "v1", "APPROVED", OvertimeType.PAID, at("09:00"), f.source().endInstant()))) {
            assertThatThrownBy(() -> apply(f, KNOWN, f.decision(), List.of(bad)))
                    .hasMessage("RECOGNITION_SOURCE_GEOMETRY_OR_TYPE_CHANGED");
        }
        var overlap = source("other", "OVERTIME:2", "v2", "APPROVED", OvertimeType.PAID, at("09:00"), at("10:00"));
        assertThatThrownBy(() -> apply(f, KNOWN, f.decision(), List.of(f.source(), overlap)))
                .hasMessage("RECOGNITION_OVERLAPS_ANOTHER_EFFECTIVE_FORM");
    }

    @Test void knowledgeAndReversalAreCutoffBoundAndMarkersChangeOnlyWhenKnown() {
        var f = fixture();
        var reversed = decision(approved(), KNOWN, LATER);
        assertThat(apply(f, KNOWN.minusNanos(1), reversed, List.of(f.source())).dailyFacts()).containsExactly(f.day());
        assertThat(apply(f, KNOWN, reversed, List.of(f.source())).dailyFacts().getFirst().facts().dailyFact().recognizedOvertimeMinutes()).isEqualTo(510);
        assertThat(apply(f, LATER, reversed, List.of(f.source())).dailyFacts()).containsExactly(f.day());
        assertThat(OaOvertimeRecognition.markers(List.of(reversed), KNOWN.minusNanos(1))).isEmpty();
        assertThat(OaOvertimeRecognition.markers(List.of(reversed), KNOWN)).isEqualTo(OaOvertimeRecognition.markers(List.of(f.decision()), KNOWN));
        assertThat(OaOvertimeRecognition.markers(List.of(reversed), LATER)).isNotEqualTo(OaOvertimeRecognition.markers(List.of(reversed), KNOWN));
    }

    @Test void partialWindowInheritsOldMarkerAndCannotConsumeNewDecisionOrReversal() {
        var f = fixture();
        var reversed = decision(approved(), KNOWN, LATER);
        List<String> old = OaOvertimeRecognition.markers(List.of(f.decision()), KNOWN);
        var inherited = OaOvertimeRecognition.apply(COMPANY, PERIOD, LATER, List.of(reversed), old, List.of(), List.of(), List.of());
        assertThat(inherited.versionMarkers()).isEqualTo(old);
        assertThat(inherited.versionMarkers()).isNotEqualTo(OaOvertimeRecognition.markers(List.of(reversed), LATER));
        var notConsumed = OaOvertimeRecognition.apply(COMPANY, PERIOD, KNOWN, List.of(f.decision()), List.of(), List.of(), List.of(), List.of());
        assertThat(notConsumed.versionMarkers()).isEmpty();
        var recomputed = apply(f, LATER, reversed, List.of(f.source()));
        assertThat(recomputed.versionMarkers()).isEqualTo(OaOvertimeRecognition.markers(List.of(reversed), LATER));
    }

    @Test void partialAssemblyReadsMarkersOnlyFromCapturedPriorPinAndRejectsFuturePin() {
        var mapper = mapper(approved());
        var decision = decision(approved(), KNOWN, LATER);
        var baseline = new AttendanceReportDependencyPlan.Baseline("prior-pin", KNOWN, "OPEN");
        when(mapper.findOaOvertimeRecognitions(any(), any(), any(), any())).thenReturn(List.of(decision));
        when(mapper.findDependencyBaseline(COMPANY, PERIOD.atDay(1), PERIOD.plusMonths(1).atDay(1))).thenReturn(baseline);
        List<String> old = OaOvertimeRecognition.markers(List.of(decision), KNOWN);
        when(mapper.findRecognitionMarkersForProjection(COMPANY, "prior-pin")).thenReturn(old);
        var result = new FullCalculationEngineOrchestrator(mapper).assemble(COMPANY, PERIOD, PeriodState.OPEN, "admin", LATER, DAY.plusDays(1), DAY.plusDays(2));
        assertThat(result.metadata().sourceVersions()).containsAll(old);
        verify(mapper).findRecognitionMarkersForProjection(COMPANY, "prior-pin");
        when(mapper.findDependencyBaseline(COMPANY, PERIOD.atDay(1), PERIOD.plusMonths(1).atDay(1)))
                .thenReturn(new AttendanceReportDependencyPlan.Baseline("future", LATER.plusSeconds(1), "OPEN"));
        assertThatThrownBy(() -> assemble(mapper, LATER)).hasMessage("recognition cutoff precedes inherited pin");
    }

    @Test void mismatchedDailyAndOaAllocationsFailBeforeChangingAnyResult() {
        var f = fixture();
        var bad = new VerifiedOaDocumentFact(f.oa().companyId(), DOC, EMPLOYEE, "version", "assignment", "org", "org-version",
                "OVERTIME", "PAID", OaTemporalShape.INTERVAL, null, f.source().startInstant(), f.source().endInstant(),
                30, "APPROVED", "v1", "OA", Map.of(DAY, 30L));
        assertThatThrownBy(() -> OaOvertimeRecognition.apply(COMPANY, PERIOD, KNOWN, List.of(f.decision()), List.of(), List.of(f.day()), List.of(bad), List.of(f.source())))
                .hasMessage("RECOGNITION_DAILY_OA_ALLOCATION_MISMATCH");
    }

    @Test void crossDateOrWrongCompanyDecisionNeverBroadensScope() {
        var f = fixture(); var d = f.decision();
        var wrongCompany = new OaOvertimeRecognitionRow(d.recognitionId(), "other", d.employeeId(), d.sourceId(), d.sourceBusinessKey(), d.sourceVersion(), d.documentId(), d.businessDate(),
                d.expectedIntervalStart(), d.expectedIntervalEnd(), d.overtimeType(), d.recognizedMinutes(), d.knowledgeAt(), null, d.confirmationSha256(), d.evidenceSha256(), d.requestId());
        assertThatThrownBy(() -> apply(f, KNOWN, wrongCompany, List.of(f.source()))).hasMessage("RECOGNITION_SCOPE_MISMATCH");
        var crossDate = new OaOvertimeRecognitionRow(d.recognitionId(), COMPANY, d.employeeId(), d.sourceId(), d.sourceBusinessKey(), d.sourceVersion(), d.documentId(), d.businessDate(),
                d.expectedIntervalStart(), d.expectedIntervalEnd().plusSeconds(86400), d.overtimeType(), d.recognizedMinutes(), d.knowledgeAt(), null, d.confirmationSha256(), d.evidenceSha256(), d.requestId());
        assertThatThrownBy(() -> apply(f, KNOWN, crossDate, List.of(f.source()))).hasMessage("RECOGNITION_REQUIRES_ONE_NORMALIZED_BUSINESS_DAY");
    }

    static OaOvertimeRecognition.Applied apply(Fixture f, Instant cutoff, OaOvertimeRecognitionRow decision, List<OaReportFactRow> sources) {
        return OaOvertimeRecognition.apply(COMPANY, PERIOD, cutoff, List.of(decision), List.of(), List.of(f.day()), List.of(f.oa()), sources);
    }
    static Fixture fixture() {
        var approved = approved();
        var command = assemble(mapper(approved), KNOWN);
        return new Fixture(decision(approved, KNOWN, null), command.calculatedFacts().getFirst(), command.oaDocumentFacts().getFirst(),
                source(DOC, KEY, "v1", "APPROVED", approved.type(), approved.start(), approved.end()));
    }
    static PublishCommand assemble(AttendanceReportCalculationMapper mapper, Instant cutoff) {
        return new FullCalculationEngineOrchestrator(mapper).assemble(COMPANY, PERIOD, PeriodState.OPEN, "admin", cutoff);
    }
    static AttendanceReportCalculationMapper mapper(Approved approved) {
        var mapper = mock(AttendanceReportCalculationMapper.class);
        when(mapper.findAttendanceSourceVersions(any(), any())).thenReturn(List.of(
                new SourceInputVersionRow("deli", "DELI_CLOUD", 1, 1, 1L, "b".repeat(64), KNOWN),
                new SourceInputVersionRow("oa", "OA_ATTENDANCE", 1, 1, 1L, "a".repeat(64), KNOWN)));
        when(mapper.findEmployeeIdentityIntervals(any(), any(), any())).thenReturn(List.of(new EmployeeIdentityIntervalRow(EMPLOYEE, "version", approved.number(), approved.name(),
                "assignment", "org", "org-version", "department", DAY, DAY.plusDays(1), LocalDate.of(2020, 1, 1), null, DAY, DAY.plusDays(1))));
        when(mapper.findPublishedCalendarDays(any(), any(), any(), any())).thenReturn(List.of(new CalendarDayRow(EMPLOYEE, DAY, "SATURDAY", 1, 1)));
        when(mapper.findAttendancePolicies(any(), any(), any(), any())).thenReturn(List.of(
                new AttendancePolicyRow(EMPLOYEE, DAY, 1, 1, true, 15, 1, true, 15, 1, false, 1, true, 7, "NEXT_DAY_START_AFTER_FULL_DAYS", 1, true, 48)));
        when(mapper.findEffectiveOaDocuments(any(), any(), any(), any())).thenReturn(List.of(new OaDocumentRow(KEY, "OVERTIME", approved.type(), null, approved.number(), approved.start(), approved.end(),
                "Asia/Shanghai", approved.start().minusSeconds(3600), true, null, null, "oa", EMPLOYEE)));
        when(mapper.findReportableOaDocuments(any(), any(), any(), any())).thenReturn(List.of(new OaReportFactRow(DOC, KEY, "OVERTIME", null, EMPLOYEE, approved.number(), "assignment", approved.start(), approved.end(), "APPROVED", "v1", approved.type())));
        return mapper;
    }
    static OaReportFactRow source(String doc, String key, String version, String status, OvertimeType type, Instant start, Instant end) {
        return new OaReportFactRow(doc, key, "OVERTIME", null, EMPLOYEE, "SZST0648", "assignment", start, end, status, version, type);
    }
    static OaOvertimeRecognitionRow decision(Approved approved, Instant known, Instant reversed) {
        return new OaOvertimeRecognitionRow("recognition", COMPANY, EMPLOYEE, "oa", KEY, "v1", DOC, DAY, approved.start(), approved.end(), approved.type(), approved.minutes(), known, reversed,
                "a".repeat(64), "b".repeat(64), "request");
    }
    static Approved approved() { return fiveApprovedForms().findFirst().orElseThrow(); }
    static Stream<Approved> fiveApprovedForms() {
        return Stream.of(new Approved("SZST0648", "巨铭铭", at("08:30"), at("18:00"), 510, OvertimeType.PAID),
                new Approved("SZST0487", "赵俊杰", at("09:00"), at("12:00"), 180, OvertimeType.VOLUNTARY),
                new Approved("SZST0443", "尚仁超", at("08:30"), at("22:30"), 750, OvertimeType.PAID),
                new Approved("SZST0599", "王星旭", at("13:30"), at("17:30"), 240, OvertimeType.PAID),
                new Approved("SZST0098", "尹双", at("07:30"), at("16:30"), 480, OvertimeType.PAID));
    }
    static Instant at(String time) { return DAY.atTime(LocalTime.parse(time)).atZone(ZONE).toInstant(); }
    record Fixture(OaOvertimeRecognitionRow decision, VerifiedCalculatedFacts day, VerifiedOaDocumentFact oa, OaReportFactRow source) { }
    record Approved(String number, String name, Instant start, Instant end, int minutes, OvertimeType type) { }

    @Test void exclusionMarkerBindsIdentityApprovalAndKnowledgeWithStableDigest() {
        var exclusion = new OaEvidenceExclusionRow("exclusion-1", COMPANY, EMPLOYEE, "oa-source", "LEAVE:123", "v1",
                "doc-id", "LEAVE", at("08:30"), at("12:00"), null, KNOWN, null, "conf".repeat(16), "evid".repeat(16), "request-1");
        List<String> markers = OaEvidenceExclusion.markers(List.of(exclusion), KNOWN);
        assertThat(markers).hasSize(1);
        String marker = markers.getFirst();
        assertThat(marker).startsWith("DECISION.EXCLUSION:exclusion-1:").matches("DECISION\\.EXCLUSION:exclusion-1:[0-9a-f]{64}");
        assertThat(marker.substring(marker.lastIndexOf(':') + 1)).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(OaEvidenceExclusion.markers(List.of(exclusion), KNOWN)).isEqualTo(markers);
    }

    @Test void exclusionMarkerChangesWhenReversalActivatesAtCutoff() {
        var active = new OaEvidenceExclusionRow("exclusion-1", COMPANY, EMPLOYEE, "oa-source", "LEAVE:123", "v1",
                "doc-id", "LEAVE", at("08:30"), at("12:00"), null, KNOWN, null, "conf".repeat(16), "evid".repeat(16), "request-1");
        var reversed = new OaEvidenceExclusionRow("exclusion-1", COMPANY, EMPLOYEE, "oa-source", "LEAVE:123", "v1",
                "doc-id", "LEAVE", at("08:30"), at("12:00"), null, KNOWN, LATER, "conf".repeat(16), "evid".repeat(16), "request-1");
        assertThat(OaEvidenceExclusion.markers(List.of(reversed), KNOWN.minusNanos(1))).isEmpty();
        var activeMarkers = OaEvidenceExclusion.markers(List.of(active), KNOWN);
        var reversedMarkersAtKnown = OaEvidenceExclusion.markers(List.of(reversed), KNOWN);
        assertThat(activeMarkers).isEqualTo(reversedMarkersAtKnown);
        var reversedMarkersAtLater = OaEvidenceExclusion.markers(List.of(reversed), LATER);
        assertThat(reversedMarkersAtLater).isNotEqualTo(reversedMarkersAtKnown);
    }

    @Test void exclusionMarkerStableAcrossPointAndIntervalGeometry() {
        var interval = new OaEvidenceExclusionRow("exclusion-1", COMPANY, EMPLOYEE, "oa", "LEAVE:1", "v1",
                "doc", "LEAVE", at("08:30"), at("12:00"), null, KNOWN, null, "a".repeat(64), "b".repeat(64), "req");
        var point = new OaEvidenceExclusionRow("exclusion-1", COMPANY, EMPLOYEE, "oa", "LEAVE:1", "v1",
                "doc", "PUNCH_CORRECTION", null, null, at("08:30"), KNOWN, null, "a".repeat(64), "b".repeat(64), "req");
        assertThat(OaEvidenceExclusion.markers(List.of(interval), KNOWN))
                .isNotEqualTo(OaEvidenceExclusion.markers(List.of(point), KNOWN));
    }

    @Test void fullMonthRecalcConsumesExclusionMarkers() {
        var mapper = mock(AttendanceReportCalculationMapper.class);
        setupMinimalMapper(mapper);
        var exclusion = new OaEvidenceExclusionRow("exclusion-1", COMPANY, EMPLOYEE, "oa", "LEAVE:123", "v1",
                "doc", "LEAVE", at("08:30"), at("12:00"), null, KNOWN, null, "conf".repeat(16), "evid".repeat(16), "req");
        when(mapper.findOaEvidenceExclusions(eq(COMPANY), any(Instant.class), any(Instant.class), eq(KNOWN)))
                .thenReturn(List.of(exclusion));
        var result = assemble(mapper, KNOWN);
        var markers = OaEvidenceExclusion.markers(List.of(exclusion), KNOWN);
        assertThat(result.metadata().sourceVersions()).containsAll(markers);
    }

    @Test void partialRecalcDoesNotConsumeExclusionMarkers() {
        var mapper = mock(AttendanceReportCalculationMapper.class);
        setupMinimalMapper(mapper);
        // Current decision: active exclusion (knowledgeAt = KNOWN)
        var currentExclusion = new OaEvidenceExclusionRow("exclusion-1", COMPANY, "other-employee", "oa", "LEAVE:123", "v1",
                "doc", "LEAVE", at("08:30"), at("12:00"), null, KNOWN, null, "conf".repeat(16), "evid".repeat(16), "req");
        when(mapper.findOaEvidenceExclusions(eq(COMPANY), any(Instant.class), any(Instant.class), eq(KNOWN)))
                .thenReturn(List.of(currentExclusion));
        // Baseline: old reversed exclusion (reversed at KNOWN, but baseline was captured before that)
        Instant baselineKnowledge = KNOWN.minusSeconds(86400);
        var oldExclusion = new OaEvidenceExclusionRow("exclusion-1", COMPANY, "other-employee", "oa", "LEAVE:123", "v1",
                "doc", "LEAVE", at("08:30"), at("12:00"), null, baselineKnowledge, KNOWN, "conf".repeat(16), "evid".repeat(16), "req");
        var baseline = new AttendanceReportDependencyPlan.Baseline("prior-pin", baselineKnowledge, "OPEN");
        when(mapper.findDependencyBaseline(COMPANY, PERIOD.atDay(1), PERIOD.plusMonths(1).atDay(1)))
                .thenReturn(baseline);
        var oldMarkers = OaEvidenceExclusion.markers(List.of(oldExclusion), baselineKnowledge);
        when(mapper.findExclusionMarkersForProjection(COMPANY, baseline.projectionVersion()))
                .thenReturn(oldMarkers);
        // Partial recalc: only one employee on one day
        var orchestrator = new FullCalculationEngineOrchestrator(mapper);
        var result = orchestrator.assemble(COMPANY, PERIOD, PeriodState.OPEN, "audit", KNOWN,
                DAY, DAY.plusDays(1), List.of(EMPLOYEE));
        // Should inherit old baseline markers (reversed state), not consume new current markers (active state)
        assertThat(result.metadata().sourceVersions()).containsAll(oldMarkers);
        var newMarkers = OaEvidenceExclusion.markers(List.of(currentExclusion), KNOWN);
        assertThat(oldMarkers).isNotEqualTo(newMarkers); // Prove they differ
        assertThat(result.metadata().sourceVersions()).doesNotContainAnyElementsOf(newMarkers);
    }

    @Test void midMonthAsOfDoesNotConsumeExclusionMarkers() {
        var mapper = mock(AttendanceReportCalculationMapper.class);
        // Mid-month asOf
        Instant midMonth = PERIOD.atDay(15).atStartOfDay(ZONE).toInstant();
        setupMinimalMapper(mapper, midMonth);
        // Current decision: active exclusion (knowledgeAt = midMonth)
        var currentExclusion = new OaEvidenceExclusionRow("exclusion-1", COMPANY, EMPLOYEE, "oa", "LEAVE:123", "v1",
                "doc", "LEAVE", at("08:30"), at("12:00"), null, midMonth, null, "conf".repeat(16), "evid".repeat(16), "req");
        when(mapper.findOaEvidenceExclusions(eq(COMPANY), any(Instant.class), any(Instant.class), eq(midMonth)))
                .thenReturn(List.of(currentExclusion));
        // Baseline: old active exclusion (captured before midMonth)
        Instant baselineKnowledge = midMonth.minusSeconds(86400);
        var oldExclusion = new OaEvidenceExclusionRow("exclusion-1", COMPANY, EMPLOYEE, "oa", "LEAVE:123", "v1",
                "doc", "LEAVE", at("08:30"), at("12:00"), null, baselineKnowledge, null, "conf".repeat(16), "evid".repeat(16), "req");
        var baseline = new AttendanceReportDependencyPlan.Baseline("prior-pin", baselineKnowledge, "OPEN");
        when(mapper.findDependencyBaseline(COMPANY, PERIOD.atDay(1), PERIOD.plusMonths(1).atDay(1)))
                .thenReturn(baseline);
        var oldMarkers = OaEvidenceExclusion.markers(List.of(oldExclusion), baselineKnowledge);
        when(mapper.findExclusionMarkersForProjection(COMPANY, baseline.projectionVersion()))
                .thenReturn(oldMarkers);
        // Mid-month asOf truncates calculationEndExclusive, so isFullMonthRecalc = false
        var result = assemble(mapper, midMonth);
        // Should inherit old baseline markers (older knowledgeAt), not consume current exclusions (newer knowledgeAt)
        assertThat(result.metadata().sourceVersions()).containsAll(oldMarkers);
        var newMarkers = OaEvidenceExclusion.markers(List.of(currentExclusion), midMonth);
        assertThat(oldMarkers).isNotEqualTo(newMarkers); // Prove they differ
        assertThat(result.metadata().sourceVersions()).doesNotContainAnyElementsOf(newMarkers);
    }

    @Test void partialRecalcInheritsBaselineMarkersEvenWhenV74IsEmpty() {
        var mapper = mock(AttendanceReportCalculationMapper.class);
        setupMinimalMapper(mapper);
        // V74 is completely empty (no current recognitions or exclusions)
        when(mapper.findOaOvertimeRecognitions(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), eq(KNOWN)))
                .thenReturn(List.of());
        when(mapper.findOaEvidenceExclusions(eq(COMPANY), any(Instant.class), any(Instant.class), eq(KNOWN)))
                .thenReturn(List.of());
        // But baseline has old markers from a previous full-month calculation
        Instant baselineKnowledge = KNOWN.minusSeconds(172800); // 2 days ago
        var oldExclusion = new OaEvidenceExclusionRow("old-exclusion", COMPANY, EMPLOYEE, "oa", "LEAVE:456", "v1",
                "doc-old", "LEAVE", at("09:00"), at("17:00"), null, baselineKnowledge, null, "old-conf".repeat(16).substring(0, 64), "old-evid".repeat(16).substring(0, 64), "req-old");
        var baseline = new AttendanceReportDependencyPlan.Baseline("old-pin", baselineKnowledge, "OPEN");
        when(mapper.findDependencyBaseline(COMPANY, PERIOD.atDay(1), PERIOD.plusMonths(1).atDay(1)))
                .thenReturn(baseline);
        var oldMarkers = OaEvidenceExclusion.markers(List.of(oldExclusion), baselineKnowledge);
        when(mapper.findExclusionMarkersForProjection(COMPANY, baseline.projectionVersion()))
                .thenReturn(oldMarkers);
        // Partial recalc: one employee on one day
        var orchestrator = new FullCalculationEngineOrchestrator(mapper);
        var result = orchestrator.assemble(COMPANY, PERIOD, PeriodState.OPEN, "audit", KNOWN,
                DAY, DAY.plusDays(1), List.of(EMPLOYEE));
        // Must inherit old baseline markers even though V74 is completely empty
        assertThat(result.metadata().sourceVersions()).containsAll(oldMarkers);
    }

    @Test void exclusionMarkerFitsWithin128CharacterLimit() {
        // VerifiedProjectionMetadata.sourceVersion has max length 128
        // Format: DECISION.EXCLUSION:<UUID-36>:<SHA256-64>
        var exclusion = new OaEvidenceExclusionRow("550e8400-e29b-41d4-a716-446655440000", COMPANY, EMPLOYEE, "oa", "LEAVE:123", "v1",
                "doc", "LEAVE", at("08:30"), at("12:00"), null, KNOWN, null, "conf".repeat(16), "evid".repeat(16), "req");
        var markers = OaEvidenceExclusion.markers(List.of(exclusion), KNOWN);
        assertThat(markers).hasSize(1);
        var marker = markers.get(0);
        assertThat(marker.length()).as("Marker must fit in sourceVersion column (128 chars)").isLessThanOrEqualTo(128);
        // Format verification: DECISION.EXCLUSION:<UUID-36>:<64-hex>
        assertThat(marker).startsWith("DECISION.EXCLUSION:550e8400-e29b-41d4-a716-446655440000:");
        var parts = marker.substring("DECISION.EXCLUSION:".length()).split(":");
        assertThat(parts).hasSize(2);
        assertThat(parts[0]).as("exclusionId").isEqualTo("550e8400-e29b-41d4-a716-446655440000");
        assertThat(parts[1]).as("SHA256 hex").hasSize(64).matches("[0-9a-f]{64}");
    }



    private void setupMinimalMapper(AttendanceReportCalculationMapper mapper) {
        setupMinimalMapper(mapper, KNOWN);
    }

    private void setupMinimalMapper(AttendanceReportCalculationMapper mapper, Instant cutoff) {
        when(mapper.findAttendanceSourceVersions(eq(COMPANY), eq(cutoff))).thenReturn(List.of(
                new SourceInputVersionRow("oa", "OA_ATTENDANCE", 1, 1, 1L, "a".repeat(64), cutoff.minusSeconds(3600)),
                new SourceInputVersionRow("deli", "DELI_CLOUD", 1, 1, 1L, "b".repeat(64), cutoff.minusSeconds(3600))));
        when(mapper.findEmployeeIdentityIntervals(eq(COMPANY), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(new EmployeeIdentityIntervalRow(EMPLOYEE, "v1", "SZST0630", "龙隆",
                        "assignment", "org", "org-v1", "现场服务部", PERIOD.atDay(1), null,
                        PERIOD.atDay(1), null, PERIOD.atDay(1), null)));
        // Calendar for whole month
        var calendar = new java.util.ArrayList<CalendarDayRow>();
        var weekdayShifts = new java.util.ArrayList<ShiftSegmentRow>();
        for (LocalDate d = PERIOD.atDay(1); !d.isAfter(PERIOD.atEndOfMonth()); d = d.plusDays(1)) {
            boolean isWeekend = d.getDayOfWeek().getValue() > 5;
            calendar.add(new CalendarDayRow(EMPLOYEE, d, isWeekend ? "WEEKEND" : "WEEKDAY", 1, 1));
            if (!isWeekend) {
                Instant s = d.atTime(8, 30).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant();
                Instant e = d.atTime(18, 0).atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant();
                weekdayShifts.add(new ShiftSegmentRow(EMPLOYEE, d, "work-" + d, s, e,
                        s.minusSeconds(1800), s.plusSeconds(1800), e.minusSeconds(1800), e.plusSeconds(1800)));
            }
        }
        when(mapper.findPublishedCalendarDays(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), any(Instant.class)))
                .thenReturn(calendar);
        when(mapper.findScheduledWorkSegments(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), any(Instant.class)))
                .thenReturn(weekdayShifts);
        when(mapper.findActivatedPunchEvents(eq(COMPANY), any(Instant.class), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());
        when(mapper.findEffectiveOaDocuments(eq(COMPANY), any(Instant.class), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());
        when(mapper.findReportableOaDocuments(eq(COMPANY), any(Instant.class), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());
        when(mapper.findOaOvertimeRecognitions(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), any(Instant.class)))
                .thenReturn(List.of());
        when(mapper.findOaEvidenceExclusions(eq(COMPANY), any(Instant.class), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());
        when(mapper.findTimeAccountSnapshotsForEmployee(eq(COMPANY), any(LocalDate.class), any(LocalDate.class),
                any(Instant.class), eq(EMPLOYEE))).thenReturn(List.of());
        when(mapper.findActivatedPunchEventsForEmployee(eq(COMPANY), any(Instant.class), any(Instant.class),
                any(Instant.class), eq(EMPLOYEE))).thenReturn(List.of());
        when(mapper.findPunchExemptionRoleIntervals(eq(COMPANY), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());
        when(mapper.findStandingPunchExemptionIntervals(eq(COMPANY), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());
        List<AttendancePolicyRow> policies = calendar.stream()
                .map(c -> new AttendancePolicyRow(
                        EMPLOYEE, c.businessDate(),
                        1, 1, true, 15,
                        1, true, 15, 1, false,
                        1, true, 7, "NEXT_DAY_START_AFTER_FULL_DAYS",
                        1, true, 48))
                .toList();
        when(mapper.findAttendancePolicies(eq(COMPANY), any(LocalDate.class), any(LocalDate.class), any(Instant.class)))
                .thenReturn(policies);
    }
}
