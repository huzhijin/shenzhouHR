package com.szsemicon.hr.people.infrastructure.persistence;

import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RosterImportMapper {

    void insert(Row row);

    Row find(@Param("batchId") String batchId);

    int markPublished(
            @Param("batchId") String batchId,
            @Param("expectedVersion") long expectedVersion,
            @Param("publishedAt") Instant publishedAt);

    record Row(
            String batchId,
            String status,
            String reason,
            String originalFileName,
            String fileSha256,
            byte[] fileContent,
            String precheckJson,
            long rowVersion,
            String createdBy,
            Instant createdAt,
            Instant publishedAt) {
    }
}
