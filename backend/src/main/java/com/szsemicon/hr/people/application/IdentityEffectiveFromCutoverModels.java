package com.szsemicon.hr.people.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class IdentityEffectiveFromCutoverModels {

    private IdentityEffectiveFromCutoverModels() {
    }

    public record Diagnosis(
            LocalDate cutoffDate,
            List<Candidate> movable,
            List<Candidate> alreadyApplied,
            List<Blocked> blocked,
            boolean safe) {

        public Diagnosis {
            movable = List.copyOf(movable);
            alreadyApplied = List.copyOf(alreadyApplied);
            blocked = List.copyOf(blocked);
        }
    }

    public record Candidate(
            String objectType,
            String objectId,
            String ownerId,
            LocalDate currentFrom) {
    }

    public record Blocked(
            String objectType,
            String objectId,
            String ownerId,
            String reason) {
    }

    public record CutoverResult(
            String runId,
            String status,
            int updatedCount,
            int refusedCount,
            Instant createdAt,
            List<Blocked> blocked) {

        public CutoverResult {
            blocked = List.copyOf(blocked);
        }
    }
}
