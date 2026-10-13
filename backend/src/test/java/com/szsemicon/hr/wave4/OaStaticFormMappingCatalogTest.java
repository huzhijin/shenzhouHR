package com.szsemicon.hr.wave4;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.evidenceingestion.domain.oa.OaStaticFormMappingCatalog;
import com.szsemicon.hr.evidenceingestion.domain.oa.OaStaticFormMappingCatalog.ContractComponent;
import com.szsemicon.hr.evidenceingestion.domain.oa.OaStaticFormMappingCatalog.FieldPurpose;
import com.szsemicon.hr.evidenceingestion.domain.oa.OaStaticFormMappingCatalog.FormKind;
import com.szsemicon.hr.evidenceingestion.domain.oa.OaStaticFormMappingCatalog.RowRole;
import com.szsemicon.hr.evidenceingestion.domain.oa.OaStaticFormMappingCatalog.TemporalShape;
import com.szsemicon.hr.evidenceingestion.domain.oa.OaStaticFormMappingCatalog.VerificationStatus;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort.DocumentType;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OaStaticFormMappingCatalogTest {

    @Test
    void registersTheExactSevenFamiliesAndElevenPhysicalTables() {
        assertThat(OaStaticFormMappingCatalog.all())
                .extracting(mapping -> mapping.formKind())
                .containsExactly(FormKind.values());

        assertTables(
                FormKind.TRIP,
                "formmain_0265");
        assertTables(
                FormKind.LEAVE,
                "formmain_0170");
        assertTables(
                FormKind.LEAVE_REVOCATION,
                "formmain_0370");
        assertTables(
                FormKind.OVERTIME,
                "formmain_0171",
                "formson_0172");
        assertTables(
                FormKind.OUTING,
                "formmain_0251",
                "formson_0252");
        assertTables(
                FormKind.EXEMPT_PUNCH,
                "formmain_0201",
                "formson_0202");
        assertTables(
                FormKind.PUNCH_CORRECTION,
                "formmain_0203",
                "formson_0204");

        assertThat(OaStaticFormMappingCatalog.all())
                .flatExtracting(mapping -> mapping.tables())
                .hasSize(11);
        assertThat(OaStaticFormMappingCatalog.all())
                .flatExtracting(mapping -> mapping.columns())
                .hasSize(104);

        assertThat(DocumentType.valueOf(FormKind.LEAVE_REVOCATION.name()))
                .isSameAs(DocumentType.LEAVE_REVOCATION);
    }

    @Test
    void keepsTheScreenshotColumnsInAClosedAllowlist() {
        assertColumns(
                FormKind.TRIP,
                RowRole.MAIN,
                "field0137", "field0148", "field0149", "field0140",
                "field0141", "field0142", "field0083", "field0084",
                "field0085", "field0138", "field0139", "field0154",
                "field0155", "field0156", "field0151", "field0152",
                "field0153");
        assertColumns(
                FormKind.LEAVE,
                RowRole.MAIN,
                "field0097", "field0083", "field0084", "field0086", "field0087",
                "field0088", "field0103", "field0089", "field0090", "field0091",
                "field0092", "field0093", "field0094", "field0095",
                "field0096", "field0074", "field0075", "field0076");
        assertColumns(
                FormKind.LEAVE_REVOCATION,
                RowRole.MAIN,
                "field0097", "field0074", "field0075", "field0076",
                "field0100", "field0098", "field0099", "field0083",
                "field0092", "field0093", "field0085", "field0084",
                "field0089", "field0086", "field0087", "field0088",
                "field0107", "field0090", "field0091", "field0094", "field0095",
                "field0096");
        assertColumns(
                FormKind.OVERTIME,
                RowRole.MAIN,
                "field0074", "field0075", "field0076", "field0102",
                "field0104", "field0105");
        assertColumns(
                FormKind.OVERTIME,
                RowRole.DETAIL,
                "field0092", "field0093", "field0094", "field0095",
                "field0096", "field0100", "field0099", "field0101",
                "field0103");
        assertColumns(
                FormKind.OUTING,
                RowRole.MAIN,
                "field0083", "field0084", "field0085");
        assertColumns(
                FormKind.OUTING,
                RowRole.DETAIL,
                "field0126", "field0127", "field0130", "field0131",
                "field0132", "field0135", "field0133");
        assertColumns(
                FormKind.EXEMPT_PUNCH,
                RowRole.MAIN,
                "field0083", "field0084", "field0085");
        assertColumns(
                FormKind.EXEMPT_PUNCH,
                RowRole.DETAIL,
                "field0126", "field0127", "field0129", "field0130",
                "field0131", "field0132", "field0134", "field0133");
        assertColumns(
                FormKind.PUNCH_CORRECTION,
                RowRole.MAIN,
                "field0083", "field0084", "field0085");
        assertColumns(
                FormKind.PUNCH_CORRECTION,
                RowRole.DETAIL,
                "field0126", "field0127", "field0129", "field0130",
                "field0131", "field0132", "field0133", "field0134");
    }

    @Test
    void distinguishesIntervalPointAndSourceDateRangeWithoutGuessing() {
        Map<FormKind, TemporalShape> expected = Map.of(
                FormKind.TRIP, TemporalShape.INTERVAL,
                FormKind.LEAVE, TemporalShape.INTERVAL,
                FormKind.LEAVE_REVOCATION, TemporalShape.INTERVAL,
                FormKind.OVERTIME, TemporalShape.INTERVAL,
                FormKind.OUTING, TemporalShape.INTERVAL,
                FormKind.EXEMPT_PUNCH, TemporalShape.DATE_RANGE,
                FormKind.PUNCH_CORRECTION, TemporalShape.POINT);
        expected.forEach((kind, shape) ->
                assertThat(OaStaticFormMappingCatalog.require(kind)
                        .temporalShape()).isEqualTo(shape));
    }

    @Test
    void leavesEveryUnconfirmedSystemContractFailClosed() {
        for (var mapping : OaStaticFormMappingCatalog.all()) {
            assertThat(mapping.liveSchemaStatus())
                    .isEqualTo(VerificationStatus.NOT_VERIFIED);
            assertThat(mapping.approvalStatusContract())
                    .isEqualTo(VerificationStatus.NOT_VERIFIED);
            assertThat(mapping.activationReady()).isFalse();
            assertThat(mapping.contractStates())
                    .containsEntry(
                            ContractComponent.APPROVAL_STATUS,
                            VerificationStatus.NOT_VERIFIED);
            if (mapping.detailTable() == null) {
                assertThat(mapping.mainDetailForeignKeyContract())
                        .isEqualTo(VerificationStatus.NOT_APPLICABLE);
            } else {
                assertThat(mapping.mainDetailForeignKeyContract())
                        .isEqualTo(VerificationStatus.NOT_VERIFIED);
            }
        }

        assertThat(OaStaticFormMappingCatalog.require(FormKind.LEAVE)
                .enumColumns())
                .extracting(column -> column.column().name())
                .containsExactly("field0089");
        assertThat(OaStaticFormMappingCatalog.require(FormKind.LEAVE)
                .enumColumns())
                .extracting(column -> column.column().purpose())
                .containsExactly(FieldPurpose.LEAVE_TYPE_ENUM);
        assertThat(OaStaticFormMappingCatalog
                .require(FormKind.LEAVE_REVOCATION)
                .enumColumns())
                .extracting(column -> column.column().name())
                .containsExactly("field0100", "field0089");
        assertThat(OaStaticFormMappingCatalog
                .require(FormKind.LEAVE_REVOCATION)
                .enumColumns())
                .extracting(column -> column.column().purpose())
                .containsExactly(
                        FieldPurpose.LEAVE_TYPE_ENUM,
                        FieldPurpose.LEAVE_TYPE_ENUM);
        assertThat(OaStaticFormMappingCatalog.require(FormKind.OVERTIME)
                .enumColumns())
                .extracting(column -> column.column().name())
                .containsExactly("field0096");
        assertThat(OaStaticFormMappingCatalog
                .require(FormKind.PUNCH_CORRECTION)
                .enumColumns())
                .extracting(column -> column.column().name())
                .containsExactly("field0134");
    }

    @Test
    void declaresLeaveSerialNumberAsContextForRevocationLinkage() {
        assertThat(OaStaticFormMappingCatalog.require(FormKind.LEAVE)
                .mainTable()
                .columns())
                .filteredOn(column -> column.name().equals("field0097"))
                .singleElement()
                .satisfies(column -> {
                    assertThat(column.label())
                            .isEqualTo("流水号（关联销假单）");
                    assertThat(column.purpose())
                            .isEqualTo(FieldPurpose.CONTEXT_ONLY);
                    assertThat(column.valueKind())
                            .isEqualTo(OaStaticFormMappingCatalog.SourceValueKind.TEXT);
                    assertThat(column.verificationStatus())
                            .isEqualTo(VerificationStatus.SCREENSHOT_DECLARED);
                });
    }

    @Test
    void scopesSystemHourFieldsToTheirPhysicalTables() {
        assertSystemHours(
                FormKind.LEAVE,
                "field0103",
                "系统计算小时");
        assertSystemHours(
                FormKind.LEAVE_REVOCATION,
                "field0107",
                "系统计算返还小时");

        assertThat(OaStaticFormMappingCatalog.require(FormKind.OVERTIME)
                .detailTable()
                .columns())
                .filteredOn(column -> column.name().equals("field0103"))
                .singleElement()
                .satisfies(column -> {
                    assertThat(column.label()).isEqualTo("原因");
                    assertThat(column.purpose())
                            .isEqualTo(FieldPurpose.CONTEXT_ONLY);
                    assertThat(column.valueKind())
                            .isEqualTo(OaStaticFormMappingCatalog.SourceValueKind.TEXT);
                });
    }

    @Test
    void resolvesOnlyExactWhitelistedTableNamesAndNeverSqlFragments() {
        assertThat(OaStaticFormMappingCatalog
                .requireByPhysicalTable("formson_0204")
                .formKind()).isEqualTo(FormKind.PUNCH_CORRECTION);

        assertThatThrownBy(() -> OaStaticFormMappingCatalog
                .requireByPhysicalTable("formmain_9999"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unsupported OA physical table");
        assertThatThrownBy(() -> OaStaticFormMappingCatalog
                .requireByPhysicalTable("formmain_0265 where 1=1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unsupported OA physical table");
    }

    private static void assertTables(
            FormKind formKind,
            String... expectedNames) {
        assertThat(OaStaticFormMappingCatalog.require(formKind).tables())
                .extracting(table -> table.name())
                .containsExactly(expectedNames);
    }

    private static void assertColumns(
            FormKind formKind,
            RowRole rowRole,
            String... expectedNames) {
        assertThat(OaStaticFormMappingCatalog.require(formKind)
                .table(rowRole)
                .columns())
                .extracting(column -> column.name())
                .containsExactly(expectedNames);
    }

    private static void assertSystemHours(
            FormKind formKind,
            String fieldName,
            String label) {
        assertThat(OaStaticFormMappingCatalog.require(formKind)
                .mainTable()
                .columns())
                .filteredOn(column -> column.name().equals(fieldName))
                .singleElement()
                .satisfies(column -> {
                    assertThat(column.label()).isEqualTo(label);
                    assertThat(column.purpose())
                            .isEqualTo(FieldPurpose.SYSTEM_CALCULATED_HOURS);
                    assertThat(column.valueKind())
                            .isEqualTo(OaStaticFormMappingCatalog.SourceValueKind.DECIMAL);
                    assertThat(column.verificationStatus())
                            .isEqualTo(VerificationStatus.NOT_VERIFIED);
                });
    }
}
