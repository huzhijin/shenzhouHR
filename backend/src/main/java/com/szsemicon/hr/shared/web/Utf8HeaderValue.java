package com.szsemicon.hr.shared.web;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public final class Utf8HeaderValue {

    private static final String RFC_3986_UTF8_PREFIX = "UTF-8''";

    private Utf8HeaderValue() {
    }

    public static String decode(String value) {
        if (value == null || !value.startsWith(RFC_3986_UTF8_PREFIX)) {
            return value;
        }
        return URLDecoder.decode(
                value.substring(RFC_3986_UTF8_PREFIX.length()),
                StandardCharsets.UTF_8);
    }
}
