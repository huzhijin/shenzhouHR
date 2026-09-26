package com.szsemicon.hr.payroll.application;

import com.szsemicon.hr.shared.domain.ExternalPreciseId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only seam for a real W5 attendance close snapshot.
 *
 * <p>W8 deliberately provides no implementation. A later W5 integration may
 * implement lookup only after its final close/reopen contract is synchronized.</p>
 */
@FunctionalInterface
public interface FrozenAttendanceSnapshotPort {

    Optional<FrozenAttendanceSnapshot> findById(ExternalPreciseId snapshotId);

    enum SnapshotState {
        CLOSED,
        REOPENED,
        SUPERSEDED
    }

    record FrozenAttendanceSnapshot(
            ExternalPreciseId snapshotId,
            LocalDate periodStartInclusive,
            LocalDate periodEndExclusive,
            SnapshotState state,
            long closeVersion,
            Instant closedAt,
            String integrityDigest) {

        public FrozenAttendanceSnapshot {
            Objects.requireNonNull(snapshotId, "snapshot id is required");
            Objects.requireNonNull(state, "snapshot state is required");
        }
    }
}
