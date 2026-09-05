package com.leonardorozza.mvgrreparacionesbackend.config.security;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "security.rate-limit")
public class RateLimitProperties {

    private static final int MAX_REQUESTS_PER_WINDOW = 10_000;
    private static final Duration MAX_WINDOW = Duration.ofDays(1);

    private boolean enabled = true;
    private boolean trustForwardedHeaders = false;
    private Limit login = new Limit(10, Duration.ofMinutes(1));
    private Limit register = new Limit(5, Duration.ofHours(1));
    private Limit accountRecovery = new Limit(5, Duration.ofMinutes(15));
    private Limit publicTrackingRead = new Limit(60, Duration.ofMinutes(1));
    private Limit publicTrackingAction = new Limit(10, Duration.ofMinutes(10));
    private Limit publicLegalDocuments = new Limit(60, Duration.ofMinutes(1));
    private Limit mercadoPagoWebhook = new Limit(300, Duration.ofMinutes(1));

    @PostConstruct
    void validate() {
        validate("login", login);
        validate("register", register);
        validate("account-recovery", accountRecovery);
        validate("public-tracking-read", publicTrackingRead);
        validate("public-tracking-action", publicTrackingAction);
        validate("public-legal-documents", publicLegalDocuments);
        validate("mercado-pago-webhook", mercadoPagoWebhook);
    }

    private void validate(String name, Limit limit) {
        if (limit == null || limit.requests < 1 || limit.requests > MAX_REQUESTS_PER_WINDOW
                || limit.window == null || limit.window.isNegative()
                || limit.window.compareTo(MAX_WINDOW) > 0 || limit.window.toMillis() < 1) {
            throw new IllegalStateException("Rate limit inválido para " + name + '.');
        }
    }

    @Getter
    @Setter
    public static class Limit {
        private int requests;
        private Duration window;

        public Limit() {
        }

        public Limit(int requests, Duration window) {
            this.requests = requests;
            this.window = window;
        }
    }
}
