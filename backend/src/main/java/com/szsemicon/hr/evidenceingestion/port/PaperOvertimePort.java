package com.szsemicon.hr.evidenceingestion.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Port for paper overtime batch and line operations.
 */
public interface PaperOvertimePort {

    int insertBatch(String batchId, String companyId, String principalId, Instant createdAt);

    int markSaved(String batchId, Instant savedAt);

    int insertAttachment(PaperOvertimeRows.AttachmentRow row);

    int insertLine(PaperOvertimeRows.LineRow row);

    int updateLineDocument(String lineId, String documentId);

    String findPaperSourceId(String companyId);

    int insertPaperSource(String sourceId, String companyId, Instant createdAt);

    List<PaperOvertimeRows.EmployeeCandidateRow> suggestEmployees(
            String companyId, String name, String department, LocalDate asOf);

    PaperOvertimeRows.EmployeeCandidateRow findEmployee(
            String companyId, String employeeId, LocalDate asOf);

    List<PaperOvertimeRows.ExistingOvertimeRow> listApprovedOvertime(
            String companyId, String employeeId);

    List<PaperOvertimeRows.SavedLineRow> listSavedLines(
            String companyId, LocalDate fromDate, LocalDate toDate);
}
