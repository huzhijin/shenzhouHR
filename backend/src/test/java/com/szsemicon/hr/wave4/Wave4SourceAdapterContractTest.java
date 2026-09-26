package com.szsemicon.hr.wave4;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.szsemicon.hr.evidenceingestion.application.SourceIntegrationStatus;
import com.szsemicon.hr.evidenceingestion.domain.AttendanceSourceModels.SourceConfigurationRevision;
import com.szsemicon.hr.evidenceingestion.port.DeliPunchSourcePort;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort;
import com.szsemicon.hr.evidenceingestion.infrastructure.synthetic.SyntheticDeliPunchAdapter;
import com.szsemicon.hr.evidenceingestion.infrastructure.synthetic.SyntheticOaAttendanceDocumentAdapter;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Wave4SourceAdapterContractTest {

    @Test
    void deliContractPreservesLongIdsAndDropsBiometricPayload() {
        DeliPunchSourcePort adapter = new SyntheticDeliPunchAdapter();
        var page = adapter.fetchPage("source-synthetic-deli", null);
        assertThat(page.records()).isNotEmpty();
        assertThat(page.records().getFirst().sourceRecordId())
                .isEqualTo("922337203685477580812345");
        assertThat(page.records().getFirst().forbiddenPayloadDropped()).isTrue();
        assertThat(page.records().getFirst().verificationMethod())
                .doesNotContainIgnoringCase("template");
    }

    @Test
    void oaMappingIsVersionedAndUnknownStatusesFailClosed() {
        OaAttendanceDocumentSourcePort adapter =
                new SyntheticOaAttendanceDocumentAdapter();
        var page = adapter.fetchPage("source-synthetic-oa", null);
        assertThat(page.records()).extracting(record -> record.sourceStatus().name())
                .contains("APPROVED", "DRAFT", "REJECTED", "UNKNOWN",
                        "MODIFIED", "SUPPLEMENTED", "REVOKED");
        assertThat(page.records())
                .filteredOn(record -> record.sourceStatus().name().equals("UNKNOWN"))
                .allSatisfy(record -> assertThat(record.effectiveCandidate()).isFalse());
        assertThat(page.records())
                .filteredOn(record -> record.sourceStatus()
                        == OaAttendanceDocumentSourcePort.SourceStatus.REVOKED)
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.documentType())
                            .isEqualTo(OaAttendanceDocumentSourcePort
                                    .DocumentType.LEAVE);
                    assertThat(record.approvedAt()).isNotNull();
                    assertThat(record.revokedAt())
                            .isAfter(record.approvedAt());
                    assertThat(record.effectiveCandidate()).isFalse();
                });
        assertThat(page.records())
                .filteredOn(record -> record.documentType()
                        == OaAttendanceDocumentSourcePort
                        .DocumentType.LEAVE_REVOCATION)
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.sourceStatus())
                            .isEqualTo(OaAttendanceDocumentSourcePort
                                    .SourceStatus.APPROVED);
                    assertThat(record.firstSubmittedAt())
                            .isBefore(record.approvedAt());
                    assertThat(record.revokedAt()).isNull();
                    assertThat(record.effectiveCandidate()).isTrue();
                });
        assertThat(page.records())
                .extracting(
                        record -> record.sourceBusinessKey(),
                        record -> record.sourceVersion())
                .doesNotHaveDuplicates()
                .contains(
                        tuple(
                                "OA-SYNTHETIC-9223372036854775808",
                                "7"),
                        tuple(
                                "OA-SYNTHETIC-LEAVE-REVOCATION-9223372036854775808",
                                "1"));
        assertThat(page.records())
                .noneMatch(record -> record.documentType()
                        == OaAttendanceDocumentSourcePort.DocumentType.TRIP);
    }

    @Test
    void contractPassCannotBeRelabeledAsLiveIntegration() {
        SourceIntegrationStatus status = SourceIntegrationStatus.syntheticPass();
        assertThat(status.deliContractStub()).isEqualTo("PASS");
        assertThat(status.oaContractStub()).isEqualTo("PASS");
        assertThat(status.deliLive()).isEqualTo("NOT_VERIFIED");
        assertThat(status.oaLive()).isEqualTo("NOT_VERIFIED");
        assertThat(status.productionFileStorage()).isEqualTo("NOT_VERIFIED");
    }

    @Test
    void sourceConfigurationStoresOnlyExternalSecretReferences() {
        var safe = new SourceConfigurationRevision(
                "config-1",
                "source-1",
                1,
                "CONTRACT_STUB",
                ZoneId.of("Asia/Shanghai"),
                100,
                60,
                5,
                "W4_DELI_CONTRACT_SECRET_REF",
                Map.of("endpointAlias", "synthetic-only"),
                Instant.EPOCH,
                "a".repeat(64));
        assertThat(safe.secretReferenceName())
                .isEqualTo("W4_DELI_CONTRACT_SECRET_REF");
        assertThatThrownBy(() -> new SourceConfigurationRevision(
                "config-2",
                "source-1",
                2,
                "CONTRACT_STUB",
                ZoneId.of("Asia/Shanghai"),
                100,
                60,
                5,
                null,
                Map.of("password", "must-not-be-stored"),
                Instant.EPOCH,
                "b".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credential");
    }
}
