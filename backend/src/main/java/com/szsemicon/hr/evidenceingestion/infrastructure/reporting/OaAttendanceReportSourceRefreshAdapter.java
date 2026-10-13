package com.szsemicon.hr.evidenceingestion.infrastructure.reporting;

import com.szsemicon.hr.evidenceingestion.application.OaDocumentSyncApplicationService;
import com.szsemicon.hr.reporting.application.AttendanceReportSourceRefreshPort;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import org.springframework.stereotype.Component;

@Component
public final class OaAttendanceReportSourceRefreshAdapter implements AttendanceReportSourceRefreshPort {
    private final OaDocumentSyncApplicationService sync;

    public OaAttendanceReportSourceRefreshAdapter(OaDocumentSyncApplicationService sync) {
        this.sync = sync;
    }

    @Override
    public Instant refresh(String companyId, YearMonth period, Instant requestedAt) {
        return sync.refreshMonthForReport(companyId, period, requestedAt);
    }

    @Override
    public Instant refreshRange(
            String companyId, LocalDate fromInclusive, LocalDate toExclusive,
            Instant requestedAt) {
        return sync.refreshRangeForReport(
                companyId, fromInclusive, toExclusive, requestedAt);
    }

    @Override
    public Instant refreshRange(
            String companyId, LocalDate fromInclusive, LocalDate toExclusive,
            Instant requestedAt, java.util.List<String> employeeIds) {
        return sync.refreshRangeForReport(
                companyId, fromInclusive, toExclusive, requestedAt, employeeIds);
    }
}
