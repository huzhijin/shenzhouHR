package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.reporting.domain.AttendanceReportModels.AuthorizedScope;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportDataSet;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportField;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public interface AttendanceReportExportEncoder {

    EncodedExport encode(
            ReportDataSet dataSet, ExportContext context);

    record ExportContext(
            String exportId,
            String principalId,
            String purpose,
            ReportFilter filter,
            AuthorizedScope authorizationScope,
            String projectionVersion,
            String formulaVersion,
            List<String> sourceVersions,
            Instant dataAsOf,
            String periodState,
            String queryFingerprint,
            String visibleContentDigest,
            Instant createdAt,
            Instant generatedAt,
            List<ReportField> selectedFields,
            AttendanceMonthMatrixPage monthMatrix) {

        public ExportContext {
            requireText(exportId, "exportId");
            requireText(principalId, "principalId");
            requireText(purpose, "purpose");
            Objects.requireNonNull(filter, "filter");
            if (filter.companyId() == null) {
                throw new IllegalArgumentException(
                        "export context company is required");
            }
            Objects.requireNonNull(
                    authorizationScope, "authorizationScope");
            requireDigest(
                    authorizationScope.authorizationDigest(),
                    "authorizationScope.authorizationDigest");
            requireText(projectionVersion, "projectionVersion");
            requireText(formulaVersion, "formulaVersion");
            sourceVersions = List.copyOf(Objects.requireNonNull(
                    sourceVersions, "sourceVersions"));
            if (sourceVersions.stream().anyMatch(
                    value -> value == null
                            || value.isBlank()
                            || value.chars().anyMatch(
                                    Character::isISOControl))) {
                throw new IllegalArgumentException(
                        "sourceVersions contains an invalid value");
            }
            Objects.requireNonNull(dataAsOf, "dataAsOf");
            requireText(periodState, "periodState");
            requireDigest(queryFingerprint, "queryFingerprint");
            requireDigest(
                    visibleContentDigest, "visibleContentDigest");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(generatedAt, "generatedAt");
            if (generatedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException(
                        "export generation cannot precede creation");
            }
            selectedFields = List.copyOf(Objects.requireNonNull(
                    selectedFields, "selectedFields"));
            if (selectedFields.isEmpty()
                    || new HashSet<>(selectedFields).size()
                            != selectedFields.size()) {
                throw new IllegalArgumentException(
                        "selectedFields must be non-empty and unique");
            }
        }

        private static void requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        field + " is required");
            }
        }

        private static void requireDigest(String value, String field) {
            if (value == null || !value.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        field + " must be a lowercase SHA-256 digest");
            }
        }
    }

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
