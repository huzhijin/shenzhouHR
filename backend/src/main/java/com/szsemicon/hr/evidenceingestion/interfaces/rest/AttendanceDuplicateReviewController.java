package com.szsemicon.hr.evidenceingestion.interfaces.rest;

import com.szsemicon.hr.shared.web.ApiProblemException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * W4 stub — duplicate punch review endpoints.
 *
 * Full deduplication review workflow is deferred to Wave 4; this stub
 * satisfies the OpenAPI contract and returns NOT_IMPLEMENTED for any call.
 */
@RestController
@RequestMapping("/api/v1/attendance-duplicate-reviews")
public class AttendanceDuplicateReviewController {

    private static final String W4_CODE = "W4_NOT_IMPLEMENTED";
    private static final String W4_MSG =
            "Duplicate punch review requires Wave 4 ingestion features"
                    + " which are not yet active.";

    @GetMapping
    @PreAuthorize("hasAuthority('ATTENDANCE_PUNCH_IMPORT:DUPLICATE_REVIEW')")
    public ResponseEntity<Void> list() {
        throw new ApiProblemException(HttpStatus.NOT_IMPLEMENTED, W4_CODE, W4_MSG);
    }

    @GetMapping("/{groupId}")
    @PreAuthorize("hasAuthority('ATTENDANCE_PUNCH_IMPORT:DUPLICATE_REVIEW')")
    public ResponseEntity<Void> get(@PathVariable String groupId) {
        throw new ApiProblemException(HttpStatus.NOT_IMPLEMENTED, W4_CODE, W4_MSG);
    }

    @PostMapping("/{groupId}/resolve")
    @PreAuthorize("hasAuthority('ATTENDANCE_PUNCH_IMPORT:DUPLICATE_REVIEW')")
    public ResponseEntity<Void> resolve(@PathVariable String groupId) {
        throw new ApiProblemException(HttpStatus.NOT_IMPLEMENTED, W4_CODE, W4_MSG);
    }
}
