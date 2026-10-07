package com.szsemicon.hr.evidenceingestion.infrastructure.deli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DeliEplusSignerTest {

    private final DeliEplusSigner signer = new DeliEplusSigner();

    @Test
    void signsPathTimestampKeyAndSecretWithLowercaseMd5() {
        String signature = signer.sign(
                "/v2.0/cloudappapi",
                "1710000000123",
                "example-app-key",
                "example-app-secret");

        assertThat(signature)
                .isEqualTo("99e1a2aae86fc44b0b138cf0d931655d")
                .matches("[0-9a-f]{32}");
    }

    @Test
    void rejectsNonCanonicalPathsAndNonMillisecondTimestamps() {
        assertThatThrownBy(() -> signer.sign(
                "/v2.0/cloudappapi?cursor=1",
                "1710000000123",
                "example-app-key",
                "example-app-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Deli E+ signature path is invalid");

        assertThatThrownBy(() -> signer.sign(
                "/v2.0/cloudappapi",
                "1710000000",
                "example-app-key",
                "example-app-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("13 digits");
    }
}
