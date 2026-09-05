package com.leonardorozza.mvgrreparacionesbackend.config.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegalPublicDocumentRateLimitPropertiesTest {

    @Test
    void theDefaultIsSixtyPerMinuteWithoutTrustingForwardedHeaders() {
        RateLimitProperties properties = new RateLimitProperties();
        assertThat(properties.getPublicLegalDocuments().getRequests()).isEqualTo(60);
        assertThat(properties.getPublicLegalDocuments().getWindow()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.isTrustForwardedHeaders()).isFalse();
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void theDocumentPolicyUsesTheSameValidatedBoundsAsExistingPolicies() {
        for (RateLimitProperties.Limit invalid : Arrays.asList(null,
                new RateLimitProperties.Limit(0, Duration.ofMinutes(1)),
                new RateLimitProperties.Limit(10_001, Duration.ofMinutes(1)),
                new RateLimitProperties.Limit(1, null),
                new RateLimitProperties.Limit(1, Duration.ZERO),
                new RateLimitProperties.Limit(1, Duration.ofNanos(1)),
                new RateLimitProperties.Limit(1, Duration.ofSeconds(-1)),
                new RateLimitProperties.Limit(1, Duration.ofDays(1).plusSeconds(1)))) {
            RateLimitProperties properties = new RateLimitProperties();
            properties.setPublicLegalDocuments(invalid);
            assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("public-legal-documents");
        }
        RateLimitProperties properties = new RateLimitProperties();
        properties.setPublicLegalDocuments(new RateLimitProperties.Limit(10_000, Duration.ofDays(1)));
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void historicalLoginAlsoRejectsAWindowBelowTheAlgorithmsMillisecondResolution() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setLogin(new RateLimitProperties.Limit(1, Duration.ofNanos(1)));
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("login");
        properties.setLogin(new RateLimitProperties.Limit(1, Duration.ofMillis(1)));
        assertThatCode(properties::validate).doesNotThrowAnyException();
    }
}
