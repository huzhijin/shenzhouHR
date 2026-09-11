package com.szsemicon.hr.evidenceingestion.interfaces.rest;

import com.szsemicon.hr.authorization.domain.CapabilityCodes;
import com.szsemicon.hr.evidenceingestion.application.DeliPunchReplayApplicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/attendance-sources")
public class DeliPunchReplayController {

    private final DeliPunchReplayApplicationService replay;

    public DeliPunchReplayController(DeliPunchReplayApplicationService replay) {
        this.replay = replay;
    }

    @PostMapping("/deli-identity-replay")
    @PreAuthorize("hasAuthority('" + CapabilityCodes.ATTENDANCE_SOURCE_RUN + "')")
    ResponseEntity<ReplayResponse> replay(@Valid @RequestBody ReplayRequest request) {
        var result = replay.replay(
                request.sourceId(),
                request.fromDate(),
                request.toDate(),
                Boolean.TRUE.equals(request.throughToday()),
                !Boolean.FALSE.equals(request.seedBindings()),
                !Boolean.FALSE.equals(request.recalculate()));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ReplayResponse.from(result));
    }

    public record ReplayRequest(
            @Size(max = 36) String sourceId,
            LocalDate fromDate,
            LocalDate toDate,
            Boolean throughToday,
            Boolean seedBindings,
            Boolean recalculate) {
    }

    public record ReplayResponse(
            String sourceId,
            String companyId,
            String fromDate,
            String toDate,
            int acceptedCount,
            int quarantinedCount,
            int identityReplayedCount,
            int identityMovedCount,
            List<String> seededBindings,
            List<String> skippedBindings,
            List<String> missingEmployees,
            int stillQuarantinedCount,
            List<QuarantineItem> stillQuarantined,
            boolean recalculated) {

        static ReplayResponse from(
                DeliPunchReplayApplicationService.ReplayResult result) {
            return new ReplayResponse(
                    result.sourceId(),
                    result.companyId(),
                    result.fromDate().toString(),
                    result.toDate().toString(),
                    result.acceptedCount(),
                    result.quarantinedCount(),
                    result.identityReplayedCount(),
                    result.identityMovedCount(),
                    result.seed().seeded(),
                    result.seed().skipped(),
                    result.seed().missingEmployees(),
                    result.stillQuarantined().size(),
                    result.stillQuarantined().stream()
                            .map(QuarantineItem::from)
                            .toList(),
                    result.recalculated());
        }
    }

    public record QuarantineItem(
            String sourceRecordId,
            String employeeNumber,
            String deliPersonId,
            String punchInstant,
            String reason) {

        static QuarantineItem from(
                DeliPunchReplayApplicationService.QuarantineNote note) {
            return new QuarantineItem(
                    note.sourceRecordId(),
                    note.employeeNumber(),
                    note.deliPersonId(),
                    note.punchInstant() == null
                            ? null
                            : note.punchInstant().toString(),
                    note.reason());
        }
    }
}
