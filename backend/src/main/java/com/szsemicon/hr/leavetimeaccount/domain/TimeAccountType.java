package com.szsemicon.hr.leavetimeaccount.domain;

public enum TimeAccountType {
    ANNUAL_LEAVE(true),
    TIME_OFF(true),
    WORK_HOURS(false);

    private final boolean balanceControlled;

    TimeAccountType(boolean balanceControlled) {
        this.balanceControlled = balanceControlled;
    }

    public boolean balanceControlled() {
        return balanceControlled;
    }
}
