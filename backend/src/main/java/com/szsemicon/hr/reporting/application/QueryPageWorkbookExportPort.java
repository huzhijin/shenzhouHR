package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.reporting.application.AttendanceReportQueryPageService.QueryPage;

public interface QueryPageWorkbookExportPort {

    WorkbookFile encode(QueryPage page);

    record WorkbookFile(
            String fileName, String contentType, byte[] content) {
    }
}
