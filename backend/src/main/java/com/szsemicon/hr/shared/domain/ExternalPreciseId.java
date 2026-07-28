package com.szsemicon.hr.shared.domain;

import java.util.Objects;

public record ExternalPreciseId(String value) {

    public ExternalPreciseId {
        Objects.requireNonNull(value, "external precise id is required");
        if (value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("external precise id must contain 1 to 128 characters");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}

