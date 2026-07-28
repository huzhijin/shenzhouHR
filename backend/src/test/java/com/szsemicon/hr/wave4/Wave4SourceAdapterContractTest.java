package com.szsemicon.hr.wave4;

import static org.assertj.core.api.Assertions.assertThat;

import com.szsemicon.hr.evidenceingestion.application.SourceIntegrationStatus;
import com.szsemicon.hr.evidenceingestion.port.DeliPunchSourcePort;
import com.szsemicon.hr.evidenceingestion.port.OaAttendanceDocumentSourcePort;
import com.szsemicon.hr.evidenceingestion.infrastructure.synthetic.SyntheticDeliPunchAdapter;
import com.szsemicon.hr.evidenceingestion.infrastructure.synthetic.SyntheticOaAttendanceDocumentAdapter;
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
}
