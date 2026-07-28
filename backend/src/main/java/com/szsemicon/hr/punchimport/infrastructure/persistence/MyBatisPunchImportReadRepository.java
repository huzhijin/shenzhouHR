package com.szsemicon.hr.punchimport.infrastructure.persistence;

import com.szsemicon.hr.punchimport.application.PunchImportReadModels;
import com.szsemicon.hr.punchimport.application.PunchImportReadRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisPunchImportReadRepository
        implements PunchImportReadRepository {

    private final PunchImportReadMapper mapper;

    public MyBatisPunchImportReadRepository(PunchImportReadMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public long countBatches(String principalId, String capability, Instant at) {
        return mapper.countBatches(principalId, capability, at);
    }

    @Override
    public List<PunchImportReadModels.BatchView> listBatches(
            String principalId,
            String capability,
            Instant at,
            int limit,
            int offset) {
        return mapper.listBatches(principalId, capability, at, limit, offset);
    }

    @Override
    public Optional<PunchImportReadModels.BatchView> findBatch(
            String principalId,
            String capability,
            String batchId,
            Instant at) {
        return mapper.findBatch(principalId, capability, batchId, at);
    }

    @Override
    public long countIssues(
            String principalId,
            String capability,
            String batchId,
            Instant at) {
        return mapper.countIssues(principalId, capability, batchId, at);
    }

    @Override
    public List<PunchImportReadModels.IssueView> listIssues(
            String principalId,
            String capability,
            String batchId,
            Instant at,
            int limit,
            int offset) {
        return mapper.listIssues(
                principalId, capability, batchId, at, limit, offset);
    }

    @Override
    public long countRows(
            String principalId,
            String capability,
            String batchId,
            Instant at) {
        return mapper.countRows(principalId, capability, batchId, at);
    }

    @Override
    public List<PunchImportReadModels.RowView> listRows(
            String principalId,
            String capability,
            String batchId,
            Instant at,
            int limit,
            int offset) {
        return mapper.listRows(
                principalId, capability, batchId, at, limit, offset);
    }
}
