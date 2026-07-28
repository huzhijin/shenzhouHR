package com.szsemicon.hr.authorization.interfaces.rest;

import java.util.List;
import java.util.Set;

public record CurrentCapabilitiesResponse(
        Set<String> capabilities,
        List<MenuItemResponse> menu) {
}

