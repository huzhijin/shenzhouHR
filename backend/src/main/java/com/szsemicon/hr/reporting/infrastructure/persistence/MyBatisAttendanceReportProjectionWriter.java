package com.szsemicon.hr.reporting.infrastructure.persistence;

import com.szsemicon.hr.reporting.application.AttendanceReportProjectionWriter;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class MyBatisAttendanceReportProjectionWriter
        implements AttendanceReportProjectionWriter {

    private static final Logger log = LoggerFactory.getLogger(
            MyBatisAttendanceReportProjectionWriter.class);

    private final AttendanceReportProjectionWriteMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisAttendanceReportProjectionWriter(
            AttendanceReportProjectionWriteMapper mapper,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockCompany(String companyId) {
        return companyId != null
                && companyId.equals(
                        mapper.lockCompany(companyId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<StoredProjection> findByDigest(
            String companyId,
            LocalDate periodStart,
            String projectionDigest) {
        return Optional.ofNullable(mapper.findByDigest(
                        companyId, periodStart, projectionDigest))
                .map(row -> row.toStoredProjection(
                        sourceVersions(row.sourceVersionsJson())));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<StoredProjection> findLatestPublished(
            String companyId, LocalDate periodStart) {
        return Optional.ofNullable(
                        mapper.findLatestPublished(
                                companyId, periodStart))
                .map(row -> row.toStoredProjection(
                        sourceVersions(row.sourceVersionsJson())));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void createDraft(ProjectionDraft draft) {
        if (draft == null) {
            throw new IllegalArgumentException(
                    "report projection draft is required");
        }
        String sourceVersionsJson;
        try {
            sourceVersionsJson =
                    objectMapper.writeValueAsString(draft.sourceVersions());
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "unable to serialize report source versions");
        }
        requireSingle(
                mapper.insertDraft(AttendanceReportProjectionWriteRows
                        .ProjectionDraftRow.from(
                                draft, sourceVersionsJson)),
                "report projection draft references are invalid");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendDailyFact(DailyFactWrite fact) {
        requireSingle(
                mapper.insertDailyFact(AttendanceReportProjectionWriteRows
                        .DailyFactRow.from(fact)),
                "daily report fact references are invalid");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendExceptionFact(ExceptionFactWrite fact) {
        requireSingle(
                mapper.insertExceptionFact(AttendanceReportProjectionWriteRows
                        .ExceptionFactRow.from(fact)),
                "exception report fact references are invalid");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendOaDocumentFact(OaDocumentFactWrite fact) {
        int written = mapper.insertOaDocumentFact(
                AttendanceReportProjectionWriteRows.OaDocumentFactRow.from(fact));
        if (written == 1) {
            return;
        }
        log.warn(
                "skipping unverified OA report fact document={} employee={}",
                fact == null ? null : fact.oaAttendanceDocumentId(),
                fact == null ? null : fact.employeeId());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendTimeAccountFact(TimeAccountFactWrite fact) {
        requireSingle(
                mapper.insertTimeAccountFact(
                        AttendanceReportProjectionWriteRows
                                .TimeAccountFactRow.from(fact)),
                "time-account report fact references are invalid");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void markPublished(String projectionId, Instant publishedAt) {
        requireSingle(
                mapper.markPublished(projectionId, publishedAt),
                "report projection is no longer a draft");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void copyFactsOutsideRange(
            String sourceProjectionId,
            String targetProjectionId,
            LocalDate windowStart,
            LocalDate windowEndExclusive,
            Instant createdAt) {
        copyFactsOutsideRange(
                sourceProjectionId,
                targetProjectionId,
                windowStart,
                windowEndExclusive,
                createdAt,
                null);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void copyFactsOutsideRange(
            String sourceProjectionId,
            String targetProjectionId,
            LocalDate windowStart,
            LocalDate windowEndExclusive,
            Instant createdAt,
            String employeeId) {
        if (sourceProjectionId == null
                || targetProjectionId == null
                || windowStart == null
                || windowEndExclusive == null) {
            return;
        }
        mapper.copyDailyFactsOutsideRange(
                sourceProjectionId,
                targetProjectionId,
                windowStart,
                windowEndExclusive,
                createdAt,
                employeeId,
                employeeId == null || employeeId.isBlank()
                        ? java.util.List.of()
                        : java.util.List.of(employeeId));
        mapper.copyExceptionFactsOutsideRange(
                sourceProjectionId,
                targetProjectionId,
                windowStart,
                windowEndExclusive,
                createdAt,
                employeeId,
                employeeId == null || employeeId.isBlank()
                        ? java.util.List.of()
                        : java.util.List.of(employeeId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void copyFactsOutsideRange(
            String sourceProjectionId,
            String targetProjectionId,
            LocalDate windowStart,
            LocalDate windowEndExclusive,
            Instant createdAt,
            String employeeId,
            java.util.Collection<String> employeeIds) {
        if (sourceProjectionId == null
                || targetProjectionId == null
                || windowStart == null
                || windowEndExclusive == null) {
            return;
        }
        java.util.List<String> ids = employeeIds == null
                ? java.util.List.of()
                : java.util.List.copyOf(employeeIds);
        mapper.copyDailyFactsOutsideRange(
                sourceProjectionId,
                targetProjectionId,
                windowStart,
                windowEndExclusive,
                createdAt,
                employeeId,
                ids);
        mapper.copyExceptionFactsOutsideRange(
                sourceProjectionId,
                targetProjectionId,
                windowStart,
                windowEndExclusive,
                createdAt,
                employeeId,
                ids);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void copyOaDocumentFactsExceptEmployeeWindow(
            String sourceProjectionId,
            String targetProjectionId,
            Instant windowStart,
            Instant windowEndExclusive,
            Instant createdAt,
            String employeeId) {
        if (sourceProjectionId == null
                || targetProjectionId == null
                || windowStart == null
                || windowEndExclusive == null
                || employeeId == null
                || employeeId.isBlank()) {
            return;
        }
        mapper.copyOaDocumentFactsExceptEmployeeWindow(
                sourceProjectionId,
                targetProjectionId,
                windowStart,
                windowEndExclusive,
                createdAt,
                employeeId,
                employeeId == null || employeeId.isBlank()
                        ? java.util.List.of()
                        : java.util.List.of(employeeId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void copyOaDocumentFactsExceptEmployeeWindow(
            String sourceProjectionId,
            String targetProjectionId,
            Instant windowStart,
            Instant windowEndExclusive,
            Instant createdAt,
            String employeeId,
            java.util.Collection<String> employeeIds) {
        java.util.List<String> ids = employeeIds == null
                ? java.util.List.of()
                : java.util.List.copyOf(employeeIds);
        if (sourceProjectionId == null
                || targetProjectionId == null
                || windowStart == null
                || windowEndExclusive == null
                || ids.isEmpty()) {
            return;
        }
        mapper.copyOaDocumentFactsExceptEmployeeWindow(
                sourceProjectionId,
                targetProjectionId,
                windowStart,
                windowEndExclusive,
                createdAt,
                employeeId,
                ids);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void copyTimeAccountFactsExceptEmployee(
            String sourceProjectionId,
            String targetProjectionId,
            Instant createdAt,
            String employeeId) {
        if (sourceProjectionId == null
                || targetProjectionId == null
                || employeeId == null
                || employeeId.isBlank()) {
            return;
        }
        mapper.copyTimeAccountFactsExceptEmployee(
                sourceProjectionId,
                targetProjectionId,
                createdAt,
                employeeId,
                employeeId == null || employeeId.isBlank()
                        ? java.util.List.of()
                        : java.util.List.of(employeeId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void copyTimeAccountFactsExceptEmployee(
            String sourceProjectionId,
            String targetProjectionId,
            Instant createdAt,
            String employeeId,
            java.util.Collection<String> employeeIds) {
        java.util.List<String> ids = employeeIds == null
                ? java.util.List.of()
                : java.util.List.copyOf(employeeIds);
        if (sourceProjectionId == null
                || targetProjectionId == null
                || ids.isEmpty()) {
            return;
        }
        mapper.copyTimeAccountFactsExceptEmployee(
                sourceProjectionId,
                targetProjectionId,
                createdAt,
                employeeId,
                ids);
    }

    private static void requireSingle(int affected, String message) {
        if (affected != 1) {
            throw new IllegalStateException(message);
        }
    }

    private List<String> sourceVersions(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isArray() || root.isEmpty()) {
                throw invalidSourceVersions();
            }
            List<String> values = new ArrayList<>();
            for (JsonNode value : root) {
                if (!value.isTextual()
                        || value.asText().isBlank()
                        || value.asText().length() > 128) {
                    throw invalidSourceVersions();
                }
                values.add(value.asText());
            }
            List<String> canonical =
                    values.stream().sorted().distinct().toList();
            if (!values.equals(canonical)) {
                throw invalidSourceVersions();
            }
            return List.copyOf(values);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidSourceVersions();
        }
    }

    private static IllegalStateException invalidSourceVersions() {
        return new IllegalStateException(
                "stored report source versions are invalid");
    }
}
