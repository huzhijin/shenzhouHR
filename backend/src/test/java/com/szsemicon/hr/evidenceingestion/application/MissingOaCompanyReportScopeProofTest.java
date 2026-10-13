package com.szsemicon.hr.evidenceingestion.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MissingOaCompanyReportScopeProofTest {
    private static final Instant AT = Instant.parse("2026-10-06T00:00:00Z");
    private static final String KEY = "OVERTIME:old";
    private static final EvidenceRows.MissingOaKeyScopeRow HISTORY = new EvidenceRows.MissingOaKeyScopeRow(
            KEY, "raw", "OA_DOCUMENT", "normalized", "VALID", "OA_INTERVAL", "doc", "OVERTIME",
            true, "MATCHED", "employee", "company", "SZST001", "SZST001", true);

    @Test
    void nullEligibilityIsNotAFalseReaderDecision() {
        assertThat(prove(List.of(HISTORY), List.of(current(null, false)))).isEmpty();
        assertThat(prove(List.of(HISTORY), List.of(current(true, null)))).isEmpty();
        assertThat(prove(List.of(HISTORY), List.of(current(false, null)))).containsExactly(KEY);
        assertThat(prove(List.of(HISTORY), List.of(current(true, false)))).containsExactly(KEY);
        assertThat(prove(List.of(HISTORY), List.of(current(true, true)))).isEmpty();
    }

    @Test
    void missingDuplicateOrUnresolvedHistoryDoesNotBecomeCompanyProof() {
        assertThat(prove(List.of(), List.of(current(false, true)))).isEmpty();
        assertThat(prove(List.of(HISTORY), List.of())).isEmpty();
        assertThat(prove(List.of(HISTORY), List.of(current(false, true), current(false, true)))).isEmpty();
        var unresolved = new EvidenceRows.MissingOaKeyScopeRow(KEY, "raw-2", "OA_DOCUMENT", null, null,
                null, null, null, false, null, null, null, null, null, false);
        assertThat(prove(List.of(HISTORY, unresolved), List.of(current(false, true)))).isEmpty();
    }

    @Test
    void noCatalogueAndWrongCompanyRemainStrictAndEmployeeEmptyScopeIsNotCompanyScope() {
        assertThat(MissingOaReportScopeProof.provenNoncontributingCompanyKeys("company", List.of(KEY),
                List.of(HISTORY), List.of(), List.of(current(false, true)), AT)).isEmpty();
        assertThat(MissingOaReportScopeProof.provenNoncontributingCompanyKeys("different", List.of(KEY),
                List.of(HISTORY), List.of("SZST001"), List.of(current(false, true)), AT)).isEmpty();
        assertThat(MissingOaReportScopeProof.provenNoncontributingEmployeeKeys("company", List.of(), List.of(KEY),
                List.of(HISTORY), List.of("SZST001"), List.of(current(false, true).readerEligibility()), AT)).isEmpty();
    }

    private static java.util.Set<String> prove(List<EvidenceRows.MissingOaKeyScopeRow> history,
            List<EvidenceRows.MissingOaCompanyCurrentEligibilityRow> current) {
        return MissingOaReportScopeProof.provenNoncontributingCompanyKeys("company", List.of(KEY), history,
                List.of("SZST001"), current, AT);
    }

    private static EvidenceRows.MissingOaCompanyCurrentEligibilityRow current(Boolean status, Boolean context) {
        return new EvidenceRows.MissingOaCompanyCurrentEligibilityRow(KEY, "doc", "OVERTIME", AT,
                "ACTIVATED", "PAID", false, false, false, false, context, status, false, true);
    }
}
