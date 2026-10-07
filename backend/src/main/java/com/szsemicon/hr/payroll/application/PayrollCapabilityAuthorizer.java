package com.szsemicon.hr.payroll.application;

@FunctionalInterface
public interface PayrollCapabilityAuthorizer {

    void requireReservationRead();
}
