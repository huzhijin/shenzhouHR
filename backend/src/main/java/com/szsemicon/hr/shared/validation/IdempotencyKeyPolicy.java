package com.szsemicon.hr.shared.validation;

import java.util.regex.Pattern;

public final class IdempotencyKeyPolicy {

    private static final String ALLOWED_CHARACTERS = "A-Za-z0-9._:-";
    private static final String LENGTH_RANGE = "{16,128}";
    private static final Pattern FORMAT =
            Pattern.compile("^[" + ALLOWED_CHARACTERS + "]" + LENGTH_RANGE + "$");

    private IdempotencyKeyPolicy() {
    }

    public static boolean isValid(String value) {
        return value != null && FORMAT.matcher(value).matches();
    }
}
