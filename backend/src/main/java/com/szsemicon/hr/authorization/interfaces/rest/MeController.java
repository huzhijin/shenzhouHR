package com.szsemicon.hr.authorization.interfaces.rest;

import com.szsemicon.hr.authorization.application.CurrentCapabilityService;
import java.util.List;
import java.util.Set;
import com.szsemicon.hr.identityaccess.interfaces.rest.AuthenticationController;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final CurrentCapabilityService capabilityService;

    public MeController(CurrentCapabilityService capabilityService) {
        this.capabilityService = capabilityService;
    }

    @GetMapping("/capabilities")
    ResponseEntity<CurrentCapabilitiesResponse> capabilities() {
        Set<String> capabilities = capabilityService.currentCapabilities();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new CurrentCapabilitiesResponse(capabilities, buildMenu(capabilities)));
    }

    private List<MenuItemResponse> buildMenu(Set<String> capabilities) {
        return AuthenticationController.menu(capabilities.stream().sorted().toList())
                .stream()
                .map(item -> new MenuItemResponse(item.key(), item.label(), item.path()))
                .toList();
    }
}
