package com.szsemicon.hr.evidenceingestion.infrastructure.deli;

import com.szsemicon.hr.evidenceingestion.port.DeliPunchSourcePort;

public final class DeliEplusClientException
        extends DeliPunchSourcePort.FetchException {

    DeliEplusClientException(
            String safeCode,
            String safeMessage,
            boolean retryable) {
        super(safeCode, safeMessage, retryable);
    }
}
