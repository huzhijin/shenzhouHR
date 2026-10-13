package com.szsemicon.hr.evidenceingestion.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MissingOaNoncontributingScopeProofTest {
    private static final Instant AT = Instant.parse("2026-10-06T00:00:00Z");
    private static final String KEY = "OVERTIME:old";
    private static final EvidenceRows.MissingOaKeyScopeRow HISTORY = new EvidenceRows.MissingOaKeyScopeRow(
            KEY, "raw", "OA_DOCUMENT", "normalized", "VALID", "OA_INTERVAL", "doc", "OVERTIME",
            true, "MATCHED", "employee", "company", "SZST001", "SZST001", true);

    @Test
    void sqlEligibilityUnknownCannotBeInferredFromNullContextFields() {
        var latest = latest("doc", null);
        assertThat(prove(List.of(HISTORY), List.of(latest))).isEmpty();
        assertThat(prove(List.of(HISTORY), List.of(latest("doc", true)))).isEmpty();
        assertThat(prove(List.of(HISTORY), List.of(latest("doc", false)))).containsExactly(KEY);
    }

    @Test
    void latestMustBelongToCompleteHistoryAndBeUnique() {
        assertThat(prove(List.of(HISTORY), List.of(latest("different", false)))).isEmpty();
        assertThat(prove(List.of(HISTORY), List.of(latest("doc", false), latest("doc", false)))).isEmpty();
        assertThat(prove(List.of(), List.of(latest("doc", false)))).isEmpty();
        assertThat(prove(List.of(HISTORY), null)).isEmpty();
    }

    @Test
    void noIdentityCatalogueOrEmptyEmployeeScopeNeverProvesTargetEvidenceSafe() {
        assertThat(MissingOaReportScopeProof.provenNoncontributingEmployeeKeys("company", List.of("employee"),
                List.of(KEY), List.of(HISTORY), List.of(), List.of(latest("doc", false)), AT)).isEmpty();
        assertThat(MissingOaReportScopeProof.provenNoncontributingEmployeeKeys("company", List.of(),
                List.of(KEY), List.of(HISTORY), List.of("SZST001"), List.of(latest("doc", false)), AT)).isEmpty();
        assertThat(MissingOaReportScopeProof.provenNoncontributingEmployeeKeys("company", List.of("employee"),
                List.of(KEY), List.of(HISTORY), List.of("unrelated"), List.of(latest("doc", false)), AT)).isEmpty();
    }

    private static java.util.Set<String> prove(List<EvidenceRows.MissingOaKeyScopeRow> history,
            List<EvidenceRows.MissingOaCurrentEligibilityRow> rows) {
        return MissingOaReportScopeProof.provenNoncontributingEmployeeKeys("company", List.of("employee"),
                List.of(KEY), history, List.of("SZST001"), rows, AT);
    }

    private static EvidenceRows.MissingOaCurrentEligibilityRow latest(String doc, Boolean contextEligible) {
        return new EvidenceRows.MissingOaCurrentEligibilityRow(KEY, doc, "OVERTIME", AT,
                null, null, false, false, false, false, contextEligible);
    }
}
