package com.szsemicon.hr.attendance.calculation.application;

import com.szsemicon.hr.attendance.calculation.domain.AttendanceCalculationModels.CalculationInputSnapshot;
import com.szsemicon.hr.attendance.calculation.domain.AttendancePeriodModels.PeriodStateSnapshot;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceRecalculationModels.CalculationVersion;
import com.szsemicon.hr.attendance.calculation.domain.AttendanceRecalculationModels.RecalculationTarget;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public final class AttendanceCalculationPorts {

    private AttendanceCalculationPorts() {
    }

    public interface AttendanceEvidenceSnapshotPort {

        EvidenceSnapshot read(
                String legalEntityId,
                String employeeId,
                LocalDate businessDate,
                Instant knowledgeCutoff,
                String expectedProviderToken);
    }

    public record EvidenceSnapshot(
            String snapshotReference,
            String digest,
            String providerToken,
            List<String> pointEventReferences,
            List<String> intervalSliceReferences,
            List<String> conflictReferences) {

        public EvidenceSnapshot {
            pointEventReferences = List.copyOf(pointEventReferences);
            intervalSliceReferences = List.copyOf(intervalSliceReferences);
            conflictReferences = List.copyOf(conflictReferences);
        }
    }

    public interface AttendanceConfigurationSnapshotPort {

        ConfigurationSnapshot read(
                String employeeId,
                LocalDate businessDate,
                Instant knowledgeCutoff);
    }

    public record ConfigurationSnapshot(
            String snapshotReference,
            String digest,
            String employmentPeriodId,
            String attendanceGroupReference,
            String shiftReference,
            String calendarReference,
            String policyReference,
            String zoneId) {
    }

    public interface AttendanceRecalculationIntentInboxPort {

        List<ClaimedIntent> claim(int limit, String workerId, Instant now);

        void acknowledge(
                String intentId,
                String leaseToken,
                String recalculationBatchId,
                String calculationVersionId);

        void retry(
                String intentId,
                String leaseToken,
                String stableReasonCode,
                Instant retryNotBefore);
    }

    public record ClaimedIntent(
            String intentId,
            long version,
            String leaseToken,
            String legalEntityId,
            String employeeId,
            List<LocalDate> candidateBusinessDates,
            List<String> evidenceReferences,
            String providerToken) {

        public ClaimedIntent {
            candidateBusinessDates = List.copyOf(candidateBusinessDates);
            evidenceReferences = List.copyOf(evidenceReferences);
        }
    }

    public interface AttendancePeriodStatePort {

        PeriodStateSnapshot read(
                String legalEntityId, LocalDate businessDate);
    }

    public interface AttendanceCloseDependencyPort {

        CloseDependencySnapshot read(
                String legalEntityId,
                LocalDate startDate,
                LocalDate endExclusive,
                Instant knowledgeCutoff);
    }

    public record CloseDependencySnapshot(
            boolean sourceFresh,
            long runningSourceOrImportJobs,
            long quarantineOrConflictBlockers,
            String digest) {
    }

    public interface AttendanceCalculationVersionStore {

        Optional<CalculationVersion> current(RecalculationTarget target);

        Optional<CalculationVersion> findByInput(
                RecalculationTarget target,
                String inputDigest,
                String algorithmVersion);

        void appendAndSelectCurrent(
                CalculationVersion version,
                String expectedCurrentVersionId);
    }

    public interface AttendanceInputSnapshotAssembler {

        CalculationInputSnapshot assemble(
                RecalculationTarget target,
                Instant knowledgeCutoff,
                String expectedPeriodToken);
    }

    public interface AttendanceClock {

        Instant now();
    }
}
