package com.szsemicon.hr.payroll.application;

public final class PayrollReservationFailure extends RuntimeException {

    private final Code code;

    public PayrollReservationFailure(Code code) {
        super(code.message());
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        W5_INTEGRATION_UNAVAILABLE("required integration is unavailable"),
        SNAPSHOT_NOT_AVAILABLE("required snapshot is unavailable"),
        INVALID_FROZEN_SNAPSHOT("frozen snapshot metadata is invalid");

        private final String message;

        Code(String message) {
            this.message = message;
        }

        String message() {
            return message;
        }
    }
}
