package com.szsemicon.hr.authorization.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
interface CapabilityMapper {

    List<String> findActiveCodes(
            @Param("principalId") String principalId,
            @Param("at") Instant at);
}

