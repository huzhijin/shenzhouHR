package com.szsemicon.hr.reporting.infrastructure.persistence;

import com.szsemicon.hr.reporting.application.OaEvidenceExclusionPort;
import java.time.Instant;
import org.springframework.stereotype.Repository;

/**
 * MyBatis adapter for OA evidence exclusion port.
 * Delegates to OaEvidenceExclusionWriteMapper, converting between mapper records and port records.
 */
@Repository
public class MyBatisOaEvidenceExclusionAdapter implements OaEvidenceExclusionPort {

    private final OaEvidenceExclusionWriteMapper mapper;

    public MyBatisOaEvidenceExclusionAdapter(OaEvidenceExclusionWriteMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ExclusionRow findByRequest(String requestId, String oaAttendanceDocumentId) {
        var mapperRow = mapper.findByRequest(requestId, oaAttendanceDocumentId);
        return mapperRow == null ? null : toPortRow(mapperRow);
    }

    @Override
    public ExclusionRow findById(String exclusionId) {
        var mapperRow = mapper.findById(exclusionId);
        return mapperRow == null ? null : toPortRow(mapperRow);
    }

    @Override
    public DocumentGeometryRow findDocumentGeometry(
            String oaAttendanceDocumentId,
            String attendanceSourceId,
            String sourceBusinessKey,
            String sourceVersion) {
        var mapperRow = mapper.findDocumentGeometry(
                oaAttendanceDocumentId, attendanceSourceId, sourceBusinessKey, sourceVersion);
        return mapperRow == null ? null : toPortGeometry(mapperRow);
    }

    @Override
    public String lockActiveOaSource(String sourceId) {
        return mapper.lockActiveOaSource(sourceId);
    }

    @Override
    public int insertExclusion(
            String exclusionId,
            String companyId,
            String employeeId,
            String attendanceSourceId,
            String sourceBusinessKey,
            String sourceVersion,
            String oaAttendanceDocumentId,
            Instant knowledgeAt,
            String reason,
            String createdBy,
            String auditReference,
            String userConfirmationText,
            String userConfirmationSha256,
            String evidenceSha256,
            String requestId) {
        return mapper.insertExclusion(
                exclusionId, companyId, employeeId, attendanceSourceId,
                sourceBusinessKey, sourceVersion, oaAttendanceDocumentId,
                knowledgeAt, reason, createdBy, auditReference,
                userConfirmationText, userConfirmationSha256,
                evidenceSha256, requestId);
    }

    @Override
    public int reverseExclusion(
            String exclusionId,
            Instant reversedAt,
            String reversedBy,
            String reversalReason,
            String reversalRequestId) {
        return mapper.reverseExclusion(
                exclusionId, reversedAt, reversedBy, reversalReason, reversalRequestId);
    }

    private ExclusionRow toPortRow(OaEvidenceExclusionWriteMapper.ExclusionRow mapperRow) {
        return new ExclusionRow(
                mapperRow.exclusionId(),
                mapperRow.companyId(),
                mapperRow.employeeId(),
                mapperRow.attendanceSourceId(),
                mapperRow.sourceBusinessKey(),
                mapperRow.sourceVersion(),
                mapperRow.oaAttendanceDocumentId(),
                mapperRow.knowledgeAt(),
                mapperRow.reason(),
                mapperRow.createdBy(),
                mapperRow.auditReference(),
                mapperRow.userConfirmationText(),
                mapperRow.userConfirmationSha256(),
                mapperRow.evidenceSha256(),
                mapperRow.requestId(),
                mapperRow.reversedAt(),
                mapperRow.reversedBy(),
                mapperRow.reversalReason(),
                mapperRow.reversalRequestId());
    }

    private DocumentGeometryRow toPortGeometry(
            OaEvidenceExclusionWriteMapper.DocumentGeometryRow mapperRow) {
        return new DocumentGeometryRow(
                mapperRow.oaAttendanceDocumentId(),
                mapperRow.documentType(),
                mapperRow.leaveType(),
                mapperRow.sourceStatus(),
                mapperRow.validationStatus(),
                mapperRow.intervalStart(),
                mapperRow.intervalEnd(),
                mapperRow.matchStatus(),
                mapperRow.employeeId(),
                mapperRow.companyId(),
                mapperRow.employeeNumber());
    }
}
