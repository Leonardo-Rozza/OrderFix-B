package com.leonardorozza.mvgrreparacionesbackend.config.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalPublicRequirementsRateLimitPropertiesTest {

    @Test
    void defaultsToSixtyPerMinuteWithAnIndependentLimitObjectAndUnchangedProxyTrust() {
        RateLimitProperties properties = new RateLimitProperties();
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.isTrustForwardedHeaders()).isFalse();
        assertThat(properties.getPublicLegalRequirements().getRequests()).isEqualTo(60);
        assertThat(properties.getPublicLegalRequirements().getWindow()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.getPublicLegalRequirements()).isNotSameAs(properties.getPublicLegalDocuments())
                .isNotSameAs(properties.getPublicTrackingRead()).isNotSameAs(properties.getRegister());
        assertThatCode(properties::validate).doesNotThrowAnyException();

        properties.getPublicLegalRequirements().setRequests(7);
        properties.getPublicLegalRequirements().setWindow(Duration.ofSeconds(20));
        assertThat(properties.getPublicLegalDocuments().getRequests()).isEqualTo(60);
        assertThat(properties.getPublicLegalDocuments().getWindow()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.getPublicTrackingRead().getRequests()).isEqualTo(60);
        assertThat(properties.getRegister().getRequests()).isEqualTo(5);
    }

    @Test
    void bindsTheDedicatedKebabCaseNamespaceWithoutSharingTheDocumentPolicy() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "security.rate-limit.public-legal-requirements.requests", "17",
                "security.rate-limit.public-legal-requirements.window", "45s",
                "security.rate-limit.public-legal-documents.requests", "29",
                "security.rate-limit.public-legal-documents.window", "2m")));
        RateLimitProperties properties = binder.bind("security.rate-limit", Bindable.of(RateLimitProperties.class))
                .orElseThrow(AssertionError::new);

        assertThat(properties.getPublicLegalRequirements().getRequests()).isEqualTo(17);
        assertThat(properties.getPublicLegalRequirements().getWindow()).isEqualTo(Duration.ofSeconds(45));
        assertThat(properties.getPublicLegalDocuments().getRequests()).isEqualTo(29);
        assertThat(properties.getPublicLegalDocuments().getWindow()).isEqualTo(Duration.ofMinutes(2));
        assertThat(properties.getLogin().getRequests()).isEqualTo(10);
        assertThat(properties.getLogin().getWindow()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.isTrustForwardedHeaders()).isFalse();
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void theRequirementsPolicyRejectsAllExistingInvalidBoundsIncludingSubMillisecondWindows() {
        for (RateLimitProperties.Limit invalid : Arrays.asList(null,
                new RateLimitProperties.Limit(0, Duration.ofMinutes(1)),
                new RateLimitProperties.Limit(-1, Duration.ofMinutes(1)),
                new RateLimitProperties.Limit(10_001, Duration.ofMinutes(1)),
                new RateLimitProperties.Limit(1, null),
                new RateLimitProperties.Limit(1, Duration.ZERO),
                new RateLimitProperties.Limit(1, Duration.ofNanos(999_999)),
                new RateLimitProperties.Limit(1, Duration.ofSeconds(-1)),
                new RateLimitProperties.Limit(1, Duration.ofDays(1).plusNanos(1)))) {
            RateLimitProperties properties = new RateLimitProperties();
            properties.setPublicLegalRequirements(invalid);
            assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("public-legal-requirements");
        }
    }

    @Test
    void theInclusiveMinimumAndMaximumBoundsRemainValid() {
        for (RateLimitProperties.Limit valid : Arrays.asList(
                new RateLimitProperties.Limit(1, Duration.ofMillis(1)),
                new RateLimitProperties.Limit(10_000, Duration.ofDays(1)))) {
            RateLimitProperties properties = new RateLimitProperties();
            properties.setPublicLegalRequirements(valid);
            assertThatCode(properties::validate).doesNotThrowAnyException();
        }
    }
}
