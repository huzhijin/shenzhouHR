package com.szsemicon.hr.evidenceingestion.application;

import java.time.Instant;
import java.util.List;

public interface AttendanceSourceReadRepository {

    long countSources(String principalId, String capability, Instant at);

    List<AttendanceSourceReadModels.SourceView> listSources(
            String principalId,
            String capability,
            Instant at,
            int limit,
            int offset);

    long countJobs(String principalId, String capability, Instant at);

    List<AttendanceSourceReadModels.JobView> listJobs(
            String principalId,
            String capability,
            Instant at,
            int limit,
            int offset);

    long countOaDocuments(
            String principalId,
            String capability,
            String sourceId,
            Instant at);

    List<AttendanceSourceReadModels.OaDocumentView> listOaDocuments(
            String principalId,
            String capability,
            String sourceId,
            Instant at,
            int limit,
            int offset);
}
