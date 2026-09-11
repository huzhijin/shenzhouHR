package com.szsemicon.hr.evidenceingestion.domain.oa;

import com.szsemicon.hr.evidenceingestion.domain.oa.OaStaticFormMappingCatalog.ContractComponent;
import com.szsemicon.hr.evidenceingestion.domain.oa.OaStaticFormMappingCatalog.FormKind;
import com.szsemicon.hr.evidenceingestion.domain.oa.OaStaticFormMappingCatalog.FormMapping;
import com.szsemicon.hr.evidenceingestion.domain.oa.OaStaticFormMappingCatalog.LocatedColumn;
import com.szsemicon.hr.evidenceingestion.domain.oa.OaStaticFormMappingCatalog.RowRole;
import com.szsemicon.hr.evidenceingestion.domain.oa.OaStaticFormMappingCatalog.Table;
import com.szsemicon.hr.evidenceingestion.domain.oa.OaStaticFormMappingCatalog.VerificationStatus;
import com.szsemicon.hr.evidenceingestion.port.OaOrgMemberDirectoryPort;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fail-closed structural conversion for statically allowlisted OA rows.
 *
 * <p>This class does not query OA and does not produce canonical instants. It
 * preserves screenshot-declared local temporal values until approval, FK,
 * enum, timezone and date-boundary contracts are signed.</p>
 */
public final class OaFormRowTransformer {

    private static final Pattern SINGLE_NUMERIC_ID = Pattern.compile("[0-9]+");

    private OaFormRowTransformer() {
    }

    public enum IssueCode {
        MISSING_MAIN_ROW,
        MISSING_DETAIL_ROW,
        UNEXPECTED_DETAIL_ROW,
        UNKNOWN_COLUMN,
        MISSING_REQUIRED_VALUE,
        INVALID_SUBJECT_MEMBER_ID,
        ORG_MEMBER_NOT_FOUND,
        ORG_MEMBER_AMBIGUOUS,
        ORG_MEMBER_LOOKUP_FAILED,
        ORG_MEMBER_LOOKUP_ID_MISMATCH,
        ORG_MEMBER_CODE_INVALID,
        EMPLOYEE_CODE_VALUE_INVALID,
        EMPLOYEE_CODE_MISMATCH,
        TEMPORAL_VALUE_INVALID,
        TEMPORAL_RANGE_INVALID,
        LIVE_SCHEMA_NOT_VERIFIED,
        APPROVAL_STATUS_NOT_VERIFIED,
        MAIN_DETAIL_FOREIGN_KEY_NOT_VERIFIED,
        ENUM_MAPPING_NOT_VERIFIED,
        SOURCE_TIME_ZONE_NOT_VERIFIED,
        DATE_RANGE_BOUNDARY_NOT_VERIFIED
    }

    public record Issue(IssueCode code, String fieldName) {

        public Issue {
            Objects.requireNonNull(code, "code");
        }
    }

    public record ResolvedSubject(
            BigInteger orgMemberId,
            String memberCode) {

        public ResolvedSubject {
            Objects.requireNonNull(orgMemberId, "orgMemberId");
            if (memberCode == null || memberCode.isBlank()) {
                throw new IllegalArgumentException(
                        "memberCode must be stored exactly and cannot be blank");
            }
        }
    }

    public sealed interface SourceTemporal
            permits LocalInterval, LocalDateRange, LocalPoint {
    }

    /**
     * A source-local date-time interval. No timezone or Instant is inferred.
     */
    public record LocalInterval(
            LocalDateTime start,
            LocalDateTime end) implements SourceTemporal {

        public LocalInterval {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            if (!end.isAfter(start)) {
                throw new IllegalArgumentException(
                        "interval end must be after start");
            }
        }
    }

    /**
     * Exact source dates. The end date is not expanded to an end-of-day instant
     * and is not labeled inclusive or exclusive before sign-off.
     */
    public record LocalDateRange(
            LocalDate fromDate,
            LocalDate toDate) implements SourceTemporal {

        public LocalDateRange {
            Objects.requireNonNull(fromDate, "fromDate");
            Objects.requireNonNull(toDate, "toDate");
            if (toDate.isBefore(fromDate)) {
                throw new IllegalArgumentException(
                        "date range end cannot precede start");
            }
        }
    }

    /**
     * One exact source-local date-time, used by punch correction.
     */
    public record LocalPoint(LocalDateTime value) implements SourceTemporal {

        public LocalPoint {
            Objects.requireNonNull(value, "value");
        }
    }

    public record Candidate(
            FormKind formKind,
            ResolvedSubject subject,
            SourceTemporal temporal) {

        public Candidate {
            Objects.requireNonNull(formKind, "formKind");
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(temporal, "temporal");
        }
    }

    public record TransformResult(
            Candidate candidate,
            boolean effectiveCandidate,
            List<Issue> issues) {

        public TransformResult {
            issues = List.copyOf(issues);
            if (effectiveCandidate
                    && (candidate == null || !issues.isEmpty())) {
                throw new IllegalArgumentException(
                        "effective candidate must be complete and issue-free");
            }
        }
    }

    public static TransformResult transform(
            FormKind formKind,
            Map<String, ?> mainRow,
            Map<String, ?> detailRow,
            OaOrgMemberDirectoryPort memberDirectory) {
        Objects.requireNonNull(formKind, "formKind");
        Objects.requireNonNull(memberDirectory, "memberDirectory");
        FormMapping mapping = OaStaticFormMappingCatalog.require(formKind);
        Map<String, Object> safeMain = immutableRowCopy(mainRow);
        Map<String, Object> safeDetail = immutableRowCopy(detailRow);
        Set<Issue> issues = new LinkedHashSet<>();

        validateRowPresence(mapping, safeMain, safeDetail, issues);
        validateAllowedColumns(mapping.mainTable(), safeMain, issues);
        if (mapping.detailTable() != null) {
            validateAllowedColumns(mapping.detailTable(), safeDetail, issues);
        }

        ResolvedSubject subject =
                resolveSubject(mapping, safeMain, safeDetail, memberDirectory, issues);
        SourceTemporal temporal =
                transformTemporal(mapping, safeMain, safeDetail, issues);
        if (subject != null) {
            checkEmployeeCodes(
                    mapping, safeMain, safeDetail, subject.memberCode(), issues);
        }
        addUnverifiedContractIssues(mapping, issues);

        Candidate candidate = subject == null || temporal == null
                ? null
                : new Candidate(formKind, subject, temporal);
        List<Issue> orderedIssues = List.copyOf(issues);
        return new TransformResult(
                candidate,
                candidate != null && orderedIssues.isEmpty(),
                orderedIssues);
    }

    private static void validateRowPresence(
            FormMapping mapping,
            Map<String, Object> mainRow,
            Map<String, Object> detailRow,
            Set<Issue> issues) {
        if (mainRow.isEmpty()) {
            issues.add(new Issue(IssueCode.MISSING_MAIN_ROW, null));
        }
        if (mapping.detailTable() == null) {
            if (!detailRow.isEmpty()) {
                issues.add(new Issue(
                        IssueCode.UNEXPECTED_DETAIL_ROW,
                        null));
            }
        } else if (detailRow.isEmpty()) {
            issues.add(new Issue(IssueCode.MISSING_DETAIL_ROW, null));
        }
    }

    private static void validateAllowedColumns(
            Table table,
            Map<String, Object> row,
            Set<Issue> issues) {
        Set<String> allowed = table.allowedColumnNames();
        row.keySet().stream()
                .filter(column -> !allowed.contains(column))
                .sorted()
                .forEach(column -> issues.add(
                        new Issue(IssueCode.UNKNOWN_COLUMN, column)));
    }

    private static ResolvedSubject resolveSubject(
            FormMapping mapping,
            Map<String, Object> mainRow,
            Map<String, Object> detailRow,
            OaOrgMemberDirectoryPort memberDirectory,
            Set<Issue> issues) {
        LocatedColumn subjectColumn = mapping.subjectColumn();
        Object rawId = valueAt(subjectColumn, mainRow, detailRow);
        if (rawId == null) {
            issues.add(new Issue(
                    IssueCode.MISSING_REQUIRED_VALUE,
                    subjectColumn.column().name()));
            return null;
        }
        BigInteger memberId = strictMemberId(rawId);
        if (memberId == null) {
            issues.add(new Issue(
                    IssueCode.INVALID_SUBJECT_MEMBER_ID,
                    subjectColumn.column().name()));
            return null;
        }

        List<OaOrgMemberDirectoryPort.OrgMemberRecord> matches;
        try {
            matches = memberDirectory.findById(memberId);
        } catch (RuntimeException exception) {
            issues.add(new Issue(
                    IssueCode.ORG_MEMBER_LOOKUP_FAILED,
                    subjectColumn.column().name()));
            return null;
        }
        if (matches == null) {
            issues.add(new Issue(
                    IssueCode.ORG_MEMBER_LOOKUP_FAILED,
                    subjectColumn.column().name()));
            return null;
        }
        if (matches.isEmpty()) {
            issues.add(new Issue(
                    IssueCode.ORG_MEMBER_NOT_FOUND,
                    subjectColumn.column().name()));
            return null;
        }
        if (matches.size() != 1) {
            issues.add(new Issue(
                    IssueCode.ORG_MEMBER_AMBIGUOUS,
                    subjectColumn.column().name()));
            return null;
        }

        OaOrgMemberDirectoryPort.OrgMemberRecord record = matches.getFirst();
        if (record == null || !memberId.equals(record.id())) {
            issues.add(new Issue(
                    IssueCode.ORG_MEMBER_LOOKUP_ID_MISMATCH,
                    subjectColumn.column().name()));
            return null;
        }
        if (record.code() == null || record.code().isBlank()) {
            issues.add(new Issue(
                    IssueCode.ORG_MEMBER_CODE_INVALID,
                    subjectColumn.column().name()));
            return null;
        }
        return new ResolvedSubject(memberId, record.code());
    }

    private static void checkEmployeeCodes(
            FormMapping mapping,
            Map<String, Object> mainRow,
            Map<String, Object> detailRow,
            String resolvedCode,
            Set<Issue> issues) {
        for (LocatedColumn column : mapping.employeeCodeCheckColumns()) {
            Object value = valueAt(column, mainRow, detailRow);
            if (value == null) {
                continue;
            }
            if (!(value instanceof String employeeCode)) {
                issues.add(new Issue(
                        IssueCode.EMPLOYEE_CODE_VALUE_INVALID,
                        column.column().name()));
                continue;
            }
            if (!employeeCode.equals(resolvedCode)) {
                issues.add(new Issue(
                        IssueCode.EMPLOYEE_CODE_MISMATCH,
                        column.column().name()));
            }
        }
    }

    private static SourceTemporal transformTemporal(
            FormMapping mapping,
            Map<String, Object> mainRow,
            Map<String, Object> detailRow,
            Set<Issue> issues) {
        return switch (mapping.temporalShape()) {
            case INTERVAL -> localInterval(
                    mapping.temporalStartColumn(),
                    mapping.temporalEndColumn(),
                    mainRow,
                    detailRow,
                    issues);
            case DATE_RANGE -> localDateRange(
                    mapping.temporalStartColumn(),
                    mapping.temporalEndColumn(),
                    mainRow,
                    detailRow,
                    issues);
            case POINT -> localPoint(
                    mapping.temporalPointColumn(),
                    mainRow,
                    detailRow,
                    issues);
        };
    }

    private static SourceTemporal localInterval(
            LocatedColumn startColumn,
            LocatedColumn endColumn,
            Map<String, Object> mainRow,
            Map<String, Object> detailRow,
            Set<Issue> issues) {
        Object startValue = valueAt(startColumn, mainRow, detailRow);
        Object endValue = valueAt(endColumn, mainRow, detailRow);
        if (missingTemporalValue(startValue, startColumn, issues)
                | missingTemporalValue(endValue, endColumn, issues)) {
            return null;
        }
        if (!(startValue instanceof LocalDateTime start)
                || !(endValue instanceof LocalDateTime end)) {
            addTemporalTypeIssue(startValue, startColumn, issues);
            addTemporalTypeIssue(endValue, endColumn, issues);
            return null;
        }
        if (!end.isAfter(start)) {
            issues.add(new Issue(
                    IssueCode.TEMPORAL_RANGE_INVALID,
                    endColumn.column().name()));
            return null;
        }
        return new LocalInterval(start, end);
    }

    private static SourceTemporal localDateRange(
            LocatedColumn startColumn,
            LocatedColumn endColumn,
            Map<String, Object> mainRow,
            Map<String, Object> detailRow,
            Set<Issue> issues) {
        Object startValue = valueAt(startColumn, mainRow, detailRow);
        Object endValue = valueAt(endColumn, mainRow, detailRow);
        if (missingTemporalValue(startValue, startColumn, issues)
                | missingTemporalValue(endValue, endColumn, issues)) {
            return null;
        }
        if (!(startValue instanceof LocalDate start)
                || !(endValue instanceof LocalDate end)) {
            addTemporalTypeIssue(startValue, startColumn, issues);
            addTemporalTypeIssue(endValue, endColumn, issues);
            return null;
        }
        if (end.isBefore(start)) {
            issues.add(new Issue(
                    IssueCode.TEMPORAL_RANGE_INVALID,
                    endColumn.column().name()));
            return null;
        }
        return new LocalDateRange(start, end);
    }

    private static SourceTemporal localPoint(
            LocatedColumn pointColumn,
            Map<String, Object> mainRow,
            Map<String, Object> detailRow,
            Set<Issue> issues) {
        Object value = valueAt(pointColumn, mainRow, detailRow);
        if (missingTemporalValue(value, pointColumn, issues)) {
            return null;
        }
        if (!(value instanceof LocalDateTime point)) {
            addTemporalTypeIssue(value, pointColumn, issues);
            return null;
        }
        return new LocalPoint(point);
    }

    private static boolean missingTemporalValue(
            Object value,
            LocatedColumn column,
            Set<Issue> issues) {
        if (value != null) {
            return false;
        }
        issues.add(new Issue(
                IssueCode.MISSING_REQUIRED_VALUE,
                column.column().name()));
        return true;
    }

    private static void addTemporalTypeIssue(
            Object value,
            LocatedColumn column,
            Set<Issue> issues) {
        if (value != null) {
            issues.add(new Issue(
                    IssueCode.TEMPORAL_VALUE_INVALID,
                    column.column().name()));
        }
    }

    private static void addUnverifiedContractIssues(
            FormMapping mapping,
            Set<Issue> issues) {
        mapping.contractStates().forEach((component, status) -> {
            if (status == VerificationStatus.NOT_VERIFIED) {
                issues.add(new Issue(issueCode(component), null));
            }
        });
    }

    private static IssueCode issueCode(ContractComponent component) {
        return switch (component) {
            case LIVE_SCHEMA -> IssueCode.LIVE_SCHEMA_NOT_VERIFIED;
            case APPROVAL_STATUS ->
                    IssueCode.APPROVAL_STATUS_NOT_VERIFIED;
            case MAIN_DETAIL_FOREIGN_KEY ->
                    IssueCode.MAIN_DETAIL_FOREIGN_KEY_NOT_VERIFIED;
            case ENUM_VALUES -> IssueCode.ENUM_MAPPING_NOT_VERIFIED;
            case SOURCE_TIME_ZONE ->
                    IssueCode.SOURCE_TIME_ZONE_NOT_VERIFIED;
            case DATE_RANGE_BOUNDARY ->
                    IssueCode.DATE_RANGE_BOUNDARY_NOT_VERIFIED;
        };
    }

    private static Object valueAt(
            LocatedColumn column,
            Map<String, Object> mainRow,
            Map<String, Object> detailRow) {
        Map<String, Object> row = column.rowRole() == RowRole.MAIN
                ? mainRow : detailRow;
        return row.get(column.column().name());
    }

    private static BigInteger strictMemberId(Object rawValue) {
        BigInteger parsed;
        if (rawValue instanceof String text) {
            if (!SINGLE_NUMERIC_ID.matcher(text).matches()) {
                return null;
            }
            parsed = new BigInteger(text);
        } else if (rawValue instanceof BigInteger integer) {
            parsed = integer;
        } else if (rawValue instanceof Byte
                || rawValue instanceof Short
                || rawValue instanceof Integer
                || rawValue instanceof Long) {
            parsed = BigInteger.valueOf(((Number) rawValue).longValue());
        } else {
            return null;
        }
        return parsed.signum() > 0 ? parsed : null;
    }

    private static Map<String, Object> immutableRowCopy(Map<String, ?> row) {
        if (row == null) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : row.entrySet()) {
            if (entry.getKey() == null) {
                throw new IllegalArgumentException(
                        "OA row column name cannot be null");
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }
}
