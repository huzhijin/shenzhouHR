package com.szsemicon.hr.leavetimeaccount.domain;

import java.util.List;
import java.util.Objects;

public record AnnualLeaveGrantPlan(
        AnnualLeaveCycle cycle,
        AnnualLeaveEntitlement entitlement,
        List<AnnualLeaveGrantEvent> events) {

    public AnnualLeaveGrantPlan {
        Objects.requireNonNull(cycle, "年假周期不能为空");
        Objects.requireNonNull(entitlement, "年假额度不能为空");
        events = List.copyOf(Objects.requireNonNull(events, "周年事件不能为空"));
        if (events.isEmpty()
                || events.getLast().type() != AnnualLeaveEventType.GRANT_NEW) {
            throw new IllegalArgumentException("周年计划必须以新周期发放结束");
        }
        if (events.size() > 2
                || (events.size() == 2
                        && events.getFirst().type()
                                != AnnualLeaveEventType.EXPIRE_PREVIOUS)) {
            throw new IllegalArgumentException("周年计划必须先失效旧周期再发放新周期");
        }
    }
}
