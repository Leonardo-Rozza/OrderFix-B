package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationService;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.AccountVerificationNotifier;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.LegalRegistrationSessionIssuer;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.LegalRegistrationSessionIssuerConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.RegistroService;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Composition and pool ownership without network; Boot/JPA and PostgreSQL are covered by HttpIT. */
class LegalRegistrationHttpConfigurationTest {
    static final String CONSENT = LegalRegistrationHttpConfiguration.CONSENT_PROPERTY;
    static final String ENFORCEMENT = LegalRegistrationHttpConfiguration.ENFORCEMENT_PROPERTY;
    static final String PREFIX = "ordenfix.legal.registration-consent.";

    @ParameterizedTest @ValueSource(strings = {"absent", "false"})
    void disabledRouteExistsWithoutLegalServicesKeysOrPools(String flag) {
        var env = new MockEnvironment();
        if (!flag.equals("absent")) env.setProperty(CONSENT, flag);
        try (var context = web(env)) {
            context.refresh();
            assertThat(context.getBeansOfType(LegalRegistrationHttpBridge.class)).hasSize(1);
            assertThat(context.getBeansOfType(LegalRegistrationService.class)).isEmpty();
            assertThat(context.getBeansOfType(LegalRegistrationSessionIssuer.class)).isEmpty();
            assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
            assertThat(context.getBeansOfType(LegalRegistrationHttpConfiguration.Capability.class)).isEmpty();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"TRUE", " false", "1", ""})
    void malformedFlagsCannotSilentlyDisableRegistration(String invalid) {
        for (String flag : new String[]{CONSENT, ENFORCEMENT}) {
            var env = new MockEnvironment().withProperty(flag, invalid);
            assertThatThrownBy(() -> LegalRegistrationHttpConfiguration.Flags.read(env))
                    .hasMessage("Los flags de registro requieren true o false exactos");
        }
    }

    @Test void additiveConsentDoesNotRequireEitherPublicReaderOrPrivateAcceptance() {
        var env = new MockEnvironment().withProperty(CONSENT, "true")
                .withProperty("ordenfix.legal.public-documents.enabled", "false")
                .withProperty("ordenfix.legal.public-requirements.enabled", "false");
        var flags = LegalRegistrationHttpConfiguration.Flags.read(env);
        assertThat(flags.consent()).isTrue(); assertThat(flags.enforcement()).isFalse();
    }

    @ParameterizedTest @ValueSource(strings = {"consent", "documents", "requirements"})
    void enforcementCannotStartWithoutEachExactDependency(String missing) {
        var env = new MockEnvironment().withProperty(CONSENT, "true").withProperty(ENFORCEMENT, "true")
                .withProperty("ordenfix.legal.public-documents.enabled", "true")
                .withProperty("ordenfix.legal.public-requirements.enabled", "true");
        String dependency = switch (missing) {
            case "consent" -> CONSENT;
            case "documents" -> "ordenfix.legal.public-documents.enabled";
            default -> "ordenfix.legal.public-requirements.enabled";
        };
        env.setProperty(dependency, "false");
        assertThatThrownBy(() -> LegalRegistrationHttpConfiguration.Flags.read(env))
                .hasMessage("El registro obligatorio requiere consentimiento y ambas lecturas públicas");
        env.setProperty(dependency, "true");
        assertThat(LegalRegistrationHttpConfiguration.Flags.read(env).enforcement()).isTrue();
    }

    @Test void capabilityReusesOneIsolatedGraphAndClosesOnlyWhatItOwns() {
        var capability = new LegalRegistrationHttpConfiguration.Capability();
        var env = environment(properties());
        try {
            var first = capability.legalRegistrationService(env);
            assertThat(capability.legalRegistrationService(env)).isSameAs(first);
            var child = (AnnotationConfigApplicationContext) ReflectionTestUtils.getField(capability, "registrationContext");
            assertThat(child.getParent()).isNull();
            assertThat(child.getEnvironment().getPropertySources()).hasSize(1);
            assertThat(child.getEnvironment().getActiveProfiles()).isEmpty();
            assertThat(child.getEnvironment().getProperty("spring.datasource.password")).isNull();
            assertThat(child.getBeansOfType(DataSource.class)).hasSize(2);
            var pool = child.getBean(com.zaxxer.hikari.HikariDataSource.class);
            assertThat(pool.getMinimumIdle()).isZero();
            assertThat(capability.metadataResolver()).isNotNull();
            capability.destroy();
            assertThat(child.isActive()).isFalse(); assertThat(pool.isClosed()).isTrue();
            assertThatThrownBy(capability::metadataResolver).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> capability.legalRegistrationService(env)).isInstanceOf(IllegalStateException.class);
        } finally { capability.destroy(); }
    }

    @Test void invalidSettingsAllocateNoChildAndCapabilityImportsOnlyTheExistingSessionComposition() {
        var capability = new LegalRegistrationHttpConfiguration.Capability();
        try (var children = mockConstruction(AnnotationConfigApplicationContext.class)) {
            assertThatThrownBy(() -> capability.legalRegistrationService(new MockEnvironment().withProperty(CONSENT, "true")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(children.constructed()).isEmpty();
        }
        assertThat(LegalRegistrationHttpConfiguration.Capability.class.getAnnotation(Import.class).value())
                .containsExactly(LegalRegistrationSessionIssuerConfiguration.class);
        capability.destroy();
    }

    @Test void failedChildRefreshClosesItsContextAndCannotProvideMetadata() {
        var capability = new LegalRegistrationHttpConfiguration.Capability();
        var failure = new IllegalStateException("synthetic refresh failure");
        try (var children = mockConstruction(AnnotationConfigApplicationContext.class, (child, construction) -> {
            when(child.getEnvironment()).thenReturn(new org.springframework.core.env.StandardEnvironment());
            doThrow(failure).when(child).refresh();
        })) {
            assertThatThrownBy(() -> capability.legalRegistrationService(environment(properties()))).isSameAs(failure);
            verify(children.constructed().getFirst()).close();
            assertThatThrownBy(capability::metadataResolver).isInstanceOf(IllegalStateException.class);
        } finally { capability.destroy(); }
    }

    private static AnnotationConfigApplicationContext web(MockEnvironment environment) {
        var context = new AnnotationConfigApplicationContext(); context.setEnvironment(environment);
        context.registerBean(RegistroService.class, () -> mock(RegistroService.class));
        context.registerBean(Validator.class, () -> mock(Validator.class));
        context.registerBean(AccountVerificationNotifier.class, () -> mock(AccountVerificationNotifier.class));
        context.register(LegalRegistrationHttpConfiguration.class); return context;
    }
    static Map<String, String> properties() {
        var properties = new LinkedHashMap<String, String>();
        properties.put(CONSENT, "true");
        properties.put(PREFIX + "jdbc-url", "jdbc:postgresql://127.0.0.1:1/registration_configuration_fixture");
        properties.put(PREFIX + "username", "registration_fixture"); properties.put(PREFIX + "password", "fixture-password");
        properties.put("ordenfix.legal.idempotency.active-write-version", "1");
        properties.put("ordenfix.legal.idempotency.keyring.1", key('h'));
        properties.put("ordenfix.legal.account-metadata.active-write-version", "1");
        properties.put("ordenfix.legal.account-metadata.keyring.1", key('m'));
        properties.put("ordenfix.legal.account-metadata.retention", "PT720H");
        properties.put("security.jwt.secret", "j".repeat(32));
        properties.put("DEVICE_CREDENTIALS_ENCRYPTION_KEY", key('d'));
        properties.put("server.forward-headers-strategy", "none");
        return properties;
    }
    static MockEnvironment environment(Map<String, String> values) {
        var env = new MockEnvironment(); values.forEach(env::setProperty); return env;
    }
    static String key(char value) { return Base64.getEncoder().encodeToString(String.valueOf(value).repeat(32).getBytes(StandardCharsets.UTF_8)); }
}
