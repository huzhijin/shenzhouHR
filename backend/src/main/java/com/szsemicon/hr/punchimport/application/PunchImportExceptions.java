package com.szsemicon.hr.punchimport.application;

public final class PunchImportExceptions {

    private PunchImportExceptions() {
    }

    public static final class UnsafeWorkbookException extends RuntimeException {
        private final String reasonCode;

        public UnsafeWorkbookException(String reasonCode, String message) {
            super(message);
            this.reasonCode = reasonCode;
        }

        public UnsafeWorkbookException(
                String reasonCode,
                String message,
                Throwable cause) {
            super(message, cause);
            this.reasonCode = reasonCode;
        }

        public String reasonCode() {
            return reasonCode;
        }
    }
}
