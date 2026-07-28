package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportDataSet;
import java.time.YearMonth;

public interface AttendanceReportExportEncoder {

    EncodedExport encode(ReportDataSet dataSet, YearMonth period);

    record EncodedExport(
            String contentType,
            String fileExtension,
            byte[] content) {

        public EncodedExport {
            if (contentType == null || contentType.isBlank()) {
                throw new IllegalArgumentException(
                        "export content type is required");
            }
            if (fileExtension == null
                    || !fileExtension.matches("[a-z0-9]{1,8}")) {
                throw new IllegalArgumentException(
                        "export file extension is invalid");
            }
            content = content == null ? null : content.clone();
            if (content == null || content.length == 0) {
                throw new IllegalArgumentException(
                        "export content is required");
            }
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
