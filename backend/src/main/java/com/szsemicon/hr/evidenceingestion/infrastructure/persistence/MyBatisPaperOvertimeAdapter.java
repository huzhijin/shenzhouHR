package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import com.szsemicon.hr.evidenceingestion.port.PaperOvertimePort;
import com.szsemicon.hr.evidenceingestion.port.PaperOvertimeRows;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisPaperOvertimeAdapter implements PaperOvertimePort {

    private final PaperOvertimeMapper mapper;

    public MyBatisPaperOvertimeAdapter(PaperOvertimeMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public int insertBatch(String batchId, String companyId, String principalId, Instant createdAt) {
        return mapper.insertBatch(batchId, companyId, principalId, createdAt);
    }

    @Override
    public int markSaved(String batchId, Instant savedAt) {
        return mapper.markSaved(batchId, savedAt);
    }

    @Override
    public int insertAttachment(PaperOvertimeRows.AttachmentRow row) {
        return mapper.insertAttachment(new com.szsemicon.hr.evidenceingestion.infrastructure.persistence.PaperOvertimeRows.AttachmentRow(
                row.attachmentId(),
                row.batchId(),
                row.fileName(),
                row.contentType(),
                row.content(),
                row.ocrText(),
                row.createdAt()));
    }

    @Override
    public int insertLine(PaperOvertimeRows.LineRow row) {
        return mapper.insertLine(new com.szsemicon.hr.evidenceingestion.infrastructure.persistence.PaperOvertimeRows.LineRow(
                row.lineId(),
                row.batchId(),
                row.employeeId(),
                row.ocrName(),
                row.ocrDepartment(),
                row.overtimeDate(),
                row.startAt(),
                row.endAt(),
                row.overtimeType(),
                row.reason(),
                row.createdAt()));
    }

    @Override
    public int updateLineDocument(String lineId, String documentId) {
        return mapper.updateLineDocument(lineId, documentId);
    }

    @Override
    public String findPaperSourceId(String companyId) {
        return mapper.findPaperSourceId(companyId);
    }

    @Override
    public int insertPaperSource(String sourceId, String companyId, Instant createdAt) {
        return mapper.insertPaperSource(sourceId, companyId, createdAt);
    }

    @Override
    public List<PaperOvertimeRows.EmployeeCandidateRow> suggestEmployees(
            String companyId, String name, String department, LocalDate asOf) {
        return mapper.suggestEmployees(companyId, name, department, asOf).stream()
                .map(row -> new PaperOvertimeRows.EmployeeCandidateRow(
                        row.employeeId(),
                        row.employeeNumber(),
                        row.displayName(),
                        row.organizationId(),
                        row.departmentName(),
                        row.employmentStatus()))
                .collect(Collectors.toList());
    }

    @Override
    public PaperOvertimeRows.EmployeeCandidateRow findEmployee(
            String companyId, String employeeId, LocalDate asOf) {
        var row = mapper.findEmployee(companyId, employeeId, asOf);
        if (row == null) {
            return null;
        }
        return new PaperOvertimeRows.EmployeeCandidateRow(
                row.employeeId(),
                row.employeeNumber(),
                row.displayName(),
                row.organizationId(),
                row.departmentName(),
                row.employmentStatus());
    }

    @Override
    public List<PaperOvertimeRows.ExistingOvertimeRow> listApprovedOvertime(
            String companyId, String employeeId) {
        return mapper.listApprovedOvertime(companyId, employeeId).stream()
                .map(row -> new PaperOvertimeRows.ExistingOvertimeRow(
                        row.documentId(),
                        row.sourceBusinessKey(),
                        row.intervalStart(),
                        row.intervalEnd()))
                .collect(Collectors.toList());
    }

    @Override
    public List<PaperOvertimeRows.SavedLineRow> listSavedLines(
            String companyId, LocalDate fromDate, LocalDate toDate) {
        return mapper.listSavedLines(companyId, fromDate, toDate).stream()
                .map(row -> new PaperOvertimeRows.SavedLineRow(
                        row.lineId(),
                        row.batchId(),
                        row.employeeId(),
                        row.employeeNumber(),
                        row.employeeName(),
                        row.departmentName(),
                        row.overtimeDate(),
                        row.startAt(),
                        row.endAt(),
                        row.overtimeType(),
                        row.reason(),
                        row.documentId()))
                .collect(Collectors.toList());
    }
}
