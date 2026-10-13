package com.szsemicon.hr.leavetimeaccount.application;

import com.szsemicon.hr.leavetimeaccount.application.TimeOffYearEndModels.RunSummary;

public final class TimeOffYearEndBatchException extends RuntimeException {

    private final transient RunSummary summary;

    public TimeOffYearEndBatchException(RunSummary summary) {
        super("TIME_OFF year-end run " + summary.runId()
                + " completed with " + summary.failureCount() + " failure(s)");
        this.summary = summary;
    }

    public RunSummary summary() {
        return summary;
    }
}
