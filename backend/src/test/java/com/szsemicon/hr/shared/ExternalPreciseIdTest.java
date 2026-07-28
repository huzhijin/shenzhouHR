package com.szsemicon.hr.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.szsemicon.hr.shared.domain.ExternalPreciseId;
import org.junit.jupiter.api.Test;

class ExternalPreciseIdTest {

    @Test
    void preservesValuesBeyondJavaScriptSafeInteger() {
        String sourceValue = "92233720368547758071234567890";

        ExternalPreciseId id = new ExternalPreciseId(sourceValue);

        assertThat(id.value()).isEqualTo(sourceValue);
        assertThat(id.toString()).isEqualTo(sourceValue);
    }

    @Test
    void rejectsBlankValues() {
        assertThatThrownBy(() -> new ExternalPreciseId(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

