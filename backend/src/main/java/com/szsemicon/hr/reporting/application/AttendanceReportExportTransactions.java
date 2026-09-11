package com.szsemicon.hr.reporting.application;

import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starts the authorization-sensitive part of a password-confirmed report
 * export only after reauthentication has committed.
 */
@Component
public class AttendanceReportExportTransactions {

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED)
    public <T> T readCommitted(Supplier<T> operation) {
        return Objects.requireNonNull(operation, "operation").get();
    }

    /**
     * Serializes the final authorization decision, current visibility reads
     * and artifact read against concurrent role revocation or employee
     * transfer. The transaction commits before the download is returned.
     */
    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.SERIALIZABLE)
    public <T> T serialized(Supplier<T> operation) {
        return Objects.requireNonNull(operation, "operation").get();
    }
}
