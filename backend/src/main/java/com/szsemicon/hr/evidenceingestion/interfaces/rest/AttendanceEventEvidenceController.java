package com.szsemicon.hr.evidenceingestion.interfaces.rest;

import com.szsemicon.hr.shared.web.ApiProblemException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * W4 stub — attendance event evidence read endpoint.
 *
 * The full implementation is deferred to Wave 4 (evidence ingestion
 * sub-feature); this stub declares the route so that the OpenAPI contract
 * is satisfied and any premature call fails fast with a clear error.
 */
@RestController
@RequestMapping("/api/v1/attendance-events")
public class AttendanceEventEvidenceController {

    @GetMapping("/{eventId}/evidence")
    @PreAuthorize("hasAuthority('ATTENDANCE_SOURCE:READ')")
    public ResponseEntity<Void> getEvidence(@PathVariable String eventId) {
        throw new ApiProblemException(
                HttpStatus.NOT_IMPLEMENTED,
                "W4_NOT_IMPLEMENTED",
                "Attendance event evidence detail requires Wave 4 ingestion"
                        + " features which are not yet active.");
    }
}
