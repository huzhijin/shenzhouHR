package com.szsemicon.hr.payroll.application;

import com.szsemicon.hr.payroll.application.FrozenAttendanceSnapshotPort.FrozenAttendanceSnapshot;
import com.szsemicon.hr.payroll.application.FrozenAttendanceSnapshotPort.SnapshotState;
import com.szsemicon.hr.payroll.application.PayrollReservationAudit.DenialReason;
import com.szsemicon.hr.payroll.domain.PayrollReservationModels.FrozenAttendanceSnapshotRef;
import com.szsemicon.hr.payroll.domain.PayrollReservationModels.PayrollPeriod;
import com.szsemicon.hr.shared.domain.ExternalPreciseId;
import com.szsemicon.hr.shared.security.ResourceNotAvailableAccessDeniedException;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.security.access.AccessDeniedException;

public final class PayrollReservationService {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final PayrollReservationSettings settings;
    private final PayrollCapabilityAuthorizer authorizer;
    private final PayrollReservationAudit audit;
    private final Optional<FrozenAttendanceSnapshotPort> snapshotPort;

    public PayrollReservationService(
            PayrollReservationSettings settings,
            PayrollCapabilityAuthorizer authorizer,
            PayrollReservationAudit audit,
            Optional<FrozenAttendanceSnapshotPort> snapshotPort) {
        this.settings = Objects.requireNonNull(settings, "settings are required");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer is required");
        this.audit = Objects.requireNonNull(audit, "audit is required");
        this.snapshotPort =
                Objects.requireNonNull(snapshotPort, "snapshot port optional is required");
    }

    /**
     * Validates and exposes only the immutable reference needed by a later
     * payroll implementation. It neither calculates nor persists a result.
     */
    public FrozenAttendanceSnapshotRef inspectFrozenAttendanceSnapshot(
            InspectFrozenAttendanceSnapshot command) {
        Objects.requireNonNull(command, "command is required");
        ExternalPreciseId resourceId = command.period().payrollPeriodId();
        if (!settings.enabled()) {
            audit.recordDenied(resourceId, DenialReason.FEATURE_DISABLED);
            throw new ResourceNotAvailableAccessDeniedException();
        }
        try {
            authorizer.requireReservationRead();
        } catch (AccessDeniedException denied) {
            audit.recordDenied(resourceId, DenialReason.CAPABILITY_NOT_GRANTED);
            throw denied;
        }
        FrozenAttendanceSnapshotPort port = snapshotPort.orElseThrow(() -> {
            audit.recordDenied(resourceId, DenialReason.W5_ADAPTER_UNAVAILABLE);
            return new PayrollReservationFailure(
                    PayrollReservationFailure.Code.W5_INTEGRATION_UNAVAILABLE);
        });
        FrozenAttendanceSnapshot snapshot = port.findById(command.attendanceSnapshotId())
                .orElseThrow(() -> {
                    audit.recordDenied(resourceId, DenialReason.SNAPSHOT_NOT_AVAILABLE);
                    return new PayrollReservationFailure(
                            PayrollReservationFailure.Code.SNAPSHOT_NOT_AVAILABLE);
                });
        if (!valid(snapshot, command)) {
            audit.recordDenied(resourceId, DenialReason.INVALID_FROZEN_SNAPSHOT);
            throw new PayrollReservationFailure(
                    PayrollReservationFailure.Code.INVALID_FROZEN_SNAPSHOT);
        }
        return new FrozenAttendanceSnapshotRef(
                snapshot.snapshotId(),
                snapshot.periodStartInclusive(),
                snapshot.periodEndExclusive(),
                snapshot.closeVersion(),
                snapshot.closedAt(),
                snapshot.integrityDigest());
    }

    private static boolean valid(
            FrozenAttendanceSnapshot snapshot,
            InspectFrozenAttendanceSnapshot command) {
        return command.attendanceSnapshotId().equals(snapshot.snapshotId())
                && snapshot.state() == SnapshotState.CLOSED
                && snapshot.closeVersion() > 0
                && snapshot.closedAt() != null
                && command.period().startInclusive().equals(
                        snapshot.periodStartInclusive())
                && command.period().endExclusive().equals(
                        snapshot.periodEndExclusive())
                && snapshot.integrityDigest() != null
                && SHA256.matcher(snapshot.integrityDigest()).matches();
    }

    public record InspectFrozenAttendanceSnapshot(
            PayrollPeriod period,
            ExternalPreciseId attendanceSnapshotId) {

        public InspectFrozenAttendanceSnapshot {
            Objects.requireNonNull(period, "payroll period is required");
            Objects.requireNonNull(
                    attendanceSnapshotId, "attendance snapshot id is required");
        }
    }
}
