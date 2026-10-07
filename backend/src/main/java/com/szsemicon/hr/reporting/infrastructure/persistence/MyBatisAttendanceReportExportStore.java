package com.szsemicon.hr.reporting.infrastructure.persistence;

import com.szsemicon.hr.reporting.application.AttendanceReportExportStore;
import com.szsemicon.hr.reporting.application.AttendanceReportExportStore.DeliveryMode;
import com.szsemicon.hr.reporting.application.AttendanceReportExportStore.ExportArtifact;
import com.szsemicon.hr.reporting.application.AttendanceReportExportStore.ExportJob;
import com.szsemicon.hr.reporting.application.AttendanceReportExportStore.ExportStatus;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportField;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class MyBatisAttendanceReportExportStore
        implements AttendanceReportExportStore {

    private final AttendanceReportExportMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisAttendanceReportExportStore(
            AttendanceReportExportMapper mapper,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(ExportJob job, byte[] content) {
        if (job == null) {
            throw new IllegalArgumentException("export job is required");
        }
        boolean synchronousReady =
                job.deliveryMode() == DeliveryMode.SYNC
                        && job.status() == ExportStatus.READY;
        boolean asynchronousQueued =
                job.deliveryMode() == DeliveryMode.ASYNC
                        && job.status() == ExportStatus.QUEUED;
        if (!synchronousReady && !asynchronousQueued) {
            throw new IllegalArgumentException(
                    "new export must be synchronous-ready or asynchronous-queued");
        }
        byte[] safeContent = content == null ? null : content.clone();
        if (synchronousReady) {
            requireMatchingContent(
                    safeContent,
                    job.contentSha256(),
                    job.contentLength());
        } else if (safeContent != null) {
            throw new IllegalArgumentException(
                    "queued export cannot contain an artifact");
        }
        mapper.insertJob(AttendanceReportExportRows.ExportWriteRow.from(
                job, exportFieldsJson(job.exportFields())));
        if (safeContent != null) {
            mapper.insertArtifact(
                    job.exportId(), safeContent, job.completedAt());
        }
    }

    @Override
    @Transactional(
            readOnly = true,
            propagation = Propagation.MANDATORY)
    public Optional<ExportJob> findOwnedJob(
            String exportId, String principalId) {
        return Optional.ofNullable(
                        mapper.findOwnedJob(exportId, principalId))
                .map(this::toJob);
    }

    @Override
    @Transactional(
            readOnly = true,
            propagation = Propagation.MANDATORY)
    public Optional<ExportArtifact> findOwnedReady(
            String exportId,
            String principalId,
            String expectedQueryFingerprint,
            String expectedVisibleContentDigest,
            Instant readAt) {
        requireIdentifier(exportId);
        requireIdentifier(principalId);
        requireDigest(expectedQueryFingerprint);
        requireDigest(expectedVisibleContentDigest);
        if (readAt == null) {
            throw new IllegalArgumentException(
                    "artifact read time is required");
        }
        return Optional.ofNullable(
                        mapper.findOwnedReadyArtifact(
                                exportId,
                                principalId,
                                expectedQueryFingerprint,
                                expectedVisibleContentDigest,
                                readAt))
                .map(row -> new ExportArtifact(
                        toJob(row), row.content()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ExportJob> claimNextQueued(Instant claimedAt) {
        if (claimedAt == null) {
            throw new IllegalArgumentException("claim time is required");
        }
        var row = mapper.findNextQueuedForUpdate(claimedAt);
        if (row == null) {
            return Optional.empty();
        }
        if (mapper.markBuilding(row.exportId(), claimedAt) != 1) {
            throw new IllegalStateException(
                    "queued export claim lost its state transition");
        }
        return Optional.of(toJob(row, ExportStatus.BUILDING));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void markReady(
            String exportId,
            byte[] content,
            String contentType,
            String fileExtension,
            String contentSha256,
            String expectedVisibleContentDigest,
            Instant completedAt) {
        requireIdentifier(exportId);
        if (contentType == null
                || contentType.isBlank()
                || contentType.length() > 128
                || fileExtension == null
                || !fileExtension.matches("[a-z0-9]{1,8}")
                || completedAt == null) {
            throw new IllegalArgumentException(
                    "ready export metadata is invalid");
        }
        byte[] safeContent = content == null ? null : content.clone();
        requireMatchingContent(
                safeContent, contentSha256, safeContent == null
                        ? 0
                        : safeContent.length);
        requireDigest(expectedVisibleContentDigest);
        mapper.insertArtifact(exportId, safeContent, completedAt);
        if (mapper.markReady(
                        exportId,
                        contentType,
                        fileExtension,
                        contentSha256,
                        expectedVisibleContentDigest,
                        safeContent.length,
                        completedAt)
                != 1) {
            throw new IllegalStateException(
                    "export is no longer buildable");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void markFailed(
            String exportId,
            String failureCode,
            Instant completedAt) {
        requireIdentifier(exportId);
        if (failureCode == null
                || !failureCode.matches("[A-Z0-9_]{1,96}")
                || completedAt == null) {
            throw new IllegalArgumentException(
                    "failed export metadata is invalid");
        }
        if (mapper.markFailed(exportId, failureCode, completedAt) != 1) {
            throw new IllegalStateException(
                    "export is no longer buildable");
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int purgeExpired(Instant expiredAt, int limit) {
        if (expiredAt == null || limit < 1 || limit > 1_000) {
            throw new IllegalArgumentException(
                    "export purge boundary is invalid");
        }
        return mapper.purgeExpired(expiredAt, limit);
    }

    private ExportJob toJob(
            AttendanceReportExportRows.ExportReadRow row) {
        return toJob(row, null);
    }

    private ExportJob toJob(
            AttendanceReportExportRows.ExportReadRow row,
            ExportStatus statusOverride) {
        return row.toJob(
                exportFields(row.exportFieldsJson()), statusOverride);
    }

    private String exportFieldsJson(List<ReportField> fields) {
        try {
            return objectMapper.writeValueAsString(fields.stream()
                    .map(ReportField::name)
                    .toList());
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "unable to serialize report export fields",
                    exception);
        }
    }

    private List<ReportField> exportFields(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isArray() || root.isEmpty()) {
                throw invalidFields();
            }
            List<ReportField> fields = new ArrayList<>();
            for (JsonNode value : root) {
                if (!value.isTextual()) {
                    throw invalidFields();
                }
                ReportField field = ReportField.valueOf(value.asText());
                if (fields.contains(field)) {
                    throw invalidFields();
                }
                fields.add(field);
            }
            return List.copyOf(fields);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidFields();
        }
    }

    private static IllegalStateException invalidFields() {
        return new IllegalStateException(
                "stored report export fields are invalid");
    }

    private static void requireMatchingContent(
            byte[] content, String expectedDigest, long expectedLength) {
        if (content == null
                || content.length == 0
                || expectedLength != content.length
                || !constantTimeEquals(
                        expectedDigest, sha256(content))) {
            throw new IllegalArgumentException(
                    "export artifact does not match its metadata");
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "required report export digest is unavailable",
                    exception);
        }
    }

    private static boolean constantTimeEquals(
            String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    private static void requireIdentifier(String exportId) {
        if (exportId == null || exportId.isBlank()) {
            throw new IllegalArgumentException(
                    "export id is required");
        }
    }

    private static void requireDigest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "export digest is invalid");
        }
    }
}
