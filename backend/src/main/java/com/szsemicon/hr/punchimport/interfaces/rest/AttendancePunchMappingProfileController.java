package com.szsemicon.hr.punchimport.interfaces.rest;

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
 * W4 stub — punch mapping profile endpoints.
 *
 * Configurable punch-column mapping profiles are a Wave 4 feature; this
 * stub satisfies the OpenAPI contract and returns NOT_IMPLEMENTED for any
 * call until the full implementation lands.
 */
@RestController
@RequestMapping("/api/v1/attendance-punch-mapping-profiles")
public class AttendancePunchMappingProfileController {

    private static final String W4_CODE = "W4_NOT_IMPLEMENTED";
    private static final String W4_MSG =
            "Punch mapping profile management requires Wave 4 punch-import"
                    + " features which are not yet active.";

    @GetMapping
    @PreAuthorize("hasAuthority('ATTENDANCE_PUNCH_IMPORT:READ')")
    public ResponseEntity<Void> list() {
        throw new ApiProblemException(HttpStatus.NOT_IMPLEMENTED, W4_CODE, W4_MSG);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ATTENDANCE_PUNCH_IMPORT:UPLOAD')")
    public ResponseEntity<Void> create() {
        throw new ApiProblemException(HttpStatus.NOT_IMPLEMENTED, W4_CODE, W4_MSG);
    }

    @GetMapping("/{profileId}/versions")
    @PreAuthorize("hasAuthority('ATTENDANCE_PUNCH_IMPORT:READ')")
    public ResponseEntity<Void> listVersions(@PathVariable String profileId) {
        throw new ApiProblemException(HttpStatus.NOT_IMPLEMENTED, W4_CODE, W4_MSG);
    }
}
