package com.szsemicon.hr.reporting.infrastructure.persistence;

import com.szsemicon.hr.reporting.application.AttendanceReportProjectionWriter;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class MyBatisAttendanceReportProjectionWriter
        implements AttendanceReportProjectionWriter {

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
        requireSingle(
                mapper.insertOaDocumentFact(AttendanceReportProjectionWriteRows
                        .OaDocumentFactRow.from(fact)),
                "OA report fact is not a verified canonical document");
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
