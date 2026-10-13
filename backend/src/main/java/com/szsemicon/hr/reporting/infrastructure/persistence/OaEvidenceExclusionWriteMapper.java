package com.szsemicon.hr.reporting.infrastructure.persistence;

import com.szsemicon.hr.reporting.application.OaEvidenceExclusionWriteService.ExclusionReceipt;
import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OaEvidenceExclusionWriteMapper {

    ExclusionRow findByRequest(
            @Param("requestId") String requestId,
            @Param("oaAttendanceDocumentId") String oaAttendanceDocumentId);

    ExclusionRow findById(@Param("exclusionId") String exclusionId);

    DocumentGeometryRow findDocumentGeometry(
            @Param("oaAttendanceDocumentId") String oaAttendanceDocumentId,
            @Param("attendanceSourceId") String attendanceSourceId,
            @Param("sourceBusinessKey") String sourceBusinessKey,
            @Param("sourceVersion") String sourceVersion);

    int insertExclusion(
            @Param("exclusionId") String exclusionId,
            @Param("companyId") String companyId,
            @Param("employeeId") String employeeId,
            @Param("attendanceSourceId") String attendanceSourceId,
            @Param("sourceBusinessKey") String sourceBusinessKey,
            @Param("sourceVersion") String sourceVersion,
            @Param("oaAttendanceDocumentId") String oaAttendanceDocumentId,
            @Param("knowledgeAt") Instant knowledgeAt,
            @Param("reason") String reason,
            @Param("createdBy") String createdBy,
            @Param("auditReference") String auditReference,
            @Param("userConfirmationText") String userConfirmationText,
            @Param("userConfirmationSha256") String userConfirmationSha256,
            @Param("evidenceSha256") String evidenceSha256,
            @Param("requestId") String requestId);

    String lockActiveOaSource(@Param("sourceId") String sourceId);

    int reverseExclusion(
            @Param("exclusionId") String exclusionId,
            @Param("reversedAt") Instant reversedAt,
            @Param("reversedBy") String reversedBy,
            @Param("reversalReason") String reversalReason,
            @Param("reversalRequestId") String reversalRequestId);

    record ExclusionRow(
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
            String requestId,
            Instant reversedAt,
            String reversedBy,
            String reversalReason,
            String reversalRequestId) {
    }

    record DocumentGeometryRow(
            String oaAttendanceDocumentId,
            String documentType,
            String leaveType,
            String sourceStatus,
            String validationStatus,
            Instant intervalStart,
            Instant intervalEnd,
            String matchStatus,
            String employeeId,
            String companyId,
            String employeeNumber) {
    }
}
