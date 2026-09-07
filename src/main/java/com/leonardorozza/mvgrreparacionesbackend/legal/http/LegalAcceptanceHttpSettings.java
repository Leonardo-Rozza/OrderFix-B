package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceDatabaseConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPrivateRequirementsDatabaseConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.service.security.DeviceCredentialCipher;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Immutable selection for the independent consumer; external secrets never cross that boundary. */
final class LegalAcceptanceHttpSettings {
    private static final String ACCEPTANCE = LegalAcceptanceDatabaseConfiguration.PROPERTY_PREFIX;
    private static final String READ_ENABLED = LegalPrivateRequirementsDatabaseConfiguration.ENABLED_PROPERTY;
    private static final String IDEMPOTENCY = "ordenfix.legal.idempotency.";
    private static final String METADATA = "ordenfix.legal.account-metadata.";
    private static final String PROXIES = METADATA + "trusted-proxy-cidrs";
    private static final String JWT_SECRET = "security.jwt.secret";
    private static final int MAX_PROXY_TEXT = 4_096;
    private static final int MAX_PROXIES = 64;

    private final Map<String, Object> isolatedProperties;
    private final LegalRequestMetadataResolver metadataResolver;

    private LegalAcceptanceHttpSettings(Map<String, Object> properties, LegalRequestMetadataResolver resolver) {
        isolatedProperties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        metadataResolver = resolver;
    }

    static LegalAcceptanceHttpSettings from(Environment environment) {
        try {
            if (!(environment instanceof ConfigurableEnvironment configurable)
                    || !"true".equals(environment.getProperty(ACCEPTANCE + "enabled"))
                    || !"true".equals(environment.getProperty(READ_ENABLED))) throw invalid();
            requireOriginalPeer(environment);

            Map<String, Object> selected = new LinkedHashMap<>();
            selected.put(ACCEPTANCE + "enabled", "true");
            selected.put(READ_ENABLED, "true");
            for (String suffix : List.of("jdbc-url", "username", "password")) {
                String name = ACCEPTANCE + suffix;
                selected.put(name, required(environment, name));
            }

            Map<Integer, String> hmac = keyring(configurable, IDEMPOTENCY);
            Map<Integer, String> aes = keyring(configurable, METADATA);
            requireIndependentKeys(environment, hmac, aes);
            for (String prefix : List.of(IDEMPOTENCY, METADATA)) {
                selected.put(prefix + "active-write-version", required(environment, prefix + "active-write-version"));
            }
            hmac.forEach((version, secret) -> selected.put(IDEMPOTENCY + "keyring." + version, secret));
            aes.forEach((version, secret) -> selected.put(METADATA + "keyring." + version, secret));
            String ttl = environment.getProperty(IDEMPOTENCY + "result-ttl");
            if (ttl != null) selected.put(IDEMPOTENCY + "result-ttl", ttl);
            selected.put(METADATA + "retention", required(environment, METADATA + "retention"));
            // I2 accredits active membership, TTL/retention and the full cryptographic configuration
            // before constructing its pool. Only selected strings reach that independent context.
            return new LegalAcceptanceHttpSettings(selected,
                    new LegalRequestMetadataResolver(proxyCidrs(configurable)));
        } catch (RuntimeException failure) {
            // Environment/conversion/parser exceptions can quote configured secrets; retain none.
            throw invalid();
        }
    }

    Map<String, Object> isolatedProperties() { return isolatedProperties; }
    LegalRequestMetadataResolver metadataResolver() { return metadataResolver; }

    @Override public String toString() { return "LegalAcceptanceHttpSettings[redacted]"; }

    private static Map<Integer, String> keyring(ConfigurableEnvironment environment, String prefix) {
        String keyPrefix = prefix + "keyring.";
        Map<Integer, String> keys = new TreeMap<>();
        for (var source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) continue;
            for (String name : enumerable.getPropertyNames()) {
                if (name == null) throw invalid();
                if (name.equals(prefix + "keyring") || name.startsWith(prefix + "keyring[")) throw invalid();
                if (!name.startsWith(keyPrefix)) continue;
                String version = name.substring(keyPrefix.length());
                if (!version.matches("[1-9][0-9]{0,9}")) throw invalid();
                int number = Integer.parseInt(version);
                if (number <= 0) throw invalid();
                // Enumerate the complete retained set but select each effective value with the
                // Environment's normal precedence, never an arbitrary lower-priority source.
                keys.put(number, environment.getRequiredProperty(name));
                if (keys.size() > 8) throw invalid();
            }
        }
        if (keys.isEmpty() || new HashSet<>(keys.values()).size() != keys.size()) throw invalid();
        return keys;
    }

    private static void requireIndependentKeys(Environment environment, Map<Integer, String> hmac,
                                                Map<Integer, String> aes) {
        byte[] jwt = null;
        byte[] device = null;
        try {
            String jwtText = environment.getRequiredProperty(JWT_SECRET);
            jwt = jwtText.getBytes(StandardCharsets.UTF_8);
            if (jwt.length < 32) throw invalid();
            String deviceText = environment.getRequiredProperty(DeviceCredentialCipher.ENVIRONMENT_VARIABLE);
            if (!StringUtils.hasText(deviceText)) throw invalid();
            // Mirror the existing device cipher: trim and basic Base64, including omitted padding.
            device = Base64.getDecoder().decode(deviceText.trim());
            if (device.length != 32) throw invalid();
            for (String secret : hmac.values()) if (aes.containsValue(secret)) throw invalid();
            for (Map<Integer, String> ring : List.of(hmac, aes)) {
                for (String encoded : ring.values()) {
                    byte[] decoded = null;
                    try {
                        if (encoded == null || encoded.length() != 44 || encoded.equals(jwtText)) throw invalid();
                        decoded = Base64.getDecoder().decode(encoded);
                        if (decoded.length != 32 || !Base64.getEncoder().encodeToString(decoded).equals(encoded)
                                || MessageDigest.isEqual(decoded, jwt) || MessageDigest.isEqual(decoded, device)) {
                            throw invalid();
                        }
                    } finally {
                        if (decoded != null) Arrays.fill(decoded, (byte) 0);
                    }
                }
            }
        } finally {
            if (jwt != null) Arrays.fill(jwt, (byte) 0);
            if (device != null) Arrays.fill(device, (byte) 0);
        }
    }

    private static void requireOriginalPeer(Environment environment) {
        if (!"none".equals(environment.getProperty("server.forward-headers-strategy"))) throw invalid();
        for (String name : List.of("server.tomcat.remoteip.protocol-header", "server.tomcat.remoteip.remote-ip-header")) {
            if (StringUtils.hasText(environment.getProperty(name))) throw invalid();
        }
    }

    private static List<String> proxyCidrs(ConfigurableEnvironment environment) {
        for (var source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) continue;
            for (String name : enumerable.getPropertyNames()) {
                if (name != null && (name.startsWith(PROXIES + '[') || name.startsWith(PROXIES + '.'))) throw invalid();
            }
        }
        String value = environment.getProperty(PROXIES);
        if (value == null || value.isEmpty()) return List.of();
        if (value.length() > MAX_PROXY_TEXT) throw invalid();
        int count = 1;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if ((current < 32 && current != '\t') || current > 126) throw invalid();
            if (current == ',' && ++count > MAX_PROXIES) throw invalid();
        }
        String[] entries = value.split(",", -1);
        List<String> result = new ArrayList<>(entries.length);
        for (String entry : entries) {
            int start = 0;
            int end = entry.length();
            while (start < end && ows(entry.charAt(start))) start++;
            while (end > start && ows(entry.charAt(end - 1))) end--;
            if (start == end) throw invalid();
            result.add(entry.substring(start, end));
        }
        return List.copyOf(result);
    }

    private static boolean ows(char character) { return character == ' ' || character == '\t'; }

    private static String required(Environment environment, String name) {
        String value = environment.getRequiredProperty(name);
        if (value.isBlank()) throw invalid();
        return value;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("La configuración HTTP de aceptación legal es inválida.");
    }
}
