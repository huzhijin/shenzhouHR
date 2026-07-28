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
}
