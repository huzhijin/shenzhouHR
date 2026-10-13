package com.szsemicon.hr.people.infrastructure.persistence;

import com.szsemicon.hr.people.application.RosterImportBatchPort;
import java.time.Instant;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisRosterImportBatchAdapter implements RosterImportBatchPort {

    private final RosterImportMapper mapper;

    public MyBatisRosterImportBatchAdapter(RosterImportMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(Row row) {
        mapper.insert(new RosterImportMapper.Row(
                row.batchId(),
                row.status(),
                row.reason(),
                row.originalFileName(),
                row.fileSha256(),
                row.fileContent(),
                row.precheckJson(),
                row.rowVersion(),
                row.createdBy(),
                row.createdAt(),
                row.publishedAt()));
    }

    @Override
    public Row find(String batchId) {
        var mapperRow = mapper.find(batchId);
        if (mapperRow == null) {
            return null;
        }
        return new Row(
                mapperRow.batchId(),
                mapperRow.status(),
                mapperRow.reason(),
                mapperRow.originalFileName(),
                mapperRow.fileSha256(),
                mapperRow.fileContent(),
                mapperRow.precheckJson(),
                mapperRow.rowVersion(),
                mapperRow.createdBy(),
                mapperRow.createdAt(),
                mapperRow.publishedAt());
    }

    @Override
    public int markPublished(String batchId, long expectedVersion, Instant publishedAt) {
        return mapper.markPublished(batchId, expectedVersion, publishedAt);
    }
}
