package com.szsemicon.hr.shared.security;

import com.szsemicon.hr.shared.web.ApiErrorResponse;
import com.szsemicon.hr.shared.web.CorrelationIdFilter;
import com.szsemicon.hr.shared.web.ResponseCachePolicy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

@Component
public final class SessionCsrfFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-CSRF-TOKEN";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Set<String> PUBLIC_MUTATIONS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/password-reset-requests",
            "/api/v1/auth/password-resets");
    private final SecurityTokenService tokenService;
    private final ObjectMapper objectMapper;

    public SessionCsrfFilter(
            SecurityTokenService tokenService,
            ObjectMapper objectMapper) {
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (SAFE_METHODS.contains(request.getMethod())
                || PUBLIC_MUTATIONS.contains(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        String rawToken = SessionAuthenticationFilter.findCookie(
                request,
                SessionAuthenticationFilter.COOKIE_NAME);
        boolean valid;
        if (rawToken != null) {
            valid = tokenService.constantTimeEquals(
                    tokenService.csrfToken(rawToken),
                    request.getHeader(HEADER_NAME));
        } else {
            valid = notBlank(request.getHeader(HEADER_NAME))
                    || notBlank(request.getParameter("_csrf"));
        }
        if (!valid) {
            Object correlation = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            ResponseCachePolicy.preventStorage(response);
            objectMapper.writeValue(
                    response.getOutputStream(),
                    new ApiErrorResponse(
                            "CSRF_VALIDATION_FAILED",
                            "请求缺少有效的防跨站令牌",
                            safeResponseValue(correlation),
                            false));
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String safeResponseValue(Object value) {
        if (value == null) {
            return "unavailable";
        }
        return value.toString().replace("\r", "").replace("\n", "");
    }
}
