package com.szsemicon.hr.reporting.infrastructure.persistence;

import com.szsemicon.hr.reporting.application.OaOvertimeRecognitionPort;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.stereotype.Repository;

/**
 * MyBatis adapter for OA overtime recognition port.
 * Delegates to OaOvertimeRecognitionWriteMapper, converting between mapper records and port records.
 */
@Repository
public class MyBatisOaOvertimeRecognitionAdapter implements OaOvertimeRecognitionPort {

    private final OaOvertimeRecognitionWriteMapper mapper;

    public MyBatisOaOvertimeRecognitionAdapter(OaOvertimeRecognitionWriteMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public RecognitionRow findByRequest(String requestId, String oaAttendanceDocumentId) {
        var mapperRow = mapper.findByRequest(requestId, oaAttendanceDocumentId);
        return mapperRow == null ? null : toPortRow(mapperRow);
    }

    @Override
    public RecognitionRow findById(String recognitionId) {
        var mapperRow = mapper.findById(recognitionId);
        return mapperRow == null ? null : toPortRow(mapperRow);
    }

    @Override
    public ValidatedOaDocumentRow findValidatedOaDocument(
            String oaAttendanceDocumentId,
            String attendanceSourceId,
            String sourceBusinessKey,
            String sourceVersion) {
        var mapperRow = mapper.findValidatedOaDocument(
                oaAttendanceDocumentId, attendanceSourceId, sourceBusinessKey, sourceVersion);
        return mapperRow == null ? null : toPortValidated(mapperRow);
    }

    @Override
    public boolean hasActiveExclusion(
            String companyId,
            String employeeId,
            String attendanceSourceId,
            String sourceBusinessKey,
            String sourceVersion,
            String oaAttendanceDocumentId,
            Instant asOf) {
        return mapper.hasActiveExclusion(
                companyId, employeeId, attendanceSourceId,
                sourceBusinessKey, sourceVersion, oaAttendanceDocumentId, asOf);
    }

    @Override
    public boolean hasExistingV75Overlap(
            String companyId,
            String employeeId,
            String attendanceSourceId,
            LocalDate businessDate,
            Instant intervalStart,
            Instant intervalEnd,
            String excludeDocId,
            Instant asOf) {
        return mapper.hasExistingV75Overlap(
                companyId, employeeId, attendanceSourceId,
                businessDate, intervalStart, intervalEnd, excludeDocId, asOf);
    }

    @Override
    public boolean hasOtherConsumableOaOverlap(
            String companyId,
            String attendanceSourceId,
            String employeeId,
            Instant intervalStart,
            Instant intervalEnd,
            String excludeDocId,
            String excludeKey,
            Instant asOf) {
        return mapper.hasOtherConsumableOaOverlap(
                companyId, attendanceSourceId, employeeId,
                intervalStart, intervalEnd, excludeDocId, excludeKey, asOf);
    }

    @Override
    public String lockActiveOaSource(String sourceId) {
        return mapper.lockActiveOaSource(sourceId);
    }

    @Override
    public int insertRecognition(
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
            String requestId) {
        return mapper.insertRecognition(
                recognitionId, companyId, employeeId, attendanceSourceId,
                sourceBusinessKey, sourceVersion, oaAttendanceDocumentId,
                businessDate, expectedIntervalStart, expectedIntervalEnd,
                overtimeType, recognizedMinutes, knowledgeAt, reason,
                createdBy, auditReference, userConfirmationText,
                userConfirmationSha256, evidenceSha256, requestId);
    }

    @Override
    public int reverseRecognition(
            String recognitionId,
            Instant reversedAt,
            String reversedBy,
            String reversalReason,
            String reversalRequestId) {
        return mapper.reverseRecognition(
                recognitionId, reversedAt, reversedBy, reversalReason, reversalRequestId);
    }

    private RecognitionRow toPortRow(OaOvertimeRecognitionWriteMapper.RecognitionRow mapperRow) {
        return new RecognitionRow(
                mapperRow.recognitionId(),
                mapperRow.companyId(),
                mapperRow.employeeId(),
                mapperRow.attendanceSourceId(),
                mapperRow.sourceBusinessKey(),
                mapperRow.sourceVersion(),
                mapperRow.oaAttendanceDocumentId(),
                mapperRow.businessDate(),
                mapperRow.expectedIntervalStart(),
                mapperRow.expectedIntervalEnd(),
                mapperRow.overtimeType(),
                mapperRow.recognizedMinutes(),
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

    private ValidatedOaDocumentRow toPortValidated(
            OaOvertimeRecognitionWriteMapper.ValidatedOaDocumentRow mapperRow) {
        return new ValidatedOaDocumentRow(
                mapperRow.oaAttendanceDocumentId(),
                mapperRow.documentType(),
                mapperRow.sourceStatus(),
                mapperRow.validationStatus(),
                mapperRow.matchStatus(),
                mapperRow.activationDecision(),
                mapperRow.overtimeType(),
                mapperRow.intervalStart(),
                mapperRow.intervalEnd(),
                mapperRow.employeeId(),
                mapperRow.companyId());
    }
}
