package com.szsemicon.hr.authorization.infrastructure.persistence;

import com.szsemicon.hr.authorization.application.CapabilityRepository;
import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisCapabilityRepository implements CapabilityRepository {

    private final CapabilityMapper mapper;

    public MyBatisCapabilityRepository(CapabilityMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Set<String> findActiveCodes(String principalId, Instant at) {
        return new TreeSet<>(mapper.findActiveCodes(principalId, at));
    }
}

