package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;

public final class PaperOvertimeRows {

    private PaperOvertimeRows() {
    }

    public record AttachmentRow(
            String attachmentId,
            String batchId,
            String fileName,
            String contentType,
            byte[] content,
            String ocrText,
            Instant createdAt) {
    }

    public record LineRow(
            String lineId,
            String batchId,
            String employeeId,
            String ocrName,
            String ocrDepartment,
            LocalDate overtimeDate,
            Instant startAt,
            Instant endAt,
            String overtimeType,
            String reason,
            Instant createdAt) {
    }

    public record EmployeeCandidateRow(
            String employeeId,
            String employeeNumber,
            String displayName,
            String organizationId,
            String departmentName,
            String employmentStatus) {
    }

    public record ExistingOvertimeRow(
            String documentId,
            String sourceBusinessKey,
            Instant intervalStart,
            Instant intervalEnd) {
    }

    public record SavedLineRow(
            String lineId,
            String batchId,
            String employeeId,
            String employeeNumber,
            String employeeName,
            String departmentName,
            LocalDate overtimeDate,
            Instant startAt,
            Instant endAt,
            String overtimeType,
            String reason,
            String documentId) {
    }
}
