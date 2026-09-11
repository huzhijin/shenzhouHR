package com.szsemicon.hr.evidenceingestion.application;

final class AttendanceSourceSyncFailure extends RuntimeException {

    private final String safeCode;

    AttendanceSourceSyncFailure(String safeCode) {
        super(safeCode);
        this.safeCode = safeCode;
    }

    String safeCode() {
        return safeCode;
    }
}
