package com.szsemicon.hr.reporting.infrastructure.export;

import com.szsemicon.hr.reporting.application.AttendanceReportExportService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "shenzhouhr.reporting",
        name = "export-worker-enabled",
        havingValue = "true",
        matchIfMissing = true)
public final class AttendanceReportExportWorker {

    private static final int MAX_JOBS_PER_TICK = 5;

    private final AttendanceReportExportService exportService;

    public AttendanceReportExportWorker(
            AttendanceReportExportService exportService) {
        this.exportService = exportService;
    }

    @Scheduled(
            fixedDelayString =
                    "${shenzhouhr.reporting.export-worker-delay:PT5S}")
    void processQueuedExports() {
        exportService.purgeExpiredExports();
        for (int index = 0; index < MAX_JOBS_PER_TICK; index++) {
            if (!exportService.processNextQueued()) {
                return;
            }
        }
    }
}
