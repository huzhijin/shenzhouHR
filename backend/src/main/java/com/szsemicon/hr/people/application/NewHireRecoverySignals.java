package com.szsemicon.hr.people.application;

import java.time.LocalDate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

final class NewHireRecoverySignals {

    private NewHireRecoverySignals() {
    }

    static void afterCommit(NewHireAttendanceRecovery recovery, LocalDate effectiveFrom) {
        if (recovery == null || effectiveFrom == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            recovery.noteHire(effectiveFrom);
                        }
                    });
            return;
        }
        recovery.noteHire(effectiveFrom);
    }
}
