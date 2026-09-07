package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;

import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

/** Validated, secret-redacted configuration of the two independent acceptance keyrings. */
final class LegalAcceptanceKeyConfiguration {
    static final String IDEMPOTENCY_PREFIX = "ordenfix.legal.idempotency.";
    static final String METADATA_PREFIX = "ordenfix.legal.account-metadata.";
    private final LegalIdempotencyKeyring idempotency;
    private final LegalAcceptanceMetadataCodec metadata;
    private final LegalAcceptanceMetadataPolicy retention;

    private LegalAcceptanceKeyConfiguration(LegalIdempotencyKeyring idempotency,
            LegalAcceptanceMetadataCodec metadata, LegalAcceptanceMetadataPolicy retention) {
        this.idempotency = idempotency;
        this.metadata = metadata;
        this.retention = retention;
    }

    static LegalAcceptanceKeyConfiguration from(Environment environment) {
        try {
            if (!(environment instanceof ConfigurableEnvironment configurable)) throw invalid();
            Map<Integer, String> hmac = keys(configurable, IDEMPOTENCY_PREFIX);
            Map<Integer, String> aes = keys(configurable, METADATA_PREFIX);
            String ttl = environment.getProperty(IDEMPOTENCY_PREFIX + "result-ttl");
            LegalIdempotencyKeyring idempotency = new LegalIdempotencyKeyring(hmac,
                    version(environment.getRequiredProperty(IDEMPOTENCY_PREFIX + "active-write-version")),
                    ttl == null ? Duration.ofHours(25) : Duration.parse(ttl));
            LegalAcceptanceMetadataCodec metadata = new LegalAcceptanceMetadataCodec(aes,
                    version(environment.getRequiredProperty(METADATA_PREFIX + "active-write-version")));
            // Both constructors have accredited canonical Base64. String equality is byte equality.
            for (String secret : hmac.values()) if (aes.containsValue(secret)) throw invalid();
            LegalAcceptanceMetadataPolicy retention = new LegalAcceptanceMetadataPolicy(
                    Duration.parse(environment.getRequiredProperty(METADATA_PREFIX + "retention")));
            return new LegalAcceptanceKeyConfiguration(idempotency, metadata, retention);
        } catch (RuntimeException failure) {
            // Property sources/parsers may include supplied secrets. Never retain their diagnostics.
            throw invalid();
        }
    }

    private static Map<Integer, String> keys(ConfigurableEnvironment environment, String prefix) {
        String keyPrefix = prefix + "keyring.";
        Map<Integer, String> result = new TreeMap<>();
        for (var source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) continue;
            for (String name : enumerable.getPropertyNames()) {
                if (!name.startsWith(keyPrefix)) continue;
                int version = version(name.substring(keyPrefix.length()));
                result.put(version, environment.getRequiredProperty(name));
                if (result.size() > 8) throw invalid();
            }
        }
        if (result.isEmpty()) throw invalid();
        return result;
    }

    private static int version(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,9}")) throw invalid();
        int parsed = Integer.parseInt(value);
        if (parsed <= 0) throw invalid();
        return parsed;
    }

    LegalIdempotencyKeyring keyring() { return idempotency; }
    LegalAcceptanceMetadataCodec codec() { return metadata; }
    LegalAcceptanceMetadataPolicy retentionPolicy() { return retention; }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("La configuración criptográfica de aceptación es inválida");
    }

    @Override public String toString() {
        return "LegalAcceptanceKeyConfiguration[secrets=REDACTED]";
    }
}
