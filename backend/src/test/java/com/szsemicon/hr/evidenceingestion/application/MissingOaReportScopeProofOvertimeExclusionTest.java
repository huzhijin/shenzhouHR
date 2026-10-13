package com.szsemicon.hr.evidenceingestion.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MissingOaReportScopeProofOvertimeExclusionTest {

    @Test
    void provenNoncontributingEmployeeKeys_acceptsOvertimeWithExplicitExclusion() {
        String companyId = "c1";
        String employeeId = "e1";
        String key = "OVERTIME:123";
        Instant dataAsOf = Instant.parse("2026-10-12T10:00:00Z");

        var history = List.of(
                new EvidenceRows.MissingOaKeyScopeRow(
                        key, "raw1", "OA_DOCUMENT", "norm1", "VALID", "OA_INTERVAL",
                        "doc1", "OVERTIME", true, "MATCHED", employeeId, companyId,
                        "EMP001", "EMP001", false
                )
        );

        var latest = List.of(
                new EvidenceRows.MissingOaCurrentEligibilityRow(
                        key, "doc1", "OVERTIME",
                        Instant.parse("2026-10-01T00:00:00Z"),
                        null, null,
                        false, false,
                        true, // hasApplicableReportExclusion = true (NEW)
                        false,
                        true // hasConsumableOvertimeContext = true
                )
        );

        Set<String> proven = MissingOaReportScopeProof.provenNoncontributingEmployeeKeys(
                companyId, Set.of(employeeId), Set.of(key),
                history, List.of("EMP001"), latest, dataAsOf
        );

        assertThat(proven).contains(key);
    }

    @Test
    void provenNoncontributingEmployeeKeys_acceptsOvertimeWithNoConsumableContext() {
        String companyId = "c1";
        String employeeId = "e1";
        String key = "OVERTIME:456";
        Instant dataAsOf = Instant.parse("2026-10-12T10:00:00Z");

        var history = List.of(
                new EvidenceRows.MissingOaKeyScopeRow(
                        key, "raw2", "OA_DOCUMENT", "norm2", "VALID", "OA_INTERVAL",
                        "doc2", "OVERTIME", true, "MATCHED", employeeId, companyId,
                        "EMP002", "EMP002", false
                )
        );

        var latest = List.of(
                new EvidenceRows.MissingOaCurrentEligibilityRow(
                        key, "doc2", "OVERTIME",
                        Instant.parse("2026-10-01T00:00:00Z"),
                        null, null,
                        false, false,
                        false, // hasApplicableReportExclusion = false
                        false,
                        false // hasConsumableOvertimeContext = false
                )
        );

        Set<String> proven = MissingOaReportScopeProof.provenNoncontributingEmployeeKeys(
                companyId, Set.of(employeeId), Set.of(key),
                history, List.of("EMP002"), latest, dataAsOf
        );

        assertThat(proven).contains(key);
    }

    @Test
    void provenNoncontributingEmployeeKeys_rejectsOvertimeWithContextAndNoExclusion() {
        String companyId = "c1";
        String employeeId = "e1";
        String key = "OVERTIME:789";
        Instant dataAsOf = Instant.parse("2026-10-12T10:00:00Z");

        var history = List.of(
                new EvidenceRows.MissingOaKeyScopeRow(
                        key, "raw3", "OA_DOCUMENT", "norm3", "VALID", "OA_INTERVAL",
                        "doc3", "OVERTIME", true, "MATCHED", employeeId, companyId,
                        "EMP003", "EMP003", false
                )
        );

        var latest = List.of(
                new EvidenceRows.MissingOaCurrentEligibilityRow(
                        key, "doc3", "OVERTIME",
                        Instant.parse("2026-10-01T00:00:00Z"),
                        null, null,
                        false, false,
                        false, // hasApplicableReportExclusion = false
                        false,
                        true // hasConsumableOvertimeContext = true
                )
        );

        Set<String> proven = MissingOaReportScopeProof.provenNoncontributingEmployeeKeys(
                companyId, Set.of(employeeId), Set.of(key),
                history, List.of("EMP003"), latest, dataAsOf
        );

        assertThat(proven).isEmpty();
    }

    @Test
    void provenNoncontributingEmployeeKeys_punchCorrectionStillUsesExclusionOnly() {
        String companyId = "c1";
        String employeeId = "e1";
        String key = "PUNCH_CORRECTION:999";
        Instant dataAsOf = Instant.parse("2026-10-12T10:00:00Z");

        var history = List.of(
                new EvidenceRows.MissingOaKeyScopeRow(
                        key, "raw4", "OA_DOCUMENT", "norm4", "VALID", "OA_INTERVAL",
                        "doc4", "PUNCH_CORRECTION", true, "MATCHED", employeeId, companyId,
                        "EMP004", "EMP004", false
                )
        );

        var latest = List.of(
                new EvidenceRows.MissingOaCurrentEligibilityRow(
                        key, "doc4", "PUNCH_CORRECTION",
                        Instant.parse("2026-10-01T00:00:00Z"),
                        null, null,
                        false, false,
                        true, // hasApplicableReportExclusion = true
                        false,
                        null // not applicable for PC
                )
        );

        Set<String> proven = MissingOaReportScopeProof.provenNoncontributingEmployeeKeys(
                companyId, Set.of(employeeId), Set.of(key),
                history, List.of("EMP004"), latest, dataAsOf
        );

        assertThat(proven).contains(key);
    }

    @Test
    void provenNoncontributingEmployeeKeys_punchCorrectionWithoutExclusionRejected() {
        String companyId = "c1";
        String employeeId = "e1";
        String key = "PUNCH_CORRECTION:888";
        Instant dataAsOf = Instant.parse("2026-10-12T10:00:00Z");

        var history = List.of(
                new EvidenceRows.MissingOaKeyScopeRow(
                        key, "raw5", "OA_DOCUMENT", "norm5", "VALID", "OA_INTERVAL",
                        "doc5", "PUNCH_CORRECTION", true, "MATCHED", employeeId, companyId,
                        "EMP005", "EMP005", false
                )
        );

        var latest = List.of(
                new EvidenceRows.MissingOaCurrentEligibilityRow(
                        key, "doc5", "PUNCH_CORRECTION",
                        Instant.parse("2026-10-01T00:00:00Z"),
                        null, null,
                        false, false,
                        false, // hasApplicableReportExclusion = false
                        false,
                        null
                )
        );

        Set<String> proven = MissingOaReportScopeProof.provenNoncontributingEmployeeKeys(
                companyId, Set.of(employeeId), Set.of(key),
                history, List.of("EMP005"), latest, dataAsOf
        );

        assertThat(proven).isEmpty();
    }
}
