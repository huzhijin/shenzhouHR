package com.szsemicon.hr.attendance.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface PunchCorrectionRequestMapper {

    String lockEmployee(@Param("employeeId") String employeeId);

    int countQuotaConsuming(
            @Param("employeeId") String employeeId,
            @Param("requestMonth") LocalDate requestMonth);

    boolean isScheduledWorkDay(
            @Param("employeeId") String employeeId,
            @Param("businessDate") LocalDate businessDate,
            @Param("knowledgeAsOf") Instant knowledgeAsOf);

    boolean isPunchExempt(
            @Param("employeeId") String employeeId,
            @Param("knowledgeAsOf") Instant knowledgeAsOf);

    void insert(@Param("row") PunchCorrectionRows.RequestRow row);

    PunchCorrectionRows.RequestRow find(
            @Param("requestId") String requestId);

    int approve(
            @Param("requestId") String requestId,
            @Param("reviewerId") String reviewerId,
            @Param("reviewedAt") Instant reviewedAt,
            @Param("reviewNotes") String reviewNotes);
}
