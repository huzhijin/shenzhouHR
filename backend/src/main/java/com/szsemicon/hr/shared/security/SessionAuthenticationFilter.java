package com.szsemicon.hr.shared.security;

import com.szsemicon.hr.identityaccess.application.AuthenticationService;
import com.szsemicon.hr.identityaccess.application.AuthenticationService.SessionIdentity;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public final class SessionAuthenticationFilter extends OncePerRequestFilter {

    public static final String COOKIE_NAME = "SHENZHOUHR_SESSION";
    public static final String SESSION_IDENTITY_ATTRIBUTE =
            SessionAuthenticationFilter.class.getName() + ".sessionIdentity";
    private final AuthenticationService authenticationService;
    private final Clock clock;

    public SessionAuthenticationFilter(
            AuthenticationService authenticationService,
            Clock clock) {
        this.authenticationService = authenticationService;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String rawToken = findCookie(request, COOKIE_NAME);
        if (rawToken != null) {
            SessionIdentity identity = authenticationService.authenticateSession(rawToken);
            if (identity != null) {
                request.setAttribute(SESSION_IDENTITY_ATTRIBUTE, identity);
                List<String> grantedCapabilities = identity.account().firstPasswordChangeRequired()
                        ? List.of("FIRST_PASSWORD_CHANGE_REQUIRED")
                        : identity.capabilities();
                List<SimpleGrantedAuthority> authorities = grantedCapabilities.stream()
                        .map(SimpleGrantedAuthority::new)
                        .toList();
                var authentication = UsernamePasswordAuthenticationToken.authenticated(
                        identity.account().principalId(),
                        "session-cookie",
                        authorities);
                authentication.setDetails(new SessionSecurityDetails(
                        identity.session().sessionId(),
                        identity.account().accountId(),
                        clock.instant()));
                SecurityContextHolder.getContext().setAuthentication(authentication);
                filterChain.doFilter(request, response);
                return;
            }
        }

        var current = SecurityContextHolder.getContext().getAuthentication();
        if (current != null && current.isAuthenticated()) {
            List<SimpleGrantedAuthority> authorities = authenticationService
                    .capabilities(current.getName())
                    .stream()
                    .map(SimpleGrantedAuthority::new)
                    .toList();
            var hydrated = UsernamePasswordAuthenticationToken.authenticated(
                    current.getName(),
                    "authority-catalogue",
                    authorities);
            hydrated.setDetails(current.getDetails());
            SecurityContextHolder.getContext().setAuthentication(hydrated);
            filterChain.doFilter(request, response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    public static String findCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (name.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
                    return cookie.getValue();
                }
            }
        }
        String cookieHeader = request.getHeader("Cookie");
        if (cookieHeader != null) {
            for (String part : cookieHeader.split(";")) {
                String[] pair = part.trim().split("=", 2);
                if (pair.length == 2 && name.equals(pair[0]) && !pair[1].isBlank()) {
                    return pair[1];
                }
            }
        }
        return null;
    }

    public record SessionSecurityDetails(
            String sessionId,
            String accountId,
            java.time.Instant authenticatedAt) {
    }
}
