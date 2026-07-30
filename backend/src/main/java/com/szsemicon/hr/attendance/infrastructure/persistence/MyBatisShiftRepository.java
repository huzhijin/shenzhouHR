package com.szsemicon.hr.attendance.infrastructure.persistence;

import com.szsemicon.hr.attendance.application.ShiftRepository;
import com.szsemicon.hr.attendance.domain.AttendanceGroupModels.LifecycleStatus;
import com.szsemicon.hr.attendance.domain.ShiftModels.Segment;
import com.szsemicon.hr.attendance.domain.ShiftModels.ShiftTemplate;
import com.szsemicon.hr.attendance.domain.ShiftModels.ShiftVersion;
import com.szsemicon.hr.attendance.domain.ShiftModels.VersionStatus;
import com.szsemicon.hr.attendance.domain.ShiftSnapshotDigest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Repository
class MyBatisShiftRepository implements ShiftRepository {

    private static final TypeReference<List<Segment>> SEGMENT_TYPE = new TypeReference<>() {
    };

    private final ShiftMapper mapper;
    private final ObjectMapper objectMapper;

    MyBatisShiftRepository(ShiftMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<ShiftTemplate> findTemplate(String shiftId) {
        return Optional.ofNullable(mapper.findTemplate(shiftId)).map(this::template);
    }

    @Override
    public Optional<ShiftTemplate> findTemplateByIdempotency(
            String actorId, String idempotencyKey) {
        return Optional.ofNullable(mapper.findTemplateByIdempotency(actorId, idempotencyKey))
                .map(this::template);
    }

    @Override
    public List<ShiftTemplate> listTemplates(
            String principalId,
            String capability,
            int limit,
            int offset,
            Instant at) {
        return mapper.listTemplates(principalId, capability, limit, offset, at)
                .stream().map(this::template).toList();
    }

    @Override
    public long countTemplates(String principalId, String capability, Instant at) {
        return mapper.countTemplates(principalId, capability, at);
    }

    @Override
    public void insertTemplate(ShiftTemplate template, String idempotencyKey) {
        mapper.insertTemplate(row(template), idempotencyKey);
        appendTemplateMetadata(template, template.rowVersion());
    }

    @Override
    public void lockTemplate(String shiftId) {
        if (mapper.lockTemplate(shiftId) == null) {
            throw new OptimisticLockingFailureException("shift template no longer exists");
        }
    }

    @Override
    public boolean updateTemplate(ShiftTemplate template, long expectedVersion) {
        if (mapper.updateTemplate(row(template), expectedVersion) != 1) {
            return false;
        }
        appendTemplateMetadata(template, expectedVersion + 1);
        return true;
    }

    @Override
    public Optional<ShiftVersion> findVersion(String versionId) {
        return Optional.ofNullable(mapper.findVersion(versionId)).map(this::version);
    }

    @Override
    public Optional<ShiftVersion> findVersion(
            String versionId, Instant knowledgeAsOf) {
        return Optional.ofNullable(mapper.findVersionAsOf(versionId, knowledgeAsOf))
                .map(this::version);
    }

    @Override
    public Optional<ShiftVersion> findVersionByIdempotency(
            String actorId, String idempotencyKey) {
        return Optional.ofNullable(mapper.findVersionByIdempotency(actorId, idempotencyKey))
                .map(this::version);
    }

    @Override
    public List<ShiftVersion> listVersions(String shiftId, int limit, int offset) {
        return mapper.listVersions(shiftId, limit, offset)
                .stream().map(this::version).toList();
    }

    @Override
    public long countVersions(String shiftId) {
        return mapper.countVersions(shiftId);
    }

    @Override
    public int nextVersionNumber(String shiftId) {
        return mapper.nextVersionNumber(shiftId);
    }

    @Override
    public void insertVersion(
            ShiftVersion version, String segmentsJson, String idempotencyKey) {
        mapper.insertVersion(row(version, segmentsJson), idempotencyKey);
    }

    @Override
    public boolean updateDraftVersion(
            ShiftVersion version, String segmentsJson, long expectedVersion) {
        ShiftVersion current = findVersion(version.shiftVersionId()).orElse(null);
        if (current == null
                || current.status() != VersionStatus.DRAFT
                || current.rowVersion() != expectedVersion) {
            return false;
        }
        ShiftVersion successor = new ShiftVersion(
                UUID.randomUUID().toString(),
                current.shiftId(),
                mapper.nextVersionNumber(current.shiftId()),
                VersionStatus.DRAFT,
                version.effectiveFrom(),
                version.effectiveTo(),
                version.timeZone(),
                version.segments(),
                version.snapshotDigest(),
                0,
                version.changeReason(),
                version.updatedBy(),
                version.updatedAt(),
                null,
                version.updatedBy(),
                version.updatedAt());
        return mapper.updateDraftVersion(
                row(successor, segmentsJson),
                current.shiftVersionId(),
                expectedVersion) == 1;
    }

    @Override
    public boolean publishVersion(
            String versionId,
            long expectedVersion,
            String snapshotDigest,
            String actorId,
            String reason,
            Instant at) {
        ShiftVersion version = findVersion(versionId).orElse(null);
        if (version == null
                || version.status() != VersionStatus.DRAFT
                || version.rowVersion() != expectedVersion
                || !ShiftSnapshotDigest.digest(version).equals(snapshotDigest)
                || !Objects.equals(snapshotDigest, version.snapshotDigest())
                || mapper.publishVersion(
                        version.shiftVersionId(), expectedVersion, snapshotDigest,
                        actorId, reason, at) != 1) {
            return false;
        }
        appendPublication(
                version,
                "PUBLISHED",
                version.effectiveFrom(),
                actorId,
                at);
        return true;
    }

    @Override
    public boolean scheduleVersionDeactivation(
            String versionId,
            long expectedVersion,
            LocalDate businessEffectiveFrom,
            String successorVersionId,
            String actorId,
            String reason,
            Instant at) {
        ShiftVersion version = findVersion(versionId).orElse(null);
        if (version == null
                || mapper.transitionVersionStatus(
                        version.shiftVersionId(), expectedVersion,
                        VersionStatus.PUBLISHED.name(),
                        VersionStatus.INACTIVE.name(),
                        actorId, reason, at) != 1) {
            return false;
        }
        appendPublication(
                version,
                VersionStatus.INACTIVE.name(),
                businessEffectiveFrom,
                actorId,
                at);
        if (successorVersionId != null) {
            ShiftVersion successor = findVersion(successorVersionId)
                    .filter(candidate -> candidate.shiftId().equals(version.shiftId()))
                    .filter(candidate -> candidate.status() == VersionStatus.PUBLISHED)
                    .orElseThrow(() -> new OptimisticLockingFailureException(
                            "shift successor changed"));
            appendPublication(
                    successor,
                    VersionStatus.PUBLISHED.name(),
                    businessEffectiveFrom,
                    actorId,
                    at);
        }
        return true;
    }

    @Override
    public boolean hasOverrideReferencesAtOrAfter(
            String versionId, LocalDate businessEffectiveFrom) {
        return mapper.hasOverrideReferencesAtOrAfter(
                versionId, businessEffectiveFrom);
    }

    @Override
    public boolean hasActiveGroupReferencesAtOrAfter(
            String shiftId, LocalDate businessEffectiveFrom) {
        return mapper.hasActiveGroupReferencesAtOrAfter(
                shiftId, businessEffectiveFrom);
    }

    @Override
    public boolean hasPublishedOverlap(
            String shiftId, LocalDate from, LocalDate to, String excludeVersionId) {
        int offset = 0;
        while (true) {
            List<ShiftVersion> page = listVersions(shiftId, 100, offset);
            if (page.stream()
                    .filter(version -> version.status() == VersionStatus.PUBLISHED)
                    .filter(version -> !version.shiftVersionId().equals(excludeVersionId))
                    .anyMatch(version ->
                            (to == null || version.effectiveFrom().isBefore(to))
                                    && (version.effectiveTo() == null
                                    || version.effectiveTo().isAfter(from)))) {
                return true;
            }
            if (page.size() < 100) {
                return false;
            }
            offset += page.size();
        }
    }

    @Override
    public List<ShiftVersion> resolvePublishedAt(
            String shiftId, LocalDate date, Instant knowledgeAsOf) {
        return mapper.resolvePublishedAt(shiftId, date, knowledgeAsOf)
                .stream()
                .map(this::version)
                .filter(version -> !date.isBefore(version.effectiveFrom()))
                .filter(version -> version.effectiveTo() == null
                        || date.isBefore(version.effectiveTo()))
                .toList();
    }

    private ShiftTemplate template(ShiftRows.TemplateRow row) {
        return new ShiftTemplate(
                row.shiftTemplateId(), row.companyId(), row.locationId(),
                row.templateCode(), row.templateName(), LifecycleStatus.valueOf(row.status()),
                row.rowVersion(), row.changeReason(), row.createdBy(), row.createdAt(),
                row.updatedBy(), row.updatedAt());
    }

    private ShiftVersion version(ShiftRows.VersionRow row) {
        return new ShiftVersion(
                row.shiftVersionId(), row.shiftTemplateId(), row.versionNumber(),
                VersionStatus.valueOf(row.status()), row.effectiveFrom(),
                readEffectiveTo(row.segmentsJson(), row.effectiveTo()),
                row.timeZoneSnapshot(), readSegments(row.segmentsJson()),
                row.snapshotDigest(), row.rowVersion(),
                row.changeReason(), row.createdBy(), row.createdAt(), row.publishedAt(),
                row.updatedBy(), row.updatedAt());
    }

    private ShiftRows.TemplateRow row(ShiftTemplate value) {
        return new ShiftRows.TemplateRow(
                value.shiftId(), value.companyId(), value.locationId(), value.code(),
                value.name(), value.status().name(), value.rowVersion(), value.changeReason(),
                value.createdBy(), value.createdAt(), value.updatedBy(), value.updatedAt());
    }

    private ShiftRows.VersionRow row(ShiftVersion value, String segmentsJson) {
        String storedSegments = storedSegments(value, segmentsJson);
        String snapshotDigest = ShiftSnapshotDigest.digest(value);
        if (value.snapshotDigest() != null
                && !snapshotDigest.equals(value.snapshotDigest())) {
            throw new IllegalArgumentException(
                    "shift snapshot digest must match canonical content");
        }
        return new ShiftRows.VersionRow(
                value.shiftVersionId(), value.shiftId(), value.versionNumber(),
                value.status().name(), value.effectiveFrom(), value.effectiveTo(),
                value.timeZone(), storedSegments, snapshotDigest, value.rowVersion(),
                value.changeReason(), value.createdBy(), value.createdAt(),
                value.publishedAt(), value.updatedBy(), value.updatedAt());
    }

    private void appendPublication(
            ShiftVersion version,
            String state,
            LocalDate businessEffectiveFrom,
            String actorId,
            Instant at) {
        int sequence = mapper.nextPublicationSequence(version.shiftId());
        mapper.insertPublicationTimeline(new AttendanceGroupRows.TimelineFactRow(
                UUID.randomUUID().toString(),
                version.shiftId(),
                version.shiftVersionId(),
                null,
                sequence,
                state,
                businessEffectiveFrom,
                mapper.latestPublicationTimelineId(version.shiftId()),
                at,
                actorId,
                UUID.randomUUID().toString()));
    }

    private void appendTemplateMetadata(
            ShiftTemplate template, long resourceVersion) {
        String eventId = UUID.randomUUID().toString();
        String correlationId = UUID.randomUUID().toString();
        String requestId = UUID.randomUUID().toString();
        String nameDigest = digest(template.name());
        String eventHash = digest(String.join(
                "|",
                eventId,
                template.updatedAt().toString(),
                template.updatedBy(),
                template.shiftId(),
                Long.toString(resourceVersion),
                nameDigest,
                requestId));
        mapper.insertTemplateMetadata(
                eventId,
                template.updatedAt(),
                template.updatedBy(),
                template.shiftId(),
                template.name(),
                truncate(template.changeReason(), 64),
                resourceVersion,
                nameDigest,
                correlationId,
                requestId,
                eventHash);
    }

    private String truncate(String value, int maximumLength) {
        return value.length() <= maximumLength
                ? value : value.substring(0, maximumLength);
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }

    private List<Segment> readSegments(String json) {
        try {
            if (json.stripLeading().startsWith("[")) {
                return objectMapper.readValue(json, SEGMENT_TYPE);
            }
            return objectMapper.convertValue(
                    objectMapper.readTree(json).get("segments"), SEGMENT_TYPE);
        } catch (Exception exception) {
            throw new IllegalStateException("stored shift segments must be valid", exception);
        }
    }

    private LocalDate readEffectiveTo(String json, LocalDate derived) {
        try {
            if (json.stripLeading().startsWith("[")) {
                return derived;
            }
            var value = objectMapper.readTree(json).get("effectiveTo");
            return value == null || value.isNull()
                    ? derived : LocalDate.parse(value.asString());
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "stored shift effectiveTo must be valid", exception);
        }
    }

    private String storedSegments(ShiftVersion value, String segmentsJson) {
        try {
            List<Segment> segments =
                    objectMapper.readValue(segmentsJson, SEGMENT_TYPE);
            if (!segments.equals(value.segments())) {
                throw new IllegalArgumentException(
                        "shift segment JSON must match canonical content");
            }
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("effectiveTo", value.effectiveTo());
            envelope.put("segments", segments);
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "shift timeline envelope must be serializable", exception);
        }
    }
}
