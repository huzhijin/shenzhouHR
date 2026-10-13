package com.szsemicon.hr.reporting.infrastructure.orchestrator;

import com.szsemicon.hr.reporting.application.AttendanceReportDecisionVersionProvider;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class AttendanceReportDecisionVersions implements AttendanceReportDecisionVersionProvider {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final AttendanceReportCalculationMapper mapper;

    AttendanceReportDecisionVersions(AttendanceReportCalculationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> versions(String companyId, YearMonth period, Instant asOf) {
        LocalDate periodStart = period.atDay(1);
        LocalDate periodEndExclusive = period.plusMonths(1).atDay(1);
        Instant windowStart = periodStart.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant windowEndExclusive = periodEndExclusive.atStartOfDay(BUSINESS_ZONE).toInstant();

        List<String> markers = new ArrayList<>();
        markers.addAll(OaOvertimeRecognition.markers(mapper.findOaOvertimeRecognitions(
                companyId, periodStart, periodEndExclusive, asOf), asOf));
        markers.addAll(OaEvidenceExclusion.markers(mapper.findOaEvidenceExclusions(
                companyId, windowStart, windowEndExclusive, asOf), asOf));
        return markers.stream().sorted().toList();
    }
}
