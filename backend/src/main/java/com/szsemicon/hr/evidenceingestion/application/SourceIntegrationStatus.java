package com.szsemicon.hr.evidenceingestion.application;

public record SourceIntegrationStatus(
        String deliContractStub,
        String oaContractStub,
        String filePortContract,
        String deliLive,
        String oaLive,
        String productionFileStorage) {

    public static SourceIntegrationStatus syntheticPass() {
        return new SourceIntegrationStatus(
                "PASS",
                "PASS",
                "PASS",
                "NOT_VERIFIED",
                "NOT_VERIFIED",
                "NOT_VERIFIED");
    }

    public static SourceIntegrationStatus formalBaseline() {
        return new SourceIntegrationStatus(
                "IMPLEMENTED",
                "FAIL_CLOSED_PENDING_SIGNOFF",
                "PASS",
                "RUNTIME_PROBE_REQUIRED",
                "NOT_VERIFIED",
                "NOT_VERIFIED");
    }
}
