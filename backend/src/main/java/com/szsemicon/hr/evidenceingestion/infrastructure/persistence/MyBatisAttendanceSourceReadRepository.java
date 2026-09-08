package com.szsemicon.hr.evidenceingestion.infrastructure.persistence;

import com.szsemicon.hr.evidenceingestion.application.AttendanceSourceReadModels;
import com.szsemicon.hr.evidenceingestion.application.AttendanceSourceReadRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisAttendanceSourceReadRepository
        implements AttendanceSourceReadRepository {

    private final AttendanceSourceReadMapper mapper;

    public MyBatisAttendanceSourceReadRepository(AttendanceSourceReadMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public long countSources(String principalId, String capability, Instant at) {
        return mapper.countSources(principalId, capability, at);
    }

    @Override
    public List<AttendanceSourceReadModels.SourceView> listSources(
            String principalId,
            String capability,
            Instant at,
            int limit,
            int offset) {
        return mapper.listSources(principalId, capability, at, limit, offset);
    }

    @Override
    public long countJobs(String principalId, String capability, Instant at) {
        return mapper.countJobs(principalId, capability, at);
    }

    @Override
    public List<AttendanceSourceReadModels.JobView> listJobs(
            String principalId,
            String capability,
            Instant at,
            int limit,
            int offset) {
        return mapper.listJobs(principalId, capability, at, limit, offset);
    }

    @Override
    public long countOaDocuments(
            String principalId,
            String capability,
            String sourceId,
            Instant at,
            String documentType) {
        return mapper.countOaDocuments(
                principalId, capability, sourceId, at, documentType);
    }

    @Override
    public List<AttendanceSourceReadModels.OaDocumentView> listOaDocuments(
            String principalId,
            String capability,
            String sourceId,
            Instant at,
            String documentType,
            int limit,
            int offset) {
        return mapper.listOaDocuments(
                principalId, capability, sourceId, at, documentType, limit, offset);
    }
}
