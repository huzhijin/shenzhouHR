package com.szsemicon.hr.wave4;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.evidenceingestion.domain.EvidenceLedger;
import com.szsemicon.hr.evidenceingestion.domain.EvidenceLedger.Direction;
import com.szsemicon.hr.evidenceingestion.domain.EvidenceLedger.EvidenceCandidate;
import com.szsemicon.hr.evidenceingestion.domain.EvidenceLedger.IntervalCandidate;
import com.szsemicon.hr.evidenceingestion.domain.EvidenceLedger.Resolution;
import com.szsemicon.hr.evidenceingestion.domain.EvidenceLedger.SourceType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class Wave4DomainContractTest {

    @Test
    void canonicalFingerprintIsStableAndSensitiveToTheSecondAndDirection() {
        String first = EvidenceLedger.stableFingerprint(
                "legal-1", "location-1", "device-1", "person-1",
                Instant.parse("2026-07-28T01:00:00Z"), Direction.IN);
        assertThat(EvidenceLedger.stableFingerprint(
                "legal-1", "location-1", "device-1", "person-1",
                Instant.parse("2026-07-28T01:00:00Z"), Direction.IN))
                .isEqualTo(first);
        assertThat(EvidenceLedger.stableFingerprint(
                "legal-1", "location-1", "device-1", "person-1",
                Instant.parse("2026-07-28T01:00:01Z"), Direction.IN))
                .isNotEqualTo(first);
        assertThat(EvidenceLedger.stableFingerprint(
                "legal-1", "location-1", "device-1", "person-1",
                Instant.parse("2026-07-28T01:00:00Z"), Direction.OUT))
                .isNotEqualTo(first);
    }

    @Test
    void exactCrossSourceCandidatesMergeOrderIndependently() {
        EvidenceCandidate excel = candidate(
                "raw-excel", SourceType.DEVICE_EXCEL, "2026-07-28T01:00:00Z");
        EvidenceCandidate deli = candidate(
                "raw-deli", SourceType.DELI_CLOUD, "2026-07-28T01:00:00Z");
        assertThat(EvidenceLedger.classify(List.of(excel, deli), 60))
                .isEqualTo(EvidenceLedger.classify(List.of(deli, excel), 60))
                .satisfies(result -> {
                    assertThat(result.activeEventCount()).isEqualTo(1);
                    assertThat(result.rawFactIds())
                            .containsExactly("raw-deli", "raw-excel");
                });
    }

    @Test
    void nearDuplicateBoundariesAreZeroOneOrN() {
        EvidenceCandidate anchor = candidate(
                "raw-1", SourceType.DEVICE_EXCEL, "2026-07-28T01:00:00Z");
        assertThat(EvidenceLedger.classify(
                List.of(anchor, candidate(
                        "raw-0", SourceType.DELI_CLOUD, "2026-07-28T01:00:00Z")),
                60).activeEventCount()).isEqualTo(1);
        for (int seconds : List.of(1, 60)) {
            var result = EvidenceLedger.classify(
                    List.of(anchor, candidate(
                            "raw-" + seconds,
                            SourceType.DELI_CLOUD,
                            "2026-07-28T01:00:%02dZ".formatted(seconds))),
                    60);
            assertThat(result.pendingReview()).isTrue();
            assertThat(result.activeEventCount()).isZero();
            assertThat(result.resolve(Resolution.SAME_FACT).activeEventCount()).isEqualTo(1);
            assertThat(result.resolve(Resolution.DISTINCT_FACTS).activeEventCount())
                    .isEqualTo(2);
        }
        assertThat(EvidenceLedger.classify(
                List.of(anchor, candidate(
                        "raw-61", SourceType.DELI_CLOUD, "2026-07-28T01:01:01Z")),
                60).activeEventCount()).isEqualTo(2);
    }

    @Test
    void intervalSlicingIsHalfOpenDeterministicAndConflictsAtSamePriority() {
        var leave = new IntervalCandidate(
                "leave", Instant.parse("2026-07-28T01:00:00Z"),
                Instant.parse("2026-07-28T04:00:00Z"), 3, "LEAVE");
        var outing = new IntervalCandidate(
                "outing", Instant.parse("2026-07-28T02:00:00Z"),
                Instant.parse("2026-07-28T03:00:00Z"), 3, "OUTING");
        var slices = EvidenceLedger.sliceIntervals(List.of(leave, outing));
        assertThat(slices).extracting(EvidenceLedger.IntervalSlice::start)
                .containsExactly(
                        Instant.parse("2026-07-28T01:00:00Z"),
                        Instant.parse("2026-07-28T02:00:00Z"),
                        Instant.parse("2026-07-28T03:00:00Z"));
        assertThat(slices.get(1).status()).isEqualTo("EVIDENCE_CONFLICT");
        assertThat(slices.get(1).winnerId()).isNull();
        assertThat(EvidenceLedger.sliceIntervals(List.of(outing, leave)))
                .isEqualTo(slices);
    }

    @Test
    void invalidIntervalsFailClosed() {
        assertThatThrownBy(() -> EvidenceLedger.sliceIntervals(List.of(
                new IntervalCandidate(
                        "bad", Instant.parse("2026-07-28T02:00:00Z"),
                        Instant.parse("2026-07-28T02:00:00Z"), 3, "LEAVE"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("half-open");
    }

    private static EvidenceCandidate candidate(
            String rawId, SourceType source, String instant) {
        return new EvidenceCandidate(
                rawId, source, "legal-1", "employee-1",
                Instant.parse(instant), Direction.IN);
    }
}
