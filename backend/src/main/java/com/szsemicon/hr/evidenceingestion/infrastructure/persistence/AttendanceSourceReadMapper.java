package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import com.szsemicon.hr.evidenceingestion.application.AttendanceSourceReadModels;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface AttendanceSourceReadMapper {

    long countSources(
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("at") Instant at);

    List<AttendanceSourceReadModels.SourceView> listSources(
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("at") Instant at,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countJobs(
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("at") Instant at);

    List<AttendanceSourceReadModels.JobView> listJobs(
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("at") Instant at,
            @Param("limit") int limit,
            @Param("offset") int offset);

    long countOaDocuments(
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("sourceId") String sourceId,
            @Param("at") Instant at);

    List<AttendanceSourceReadModels.OaDocumentView> listOaDocuments(
            @Param("principalId") String principalId,
            @Param("capability") String capability,
            @Param("sourceId") String sourceId,
            @Param("at") Instant at,
            @Param("limit") int limit,
            @Param("offset") int offset);
}
