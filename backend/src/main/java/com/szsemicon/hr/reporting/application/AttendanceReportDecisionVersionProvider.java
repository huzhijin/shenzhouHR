package com.szsemicon.hr.reporting.application;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

/** Company-month audit decisions participate in the same pin freshness contract as source evidence. */
public interface AttendanceReportDecisionVersionProvider {
    String OVERTIME_RECOGNITION_PREFIX = "DECISION.OT:";
    String EVIDENCE_EXCLUSION_PREFIX = "DECISION.EXCLUSION:";

    List<String> versions(String companyId, YearMonth period, Instant asOf);
}
