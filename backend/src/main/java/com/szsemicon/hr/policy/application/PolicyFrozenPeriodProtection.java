package com.szsemicon.hr.policy.application;

import com.szsemicon.hr.policy.domain.PolicyModels.PolicyVersion;
import java.time.Clock;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

public interface PolicyFrozenPeriodProtection {

    Decision assessPublication(PolicyVersion version);

    record Decision(boolean publicationAllowed, boolean protectionActive, String reasonCode) {
    }
}

@Component
final class DefaultPolicyFrozenPeriodProtection implements PolicyFrozenPeriodProtection {

    private final Clock clock;

    DefaultPolicyFrozenPeriodProtection(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Decision assessPublication(PolicyVersion version) {
        LocalDate today = LocalDate.now(clock);
        if (version.effectiveFrom().isAfter(today)) {
            return new Decision(true, true, null);
        }
        return new Decision(false, true, "FROZEN_PERIOD_PROTECTION_UNAVAILABLE");
    }
}
