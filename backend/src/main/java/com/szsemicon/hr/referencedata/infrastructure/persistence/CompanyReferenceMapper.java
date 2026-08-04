package com.szsemicon.hr.referencedata.infrastructure.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
interface CompanyReferenceMapper {

    List<CompanyReferenceRow> findActive();
}
