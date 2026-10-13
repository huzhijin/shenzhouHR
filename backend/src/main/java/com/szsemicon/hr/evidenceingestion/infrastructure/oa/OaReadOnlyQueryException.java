package com.szsemicon.hr.evidenceingestion.infrastructure.oa;

public final class OaReadOnlyQueryException extends RuntimeException {

    private final String safeCode;

    OaReadOnlyQueryException(String safeCode, String safeMessage) {
        super(safeMessage);
        this.safeCode = safeCode;
    }

    public String safeCode() {
        return safeCode;
    }
}
