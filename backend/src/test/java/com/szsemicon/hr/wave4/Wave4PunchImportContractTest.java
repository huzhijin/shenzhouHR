package com.szsemicon.hr.wave4;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.punchimport.domain.PunchImportStateMachine;
import com.szsemicon.hr.punchimport.domain.PunchImportStateMachine.BatchState;
import com.szsemicon.hr.punchimport.infrastructure.excel.PunchWorkbookPolicy;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
}
