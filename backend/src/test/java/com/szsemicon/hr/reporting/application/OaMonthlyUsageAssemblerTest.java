package com.szsemicon.hr.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.reporting.application.QueryPageRows.OaUsageDocumentRow;
import com.szsemicon.hr.reporting.application.QueryPageRows.LegacyLeaveDayEvidenceRow;
import com.szsemicon.hr.reporting.application.QueryPageRows.MonthlyLeaveUsageRow;
import com.szsemicon.hr.shared.web.ApiProblemException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OaMonthlyUsageAssemblerTest {
    private static final LocalDate SEP = LocalDate.of(2026,9,1), OCT = LocalDate.of(2026,10,1);

    @ParameterizedTest
    @ValueSource(strings = {"ANNUAL", "TIME_OFF"})
    void approvedAmendmentCanMoveActualUsageBeforeOrAfterTheOriginalDate(String kind) {
        for (String actual : List.of("2026-09-15", "2026-09-17")) {
            var moved = row("form", kind, "2026-09-16T05:30:00Z", "2026-09-16T10:00:00Z",270,
                    "{\"" + actual + "\":270}");
            assertThat(aggregate(moved)).containsExactly(new MonthlyLeaveUsageRow("employee",9,270,kind));
            assertThat(OaMonthlyUsageAssembler.aggregate(List.of(moved),LocalDate.of(2026,9,16),LocalDate.of(2026,9,17))).isEmpty();
            LocalDate day = LocalDate.parse(actual);
            assertThat(OaMonthlyUsageAssembler.aggregate(List.of(moved),day,day.plusDays(1)))
                    .containsExactly(new MonthlyLeaveUsageRow("employee",9,270,kind));
            assertThat(moved.startAt()).isEqualTo(Instant.parse("2026-09-16T05:30:00Z"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ANNUAL", "TIME_OFF"})
    void movedAcrossMonthUsageBelongsOnlyToTheActualMonthAndItsOwnPin(String kind) {
        var moved = row("form",kind,"2026-08-31T00:30:00Z","2026-08-31T10:00:00Z",480,"{\"2026-09-01\":480}");
        var august = new OaUsageDocumentRow(moved.documentId(),moved.employeeId(),LocalDate.of(2026,8,1),SEP,
                moved.startAt(),moved.endExclusive(),480,kind,moved.recognizedMinutesByDateJson());
        assertThat(aggregate(moved)).containsExactly(new MonthlyLeaveUsageRow("employee",9,480,kind));
        assertThat(aggregate(august)).isEmpty();
        assertThat(OaMonthlyUsageAssembler.aggregate(List.of(august,moved,moved),LocalDate.of(2026,8,1),OCT))
                .containsExactly(new MonthlyLeaveUsageRow("employee",9,480,kind));
    }

    @Test
    void overtimeOutsideOriginalDatesStillFailsAndActualLeaveSharesStillRequireExactTotal() {
        assertThatThrownBy(() -> aggregate(row("ot","COMPENSATORY_OT","2026-09-16T05:30:00Z","2026-09-16T10:00:00Z",270,"{\"2026-09-15\":270}")))
                .isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> aggregate(row("leave","TIME_OFF","2026-09-16T05:30:00Z","2026-09-16T10:00:00Z",270,"{\"2026-09-15\":269}")))
                .isInstanceOf(ApiProblemException.class);
    }

    @Test
    void august31ThroughSeptember4ApprovedTimeOffSplitsFortyIntoEightAndThirtyTwo() {
        String days = "{\"2026-08-31\":480,\"2026-09-01\":480,\"2026-09-02\":480,\"2026-09-03\":480,\"2026-09-04\":480}";
        var row = row("form", "TIME_OFF", "2026-08-31T01:00:00Z", "2026-09-04T10:00:00Z", 2400, days);
        assertThat(aggregate(row)).containsExactly(new MonthlyLeaveUsageRow("employee",9,1920,"TIME_OFF"));
        var august = new OaUsageDocumentRow(row.documentId(),row.employeeId(),LocalDate.of(2026,8,1),SEP,
                row.startAt(),row.endExclusive(),row.recognizedMinutes(),row.kind(),days);
        assertThat(OaMonthlyUsageAssembler.aggregate(List.of(august,row),LocalDate.of(2026,8,1),OCT))
                .containsExactly(new MonthlyLeaveUsageRow("employee",8,480,"TIME_OFF"),
                        new MonthlyLeaveUsageRow("employee",9,1920,"TIME_OFF"));
    }

    @Test
    void annualLeaveAndCompensatoryOvertimeKeepTheirOwnBusinessDateRules() {
        var annual = row("annual", "ANNUAL", "2026-08-31T00:30:00Z", "2026-09-01T10:00:00Z",960,
                "{\"2026-08-31\":480,\"2026-09-01\":480}");
        // Existing overtime calculation assigns a form to its starting business date.
        var overtime = row("ot", "COMPENSATORY_OT", "2026-08-31T10:00:00Z", "2026-09-01T02:00:00Z",480,
                "{\"2026-08-31\":480}");
        assertThat(aggregate(annual,overtime)).containsExactly(new MonthlyLeaveUsageRow("employee",9,480,"ANNUAL"));
    }

    @Test
    void amendedLeaveUsesCalculatedRemainingSharesNotTheUnchangedOriginalEnd() {
        var row = row("amended", "TIME_OFF", "2026-08-31T00:30:00Z", "2026-09-04T10:00:00Z",960,
                "{\"2026-08-31\":480,\"2026-09-01\":480}");
        assertThat(aggregate(row)).containsExactly(new MonthlyLeaveUsageRow("employee",9,480,"TIME_OFF"));
        assertThat(row.endExclusive()).isEqualTo(Instant.parse("2026-09-04T10:00:00Z"));
    }

    @Test
    void legacySingleMonthRemainsAvailableAndZeroFullCancellationDoesNotRequireShares() {
        var sameMonth = row("legacy", "TIME_OFF", "2026-09-28T00:30:00Z", "2026-09-28T07:00:00Z",300,null);
        var zero = row("cancelled", "TIME_OFF", "2026-08-31T00:30:00Z", "2026-09-04T10:00:00Z",0,null);
        assertThat(aggregate(sameMonth,zero)).containsExactly(new MonthlyLeaveUsageRow("employee",9,300,"TIME_OFF"));
    }

    @Test
    void legacyCrossMonthPositiveTotalMustNotBeGuessedOrSilentlyZeroed() {
        var legacy = row("legacy", "TIME_OFF", "2026-08-31T00:30:00Z", "2026-09-04T10:00:00Z",2400,null);
        assertThatThrownBy(() -> aggregate(legacy)).isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("跨月请假尚缺逐日使用明细");
    }

    @Test
    void duplicateDocumentDayAcrossInputRowsCannotDoubleCount() {
        var row = row("form", "TIME_OFF", "2026-09-28T00:30:00Z", "2026-09-28T07:00:00Z",300,
                "{\"2026-09-28\":300}");
        assertThat(aggregate(row,row)).containsExactly(new MonthlyLeaveUsageRow("employee",9,300,"TIME_OFF"));
    }

    @Test
    void requestedDaysSelectOnlyTheirSharesAndCannotConsumeAnotherMonthsPin() {
        var row = row("form", "TIME_OFF", "2026-09-28T00:30:00Z", "2026-09-29T10:00:00Z",960,
                "{\"2026-09-28\":480,\"2026-09-29\":480}");
        assertThat(OaMonthlyUsageAssembler.aggregate(List.of(row),LocalDate.of(2026,9,29),OCT))
                .containsExactly(new MonthlyLeaveUsageRow("employee",9,480,"TIME_OFF"));
    }

    @ParameterizedTest
    @ValueSource(strings={"{}","[]","{\"2026-09-28\":299}","{\"2026-09-28\":300.0}",
            "{\"2026-09-28\":-1}","{\"2026-09-28\":300,\"2026-09-28\":300}","{\"2026-09-28\":\"300\"}"})
    void invalidOrIncompleteSharesNeverTurnIntoUsage(String json) {
        var row = row("form", "TIME_OFF", "2026-09-28T00:30:00Z", "2026-09-28T07:00:00Z",300,json);
        assertThatThrownBy(() -> aggregate(row)).isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("校验失败");
    }

    @Test
    void canonicalJsonIsDateSortedImmutableAndBalancesExactly() {
        var days = OaRecognizedDayMinutes.validated(Map.of(SEP.plusDays(1),120L,SEP,480L),600);
        assertThat(OaRecognizedDayMinutes.encode(days)).isEqualTo("{\"2026-09-01\":480,\"2026-09-02\":120}");
        assertThatThrownBy(() -> days.put(SEP,0L)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> OaRecognizedDayMinutes.validated(Map.of(SEP,480L),600))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void legacyCompensatoryOvertimeUsesKnownStartDayPolicyAcrossMonths() {
        var september = row("ot", "COMPENSATORY_OT", "2026-08-31T10:00:00Z", "2026-09-02T10:00:00Z",1590,null);
        var august = new OaUsageDocumentRow("ot","employee",LocalDate.of(2026,8,1),SEP,
                september.startAt(),september.endExclusive(),1590,"COMPENSATORY_OT",null);
        assertThat(aggregate(september)).isEmpty();
        assertThat(OaMonthlyUsageAssembler.aggregate(List.of(august,september),LocalDate.of(2026,1,1),LocalDate.of(2027,1,1)))
                .containsExactly(new MonthlyLeaveUsageRow("employee",8,1590,"COMPENSATORY_OT"));
        assertThat(OaMonthlyUsageAssembler.legacyEvidenceDocumentIds(List.of(august,september),SEP,OCT)).isEmpty();
    }

    @Test
    void completeSoleApprovedLegacyDailyFactsRecoverOnlyTheirOwnPinsShare() {
        var september = row("form","TIME_OFF","2026-08-31T00:30:00Z","2026-09-02T10:00:00Z",1440,null);
        var august = new OaUsageDocumentRow("form","employee",LocalDate.of(2026,8,1),SEP,
                september.startAt(),september.endExclusive(),1440,"TIME_OFF",null);
        var evidence = List.of(
                new LegacyLeaveDayEvidenceRow("form","employee",LocalDate.of(2026,8,1),LocalDate.of(2026,8,31),480L,"COMPENSATORY",1),
                day(1,480L,"COMPENSATORY",1),day(2,480L,"COMPENSATORY",1));
        assertThat(OaMonthlyUsageAssembler.aggregate(List.of(august,september),LocalDate.of(2026,1,1),LocalDate.of(2027,1,1),evidence))
                .containsExactly(new MonthlyLeaveUsageRow("employee",8,480,"TIME_OFF"),
                        new MonthlyLeaveUsageRow("employee",9,960,"TIME_OFF"));
        assertThat(OaMonthlyUsageAssembler.legacyEvidenceDocumentIds(List.of(august,september),LocalDate.of(2026,1,1),LocalDate.of(2027,1,1)))
                .containsExactly("form");
    }

    @Test
    void amendedLegacyLeaveUsesActualDailyZeroWithoutExtendingOriginalTime() {
        var document = row("form","TIME_OFF","2026-08-31T00:30:00Z","2026-09-02T10:00:00Z",960,null);
        var actual = List.of(day(1,480L,"COMPENSATORY",1),day(2,0L,null,1));
        assertThat(OaMonthlyUsageAssembler.aggregate(List.of(document),SEP,OCT,actual))
                .containsExactly(new MonthlyLeaveUsageRow("employee",9,480,"TIME_OFF"));
        assertThat(document.endExclusive()).isEqualTo(Instant.parse("2026-09-02T10:00:00Z"));
    }

    @Test
    void pendingOrOtherLeaveCompetingOnTheDayPreventsAttributionEvenIfItsTotalIsZero() {
        var document = row("form","TIME_OFF","2026-08-31T00:30:00Z","2026-09-02T10:00:00Z",1440,null);
        assertRequired(document,List.of(day(1,480L,"COMPENSATORY",2),day(2,480L,"COMPENSATORY",1)));
    }

    @Test
    void incompleteWrongTypeDuplicateAndImpossibleDailyEvidenceNeverBecomesApprovedUsage() {
        var document = row("form","TIME_OFF","2026-08-31T00:30:00Z","2026-09-02T10:00:00Z",1440,null);
        assertRequired(document,List.of(day(1,480L,"COMPENSATORY",1)));
        assertRequired(document,List.of(day(1,480L,"ANNUAL",1),day(2,480L,"COMPENSATORY",1)));
        assertRequired(document,List.of(day(1,480L,null,1),day(2,480L,"COMPENSATORY",1)));
        assertRequired(document,List.of(day(1,480L,"COMPENSATORY",1),day(1,480L,"COMPENSATORY",1),day(2,480L,"COMPENSATORY",1)));
        assertRequired(document,List.of(day(1,-1L,"COMPENSATORY",1),day(2,480L,"COMPENSATORY",1)));
        assertRequired(document,List.of(day(1,1441L,"COMPENSATORY",1),day(2,0L,null,1)));
        assertRequired(document,List.of(day(1,960L,"COMPENSATORY",1),day(2,960L,"COMPENSATORY",1)));
        assertRequired(document,List.of(day(1,480L,"COMPENSATORY",0),day(2,480L,"COMPENSATORY",1)));
    }

    @Test
    void subrangeStillRequiresCompleteOriginalCoverageInsideItsOwnPin() {
        var document = row("form","TIME_OFF","2026-08-31T00:30:00Z","2026-09-02T10:00:00Z",1440,null);
        assertThatThrownBy(() -> OaMonthlyUsageAssembler.aggregate(List.of(document),SEP.plusDays(1),OCT,
                List.of(day(2,480L,"COMPENSATORY",1)))).isInstanceOf(ApiProblemException.class);
        assertThat(OaMonthlyUsageAssembler.aggregate(List.of(document),SEP.plusDays(1),OCT,
                List.of(day(1,480L,"COMPENSATORY",1),day(2,480L,"COMPENSATORY",1))))
                .containsExactly(new MonthlyLeaveUsageRow("employee",9,480,"TIME_OFF"));
    }

    private static LegacyLeaveDayEvidenceRow day(int day,long minutes,String type,long count) {
        return new LegacyLeaveDayEvidenceRow("form","employee",SEP,SEP.plusDays(day-1),minutes,type,count);
    }

    private static void assertRequired(OaUsageDocumentRow document,List<LegacyLeaveDayEvidenceRow> evidence) {
        assertThatThrownBy(() -> OaMonthlyUsageAssembler.aggregate(List.of(document),SEP,OCT,evidence))
                .isInstanceOf(ApiProblemException.class).hasMessageContaining("跨月请假尚缺逐日使用明细");
    }

    private static List<MonthlyLeaveUsageRow> aggregate(OaUsageDocumentRow... rows) {
        return OaMonthlyUsageAssembler.aggregate(List.of(rows),SEP,OCT);
    }
    private static OaUsageDocumentRow row(String id,String kind,String start,String end,long minutes,String json) {
        return new OaUsageDocumentRow(id,"employee",SEP,OCT,Instant.parse(start),Instant.parse(end),minutes,kind,json);
    }
}
