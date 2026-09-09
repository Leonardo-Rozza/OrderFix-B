package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;

class LegalAcceptanceHttpSettingsTest {
    private static final String ACCEPTANCE = "ordenfix.legal.account-acceptance.";
    private static final String READ = "ordenfix.legal.account-read.enabled";
    private static final String HMAC = "ordenfix.legal.idempotency.";
    private static final String AES = "ordenfix.legal.account-metadata.";
    private static final String PROXIES = AES + "trusted-proxy-cidrs";
    private static final String JWT = "security.jwt.secret";
    private static final String DEVICE = "DEVICE_CREDENTIALS_ENCRYPTION_KEY";
    private static final String FORWARD = "server.forward-headers-strategy";
    private static final String JWT_TEXT = "J".repeat(32);
    private static final String DEVICE_KEY = encoded('D');

    @Test void selectsOnlyTheTwoFlagsDedicatedCredentialsAndCompleteLegalKeyrings() {
        var input = properties();
        input.put(HMAC + "result-ttl", "PT49H");
        input.put(PROXIES, "10.0.0.0/8");
        input.put("spring.datasource.url", "jdbc:postgresql://unrelated/web");
        input.put("spring.datasource.username", "unrelated-user");
        input.put("spring.datasource.password", "unrelated-password");
        input.put("ordenfix.legal.account-read.password", "other-legal-password");
        input.put("ordenfix.legal.registration-consent.enabled", "true");
        input.put("ordenfix.legal.registration-consent.jdbc-url", "jdbc:postgresql://unrelated/registration");
        input.put("spring.profiles.active", "production");
        input.put("security.rate-limit.trust-forwarded-headers", "true");
        var settings = LegalAcceptanceHttpSettings.from(environment(input));
        Map<String, Object> expected = new LinkedHashMap<>();
        for (String name : List.of(ACCEPTANCE + "enabled", READ, ACCEPTANCE + "jdbc-url",
                ACCEPTANCE + "username", ACCEPTANCE + "password", HMAC + "active-write-version",
                AES + "active-write-version", HMAC + "result-ttl", AES + "retention")) {
            expected.put(name, input.get(name));
        }
        for (String prefix : List.of(HMAC, AES)) {
            for (int version = 1; version <= 3; version++) {
                expected.put(prefix + "keyring." + version, input.get(prefix + "keyring." + version));
            }
        }
        assertThat(settings.isolatedProperties()).isEqualTo(expected);
        assertThat(settings.isolatedProperties()).doesNotContainKeys(JWT, DEVICE, PROXIES, FORWARD,
                "spring.profiles.active", "spring.datasource.password");
    }

    @Test void snapshotPreservesExactStringsAndDoesNotFollowLaterEnvironmentChanges() {
        var input = properties();
        input.put(ACCEPTANCE + "password", " exact password with spaces ");
        input.put(PROXIES, "10.0.0.0/8");
        var environment = environment(input);
        var settings = LegalAcceptanceHttpSettings.from(environment);
        var snapshot = settings.isolatedProperties();
        environment.setProperty(HMAC + "keyring.1", encoded(77));
        environment.setProperty(ACCEPTANCE + "password", "changed");
        environment.setProperty(PROXIES, "192.0.2.0/24");
        assertThat(snapshot.get(ACCEPTANCE + "password")).isEqualTo(" exact password with spaces ");
        assertThat(snapshot.get(HMAC + "keyring.1")).isEqualTo(input.get(HMAC + "keyring.1"));
        assertThatThrownBy(() -> snapshot.put(JWT, "bad")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(settings.metadataResolver().resolve(request("10.0.0.2", "198.51.100.4")).ipAddress()).isEqualTo("198.51.100.4");
        assertThat(settings.toString()).isEqualTo("LegalAcceptanceHttpSettings[redacted]");
    }

    @Test void optionalTtlRemainsAbsentAndI2OwnsItsDefault() {
        var settings = LegalAcceptanceHttpSettings.from(environment(properties()));
        assertThat(settings.isolatedProperties()).doesNotContainKey(HMAC + "result-ttl");
        assertThat(settings.isolatedProperties().get(AES + "retention")).isEqualTo("PT720H");
    }

    @Test void effectiveValuesFollowPropertySourcePrecedenceWithoutDroppingLowerRetainedVersions() {
        var environment = environment(properties());
        environment.getPropertySources().addLast(new MapPropertySource("older-source", Map.of(
                HMAC + "keyring.1", encoded(JWT_TEXT), HMAC + "keyring.4", encoded(14))));
        var settings = LegalAcceptanceHttpSettings.from(environment);
        assertThat(settings.isolatedProperties().get(HMAC + "keyring.1")).isEqualTo(properties().get(HMAC + "keyring.1"));
        assertThat(settings.isolatedProperties().get(HMAC + "keyring.4")).isEqualTo(encoded(14));
        environment.getPropertySources().replace("older-source", new MapPropertySource("older-source", Map.of(
                HMAC + "keyring.1", encoded(JWT_TEXT), HMAC + "keyring.4", encoded(JWT_TEXT))));
        invalid(environment);
    }

    @ParameterizedTest @MethodSource("externalCollisions")
    void everyRetainedVersionIsComparedWithEffectiveExternalKeyBytes(String prefix, int version, String external) {
        var input = properties();
        if (external.equals("jwt")) input.put(prefix + "keyring." + version, encoded(JWT_TEXT));
        else {
            input.put(prefix + "keyring." + version, DEVICE_KEY);
            if (external.equals("device-unpadded")) input.put(DEVICE, " \t" + DEVICE_KEY.replace("=", "") + "\r\n");
        }
        invalid(environment(input));
    }

    static Stream<Arguments> externalCollisions() {
        return Stream.of(HMAC, AES).flatMap(prefix -> Stream.of(1, 2, 3)
                .flatMap(version -> Stream.of("jwt", "device", "device-unpadded")
                        .map(external -> Arguments.of(prefix, version, external))));
    }

    @ParameterizedTest @ValueSource(strings = {HMAC, AES})
    void theSameJwtConfigurationStringCannotBeReusedAsAnyLegalKey(String prefix) {
        var input = properties(); input.put(JWT, input.get(prefix + "keyring.3"));
        invalid(environment(input));
    }

    @Test void jwtComparisonUsesOriginalUtf8AndNeverTrimmedOrDecodedBytes() {
        var input = properties();
        String raw = " " + "j".repeat(30) + " ";
        input.put(JWT, raw); input.put(HMAC + "keyring.3", encoded(raw));
        invalid(environment(input));
        input = properties(); input.put(JWT, "é".repeat(16));
        assertThat(LegalAcceptanceHttpSettings.from(environment(input))).isNotNull();
        input.put(AES + "keyring.1", encoded("é".repeat(16)));
        invalid(environment(input));
    }

    @Test void deviceComparisonMatchesItsExistingTrimAndUnpaddedBase64Semantics() {
        var input = properties(); input.put(DEVICE, " \t" + DEVICE_KEY.replace("=", "") + "\r\n");
        assertThat(LegalAcceptanceHttpSettings.from(environment(input))).isNotNull();
    }

    @Test void crossRingReuseIsRejectedEvenWhenBothCollidingVersionsAreInactive() {
        var input = properties(); input.put(AES + "keyring.3", input.get(HMAC + "keyring.1"));
        invalid(environment(input));
    }

    @ParameterizedTest @ValueSource(strings = {HMAC, AES})
    void repeatedKeyMaterialWithinEitherRingIsInvalid(String prefix) {
        var input = properties(); input.put(prefix + "keyring.3", input.get(prefix + "keyring.1"));
        invalid(environment(input));
    }

    @Test void selectsEightKeysFromEachRingAndRejectsANinthBeforeBuildingResources() {
        var input = properties();
        for (int version = 1; version <= 8; version++) {
            input.put(HMAC + "keyring." + version, encoded(10 + version));
            input.put(AES + "keyring." + version, encoded(100 + version));
        }
        var selected = LegalAcceptanceHttpSettings.from(environment(input)).isolatedProperties();
        assertThat(selected.keySet().stream().filter(name -> name.startsWith(HMAC + "keyring.")).toList()).hasSize(8);
        assertThat(selected.keySet().stream().filter(name -> name.startsWith(AES + "keyring.")).toList()).hasSize(8);
        input.put(HMAC + "keyring.9", encoded(19)); invalid(environment(input));
    }

    @ParameterizedTest @ValueSource(strings = {"0", "01", "+1", "-1", "2147483648", "99999999999", "1.extra", "1[0]", "", "one"})
    void versionNamesCannotBeAliasedOverflowedOrPartiallyParsed(String suffix) {
        var input = properties(); input.put(HMAC + "keyring." + suffix, encoded(21));
        invalid(environment(input));
    }

    @ParameterizedTest @ValueSource(strings = {"keyring", "keyring[0]"})
    void keyringsHaveOnlyTheDocumentedDottedVersionRepresentation(String suffix) {
        var input = properties(); input.put(AES + suffix, encoded(21)); invalid(environment(input));
    }

    @ParameterizedTest @MethodSource("invalidLegalKeys")
    void legalKeysRemainCanonicalBase64OfExactly32Bytes(String encoded) {
        var input = properties(); input.put(AES + "keyring.3", encoded); invalid(environment(input));
    }

    static Stream<String> invalidLegalKeys() {
        return Stream.of("", " ", "not-base64-private-value", encoded(21).replace("=", ""), ' ' + encoded(21),
                Base64.getEncoder().encodeToString(new byte[31]), Base64.getEncoder().encodeToString(new byte[33]),
                encoded(21).substring(0, 42) + "F=");
    }

    @ParameterizedTest @MethodSource("invalidExternalSecrets")
    void mandatoryExternalSecretsUseTheExistingJwtAndDeviceValidationSemantics(String name, String value) {
        var input = properties(); if (value == null) input.remove(name); else input.put(name, value);
        invalid(environment(input));
    }

    static Stream<Arguments> invalidExternalSecrets() {
        return Stream.concat(Arrays.asList(null, "", "j".repeat(31)).stream().map(value -> Arguments.of(JWT, value)),
                Arrays.asList(null, "", " \t", "invalid-device-secret", Base64.getEncoder().encodeToString(new byte[31]),
                        Base64.getEncoder().encodeToString(new byte[33])).stream().map(value -> Arguments.of(DEVICE, value)));
    }

    @ParameterizedTest @MethodSource("invalidFlags")
    void settingsOnlyComposeAnEnabledAcceptanceWithEnabledAccountReading(String name, String value) {
        var input = properties(); if (value == null) input.remove(name); else input.put(name, value);
        invalid(environment(input));
    }

    static Stream<Arguments> invalidFlags() {
        return Stream.of(ACCEPTANCE + "enabled", READ).flatMap(name ->
                Arrays.asList(null, "", "false", "TRUE", " true", "true ", "1").stream().map(value -> Arguments.of(name, value)));
    }

    @ParameterizedTest @ValueSource(strings = {"jdbc-url", "username", "password"})
    void missingOrBlankDedicatedCredentialsNeverFallBackToWebOrReadCredentials(String suffix) {
        var input = properties(); input.remove(ACCEPTANCE + suffix);
        input.put("spring.datasource." + suffix, "ambient-value");
        input.put("ordenfix.legal.account-read." + suffix, "other-legal-value");
        invalid(environment(input));
        input.put(ACCEPTANCE + suffix, " \t"); invalid(environment(input));
    }

    @ParameterizedTest @ValueSource(strings = {HMAC + "active-write-version", AES + "active-write-version", AES + "retention"})
    void selectedRequiredWriterPropertiesCannotBeMissing(String name) {
        var input = properties(); input.remove(name); invalid(environment(input));
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {"NONE", "native", "framework", "none ", " none", "false"})
    void originalPeerPolicyRequiresExplicitExactNone(String strategy) {
        var input = properties(); if (strategy == null) input.remove(FORWARD); else input.put(FORWARD, strategy);
        invalid(environment(input));
    }

    @ParameterizedTest @ValueSource(strings = {"server.tomcat.remoteip.remote-ip-header", "server.tomcat.remoteip.protocol-header"})
    void aTomcatHeaderCannotReactivateItsRemoteIpValveUnderNone(String name) {
        var input = properties(); input.put(name, "X-Untrusted-Forwarded"); invalid(environment(input));
        input.put(name, " \t"); assertThat(LegalAcceptanceHttpSettings.from(environment(input))).isNotNull();
    }

    @Test void absentAndEmptyProxyCsvTrustOnlyTheSocketPeerRegardlessOfRateLimitPolicy() {
        for (String csv : Arrays.asList(null, "")) {
            var input = properties(); if (csv != null) input.put(PROXIES, csv);
            input.put("security.rate-limit.trust-forwarded-headers", "true");
            var resolver = LegalAcceptanceHttpSettings.from(environment(input)).metadataResolver();
            var request = request("192.0.2.8", "spoofed malformed header ignored");
            assertThat(resolver.resolve(request).ipAddress()).isEqualTo("192.0.2.8");
        }
    }

    @Test void explicitCsvAllowsOnlyAsciiOwsAndConfiguresIpv4Ipv6AndMappedTrust() {
        var input = properties(); input.put(PROXIES, " \t10.0.0.0/8\t , 2001:db8::/32, ::ffff:192.0.2.0/120\t");
        var resolver = LegalAcceptanceHttpSettings.from(environment(input)).metadataResolver();
        assertThat(resolver.resolve(request("10.0.0.2", "198.51.100.4, 10.1.2.3")).ipAddress()).isEqualTo("198.51.100.4");
        assertThat(resolver.resolve(request("2001:db8::2", "2001:4860::1")).ipAddress()).isEqualTo("2001:4860::1");
        assertThat(resolver.resolve(request("192.0.2.9", "198.51.100.5")).ipAddress()).isEqualTo("198.51.100.5");
    }

    @ParameterizedTest @ValueSource(strings = {" ", "\t", ",", "10.0.0.0/8,", ",10.0.0.0/8", "10.0.0.0/8,,192.0.2.0/24",
            "10.0.0.0/8, \t", "10.0.0.0/8\n", "10.0.0.0/8\r", "\u00a010.0.0.0/8", "10.0.0.1/8", "localhost/32",
            "10.0.0.0/33", "2001:db8::1/32", "::ffff:192.0.2.0/24", "[2001:db8::]/32"})
    void invalidProxyEntriesNeverBecomeAnEmptyOrPartiallyAcceptedPolicy(String csv) {
        var input = properties(); input.put(PROXIES, csv); invalid(environment(input));
    }

    @Test void proxyCountAndRawLengthAreBoundedBeforeSplitting() {
        String csv = String.join(",", java.util.Collections.nCopies(64, "192.0.2.0/24"));
        var input = properties(); input.put(PROXIES, csv);
        assertThat(LegalAcceptanceHttpSettings.from(environment(input))).isNotNull();
        input.put(PROXIES, csv + ",192.0.2.0/24"); invalid(environment(input));
        String padded = "192.0.2.0/24" + " ".repeat(4_096 - "192.0.2.0/24".length());
        input.put(PROXIES, padded); assertThat(LegalAcceptanceHttpSettings.from(environment(input))).isNotNull();
        input.put(PROXIES, padded + ' '); invalid(environment(input));
    }

    @ParameterizedTest @ValueSource(strings = {"[0]", ".0", "[01]", ".one"})
    void indexedProxyConfigurationIsRejectedEvenBesideAValidCsv(String suffix) {
        var input = properties(); input.put(PROXIES, "10.0.0.0/8"); input.put(PROXIES + suffix, "192.0.2.0/24");
        invalid(environment(input));
    }

    @Test void aNonEnumerableKeyringCannotPretendToBeACompleteRetainedSnapshot() {
        var values = properties(); Map<String, Object> hidden = new LinkedHashMap<>();
        for (String name : new ArrayList<>(values.keySet())) {
            if (name.startsWith(HMAC + "keyring.") || name.startsWith(AES + "keyring.")) hidden.put(name, values.remove(name));
        }
        var environment = environment(values);
        environment.getPropertySources().addFirst(new PropertySource<Map<String, Object>>("opaque-keys", hidden) {
            @Override public Object getProperty(String name) { return source.get(name); }
        });
        invalid(environment);
        invalid(mock(Environment.class));
        invalid(null);
    }

    @Test void failuresFromPropertyEnumerationOrSecretLookupNeverRetainTheirDiagnostics() {
        var enumeration = environment(properties());
        enumeration.getPropertySources().addFirst(new EnumerablePropertySource<Object>("broken", new Object()) {
            @Override public String[] getPropertyNames() { throw new IllegalStateException("private-config-credential"); }
            @Override public Object getProperty(String name) { return null; }
        });
        invalid(enumeration);
        var lookup = environment(properties());
        lookup.getPropertySources().addFirst(new EnumerablePropertySource<Object>("broken", new Object()) {
            @Override public String[] getPropertyNames() { return new String[]{HMAC + "keyring.1"}; }
            @Override public Object getProperty(String name) {
                if (name.equals(HMAC + "keyring.1")) throw new IllegalStateException("private-config-credential");
                return null;
            }
        });
        invalid(lookup);
    }

    @Test void registrationSelectsItsOwnCredentialsWithoutRequiringAcceptanceOrPublicFlags() {
        var input = properties();
        input.remove(ACCEPTANCE + "enabled"); input.remove(READ);
        String prefix = "ordenfix.legal.registration-consent.";
        input.put(prefix + "enabled", "true");
        input.put(prefix + "jdbc-url", "jdbc:postgresql://127.0.0.1:1/registration_fixture");
        input.put(prefix + "username", "registration_fixture"); input.put(prefix + "password", "registration-private");
        input.put("plan.trial-dias", "21");
        var selected = LegalAcceptanceHttpSettings.registration(environment(input)).isolatedProperties();
        assertThat(selected).containsEntry(prefix + "username", "registration_fixture")
                .containsEntry("plan.trial-dias", "21").containsEntry(HMAC + "keyring.3", input.get(HMAC + "keyring.3"));
        assertThat(selected.keySet()).noneMatch(name -> name.startsWith(ACCEPTANCE) || name.equals(READ));
        assertThat(selected).doesNotContainKeys(JWT, DEVICE, PROXIES, FORWARD);
    }

    @Test void registrationRetainsSecretSeparationAndNeverFallsBackToAcceptanceCredentials() {
        var input = LegalRegistrationHttpConfigurationTest.properties();
        input.put(ACCEPTANCE + "password", "unrelated-acceptance");
        input.remove("ordenfix.legal.registration-consent.password");
        assertThatThrownBy(() -> LegalAcceptanceHttpSettings.registration(environment(input)))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
        input.put("ordenfix.legal.registration-consent.password", "fixture");
        input.put(AES + "keyring.2", input.get(HMAC + "keyring.1"));
        assertThatThrownBy(() -> LegalAcceptanceHttpSettings.registration(environment(input)))
                .isInstanceOf(IllegalArgumentException.class).hasNoCause();
    }

    private static Map<String, String> properties() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(ACCEPTANCE + "enabled", "true"); values.put(READ, "true");
        values.put(ACCEPTANCE + "jdbc-url", "jdbc:postgresql://127.0.0.1:1/unused_fixture");
        values.put(ACCEPTANCE + "username", "acceptance-fixture-user");
        values.put(ACCEPTANCE + "password", "acceptance-fixture-password");
        values.put(HMAC + "active-write-version", "2"); values.put(AES + "active-write-version", "2");
        values.put(AES + "retention", "PT720H");
        for (int version = 1; version <= 3; version++) {
            values.put(HMAC + "keyring." + version, encoded(10 + version));
            values.put(AES + "keyring." + version, encoded(100 + version));
        }
        values.put(JWT, JWT_TEXT); values.put(DEVICE, DEVICE_KEY); values.put(FORWARD, "none");
        return values;
    }

    private static MockEnvironment environment(Map<String, String> values) {
        var environment = new MockEnvironment(); values.forEach(environment::setProperty); return environment;
    }

    private static MockHttpServletRequest request(String peer, String forwarded) {
        var request = new MockHttpServletRequest(); request.setRemoteAddr(peer); request.addHeader("X-Forwarded-For", forwarded); return request;
    }

    private static void invalid(Environment environment) {
        Throwable failure = catchThrowable(() -> LegalAcceptanceHttpSettings.from(environment));
        assertThat(failure).isInstanceOf(IllegalArgumentException.class);
        assertThat(failure.getMessage()).isEqualTo("La configuración HTTP de aceptación legal es inválida.");
        assertThat(failure.getCause()).isNull(); assertThat(failure.getSuppressed()).isEmpty();
        assertThat(failure.toString()).doesNotContain(JWT_TEXT, DEVICE_KEY, "private-config-credential", "acceptance-fixture-password");
    }

    private static String encoded(int repeated) {
        byte[] bytes = new byte[32]; Arrays.fill(bytes, (byte) repeated); return Base64.getEncoder().encodeToString(bytes);
    }
    private static String encoded(String text) { return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)); }
}
