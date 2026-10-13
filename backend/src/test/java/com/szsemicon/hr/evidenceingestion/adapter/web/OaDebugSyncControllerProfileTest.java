package com.szsemicon.hr.evidenceingestion.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.AnnotatedElementUtils;

class OaDebugSyncControllerProfileTest {

    @Test
    void debugSyncEndpointRequiresAnExplicitNonProductionProfile() {
        Profile profile = AnnotatedElementUtils.findMergedAnnotation(
                OaDebugSyncController.class, Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value())
                .containsExactly("!prod & (dev | test)");
    }
}
