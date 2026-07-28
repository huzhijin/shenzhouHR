package com.szsemicon.hr.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class DevelopmentPrincipalFilterTest {

    private static final String ALLOWED_PRINCIPAL = "80000000-0000-0000-0000-000000000001";

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatesOnlyTheConfiguredDevelopmentPrincipal() throws Exception {
        DevelopmentPrincipalFilter filter = new DevelopmentPrincipalFilter(ALLOWED_PRINCIPAL);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(DevelopmentPrincipalFilter.HEADER_NAME, ALLOWED_PRINCIPAL);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .isNotNull()
                .extracting(authentication -> authentication.getName())
                .isEqualTo(ALLOWED_PRINCIPAL);
    }

    @Test
    void rejectsAnUnconfiguredOrMalformedDevelopmentPrincipal() throws Exception {
        DevelopmentPrincipalFilter filter = new DevelopmentPrincipalFilter(ALLOWED_PRINCIPAL);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(DevelopmentPrincipalFilter.HEADER_NAME, "not-a-principal");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
