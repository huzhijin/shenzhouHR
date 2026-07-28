package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.reporting.domain.AttendanceReportModels.DailyFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ExceptionFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.OaDocumentFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportDataSet;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportField;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportFilter;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportRow;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportSourceSnapshot;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.ReportType;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.TimeAccountFact;
import com.szsemicon.hr.reporting.domain.AttendanceReportModels.TimeAccountType;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Produces a stable digest of the exact report content and the source fact
 * identities that were visible while that content was calculated.
 *
 * <p>The source provenance is intentional. A digest based only on row count,
 * scope identifiers, or rendered totals would not detect an equal-sized
 * employee transfer, or replacement of facts that happen to aggregate to the
 * same values.
 */
final class AttendanceReportVisibilityDigest {

    private static final String FORMAT_VERSION =
            "ATTENDANCE_REPORT_VISIBLE_CONTENT_V1";
    private static final ZoneId BUSINESS_ZONE =
            ZoneId.of("Asia/Shanghai");
    private static final Set<String> EFFECTIVE_OA_STATUSES = Set.of(
            "APPROVED", "MODIFIED", "SUPPLEMENTED");

    private AttendanceReportVisibilityDigest() {
    }

    static String calculate(
            ReportType reportType,
            ReportSourceSnapshot snapshot,
            ReportDataSet dataSet) {
        Objects.requireNonNull(reportType, "reportType");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(dataSet, "dataSet");
        if (dataSet.type() != reportType) {
            throw new IllegalArgumentException(
                    "visible content digest report type does not match");
        }

        DigestWriter digest = new DigestWriter();
        digest.add(FORMAT_VERSION);
        digest.add(reportType.name());
        digest.add(snapshot.projectionVersion());
        digest.add(snapshot.periodState());
        digest.add(snapshot.dataAsOf());
        digest.add(snapshot.scope().type().name());
        digest.add(snapshot.scope().reference());
        digest.add(snapshot.scope().authorizationDigest());
        appendFilter(digest, snapshot.filter());
        digest.add(dataSet.calculationFormulaVersion());
        appendStrings(digest, snapshot.sourceVersions());
        appendFields(
                digest,
                dataSet.columns().stream()
                        .map(column -> column.field())
                        .toList());
        appendFields(digest, dataSet.exportAllowlist());
        appendRows(digest, dataSet.rows());
        appendVisibleSourceProvenance(
                digest, reportType, snapshot);
        return digest.finish();
    }

    private static void appendFilter(
            DigestWriter digest, ReportFilter filter) {
        digest.add(filter.period());
        digest.add(filter.legalEntityId());
        digest.add(filter.organizationId());
        digest.add(filter.employeeId());
        digest.add(filter.status());
    }

    private static void appendStrings(
            DigestWriter digest, List<String> values) {
        digest.add(values.size());
        values.forEach(digest::add);
    }

    private static void appendFields(
            DigestWriter digest, List<ReportField> fields) {
        digest.add(fields.size());
        fields.forEach(field -> digest.add(field.name()));
    }

    private static void appendRows(
            DigestWriter digest, List<ReportRow> rows) {
        digest.add(rows.size());
        for (ReportRow row : rows) {
            digest.add("ROW");
            digest.add(row.rowReference());
            digest.add(row.drillDownReference());
            var values = row.values().entrySet().stream()
                    .sorted(Comparator.comparing(
                            entry -> entry.getKey().name()))
                    .toList();
            digest.add(values.size());
            for (var value : values) {
                digest.add(value.getKey().name());
                digest.add(value.getValue());
            }
        }
    }

    private static void appendVisibleSourceProvenance(
            DigestWriter digest,
            ReportType reportType,
            ReportSourceSnapshot snapshot) {
        switch (reportType) {
            case ATTENDANCE_DETAIL,
                    OVERTIME,
                    WORK_HOURS,
                    LATE,
                    MISSED_PUNCH,
                    ATTENDANCE_RATE ->
                    appendDailyFacts(
                            digest,
                            snapshot.dailyFacts().stream()
                                    .filter(fact -> visible(
                                            fact.businessDate()
                                                    .getYear(),
                                            fact.businessDate()
                                                    .getMonthValue(),
                                            fact.organizationId(),
                                            fact.employeeId(),
                                            snapshot.filter()))
                                    .sorted(Comparator.comparing(
                                                    DailyFact::factId)
                                            .thenComparing(
                                                    DailyFact::employeeId))
                                    .toList());
            case LEAVE -> appendOaFacts(
                    digest,
                    snapshot.oaDocumentFacts().stream()
                            .filter(fact -> visibleOa(
                                    fact, snapshot.filter()))
                            .filter(fact -> Set.of("LEAVE", "TIME_OFF")
                                    .contains(fact.documentType()
                                            .toUpperCase(Locale.ROOT)))
                            .filter(fact -> EFFECTIVE_OA_STATUSES.contains(
                                    fact.sourceStatus()
                                            .toUpperCase(Locale.ROOT)))
                            .sorted(Comparator.comparing(
                                            OaDocumentFact::documentId)
                                    .thenComparing(
                                            OaDocumentFact::employeeId))
                            .toList());
            case EXCEPTIONS -> appendExceptionFacts(
                    digest,
                    snapshot.exceptionFacts().stream()
                            .filter(fact -> visible(
                                    fact.businessDate().getYear(),
                                    fact.businessDate().getMonthValue(),
                                    fact.organizationId(),
                                    fact.employeeId(),
                                    snapshot.filter()))
                            .filter(fact -> snapshot.filter().status() == null
                                    || fact.state().name().equals(
                                            snapshot.filter().status()))
                            .sorted(Comparator.comparing(
                                            ExceptionFact::caseId)
                                    .thenComparing(
                                            ExceptionFact::employeeId))
                            .toList());
            case ANNUAL_LEAVE -> appendTimeAccountFacts(
                    digest,
                    snapshot.timeAccountFacts().stream()
                            .filter(fact -> matchesReferences(
                                    fact.organizationId(),
                                    fact.employeeId(),
                                    snapshot.filter()))
                            .filter(fact -> fact.accountType()
                                    == TimeAccountType.ANNUAL_LEAVE)
                            .sorted(Comparator.comparing(
                                            TimeAccountFact::accountId)
                                    .thenComparing(
                                            TimeAccountFact::employeeId))
                            .toList());
        }
    }

    private static boolean visible(
            int year,
            int month,
            String organizationId,
            String employeeId,
            ReportFilter filter) {
        return filter.period().getYear() == year
                && filter.period().getMonthValue() == month
                && matchesReferences(
                        organizationId, employeeId, filter);
    }

    private static boolean visibleOa(
            OaDocumentFact fact, ReportFilter filter) {
        var periodStart = filter.period()
                .atDay(1)
                .atStartOfDay(BUSINESS_ZONE)
                .toInstant();
        var periodEnd = filter.period()
                .plusMonths(1)
                .atDay(1)
                .atStartOfDay(BUSINESS_ZONE)
                .toInstant();
        return fact.start().isBefore(periodEnd)
                && fact.endExclusive().isAfter(periodStart)
                && matchesReferences(
                        fact.organizationId(),
                        fact.employeeId(),
                        filter);
    }

    private static boolean matchesReferences(
            String organizationId,
            String employeeId,
            ReportFilter filter) {
        return (filter.organizationId() == null
                        || filter.organizationId().equals(organizationId))
                && (filter.employeeId() == null
                        || filter.employeeId().equals(employeeId));
    }

    private static void appendDailyFacts(
            DigestWriter digest, List<DailyFact> facts) {
        digest.add(facts.size());
        for (DailyFact fact : facts) {
            digest.add("DAILY");
            digest.add(fact.factId());
            digest.add(fact.legalEntityId());
            digest.add(fact.employeeId());
            digest.add(fact.employeeNumber());
            digest.add(fact.employeeName());
            digest.add(fact.organizationId());
            digest.add(fact.organizationVersionId());
            digest.add(fact.organizationName());
            digest.add(fact.businessDate());
            digest.add(fact.dayType().name());
            digest.add(fact.shiftLabel());
            digest.add(fact.scheduledMinutes());
            digest.add(fact.confirmedScheduledWorkMinutes());
            digest.add(fact.recognizedOvertimeMinutes());
            digest.add(fact.leaveOrTimeOffMinutes());
            digest.add(fact.absenceMinutes());
            digest.add(fact.actualWorkMinutes());
            digest.add(fact.lateMinutes());
            digest.add(fact.penalizedLateMinutes());
            digest.add(fact.earlyDepartureMinutes());
            digest.add(fact.missingPunchCount());
            digest.add(fact.firstPunchAt());
            digest.add(fact.lastPunchAt());
            digest.add(fact.calculationVersionId());
            digest.add(fact.resultDigest());
        }
    }

    private static void appendOaFacts(
            DigestWriter digest, List<OaDocumentFact> facts) {
        digest.add(facts.size());
        for (OaDocumentFact fact : facts) {
            digest.add("OA");
            digest.add(fact.documentId());
            digest.add(fact.employeeId());
            digest.add(fact.employeeNumber());
            digest.add(fact.employeeName());
            digest.add(fact.organizationId());
            digest.add(fact.organizationName());
            digest.add(fact.documentType());
            digest.add(fact.leaveType());
            digest.add(fact.start());
            digest.add(fact.endExclusive());
            digest.add(fact.recognizedMinutes());
            digest.add(fact.sourceStatus());
            digest.add(fact.sourceVersion());
        }
    }

    private static void appendExceptionFacts(
            DigestWriter digest, List<ExceptionFact> facts) {
        digest.add(facts.size());
        for (ExceptionFact fact : facts) {
            digest.add("EXCEPTION");
            digest.add(fact.caseId());
            digest.add(fact.employeeId());
            digest.add(fact.employeeNumber());
            digest.add(fact.employeeName());
            digest.add(fact.organizationId());
            digest.add(fact.organizationName());
            digest.add(fact.businessDate());
            digest.add(fact.exceptionType());
            digest.add(fact.severity().name());
            digest.add(fact.state().name());
            digest.add(fact.minutes());
            digest.add(fact.safeEvidenceSummary());
            digest.add(fact.calculationVersionId());
        }
    }

    private static void appendTimeAccountFacts(
            DigestWriter digest, List<TimeAccountFact> facts) {
        digest.add(facts.size());
        for (TimeAccountFact fact : facts) {
            digest.add("TIME_ACCOUNT");
            digest.add(fact.accountId());
            digest.add(fact.employeeId());
            digest.add(fact.employeeNumber());
            digest.add(fact.employeeName());
            digest.add(fact.organizationId());
            digest.add(fact.organizationName());
            digest.add(fact.accountType().name());
            digest.add(fact.openingHours());
            digest.add(fact.grantedHours());
            digest.add(fact.overtimeCreditHours());
            digest.add(fact.manualIncreaseHours());
            digest.add(fact.usedHours());
            digest.add(fact.expiredHours());
            digest.add(fact.returnedHours());
            digest.add(fact.manualDeductionHours());
            digest.add(fact.ledgerVersion());
        }
    }

    private static final class DigestWriter {

        private final MessageDigest digest;

        private DigestWriter() {
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException(
                        "required report visibility digest is unavailable",
                        exception);
            }
        }

        private void add(Object value) {
            if (value == null) {
                digest.update(ByteBuffer.allocate(Integer.BYTES)
                        .putInt(-1)
                        .array());
                return;
            }
            byte[] bytes = value.toString()
                    .getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES)
                    .putInt(bytes.length)
                    .array());
            digest.update(bytes);
        }

        private String finish() {
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
