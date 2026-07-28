package com.szsemicon.hr.shared.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;

public final class ResponseCachePolicy {

    private ResponseCachePolicy() {
    }

    public static void preventStorage(HttpServletResponse response) {
        response.setHeader(
                HttpHeaders.CACHE_CONTROL,
                CacheControl.noStore().getHeaderValue());
    }
}
