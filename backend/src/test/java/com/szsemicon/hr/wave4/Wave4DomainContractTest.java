package com.szsemicon.hr.wave4;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.evidenceingestion.domain.EvidenceLedger;
import com.szsemicon.hr.evidenceingestion.domain.EvidenceLedger.Direction;
import com.szsemicon.hr.evidenceingestion.domain.EvidenceLedger.EvidenceCandidate;
import com.szsemicon.hr.evidenceingestion.domain.EvidenceLedger.IntervalCandidate;
import com.szsemicon.hr.evidenceingestion.domain.EvidenceLedger.Resolution;
import com.szsemicon.hr.evidenceingestion.domain.EvidenceLedger.SourceType;
import com.szsemicon.hr.evidenceingestion.domain.EvidenceResolutionPolicy;
import com.szsemicon.hr.evidenceingestion.domain.SourcePageCommitPolicy;
import com.szsemicon.hr.evidenceingestion.port.EmployeeEmploymentResolverPort;
import com.szsemicon.hr.evidenceingestion.port.EmployeeEmploymentResolverPort.ConfirmedBindingKind;
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
                "raw-anchor", SourceType.DEVICE_EXCEL, "2026-07-28T01:00:00Z");
        assertThat(EvidenceLedger.classify(
                List.of(anchor, candidate(
                        "raw-0", SourceType.DELI_CLOUD, "2026-07-28T01:00:00Z")),
                60).activeEventCount()).isEqualTo(1);
        for (int seconds : List.of(1, 60)) {
            var result = EvidenceLedger.classify(
                    List.of(anchor, candidate(
                            "raw-" + seconds,
                            SourceType.DELI_CLOUD,
                            Instant.parse("2026-07-28T01:00:00Z")
                                    .plusSeconds(seconds))),
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

    @Test
    void employeeNumberWinsAndZeroOrMultipleMatchesFailClosed() {
        EmployeeEmploymentResolverPort resolver = new EmployeeEmploymentResolverPort() {
            @Override
            public List<Resolution> resolveByEmployeeNumber(
                    String legalEntityId,
                    String employeeNumber,
                    Instant at) {
                return switch (employeeNumber) {
                    case "ONE" -> List.of(resolution("employee-number"));
                    case "MANY" -> List.of(
                            resolution("employee-b"),
                            resolution("employee-a"));
                    default -> List.of();
                };
            }

            @Override
            public List<Resolution> resolveByConfirmedBinding(
                    String legalEntityId,
                    String locationId,
                    String deviceId,
                    ConfirmedBindingKind bindingKind,
                    String externalPersonRef,
                    Instant at) {
                return "BOUND".equals(externalPersonRef)
                        ? List.of(resolution("employee-binding"))
                        : List.of();
            }
        };

        assertThat(EvidenceResolutionPolicy.resolve(
                resolver, "legal-1", "ONE", "location-1",
                "device-1", "BOUND", ConfirmedBindingKind.DELI_EXT_ID,
                Instant.EPOCH).reason())
                .isEqualTo("EMPLOYEE_NUMBER");
        assertThat(EvidenceResolutionPolicy.resolve(
                resolver, "legal-1", "MANY", "location-1",
                "device-1", "BOUND", ConfirmedBindingKind.DELI_EXT_ID,
                Instant.EPOCH).status())
                .isEqualTo(EvidenceResolutionPolicy.MatchStatus.AMBIGUOUS);
        assertThat(EvidenceResolutionPolicy.resolve(
                resolver, "legal-1", "NONE", "location-1",
                "device-1", "BOUND", ConfirmedBindingKind.DELI_EXT_ID,
                Instant.EPOCH).reason())
                .isEqualTo("CONFIRMED_BINDING");
        assertThat(EvidenceResolutionPolicy.resolve(
                resolver, "legal-1", "NONE", "location-1",
                "device-1", "NONE", ConfirmedBindingKind.DELI_EXT_ID,
                Instant.EPOCH).status())
                .isEqualTo(EvidenceResolutionPolicy.MatchStatus.UNMATCHED);
    }

    @Test
    void onlyACompleteCommittedPageCanAdvanceItsWatermark() {
        String digest = "a".repeat(64);
        var quarantined = SourcePageCommitPolicy.decide(
                new SourcePageCommitPolicy.PageOutcome(
                        "cursor-1", "cursor-2", 3, 2, 1, digest,
                        true, true, true));
        assertThat(quarantined.commitPage()).isTrue();
        assertThat(quarantined.advanceWatermark()).isTrue();
        assertThat(quarantined.reason())
                .isEqualTo("COMPLETE_PAGE_WITH_QUARANTINE");

        for (var failed : List.of(
                new SourcePageCommitPolicy.PageOutcome(
                        "cursor-1", "cursor-2", 3, 3, 0, digest,
                        false, true, true),
                new SourcePageCommitPolicy.PageOutcome(
                        "cursor-1", "cursor-2", 3, 3, 0, digest,
                        true, false, true),
                new SourcePageCommitPolicy.PageOutcome(
                        "cursor-1", "cursor-2", 3, 3, 0, digest,
                        true, true, false),
                new SourcePageCommitPolicy.PageOutcome(
                        "cursor-1", "cursor-1", 3, 3, 0, digest,
                        true, true, true))) {
            assertThat(SourcePageCommitPolicy.decide(failed).advanceWatermark())
                    .isFalse();
        }
    }

    private static EmployeeEmploymentResolverPort.Resolution resolution(
            String employeeId) {
        return new EmployeeEmploymentResolverPort.Resolution(
                employeeId,
                employeeId + "-employment",
                "a".repeat(64));
    }

    private static EvidenceCandidate candidate(
            String rawId, SourceType source, String instant) {
        return candidate(rawId, source, Instant.parse(instant));
    }

    private static EvidenceCandidate candidate(
            String rawId, SourceType source, Instant instant) {
        return new EvidenceCandidate(
                rawId, source, "legal-1", "employee-1",
                instant, Direction.IN);
    }
}
