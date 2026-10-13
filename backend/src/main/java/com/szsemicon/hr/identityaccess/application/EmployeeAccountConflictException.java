package com.szsemicon.hr.identityaccess.application;

/**
 * Signals the unique active-principal invariant for an employee without
 * exposing database constraint details at the API boundary.
 */
public final class EmployeeAccountConflictException extends RuntimeException {

    public EmployeeAccountConflictException(Throwable cause) {
        super("employee already has a principal", cause);
    }
}
