package com.szsemicon.hr.shared.web;

import org.springframework.http.HttpStatus;

public final class StrongEtag {

    private StrongEtag() {
    }

    public static long parseVersion(String value) {
        if (value == null || value.length() < 3
                || value.charAt(0) != '"'
                || value.charAt(value.length() - 1) != '"'
                || value.startsWith("W/")) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "If-Match 必须是形如 \"0\" 的强 ETag");
        }
        try {
            long parsed = Long.parseLong(value.substring(1, value.length() - 1));
            if (parsed < 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new ApiProblemException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "If-Match 必须包含非负版本号");
        }
    }

    public static String ofVersion(long version) {
        return "\"" + version + "\"";
    }
}
