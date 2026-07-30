package com.szsemicon.hr.evidenceingestion.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public final class EvidenceLedger {

    private EvidenceLedger() {
    }

    public enum SourceType {
        DELI_CLOUD,
        OA_ATTENDANCE,
        DEVICE_EXCEL,
        STANDARD_XLSX
    }

    public enum Direction {
        AUTO,
        IN,
        OUT
    }

    public enum Resolution {
        SAME_FACT,
        DISTINCT_FACTS
    }

    public record EvidenceCandidate(
            String rawFactId,
            SourceType sourceType,
            String companyId,
            String employeeId,
            Instant instant,
            Direction direction) {

        public EvidenceCandidate {
            requireText(rawFactId, "rawFactId");
            Objects.requireNonNull(sourceType, "sourceType");
            requireText(companyId, "companyId");
            requireText(employeeId, "employeeId");
            Objects.requireNonNull(instant, "instant");
            Objects.requireNonNull(direction, "direction");
        }
    }

    public record Classification(
            List<String> rawFactIds,
            boolean pendingReview,
            int activeEventCount,
            String canonicalDigest) {

        public Classification {
            rawFactIds = List.copyOf(rawFactIds);
            if (activeEventCount < 0) {
                throw new IllegalArgumentException("activeEventCount cannot be negative");
            }
        }

        public Classification resolve(Resolution resolution) {
            Objects.requireNonNull(resolution, "resolution");
            if (!pendingReview) {
                throw new IllegalStateException("only a pending review can be resolved");
            }
            int resolvedCount = resolution == Resolution.SAME_FACT
                    ? 1 : rawFactIds.size();
            return new Classification(
                    rawFactIds,
                    false,
                    resolvedCount,
                    digest(List.of(
                            canonicalDigest,
                            resolution.name(),
                            Integer.toString(resolvedCount))));
        }
    }

    public record IntervalCandidate(
            String evidenceId,
            Instant start,
            Instant end,
            int priority,
            String evidenceType) {

        public IntervalCandidate {
            requireText(evidenceId, "evidenceId");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            requireText(evidenceType, "evidenceType");
            if (!end.isAfter(start)) {
                throw new IllegalArgumentException(
                        "interval must use a non-empty half-open [start,end) range");
            }
            if (priority < 1) {
                throw new IllegalArgumentException("priority must be positive");
            }
        }
    }

    public record IntervalSlice(
            Instant start,
            Instant end,
            List<String> candidateIds,
            String status,
            String winnerId,
            String canonicalDigest) {

        public IntervalSlice {
            candidateIds = List.copyOf(candidateIds);
        }
    }

    public static String stableFingerprint(
            String companyId,
            String locationId,
            String deviceId,
            String personOrEmployeeId,
            Instant normalizedInstant,
            Direction direction) {
        return digest(List.of(
                requireText(companyId, "companyId"),
                requireText(locationId, "locationId"),
                requireText(deviceId, "deviceId"),
                requireText(personOrEmployeeId, "personOrEmployeeId"),
                Objects.requireNonNull(normalizedInstant, "normalizedInstant").toString(),
                Objects.requireNonNull(direction, "direction").name()));
    }

    public static Classification classify(
            List<EvidenceCandidate> candidates,
            int nearDuplicateWindowSeconds) {
        if (nearDuplicateWindowSeconds < 1) {
            throw new IllegalArgumentException(
                    "nearDuplicateWindowSeconds must be positive");
        }
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("at least one candidate is required");
        }
        List<EvidenceCandidate> ordered = candidates.stream()
                .sorted(Comparator.comparing(EvidenceCandidate::rawFactId))
                .toList();
        ensureSameSubjectAndDirection(ordered);
        Set<String> rawIds = new TreeSet<>();
        ordered.forEach(candidate -> rawIds.add(candidate.rawFactId()));
        if (rawIds.size() != ordered.size()) {
            throw new IllegalArgumentException("raw fact IDs must be unique");
        }
        Instant minimum = ordered.stream()
                .map(EvidenceCandidate::instant)
                .min(Comparator.naturalOrder())
                .orElseThrow();
        Instant maximum = ordered.stream()
                .map(EvidenceCandidate::instant)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        long spread = Math.abs(Duration.between(minimum, maximum).toSeconds());
        long sourceCount = ordered.stream()
                .map(EvidenceCandidate::sourceType)
                .distinct()
                .count();
        boolean exact = spread == 0;
        boolean near = sourceCount > 1
                && spread >= 1
                && spread <= nearDuplicateWindowSeconds;
        int activeCount = near ? 0 : exact ? 1 : ordered.size();
        List<String> digestFields = new ArrayList<>();
        ordered.forEach(candidate -> {
            digestFields.add(candidate.rawFactId());
            digestFields.add(candidate.sourceType().name());
            digestFields.add(candidate.instant().toString());
        });
        digestFields.add("window=" + nearDuplicateWindowSeconds);
        return new Classification(
                List.copyOf(rawIds),
                near,
                activeCount,
                digest(digestFields));
    }

    public static List<IntervalSlice> sliceIntervals(
            List<IntervalCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<IntervalCandidate> canonicalCandidates = candidates.stream()
                .sorted(Comparator.comparing(IntervalCandidate::evidenceId))
                .toList();
        Set<String> ids = new LinkedHashSet<>();
        canonicalCandidates.forEach(candidate -> {
            if (!ids.add(candidate.evidenceId())) {
                throw new IllegalArgumentException("evidence IDs must be unique");
            }
        });
        TreeSet<Instant> boundaries = new TreeSet<>();
        canonicalCandidates.forEach(candidate -> {
            boundaries.add(candidate.start());
            boundaries.add(candidate.end());
        });
        List<Instant> orderedBoundaries = List.copyOf(boundaries);
        List<IntervalSlice> result = new ArrayList<>();
        for (int index = 0; index + 1 < orderedBoundaries.size(); index++) {
            Instant start = orderedBoundaries.get(index);
            Instant end = orderedBoundaries.get(index + 1);
            List<IntervalCandidate> covering = canonicalCandidates.stream()
                    .filter(candidate -> !candidate.start().isAfter(start)
                            && !candidate.end().isBefore(end))
                    .toList();
            if (covering.isEmpty()) {
                continue;
            }
            int bestPriority = covering.stream()
                    .mapToInt(IntervalCandidate::priority)
                    .min()
                    .orElseThrow();
            List<IntervalCandidate> winners = covering.stream()
                    .filter(candidate -> candidate.priority() == bestPriority)
                    .sorted(Comparator.comparing(IntervalCandidate::evidenceId))
                    .toList();
            boolean conflict = winners.stream()
                    .map(IntervalCandidate::evidenceType)
                    .distinct()
                    .count() > 1;
            List<String> candidateIds = covering.stream()
                    .sorted(Comparator
                            .comparingInt(IntervalCandidate::priority)
                            .thenComparing(IntervalCandidate::evidenceId))
                    .map(IntervalCandidate::evidenceId)
                    .toList();
            String status = conflict ? "EVIDENCE_CONFLICT" : "SELECTED";
            String winnerId = conflict ? null : winners.getFirst().evidenceId();
            List<String> digestFields = new ArrayList<>();
            digestFields.add(start.toString());
            digestFields.add(end.toString());
            digestFields.add(status);
            digestFields.add(winnerId == null ? "" : winnerId);
            digestFields.addAll(candidateIds);
            result.add(new IntervalSlice(
                    start,
                    end,
                    candidateIds,
                    status,
                    winnerId,
                    digest(digestFields)));
        }
        return List.copyOf(result);
    }

    private static void ensureSameSubjectAndDirection(
            List<EvidenceCandidate> candidates) {
        EvidenceCandidate first = candidates.getFirst();
        boolean incompatible = candidates.stream().anyMatch(candidate ->
                !candidate.companyId().equals(first.companyId())
                        || !candidate.employeeId().equals(first.employeeId())
                        || candidate.direction() != first.direction());
        if (incompatible) {
            throw new IllegalArgumentException(
                    "duplicate candidates must share company, employee and direction");
        }
    }

    private static String digest(List<String> fields) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] value = field.getBytes(StandardCharsets.UTF_8);
                messageDigest.update(ByteBuffer.allocate(Integer.BYTES)
                        .putInt(value.length)
                        .array());
                messageDigest.update(value);
            }
            return HexFormat.of().formatHex(messageDigest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
