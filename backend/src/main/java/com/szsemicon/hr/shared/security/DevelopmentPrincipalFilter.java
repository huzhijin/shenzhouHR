package com.szsemicon.hr.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Profile({"dev", "test"})
@ConditionalOnProperty(
        name = "shenzhouhr.development-principal.enabled",
        havingValue = "true")
public final class DevelopmentPrincipalFilter extends OncePerRequestFilter {

    static final String HEADER_NAME = "X-Development-Principal";
    private final String allowedPrincipalId;

    public DevelopmentPrincipalFilter(
            @Value("${SHENZHOUHR_DEV_PRINCIPAL_ID:}") String allowedPrincipalId) {
        this.allowedPrincipalId = normalize(allowedPrincipalId);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String candidate = normalize(request.getHeader(HEADER_NAME));
        if (!allowedPrincipalId.isEmpty()
                && allowedPrincipalId.equals(candidate)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            var authentication = UsernamePasswordAuthenticationToken.authenticated(
                    candidate,
                    "development-profile-only",
                    List.of());
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        filterChain.doFilter(request, response);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }
}
