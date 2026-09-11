package com.szsemicon.hr.attendance.interfaces.rest;

import com.szsemicon.hr.attendance.application.AttendanceConfigurationService;
import com.szsemicon.hr.attendance.application.AttendancePolicyCommands.BindingCommand;
import com.szsemicon.hr.attendance.application.AttendancePolicySimulationService;
import com.szsemicon.hr.attendance.application.AttendancePolicyService;
import com.szsemicon.hr.attendance.domain.AttendancePolicyCatalog;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.PunchInput;
import com.szsemicon.hr.attendance.domain.AttendancePolicyModels.SimulationInput;
import com.szsemicon.hr.attendance.interfaces.rest.AttendancePolicyDtos.BindingRequest;
import com.szsemicon.hr.attendance.interfaces.rest.AttendancePolicyDtos.BindingPreviewRequest;
import com.szsemicon.hr.attendance.interfaces.rest.AttendancePolicyDtos.BindingPage;
import com.szsemicon.hr.attendance.interfaces.rest.AttendancePolicyDtos.BindingView;
import com.szsemicon.hr.attendance.interfaces.rest.AttendancePolicyDtos.ConfigurationView;
import com.szsemicon.hr.attendance.interfaces.rest.AttendancePolicyDtos.ImpactView;
import com.szsemicon.hr.attendance.interfaces.rest.AttendancePolicyDtos.SimulationBatchView;
import com.szsemicon.hr.attendance.interfaces.rest.AttendancePolicyDtos.SimulationRequest;
import com.szsemicon.hr.shared.web.ChangeReasonHeader;
import com.szsemicon.hr.shared.web.StrongEtag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/attendance-setup")
public class AttendancePolicyController {

    private final AttendancePolicyService service;
    private final AttendancePolicySimulationService simulationService;
    private final AttendanceConfigurationService configurationService;

    public AttendancePolicyController(
            AttendancePolicyService service,
            AttendancePolicySimulationService simulationService,
            AttendanceConfigurationService configurationService) {
        this.service = service;
        this.simulationService = simulationService;
        this.configurationService = configurationService;
    }

    @GetMapping("/policy-catalog")
    ResponseEntity<List<AttendancePolicyCatalog.TemplateDefinition>> catalog() {
        return noStore(service.catalog());
    }

    @GetMapping("/policy-bindings")
    ResponseEntity<BindingPage> listBindings(
            @RequestParam(required = false) String companyId,
            @RequestParam(required = false) String groupId,
            @RequestParam(required = false) LocalDate asOf,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return noStore(AttendancePolicyDtos.bindings(
                service.listBindings(companyId, groupId, asOf, page, size)));
    }

    @PostMapping("/policy-bindings")
    ResponseEntity<BindingView> createBinding(
            @Valid @RequestBody BindingRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Change-Reason") String changeReason) {
        var result = service.createBinding(
                command(request, reason(changeReason, request.reason())),
                request.impactToken(),
                idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .eTag(StrongEtag.ofVersion(result.rowVersion()))
                .body(AttendancePolicyDtos.binding(result));
    }

    @PutMapping("/policy-bindings/{bindingId}")
    ResponseEntity<BindingView> updateBinding(
            @PathVariable String bindingId,
            @Valid @RequestBody BindingRequest request,
            @RequestHeader("If-Match") String ifMatch,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Change-Reason") String changeReason) {
        var result = service.updateBinding(
                bindingId,
                command(request, reason(changeReason, request.reason())),
                StrongEtag.parseVersion(ifMatch),
                request.impactToken(),
                idempotencyKey);
        return versioned(AttendancePolicyDtos.binding(result), result.rowVersion());
    }

    @PostMapping("/policy-simulations")
    ResponseEntity<SimulationBatchView> simulate(
            @Valid @RequestBody SimulationRequest request) {
        var results = simulationService.simulate(new SimulationInput(
                request.employeeId(),
                request.businessDate(),
                request.punches().stream()
                        .map(punch -> new PunchInput(
                                punch.direction(),
                                punch.instant(),
                                punch.workSegmentId(),
                                punch.association()))
                        .toList(),
                request.correctionAsOf().toInstant()));
        return noStore(new SimulationBatchView(
                results.getFirst().configurationDigest(),
                results.stream().map(AttendancePolicyDtos::simulation).toList()));
    }

    @PostMapping("/policy-impact-preview")
    ResponseEntity<ImpactView> previewImpact(
            @Valid @RequestBody BindingPreviewRequest request) {
        return noStore(AttendancePolicyDtos.impact(
                service.previewImpact(command(request))));
    }

    @GetMapping("/resolve")
    ResponseEntity<ConfigurationView> resolve(
            @RequestParam String employeeId,
            @RequestParam LocalDate businessDate) {
        return noStore(AttendancePolicyDtos.configuration(
                configurationService.resolve(employeeId, businessDate)));
    }

    private BindingCommand command(BindingRequest request) {
        return command(request, request.reason());
    }

    private BindingCommand command(BindingRequest request, String reason) {
        return new BindingCommand(
                request.policyKind(), request.policyVersionId(), request.groupId(),
                request.groupRevisionId(),
                request.effectiveFrom(), request.effectiveTo(),
                reason);
    }

    private BindingCommand command(BindingPreviewRequest request) {
        return new BindingCommand(
                request.policyKind(),
                request.policyVersionId(),
                request.groupId(),
                request.groupRevisionId(),
                request.effectiveFrom(),
                request.effectiveTo(),
                request.reason());
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static <T> ResponseEntity<T> versioned(T body, long rowVersion) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .eTag(StrongEtag.ofVersion(rowVersion))
                .body(body);
    }

    private static String reason(String encodedHeader, String bodyReason) {
        return ChangeReasonHeader.requireMatches(encodedHeader, bodyReason);
    }
}
