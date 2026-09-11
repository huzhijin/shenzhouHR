package com.szsemicon.hr.referencedata.infrastructure.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.Instant;

@Mapper
interface CompanyReferenceMapper {

    List<CompanyReferenceRow> findVisibleActive(
            @Param("principalId") String principalId,
            @Param("at") Instant at);
}
