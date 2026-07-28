package com.szsemicon.hr.wave4;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.evidenceingestion.domain.oa.OaStaticFormMappingCatalog;
import com.szsemicon.hr.evidenceingestion.domain.oa.OaStaticFormMappingCatalog.ContractComponent;
import com.szsemicon.hr.evidenceingestion.domain.oa.OaStaticFormMappingCatalog.FormKind;
import com.szsemicon.hr.evidenceingestion.domain.oa.OaStaticFormMappingCatalog.RowRole;
import com.szsemicon.hr.evidenceingestion.domain.oa.OaStaticFormMappingCatalog.TemporalShape;
import com.szsemicon.hr.evidenceingestion.domain.oa.OaStaticFormMappingCatalog.VerificationStatus;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OaStaticFormMappingCatalogTest {

    @Test
    void registersTheExactSixFamiliesAndTenPhysicalTables() {
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
                "field0083", "field0084", "field0086", "field0087",
                "field0088", "field0089", "field0090", "field0091",
                "field0092", "field0093", "field0094", "field0095",
                "field0096", "field0074", "field0075", "field0076");
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
}
