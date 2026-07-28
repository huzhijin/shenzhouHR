package com.szsemicon.hr.evidenceingestion.domain.oa;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Compile-time allowlist for the six OA attendance form families.
 *
 * <p>This catalog contains identifiers transcribed from the supplied OA
 * screenshots. It is not a live database contract and deliberately contains no
 * SQL, approval-state column, main/detail foreign key or enum value mapping.</p>
 */
public final class OaStaticFormMappingCatalog {

    private static final Pattern TABLE_NAME =
            Pattern.compile("form(?:main|son)_\\d{4}");
    private static final Pattern COLUMN_NAME =
            Pattern.compile("field\\d{4}");

    private static final Map<FormKind, FormMapping> BY_KIND;
    private static final Map<String, FormMapping> BY_PHYSICAL_TABLE;

    static {
        EnumMap<FormKind, FormMapping> mappings =
                new EnumMap<>(FormKind.class);
        register(mappings, trip());
        register(mappings, leave());
        register(mappings, overtime());
        register(mappings, outing());
        register(mappings, exemptPunch());
        register(mappings, punchCorrection());
        BY_KIND = Collections.unmodifiableMap(mappings);

        Map<String, FormMapping> byTable = new LinkedHashMap<>();
        for (FormMapping mapping : mappings.values()) {
            putUnique(byTable, mapping.mainTable().name(), mapping);
            if (mapping.detailTable() != null) {
                putUnique(byTable, mapping.detailTable().name(), mapping);
            }
        }
        BY_PHYSICAL_TABLE = Collections.unmodifiableMap(byTable);
    }

    private OaStaticFormMappingCatalog() {
    }

    public enum FormKind {
        TRIP,
        LEAVE,
        OVERTIME,
        OUTING,
        EXEMPT_PUNCH,
        PUNCH_CORRECTION
    }

    public enum TemporalShape {
        INTERVAL,
        POINT,
        DATE_RANGE
    }

    public enum RowRole {
        MAIN,
        DETAIL
    }

    public enum FieldPurpose {
        SUBJECT_MEMBER_ID,
        EMPLOYEE_CODE_CHECK_ONLY,
        TEMPORAL_START,
        TEMPORAL_END,
        TEMPORAL_POINT,
        ENUM_NOT_VERIFIED,
        DURATION_CHECK_ONLY,
        CONTEXT_ONLY
    }

    public enum SourceValueKind {
        MEMBER_ID,
        TEXT,
        DATE,
        DATE_TIME,
        DECIMAL,
        ENUM
    }

    public enum VerificationStatus {
        SCREENSHOT_DECLARED,
        NOT_VERIFIED,
        NOT_APPLICABLE
    }

    public enum ContractComponent {
        LIVE_SCHEMA,
        APPROVAL_STATUS,
        MAIN_DETAIL_FOREIGN_KEY,
        ENUM_VALUES,
        SOURCE_TIME_ZONE,
        DATE_RANGE_BOUNDARY
    }

    public record Column(
            String name,
            String label,
            FieldPurpose purpose,
            SourceValueKind valueKind,
            VerificationStatus verificationStatus) {

        public Column {
            requireIdentifier(COLUMN_NAME, name, "column");
            requireText(label, "label");
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(valueKind, "valueKind");
            Objects.requireNonNull(verificationStatus, "verificationStatus");
        }
    }

    public record Table(
            String name,
            RowRole rowRole,
            List<Column> columns) {

        public Table {
            requireIdentifier(TABLE_NAME, name, "table");
            Objects.requireNonNull(rowRole, "rowRole");
            columns = List.copyOf(columns);
            if (columns.isEmpty()) {
                throw new IllegalArgumentException(
                        "table must declare at least one allowed column");
            }
            ensureUniqueColumnNames(name, columns);
        }

        public Set<String> allowedColumnNames() {
            Set<String> names = new HashSet<>();
            columns.forEach(column -> names.add(column.name()));
            return Set.copyOf(names);
        }
    }

    public record LocatedColumn(RowRole rowRole, Column column) {

        public LocatedColumn {
            Objects.requireNonNull(rowRole, "rowRole");
            Objects.requireNonNull(column, "column");
        }
    }

    public record FormMapping(
            FormKind formKind,
            TemporalShape temporalShape,
            Table mainTable,
            Table detailTable,
            VerificationStatus liveSchemaStatus,
            VerificationStatus approvalStatusContract,
            VerificationStatus mainDetailForeignKeyContract,
            VerificationStatus sourceTimeZoneContract,
            VerificationStatus dateRangeBoundaryContract) {

        public FormMapping {
            Objects.requireNonNull(formKind, "formKind");
            Objects.requireNonNull(temporalShape, "temporalShape");
            Objects.requireNonNull(mainTable, "mainTable");
            Objects.requireNonNull(liveSchemaStatus, "liveSchemaStatus");
            Objects.requireNonNull(
                    approvalStatusContract, "approvalStatusContract");
            Objects.requireNonNull(
                    mainDetailForeignKeyContract,
                    "mainDetailForeignKeyContract");
            Objects.requireNonNull(
                    sourceTimeZoneContract, "sourceTimeZoneContract");
            Objects.requireNonNull(
                    dateRangeBoundaryContract,
                    "dateRangeBoundaryContract");
            if (mainTable.rowRole() != RowRole.MAIN) {
                throw new IllegalArgumentException(
                        "main table must have MAIN row role");
            }
            if (detailTable != null && detailTable.rowRole() != RowRole.DETAIL) {
                throw new IllegalArgumentException(
                        "detail table must have DETAIL row role");
            }
            validateShapeContract(
                    temporalShape,
                    locatedColumns(mainTable, detailTable));
            if (detailTable == null
                    && mainDetailForeignKeyContract
                    != VerificationStatus.NOT_APPLICABLE) {
                throw new IllegalArgumentException(
                        "single-table form cannot declare a foreign-key contract");
            }
            if (detailTable != null
                    && mainDetailForeignKeyContract
                    == VerificationStatus.NOT_APPLICABLE) {
                throw new IllegalArgumentException(
                        "main/detail form must declare a foreign-key contract state");
            }
        }

        public List<Table> tables() {
            return detailTable == null
                    ? List.of(mainTable)
                    : List.of(mainTable, detailTable);
        }

        public List<LocatedColumn> columns() {
            return locatedColumns(mainTable, detailTable);
        }

        public LocatedColumn subjectColumn() {
            return requireExactlyOne(columns(), FieldPurpose.SUBJECT_MEMBER_ID);
        }

        public List<LocatedColumn> employeeCodeCheckColumns() {
            return columns().stream()
                    .filter(column -> column.column().purpose()
                            == FieldPurpose.EMPLOYEE_CODE_CHECK_ONLY)
                    .toList();
        }

        public List<LocatedColumn> enumColumns() {
            return columns().stream()
                    .filter(column -> column.column().purpose()
                            == FieldPurpose.ENUM_NOT_VERIFIED)
                    .toList();
        }

        public LocatedColumn temporalStartColumn() {
            return requireExactlyOne(columns(), FieldPurpose.TEMPORAL_START);
        }

        public LocatedColumn temporalEndColumn() {
            return requireExactlyOne(columns(), FieldPurpose.TEMPORAL_END);
        }

        public LocatedColumn temporalPointColumn() {
            return requireExactlyOne(columns(), FieldPurpose.TEMPORAL_POINT);
        }

        public Map<ContractComponent, VerificationStatus> contractStates() {
            EnumMap<ContractComponent, VerificationStatus> states =
                    new EnumMap<>(ContractComponent.class);
            states.put(ContractComponent.LIVE_SCHEMA, liveSchemaStatus);
            states.put(
                    ContractComponent.APPROVAL_STATUS,
                    approvalStatusContract);
            states.put(
                    ContractComponent.MAIN_DETAIL_FOREIGN_KEY,
                    mainDetailForeignKeyContract);
            states.put(
                    ContractComponent.ENUM_VALUES,
                    enumColumns().isEmpty()
                            ? VerificationStatus.NOT_APPLICABLE
                            : VerificationStatus.NOT_VERIFIED);
            states.put(
                    ContractComponent.SOURCE_TIME_ZONE,
                    sourceTimeZoneContract);
            states.put(
                    ContractComponent.DATE_RANGE_BOUNDARY,
                    dateRangeBoundaryContract);
            return Collections.unmodifiableMap(states);
        }

        public boolean activationReady() {
            return contractStates().values().stream()
                    .noneMatch(status ->
                            status == VerificationStatus.NOT_VERIFIED);
        }

        public Table table(RowRole rowRole) {
            Objects.requireNonNull(rowRole, "rowRole");
            if (rowRole == RowRole.MAIN) {
                return mainTable;
            }
            if (detailTable == null) {
                throw new IllegalArgumentException(
                        "form " + formKind + " has no detail table");
            }
            return detailTable;
        }
    }

    public static List<FormMapping> all() {
        List<FormMapping> ordered = new ArrayList<>();
        for (FormKind kind : FormKind.values()) {
            ordered.add(BY_KIND.get(kind));
        }
        return List.copyOf(ordered);
    }

    public static FormMapping require(FormKind formKind) {
        Objects.requireNonNull(formKind, "formKind");
        FormMapping mapping = BY_KIND.get(formKind);
        if (mapping == null) {
            throw new IllegalArgumentException(
                    "unsupported OA form kind");
        }
        return mapping;
    }

    /**
     * Resolves a physical table only through the static allowlist.
     *
     * <p>The returned mapping supplies identifiers only. It never accepts or
     * produces a SQL fragment.</p>
     */
    public static FormMapping requireByPhysicalTable(String physicalTable) {
        if (physicalTable == null) {
            throw new IllegalArgumentException(
                    "unsupported OA physical table");
        }
        FormMapping mapping = BY_PHYSICAL_TABLE.get(physicalTable);
        if (mapping == null) {
            throw new IllegalArgumentException(
                    "unsupported OA physical table");
        }
        return mapping;
    }

    private static FormMapping trip() {
        return singleTable(
                FormKind.TRIP,
                TemporalShape.INTERVAL,
                table(
                        "formmain_0265",
                        RowRole.MAIN,
                        member("field0137", "主体选人"),
                        dateTimeStart("field0148", "开始"),
                        dateTimeEnd("field0149", "结束"),
                        context("field0140", "事由", SourceValueKind.TEXT),
                        duration("field0141", "累计天数"),
                        context("field0142", "地点", SourceValueKind.TEXT),
                        context("field0083", "填表人", SourceValueKind.TEXT),
                        context("field0084", "填表部门", SourceValueKind.TEXT),
                        context("field0085", "填表日期", SourceValueKind.DATE),
                        context("field0138", "出差人部门", SourceValueKind.TEXT),
                        context("field0139", "出差人岗位", SourceValueKind.TEXT),
                        context("field0154", "所属岗位", SourceValueKind.TEXT),
                        context("field0155", "代理人", SourceValueKind.TEXT),
                        context("field0156", "所属部门", SourceValueKind.TEXT),
                        context("field0151", "交通字段 1", SourceValueKind.TEXT),
                        context("field0152", "交通字段 2", SourceValueKind.TEXT),
                        context("field0153", "交通字段 3", SourceValueKind.TEXT)));
    }

    private static FormMapping leave() {
        return singleTable(
                FormKind.LEAVE,
                TemporalShape.INTERVAL,
                table(
                        "formmain_0170",
                        RowRole.MAIN,
                        member("field0083", "主体选人"),
                        employeeCode("field0084", "表单工号"),
                        dateTimeStart("field0086", "开始"),
                        dateTimeEnd("field0087", "结束"),
                        duration("field0088", "天数"),
                        unknownEnum("field0089", "类别"),
                        context("field0090", "备注", SourceValueKind.TEXT),
                        context("field0091", "说明", SourceValueKind.TEXT),
                        context("field0092", "岗位", SourceValueKind.TEXT),
                        context("field0093", "级别", SourceValueKind.TEXT),
                        context("field0094", "代理人", SourceValueKind.TEXT),
                        context("field0095", "所属部门", SourceValueKind.TEXT),
                        employeeCode("field0096", "表单工号（二次核验）"),
                        context("field0074", "填表人", SourceValueKind.TEXT),
                        context("field0075", "填表部门", SourceValueKind.TEXT),
                        context("field0076", "填表日期", SourceValueKind.DATE)));
    }

    private static FormMapping overtime() {
        return mainDetail(
                FormKind.OVERTIME,
                TemporalShape.INTERVAL,
                table(
                        "formmain_0171",
                        RowRole.MAIN,
                        context("field0074", "填表人", SourceValueKind.TEXT),
                        context("field0075", "填表部门", SourceValueKind.TEXT),
                        context("field0076", "填表日期", SourceValueKind.DATE),
                        context(
                                "field0102",
                                "明细最早时间",
                                SourceValueKind.DATE_TIME),
                        duration("field0104", "系统差值"),
                        context("field0105", "系统差值文本", SourceValueKind.TEXT)),
                table(
                        "formson_0172",
                        RowRole.DETAIL,
                        context("field0092", "序号", SourceValueKind.DECIMAL),
                        member("field0093", "主体选人"),
                        employeeCode("field0094", "表单工号"),
                        context("field0095", "部门", SourceValueKind.TEXT),
                        unknownEnum("field0096", "类别"),
                        dateTimeStart("field0100", "开始"),
                        dateTimeEnd("field0099", "结束"),
                        duration("field0101", "总时长"),
                        context("field0103", "原因", SourceValueKind.TEXT)));
    }

    private static FormMapping outing() {
        return mainDetail(
                FormKind.OUTING,
                TemporalShape.INTERVAL,
                standardMain("formmain_0251"),
                table(
                        "formson_0252",
                        RowRole.DETAIL,
                        context("field0126", "序号", SourceValueKind.DECIMAL),
                        member("field0127", "主体选人"),
                        context("field0130", "部门", SourceValueKind.TEXT),
                        employeeCode("field0131", "表单工号"),
                        dateTimeStart("field0132", "开始"),
                        dateTimeEnd("field0135", "结束"),
                        context("field0133", "事由", SourceValueKind.TEXT)));
    }

    private static FormMapping exemptPunch() {
        return mainDetail(
                FormKind.EXEMPT_PUNCH,
                TemporalShape.DATE_RANGE,
                standardMain("formmain_0201"),
                table(
                        "formson_0202",
                        RowRole.DETAIL,
                        context("field0126", "序号", SourceValueKind.DECIMAL),
                        member("field0127", "主体选人"),
                        context("field0129", "岗位", SourceValueKind.TEXT),
                        context("field0130", "部门", SourceValueKind.TEXT),
                        employeeCode("field0131", "表单工号"),
                        dateStart("field0132", "开始日期"),
                        dateEnd("field0134", "结束日期"),
                        context("field0133", "原因", SourceValueKind.TEXT)));
    }

    private static FormMapping punchCorrection() {
        return mainDetail(
                FormKind.PUNCH_CORRECTION,
                TemporalShape.POINT,
                standardMain("formmain_0203"),
                table(
                        "formson_0204",
                        RowRole.DETAIL,
                        context("field0126", "序号", SourceValueKind.DECIMAL),
                        member("field0127", "主体选人"),
                        context("field0129", "岗位", SourceValueKind.TEXT),
                        context("field0130", "部门", SourceValueKind.TEXT),
                        employeeCode("field0131", "表单工号"),
                        dateTimePoint("field0132", "补签时间"),
                        context("field0133", "原因", SourceValueKind.TEXT),
                        unknownEnum("field0134", "补卡类型")));
    }

    private static FormMapping singleTable(
            FormKind formKind,
            TemporalShape temporalShape,
            Table mainTable) {
        return new FormMapping(
                formKind,
                temporalShape,
                mainTable,
                null,
                VerificationStatus.NOT_VERIFIED,
                VerificationStatus.NOT_VERIFIED,
                VerificationStatus.NOT_APPLICABLE,
                VerificationStatus.NOT_VERIFIED,
                VerificationStatus.NOT_APPLICABLE);
    }

    private static FormMapping mainDetail(
            FormKind formKind,
            TemporalShape temporalShape,
            Table mainTable,
            Table detailTable) {
        return new FormMapping(
                formKind,
                temporalShape,
                mainTable,
                detailTable,
                VerificationStatus.NOT_VERIFIED,
                VerificationStatus.NOT_VERIFIED,
                VerificationStatus.NOT_VERIFIED,
                temporalShape == TemporalShape.DATE_RANGE
                        ? VerificationStatus.NOT_APPLICABLE
                        : VerificationStatus.NOT_VERIFIED,
                temporalShape == TemporalShape.DATE_RANGE
                        ? VerificationStatus.NOT_VERIFIED
                        : VerificationStatus.NOT_APPLICABLE);
    }

    private static Table standardMain(String tableName) {
        return table(
                tableName,
                RowRole.MAIN,
                context("field0083", "填表人", SourceValueKind.TEXT),
                context("field0084", "填表部门", SourceValueKind.TEXT),
                context("field0085", "填表日期", SourceValueKind.DATE));
    }

    private static Table table(
            String name,
            RowRole rowRole,
            Column... columns) {
        return new Table(name, rowRole, List.of(columns));
    }

    private static Column member(String name, String label) {
        return new Column(
                name,
                label,
                FieldPurpose.SUBJECT_MEMBER_ID,
                SourceValueKind.MEMBER_ID,
                VerificationStatus.SCREENSHOT_DECLARED);
    }

    private static Column employeeCode(String name, String label) {
        return new Column(
                name,
                label,
                FieldPurpose.EMPLOYEE_CODE_CHECK_ONLY,
                SourceValueKind.TEXT,
                VerificationStatus.SCREENSHOT_DECLARED);
    }

    private static Column dateTimeStart(String name, String label) {
        return new Column(
                name,
                label,
                FieldPurpose.TEMPORAL_START,
                SourceValueKind.DATE_TIME,
                VerificationStatus.SCREENSHOT_DECLARED);
    }

    private static Column dateTimeEnd(String name, String label) {
        return new Column(
                name,
                label,
                FieldPurpose.TEMPORAL_END,
                SourceValueKind.DATE_TIME,
                VerificationStatus.SCREENSHOT_DECLARED);
    }

    private static Column dateStart(String name, String label) {
        return new Column(
                name,
                label,
                FieldPurpose.TEMPORAL_START,
                SourceValueKind.DATE,
                VerificationStatus.SCREENSHOT_DECLARED);
    }

    private static Column dateEnd(String name, String label) {
        return new Column(
                name,
                label,
                FieldPurpose.TEMPORAL_END,
                SourceValueKind.DATE,
                VerificationStatus.SCREENSHOT_DECLARED);
    }

    private static Column dateTimePoint(String name, String label) {
        return new Column(
                name,
                label,
                FieldPurpose.TEMPORAL_POINT,
                SourceValueKind.DATE_TIME,
                VerificationStatus.SCREENSHOT_DECLARED);
    }

    private static Column unknownEnum(String name, String label) {
        return new Column(
                name,
                label,
                FieldPurpose.ENUM_NOT_VERIFIED,
                SourceValueKind.ENUM,
                VerificationStatus.NOT_VERIFIED);
    }

    private static Column duration(String name, String label) {
        return new Column(
                name,
                label,
                FieldPurpose.DURATION_CHECK_ONLY,
                SourceValueKind.DECIMAL,
                VerificationStatus.SCREENSHOT_DECLARED);
    }

    private static Column context(
            String name,
            String label,
            SourceValueKind valueKind) {
        return new Column(
                name,
                label,
                FieldPurpose.CONTEXT_ONLY,
                valueKind,
                VerificationStatus.SCREENSHOT_DECLARED);
    }

    private static void register(
            Map<FormKind, FormMapping> mappings,
            FormMapping mapping) {
        if (mappings.put(mapping.formKind(), mapping) != null) {
            throw new IllegalStateException(
                    "duplicate OA form kind: " + mapping.formKind());
        }
    }

    private static void putUnique(
            Map<String, FormMapping> mappings,
            String table,
            FormMapping mapping) {
        if (mappings.put(table, mapping) != null) {
            throw new IllegalStateException(
                    "duplicate OA physical table");
        }
    }

    private static List<LocatedColumn> locatedColumns(
            Table mainTable,
            Table detailTable) {
        List<LocatedColumn> result = new ArrayList<>();
        mainTable.columns().forEach(column ->
                result.add(new LocatedColumn(RowRole.MAIN, column)));
        if (detailTable != null) {
            detailTable.columns().forEach(column ->
                    result.add(new LocatedColumn(RowRole.DETAIL, column)));
        }
        return List.copyOf(result);
    }

    private static LocatedColumn requireExactlyOne(
            List<LocatedColumn> columns,
            FieldPurpose purpose) {
        List<LocatedColumn> matches = columns.stream()
                .filter(column -> column.column().purpose() == purpose)
                .toList();
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "mapping must have exactly one " + purpose);
        }
        return matches.getFirst();
    }

    private static void validateShapeContract(
            TemporalShape shape,
            List<LocatedColumn> columns) {
        long starts = count(columns, FieldPurpose.TEMPORAL_START);
        long ends = count(columns, FieldPurpose.TEMPORAL_END);
        long points = count(columns, FieldPurpose.TEMPORAL_POINT);
        if (count(columns, FieldPurpose.SUBJECT_MEMBER_ID) != 1) {
            throw new IllegalArgumentException(
                    "mapping must declare exactly one subject member field");
        }
        boolean valid = switch (shape) {
            case INTERVAL, DATE_RANGE ->
                    starts == 1 && ends == 1 && points == 0;
            case POINT -> starts == 0 && ends == 0 && points == 1;
        };
        if (!valid) {
            throw new IllegalArgumentException(
                    "temporal columns do not match " + shape);
        }
    }

    private static long count(
            List<LocatedColumn> columns,
            FieldPurpose purpose) {
        return columns.stream()
                .filter(column -> column.column().purpose() == purpose)
                .count();
    }

    private static void ensureUniqueColumnNames(
            String tableName,
            List<Column> columns) {
        Set<String> names = new HashSet<>();
        for (Column column : columns) {
            if (!names.add(column.name())) {
                throw new IllegalArgumentException(
                        "duplicate column in " + tableName);
            }
        }
    }

    private static void requireIdentifier(
            Pattern pattern,
            String value,
            String label) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    label + " is not a statically allowed identifier");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
