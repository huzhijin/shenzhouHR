package com.szsemicon.hr.reporting.application;

import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PublicationResult;
import com.szsemicon.hr.reporting.application.AttendanceReportPublicationModels.PublishCommand;

/**
 * Trusted in-process entry point for a settlement or internal batch
 * orchestrator. This interface is not a web contract and must never be bound
 * directly to client-supplied report facts.
 */
public interface AttendanceReportProjectionPublicationUseCase {

    PublicationResult publish(PublishCommand command);

    default PublicationResult publish(
            PublishCommand command,
            java.time.LocalDate copyBefore,
            java.time.LocalDate copyFromExclusive) {
        return publish(command, copyBefore, copyFromExclusive, (String) null);
    }

    default PublicationResult publish(
            PublishCommand command,
            java.time.LocalDate copyBefore,
            java.time.LocalDate copyFromExclusive,
            String employeeId) {
        return publish(command);
    }

    default PublicationResult publish(
            PublishCommand command,
            java.time.LocalDate copyBefore,
            java.time.LocalDate copyFromExclusive,
            java.util.Collection<String> employeeIds) {
        if (employeeIds == null || employeeIds.isEmpty()) {
            return publish(command, copyBefore, copyFromExclusive, (String) null);
        }
        if (employeeIds.size() == 1) {
            return publish(
                    command,
                    copyBefore,
                    copyFromExclusive,
                    employeeIds.iterator().next());
        }
        return publish(command);
    }
}
