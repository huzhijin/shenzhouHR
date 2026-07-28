package com.szsemicon.hr.shared.security;

import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public final class PasswordCodec {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    private final String nonMatchingHash = encoder.encode(UUID.randomUUID().toString());

    public boolean matches(String suppliedSecret, String encodedSecret) {
        char[] material = materialize(suppliedSecret);
        try {
            return encoder.matches(CharBuffer.wrap(material), encodedSecret);
        } finally {
            Arrays.fill(material, '\0');
        }
    }

    public String encode(String suppliedSecret) {
        char[] material = materialize(suppliedSecret);
        try {
            return encoder.encode(CharBuffer.wrap(material));
        } finally {
            Arrays.fill(material, '\0');
        }
    }

    public boolean meetsPolicy(String suppliedSecret) {
        char[] material = materialize(suppliedSecret);
        try {
            if (material.length < 12 || material.length > 256) {
                return false;
            }
            boolean uppercase = false;
            boolean lowercase = false;
            boolean digit = false;
            boolean symbol = false;
            for (char value : material) {
                uppercase |= Character.isUpperCase(value);
                lowercase |= Character.isLowerCase(value);
                digit |= Character.isDigit(value);
                symbol |= !Character.isLetterOrDigit(value);
            }
            return uppercase && lowercase && digit && symbol;
        } finally {
            Arrays.fill(material, '\0');
        }
    }

    public String nonMatchingHash() {
        return nonMatchingHash;
    }

    private static char[] materialize(String suppliedSecret) {
        return Objects.requireNonNullElse(suppliedSecret, "").toCharArray();
    }
}
