package com.szsemicon.hr.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    @Test
    void preservesSafeCorrelationIdAndClearsDiagnosticContext() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(
                CorrelationIdFilter.HEADER_NAME,
                sanitizeHeaderValue("request-20260720.001"));

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE))
                .isEqualTo("request-20260720.001");
        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME))
                .isEqualTo("request-20260720.001");
        assertThat(MDC.get("correlation_id")).isNull();
    }

    @Test
    void replacesUnsafeCorrelationId() throws Exception {
        CorrelationIdFilter filter = new CorrelationIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(
                CorrelationIdFilter.HEADER_NAME,
                sanitizeHeaderValue("unsafe header value"));

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME))
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    private static String sanitizeHeaderValue(String value) {
        return value.replace("\r", "").replace("\n", "");
    }
}
