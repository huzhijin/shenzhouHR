package com.szsemicon.hr.wave4;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.punchimport.domain.PunchImportStateMachine;
import com.szsemicon.hr.punchimport.domain.PunchImportStateMachine.BatchState;
import com.szsemicon.hr.punchimport.domain.PunchImportStateMachine.RetryPrerequisites;
import com.szsemicon.hr.punchimport.application.PunchImportExceptions.UnsafeWorkbookException;
import com.szsemicon.hr.punchimport.infrastructure.excel.PoiPunchWorkbookGateway;
import com.szsemicon.hr.punchimport.infrastructure.excel.PunchWorkbookPolicy;
import com.szsemicon.hr.punchimport.infrastructure.storage.SyntheticMalwareScanAdapter;
import com.szsemicon.hr.punchimport.infrastructure.storage.SyntheticStoredObjectAdapter;
import com.szsemicon.hr.punchimport.port.MalwareScanPort.ScanResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class Wave4PunchImportContractTest {

    @Test
    void exactBatchStatesKeepThreeFailureKindsDistinct() {
        assertThat(BatchState.values()).extracting(Enum::name).containsExactly(
                "DRAFT", "VALIDATING", "VALIDATION_FAILED",
                "AWAITING_CONFIRMATION", "BLOCKED_BY_FROZEN_PERIOD",
                "PUBLISHING", "PUBLISHED", "PARTIALLY_PUBLISHED",
                "PUBLISH_FAILED", "VOIDED");
        assertThat(PunchImportStateMachine.allowedTargets(BatchState.VALIDATING))
                .containsExactlyInAnyOrder(
                        BatchState.VALIDATION_FAILED,
                        BatchState.AWAITING_CONFIRMATION,
                        BatchState.BLOCKED_BY_FROZEN_PERIOD);
        assertThatThrownBy(() -> PunchImportStateMachine.requireTransition(
                BatchState.VALIDATION_FAILED, BatchState.PUBLISHED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failureRetriesRequireCorrectionReopenOrConfirmedRollback() {
        assertThatThrownBy(() -> PunchImportStateMachine.requireTransition(
                BatchState.VALIDATION_FAILED,
                BatchState.VALIDATING))
                .hasMessageContaining("corrected input");
        PunchImportStateMachine.requireTransition(
                BatchState.VALIDATION_FAILED,
                BatchState.VALIDATING,
                new RetryPrerequisites(true, false, true, false, false));

        assertThatThrownBy(() -> PunchImportStateMachine.requireTransition(
                BatchState.BLOCKED_BY_FROZEN_PERIOD,
                BatchState.VALIDATING,
                new RetryPrerequisites(false, true, false, false, false)))
                .hasMessageContaining("reopen");
        PunchImportStateMachine.requireTransition(
                BatchState.BLOCKED_BY_FROZEN_PERIOD,
                BatchState.VALIDATING,
                new RetryPrerequisites(false, true, true, false, false));

        assertThatThrownBy(() -> PunchImportStateMachine.requireTransition(
                BatchState.PUBLISH_FAILED,
                BatchState.PUBLISHING,
                new RetryPrerequisites(false, false, false, true, false)))
                .hasMessageContaining("identical idempotent request");
        PunchImportStateMachine.requireTransition(
                BatchState.PUBLISH_FAILED,
                BatchState.PUBLISHING,
                new RetryPrerequisites(false, false, false, true, true));
    }

    @Test
    void mappingAllowlistRejectsScriptsExpressionsAndResultColumns() {
        assertThat(PunchWorkbookPolicy.validateMappedHeaders(List.of(
                "employeeNumber", "punchTime", "direction"))).isEmpty();
        assertThat(PunchWorkbookPolicy.validateMappedHeaders(List.of(
                "employeeNumber", "lateMinutes"))).contains("FORBIDDEN_RESULT_COLUMN");
        assertThat(PunchWorkbookPolicy.validateTransform("trim")).isEqualTo("TRIM");
        assertThatThrownBy(() -> PunchWorkbookPolicy.validateTransform(
                "javascript:alert(1)")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fileBoundaryAndSignatureChecksFailClosed() {
        byte[] zipMagic = "PK\u0003\u0004synthetic".getBytes(StandardCharsets.ISO_8859_1);
        assertThat(PunchWorkbookPolicy.validateEnvelope(
                "synthetic.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                zipMagic,
                PunchWorkbookPolicy.DEFAULT_MAX_FILE_BYTES)).isEmpty();
        assertThat(PunchWorkbookPolicy.validateEnvelope(
                "synthetic.xlsx", "", zipMagic,
                PunchWorkbookPolicy.DEFAULT_MAX_FILE_BYTES + 1))
                .contains("FILE_TOO_LARGE");
        assertThat(PunchWorkbookPolicy.validateEnvelope(
                "synthetic.csv", "text/csv", "x".getBytes(StandardCharsets.UTF_8), 1))
                .contains("INVALID_XLSX_ENVELOPE");
    }

    @Test
    void generatedTemplateIsVersionedSixSheetAndAcceptedByTheSameParser() throws Exception {
        var gateway = new PoiPunchWorkbookGateway();
        var template = gateway.currentTemplate();

        assertThat(template.templateVersion()).isEqualTo("1.0.0");
        assertThat(template.fieldContractDigest()).matches("[0-9a-f]{64}");
        assertThat(template.fileSha256()).matches("[0-9a-f]{64}");
        assertThat(template.filename()).endsWith(".xlsx");
        try (var workbook = new XSSFWorkbook(
                new ByteArrayInputStream(template.content()))) {
            assertThat(workbook.sheetIterator())
                    .toIterable()
                    .extracting(sheet -> sheet.getSheetName())
                    .containsExactly(
                            "导入说明",
                            "考勤打卡导入",
                            "设备人员映射（可选）",
                            "字段说明",
                            "枚举值",
                            "示例数据");
            assertThat(workbook.getSheet("考勤打卡导入").getRow(0).getLastCellNum())
                    .isEqualTo((short) PunchWorkbookPolicy.PUNCH_FIELDS.size());
            assertThat(workbook.getSheet("设备人员映射（可选）").getRow(0)
                    .getLastCellNum())
                    .isEqualTo((short) PunchWorkbookPolicy.DEVICE_MAPPING_FIELDS.size());
        }
        var parsed = gateway.parse(
                template.content(),
                template.filename(),
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(parsed.templateVersion()).isEqualTo(template.templateVersion());
        assertThat(parsed.fieldContractDigest()).isEqualTo(template.fieldContractDigest());
        assertThat(parsed.punchRows()).isEmpty();
        assertThat(parsed.deviceMappingRows()).isEmpty();
    }

    @Test
    void parserRejectsFormulaEvenWhenTheWorkbookHasACachedLookingValue() throws Exception {
        var gateway = new PoiPunchWorkbookGateway();
        var template = gateway.currentTemplate();
        byte[] hostile;
        try (var workbook = new XSSFWorkbook(
                        new ByteArrayInputStream(template.content()));
                var output = new ByteArrayOutputStream()) {
            workbook.getSheet("考勤打卡导入")
                    .createRow(1)
                    .createCell(0)
                    .setCellFormula("\"SYNTHETIC-E001\"");
            workbook.write(output);
            hostile = output.toByteArray();
        }

        assertThatThrownBy(() -> gateway.parse(
                hostile,
                "synthetic-hostile.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .isInstanceOfSatisfying(
                        UnsafeWorkbookException.class,
                        exception -> assertThat(exception.reasonCode())
                                .isEqualTo("FORMULA_FORBIDDEN"));
    }

    @Test
    void syntheticStorageUsesOpaqueRefsAndScannerFailsClosedForUnknownRefs()
            throws Exception {
        var storage = new SyntheticStoredObjectAdapter();
        byte[] bytes = "SYNTHETIC-XLSX".getBytes(StandardCharsets.UTF_8);
        var stored = storage.store(
                new ByteArrayInputStream(bytes),
                bytes.length,
                PunchWorkbookPolicy.XLSX_CONTENT_TYPE);
        var scanner = new SyntheticMalwareScanAdapter(storage);

        assertThat(stored.opaqueObjectReference())
                .startsWith("synthetic-object:")
                .doesNotContain("/", "\\");
        assertThat(storage.open(stored.opaqueObjectReference()).readAllBytes())
                .isEqualTo(bytes);
        assertThat(scanner.scan(stored.opaqueObjectReference()))
                .isEqualTo(ScanResult.CLEAN);
        assertThat(scanner.scan("synthetic-object:unknown"))
                .isEqualTo(ScanResult.UNAVAILABLE);
        assertThat(scanner.scan("../not-an-object"))
                .isEqualTo(ScanResult.UNAVAILABLE);
    }
}
