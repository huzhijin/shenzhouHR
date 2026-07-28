package com.szsemicon.hr.authorization.application;

import java.time.Instant;
import java.util.Set;

public interface CapabilityRepository {

    Set<String> findActiveCodes(String principalId, Instant at);
}

