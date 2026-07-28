package com.szsemicon.hr.punchimport.infrastructure.persistence;

import com.szsemicon.hr.punchimport.application.PunchImportReadModels;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface PunchImportReadMapper {

    long countBatches(
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("at") Instant at);

    List<PunchImportReadModels.BatchView> listBatches(
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("at") Instant at,
            @Param("limit") int limit,
            @Param("offset") int offset);

    Optional<PunchImportReadModels.BatchView> findBatch(
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("batchId") String batchId,
            @Param("at") Instant at);

    long countIssues(
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("batchId") String batchId,
            @Param("at") Instant at);

    List<PunchImportReadModels.IssueView> listIssues(
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("batchId") String batchId,
            @Param("at") Instant at,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countRows(
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("batchId") String batchId,
            @Param("at") Instant at);

    List<PunchImportReadModels.RowView> listRows(
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("batchId") String batchId,
            @Param("at") Instant at,
            @Param("limit") int limit,
            @Param("offset") int offset);
}
