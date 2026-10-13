package com.szsemicon.hr.reporting.infrastructure.persistence;

import com.szsemicon.hr.reporting.application.OaOvertimeRecognitionWriteService.RecognitionReceipt;
import java.time.Instant;
import java.time.LocalDate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OaOvertimeRecognitionWriteMapper {

    RecognitionRow findByRequest(
            @Param("requestId") String requestId,
            @Param("oaAttendanceDocumentId") String oaAttendanceDocumentId);

    RecognitionRow findById(@Param("recognitionId") String recognitionId);

    ValidatedOaDocumentRow findValidatedOaDocument(
            @Param("oaAttendanceDocumentId") String oaAttendanceDocumentId,
            @Param("attendanceSourceId") String attendanceSourceId,
            @Param("sourceBusinessKey") String sourceBusinessKey,
            @Param("sourceVersion") String sourceVersion);

    boolean hasActiveExclusion(
            @Param("companyId") String companyId,
            @Param("employeeId") String employeeId,
            @Param("attendanceSourceId") String attendanceSourceId,
            @Param("sourceBusinessKey") String sourceBusinessKey,
            @Param("sourceVersion") String sourceVersion,
            @Param("oaAttendanceDocumentId") String oaAttendanceDocumentId,
            @Param("asOf") Instant asOf);

    boolean hasExistingV75Overlap(
            @Param("companyId") String companyId,
            @Param("employeeId") String employeeId,
            @Param("attendanceSourceId") String attendanceSourceId,
            @Param("businessDate") LocalDate businessDate,
            @Param("intervalStart") Instant intervalStart,
            @Param("intervalEnd") Instant intervalEnd,
            @Param("excludeDocId") String excludeDocId,
            @Param("asOf") Instant asOf);

    boolean hasOtherConsumableOaOverlap(
            @Param("companyId") String companyId,
            @Param("attendanceSourceId") String attendanceSourceId,
            @Param("employeeId") String employeeId,
            @Param("intervalStart") Instant intervalStart,
            @Param("intervalEnd") Instant intervalEnd,
            @Param("excludeDocId") String excludeDocId,
            @Param("excludeKey") String excludeKey,
            @Param("asOf") Instant asOf);

    int insertRecognition(
            @Param("recognitionId") String recognitionId,
            @Param("companyId") String companyId,
            @Param("employeeId") String employeeId,
            @Param("attendanceSourceId") String attendanceSourceId,
            @Param("sourceBusinessKey") String sourceBusinessKey,
            @Param("sourceVersion") String sourceVersion,
            @Param("oaAttendanceDocumentId") String oaAttendanceDocumentId,
            @Param("businessDate") LocalDate businessDate,
            @Param("expectedIntervalStart") Instant expectedIntervalStart,
            @Param("expectedIntervalEnd") Instant expectedIntervalEnd,
            @Param("overtimeType") String overtimeType,
            @Param("recognizedMinutes") int recognizedMinutes,
            @Param("knowledgeAt") Instant knowledgeAt,
            @Param("reason") String reason,
            @Param("createdBy") String createdBy,
            @Param("auditReference") String auditReference,
            @Param("userConfirmationText") String userConfirmationText,
            @Param("userConfirmationSha256") String userConfirmationSha256,
            @Param("evidenceSha256") String evidenceSha256,
            @Param("requestId") String requestId);

    String lockActiveOaSource(@Param("sourceId") String sourceId);

    int reverseRecognition(
            @Param("recognitionId") String recognitionId,
            @Param("reversedAt") Instant reversedAt,
            @Param("reversedBy") String reversedBy,
            @Param("reversalReason") String reversalReason,
            @Param("reversalRequestId") String reversalRequestId);

    record RecognitionRow(
            String recognitionId,
            String companyId,
            String employeeId,
            String attendanceSourceId,
            String sourceBusinessKey,
            String sourceVersion,
            String oaAttendanceDocumentId,
            LocalDate businessDate,
            Instant expectedIntervalStart,
            Instant expectedIntervalEnd,
            String overtimeType,
            int recognizedMinutes,
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

    record ValidatedOaDocumentRow(
            String oaAttendanceDocumentId,
            String documentType,
            String sourceStatus,
            String validationStatus,
            Instant intervalStart,
            Instant intervalEnd,
            String matchStatus,
            String employeeId,
            String companyId,
            String employeeNumber,
            String activationDecision,
            String overtimeType) {
    }
}
