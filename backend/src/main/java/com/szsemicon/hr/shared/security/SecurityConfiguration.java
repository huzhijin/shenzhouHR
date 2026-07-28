package com.szsemicon.hr.shared.security;

import com.szsemicon.hr.shared.web.ApiErrorResponse;
import com.szsemicon.hr.shared.web.CorrelationIdFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectMapper objectMapper,
            SessionAuthenticationFilter sessionAuthenticationFilter,
            SessionCsrfFilter sessionCsrfFilter,
            ObjectProvider<DevelopmentPrincipalFilter> developmentFilterProvider) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(
                                "/api/v1/auth/login",
                                "/api/v1/auth/password-reset-requests",
                                "/api/v1/auth/password-resets").permitAll()
                        .requestMatchers(
                                "/api/v1/auth/session",
                                "/api/v1/auth/logout",
                                "/api/v1/auth/password/change",
                                "/api/v1/auth/password/first-change",
                                "/api/v1/auth/sessions/**").authenticated()
                        .anyRequest().access((authentication, context) ->
                                new AuthorizationDecision(
                                        authentication.get().isAuthenticated()
                                                && !(authentication.get()
                                                        instanceof AnonymousAuthenticationToken)
                                                && authentication.get().getAuthorities().stream()
                                                        .noneMatch(authority ->
                                                                "FIRST_PASSWORD_CHANGE_REQUIRED"
                                                                        .equals(authority.getAuthority())))))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> writeError(
                                objectMapper,
                                request,
                                response,
                                HttpServletResponse.SC_UNAUTHORIZED,
                                "AUTHENTICATION_REQUIRED",
                                "需要有效身份"))
                        .accessDeniedHandler((request, response, exception) -> writeError(
                                objectMapper,
                                request,
                                response,
                                HttpServletResponse.SC_FORBIDDEN,
                                "ACCESS_DENIED",
                                "无权执行此操作")));

        DevelopmentPrincipalFilter developmentFilter = developmentFilterProvider.getIfAvailable();
        if (developmentFilter != null) {
            http.addFilterAfter(developmentFilter, SecurityContextHolderFilter.class);
            http.addFilterAfter(sessionAuthenticationFilter, DevelopmentPrincipalFilter.class);
        } else {
            http.addFilterAfter(sessionAuthenticationFilter, SecurityContextHolderFilter.class);
        }
        http.addFilterAfter(sessionCsrfFilter, SessionAuthenticationFilter.class);
        return http.build();
    }

    private static void writeError(
            ObjectMapper objectMapper,
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String message) throws IOException {
        Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
        String correlationId = value == null ? "unavailable" : value.toString();
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Cache-Control", sanitizeHeaderValue("no-store"));
        objectMapper.writeValue(
                response.getOutputStream(),
                new ApiErrorResponse(code, message, correlationId, false));
    }

    private static String sanitizeHeaderValue(String value) {
        return value.replace("\r", "").replace("\n", "");
    }
}
