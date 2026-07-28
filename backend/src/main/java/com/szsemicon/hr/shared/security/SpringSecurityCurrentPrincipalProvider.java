package com.szsemicon.hr.shared.security;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public final class SpringSecurityCurrentPrincipalProvider implements CurrentPrincipalProvider {

    @Override
    public String currentPrincipalId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AuthenticationCredentialsNotFoundException("authenticated principal is required");
        }
        return authentication.getName();
    }
}

