package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PaperOvertimeMapper {

    int insertBatch(
            @Param("batchId") String batchId,
            @Param("companyId") String companyId,
            @Param("principalId") String principalId,
            @Param("createdAt") Instant createdAt);

    int markSaved(
            @Param("batchId") String batchId,
            @Param("savedAt") Instant savedAt);

    int insertAttachment(PaperOvertimeRows.AttachmentRow row);

    int insertLine(PaperOvertimeRows.LineRow row);

    int updateLineDocument(
            @Param("lineId") String lineId,
            @Param("documentId") String documentId);

    String findPaperSourceId(@Param("companyId") String companyId);

    int insertPaperSource(
            @Param("sourceId") String sourceId,
            @Param("companyId") String companyId,
            @Param("createdAt") Instant createdAt);

    List<PaperOvertimeRows.EmployeeCandidateRow> suggestEmployees(
            @Param("companyId") String companyId,
            @Param("name") String name,
            @Param("department") String department,
            @Param("asOf") LocalDate asOf);

    PaperOvertimeRows.EmployeeCandidateRow findEmployee(
            @Param("companyId") String companyId,
            @Param("employeeId") String employeeId,
            @Param("asOf") LocalDate asOf);

    List<PaperOvertimeRows.ExistingOvertimeRow> listApprovedOvertime(
            @Param("companyId") String companyId,
            @Param("employeeId") String employeeId);

    List<PaperOvertimeRows.SavedLineRow> listSavedLines(
            @Param("companyId") String companyId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);
}
