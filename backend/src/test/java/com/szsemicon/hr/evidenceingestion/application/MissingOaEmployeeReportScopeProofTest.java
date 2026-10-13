package com.szsemicon.hr.evidenceingestion.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MissingOaEmployeeReportScopeProofTest {
    private static final String COMPANY = "company";
    private static final String TARGET = "selected-employee";
    private static final String KEY = "OVERTIME:missing";

    @ParameterizedTest
    @ValueSource(strings = {"OVERTIME", "PUNCH_CORRECTION", "LEAVE"})
    void sameCompanyCoworkerCanBeUnrelatedOnlyToAnExplicitEmployeeWindow(String type) {
        String key = type + ":missing";
        var rows = List.of(row(key, "raw-old", "coworker", COMPANY, "OTHER-001", "OTHER-OLD", false),
                row(key, "raw-new", "coworker", COMPANY, "OTHER-001", "OTHER-OLD", false));
        assertThat(MissingOaReportScopeProof.provenUnrelatedEmployeeKeys(
                COMPANY, List.of(TARGET), List.of(key), rows, List.of("TARGET-001", "TARGET-OLD")))
                .containsExactly(key);
        assertThat(MissingOaReportScopeProof.provenUnrelatedKeys(
                COMPANY, List.of(key), rows, List.of("TARGET-001", "OTHER-001"))).isEmpty();
    }

    @Test
    void everyExplicitTargetIdentityIsProtectedEvenWhenItsNumberChanged() {
        var rows = List.of(row(KEY, "old", "another-selected", COMPANY, "OLD-NUMBER", "OLDER", false));
        assertThat(MissingOaReportScopeProof.provenUnrelatedEmployeeKeys(COMPANY,
                List.of(TARGET, "another-selected"), List.of(KEY), rows, List.of("NEW-NUMBER"))).isEmpty();
    }

    @Test
    void anOldTargetMatchOrChangingForeignMatchRemainsStrict() {
        assertThat(proven(List.of(
                row(KEY, "old", TARGET, COMPANY, "TARGET-OLD", "TARGET-OLD", false),
                row(KEY, "new", "coworker", COMPANY, "OTHER", "OTHER", false)))).isEmpty();
        assertThat(proven(List.of(
                row(KEY, "old", "coworker", COMPANY, "OTHER", "OTHER", false),
                row(KEY, "new", "different-coworker", COMPANY, "DIFFERENT", "DIFFERENT", false)))).isEmpty();
        assertThat(proven(List.of(
                row(KEY, "old", "coworker", COMPANY, "OTHER", "OTHER", false),
                row(KEY, "new", "coworker", "different-company", "OTHER", "OTHER", false)))).isEmpty();
    }

    @Test
    void historicalNumberAndConfiguredAliasesProtectTheTarget() {
        for (var pair : List.of(List.of("SZT0687", "SZST0687"), List.of("SZST0709", "SZT0709"),
                List.of(" TARGET-001 ", "TARGET-001"))) {
            var rows = List.of(row(KEY, "old", "coworker", COMPANY, "OTHER", pair.getFirst(), false));
            assertThat(MissingOaReportScopeProof.provenUnrelatedEmployeeKeys(COMPANY,
                    List.of(TARGET), List.of(KEY), rows, List.of(pair.getLast()))).isEmpty();
        }
    }

    @Test
    void anyHistoricalReportOrLinkedEvidenceDependencyRemainsStrict() {
        assertThat(proven(List.of(row(KEY, "old", "coworker", COMPANY, "OTHER", "OTHER", true)))).isEmpty();
        assertThat(proven(List.of(row(KEY, "old", "coworker", COMPANY, "OTHER", "OTHER", null)))).isEmpty();
    }

    @Test
    void emptyOrUnresolvedScopeDoesNotFallBackToACompanyWideProof() {
        var rows = List.of(row(KEY, "old", "coworker", "outside-company", "OTHER", "OTHER", false));
        assertThat(MissingOaReportScopeProof.provenUnrelatedEmployeeKeys(
                COMPANY, null, List.of(KEY), rows, List.of("TARGET-001"))).isEmpty();
        assertThat(MissingOaReportScopeProof.provenUnrelatedEmployeeKeys(
                COMPANY, List.of(), List.of(KEY), rows, List.of("TARGET-001"))).isEmpty();
        assertThat(MissingOaReportScopeProof.provenUnrelatedEmployeeKeys(
                COMPANY, Arrays.asList(TARGET, null), List.of(KEY), rows, List.of("TARGET-001"))).isEmpty();
        assertThat(MissingOaReportScopeProof.provenUnrelatedEmployeeKeys(
                COMPANY, List.of(TARGET, " "), List.of(KEY), rows, List.of("TARGET-001"))).isEmpty();
        assertThat(MissingOaReportScopeProof.provenUnrelatedEmployeeKeys(
                COMPANY, List.of(TARGET), List.of(KEY), rows, List.of())).isEmpty();
        assertThat(MissingOaReportScopeProof.provenUnrelatedEmployeeKeys(
                COMPANY, List.of(TARGET), List.of(KEY), rows, Arrays.asList("TARGET-001", null))).isEmpty();
    }

    @Test
    void incompleteHistoryCannotBeHiddenByACompleteLaterRow() {
        var valid = row(KEY, "new", "coworker", COMPANY, "OTHER", "OTHER", false);
        var unresolved = new EvidenceRows.MissingOaKeyScopeRow(KEY, "raw-only", "OA_DOCUMENT",
                null, null, null, null, null, false, null, null, null, null, null, false);
        assertThat(proven(List.of(unresolved, valid))).isEmpty();
        assertThat(MissingOaReportScopeProof.provenUnrelatedEmployeeKeys(COMPANY, List.of(TARGET),
                List.of(KEY, "OVERTIME:no-history"), List.of(valid), List.of("TARGET-001")))
                .containsExactly(KEY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"LEAVE_REVOCATION", "BUSINESS_TRIP", "OUTING", "PUNCH_EXEMPT", "UNKNOWN"})
    void otherFamiliesAreNeverOmittedByEmployeeIdentity(String type) {
        String key = type + ":missing";
        assertThat(MissingOaReportScopeProof.provenUnrelatedEmployeeKeys(COMPANY, List.of(TARGET),
                List.of(key), List.of(row(key, "old", "coworker", COMPANY, "OTHER", "OTHER", false)),
                List.of("TARGET-001"))).isEmpty();
    }

    @Test
    void missingLeaveStillCannotBeOmittedForAnyCompanyWideRefresh() {
        String key = "LEAVE:outside";
        var rows = List.of(row(key, "old", "other-employee", "other-company", "OTHER", "OTHER", false));
        assertThat(MissingOaReportScopeProof.provenUnrelatedEmployeeKeys(COMPANY, List.of(TARGET),
                List.of(key), rows, List.of("TARGET-001"))).containsExactly(key);
        assertThat(MissingOaReportScopeProof.provenUnrelatedKeys(COMPANY,
                List.of(key), rows, List.of("TARGET-001"))).isEmpty();
    }

    @Test
    void missingLeaveCannotHideTargetHistoryAmbiguousOwnersAliasesOrLinkedEvidence() {
        String key = "LEAVE:missing";
        var safe = row(key, "new", "coworker", COMPANY, "OTHER", "OTHER", false);
        var unresolved = new EvidenceRows.MissingOaKeyScopeRow(key, "raw-only", "OA_DOCUMENT",
                null, null, null, null, null, false, null, null, null, null, null, false);
        for (var unsafe : List.of(
                row(key, "old", TARGET, COMPANY, "OLD", "OLD", false),
                row(key, "old", "different-owner", COMPANY, "DIFFERENT", "DIFFERENT", false),
                row(key, "old", "coworker", COMPANY, "OTHER", "TARGET-001", false),
                row(key, "old", "coworker", COMPANY, "OTHER", "OTHER", true),
                unresolved)) {
            assertThat(MissingOaReportScopeProof.provenUnrelatedEmployeeKeys(COMPANY, List.of(TARGET),
                    List.of(key), List.of(unsafe, safe), List.of("TARGET-001"))).isEmpty();
        }
        assertThat(MissingOaReportScopeProof.provenUnrelatedEmployeeKeys(COMPANY, List.of(TARGET),
                List.of(key), List.of(row(key, "alias", "coworker", COMPANY, "SZT0687", "SZT0687", false)),
                List.of("SZST0687"))).isEmpty();
    }

    private static Set<String> proven(List<EvidenceRows.MissingOaKeyScopeRow> rows) {
        return MissingOaReportScopeProof.provenUnrelatedEmployeeKeys(COMPANY, List.of(TARGET),
                List.of(KEY), rows, List.of("TARGET-001"));
    }

    private static EvidenceRows.MissingOaKeyScopeRow row(String key, String raw, String employee,
            String company, String number, String historicalNumber, Boolean dependency) {
        return new EvidenceRows.MissingOaKeyScopeRow(key, raw, "OA_DOCUMENT", "norm-" + raw,
                "VALID", "OA_INTERVAL", "oa-" + raw, key.substring(0, key.indexOf(':')), true,
                "MATCHED", employee, company, number, historicalNumber, dependency);
    }
}
