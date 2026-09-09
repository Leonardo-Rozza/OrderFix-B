package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.leonardorozza.mvgrreparacionesbackend.config.security.LegalPublicDocumentRequestMatcher;
import com.leonardorozza.mvgrreparacionesbackend.config.security.LegalPublicRequirementsRequestMatcher;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicRequirementsReadService;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationDatabaseConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationService;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.AccountVerificationNotifier;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.LegalRegistrationSessionIssuer;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.LegalRegistrationSessionIssuerConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.service.impl.RegistroService;
import jakarta.validation.Validator;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.ArrayList;
import java.util.List;

/** The route always exists; its optional write graph owns only its restricted, isolated context. */
@Configuration(proxyBeanMethods = false)
@Import(LegalRegistrationHttpConfiguration.Capability.class)
public class LegalRegistrationHttpConfiguration {
    public static final String CONSENT_PROPERTY = LegalRegistrationDatabaseConfiguration.ENABLED_PROPERTY;
    public static final String ENFORCEMENT_PROPERTY = "ordenfix.legal.registration-enforcement.enabled";

    @Bean
    @Lazy(false)
    LegalRegistrationHttpBridge legalRegistrationHttpBridge(Environment environment, RegistroService legacy,
            Validator validator, AccountVerificationNotifier notifier,
            ObjectProvider<LegalRegistrationService> registration,
            ObjectProvider<LegalPublicRequirementsReadService> requirements,
            ObjectProvider<LegalRegistrationSessionIssuer> sessions, ObjectProvider<Capability> capability) {
        Flags flags = Flags.read(environment);
        return new LegalRegistrationHttpBridge(legacy, validator, flags.consent(), flags.enforcement(),
                registration::getObject, requirements::getObject, () -> capability.getObject().metadataResolver(),
                sessions::getObject, notifier);
    }

    record Flags(boolean consent, boolean enforcement) {
        static Flags read(Environment environment) {
            boolean consent = flag(environment, CONSENT_PROPERTY);
            boolean enforcement = flag(environment, ENFORCEMENT_PROPERTY);
            if (enforcement && (!consent
                    || !"true".equals(environment.getProperty(LegalPublicDocumentRequestMatcher.ENABLED_PROPERTY))
                    || !"true".equals(environment.getProperty(LegalPublicRequirementsRequestMatcher.ENABLED_PROPERTY)))) {
                throw new IllegalArgumentException("El registro obligatorio requiere consentimiento y ambas lecturas públicas");
            }
            return new Flags(consent, enforcement);
        }
        private static boolean flag(Environment environment, String name) {
            String value = environment.getProperty(name);
            if (value == null || "false".equals(value)) return false;
            if (!"true".equals(value)) throw new IllegalArgumentException("Los flags de registro requieren true o false exactos");
            return true;
        }
    }

    public static final class Enabled implements Condition {
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return Flags.read(context.getEnvironment()).consent();
        }
    }

    /** Imported only when consent is enabled; the historical JPA pool remains owned by Boot. */
    @Conditional(Enabled.class)
    @Import(LegalRegistrationSessionIssuerConfiguration.class)
    public static class Capability implements DisposableBean {
        private AnnotationConfigApplicationContext registrationContext;
        private LegalAcceptanceHttpSettings settings;
        private boolean destroyed;

        @Bean(destroyMethod = "")
        @Lazy(false)
        public synchronized LegalRegistrationService legalRegistrationService(Environment environment) {
            if (destroyed) throw new IllegalStateException("El contexto de registro está cerrado");
            if (registrationContext != null) return registrationContext.getBean(LegalRegistrationService.class);
            settings = LegalAcceptanceHttpSettings.registration(environment);
            var isolated = new AnnotationConfigApplicationContext();
            try {
                var sources = isolated.getEnvironment().getPropertySources();
                List<String> defaults = new ArrayList<>();
                sources.forEach(source -> defaults.add(source.getName()));
                defaults.forEach(sources::remove);
                isolated.getEnvironment().setActiveProfiles();
                isolated.getEnvironment().setDefaultProfiles();
                sources.addFirst(new MapPropertySource("legal-registration-isolated", settings.isolatedProperties()));
                isolated.register(LegalRegistrationDatabaseConfiguration.class);
                isolated.refresh();
                var service = isolated.getBean(LegalRegistrationService.class);
                registrationContext = isolated;
                return service;
            } catch (RuntimeException | Error failure) {
                try { isolated.close(); }
                catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                settings = null;
                throw failure;
            }
        }

        synchronized LegalRequestMetadataResolver metadataResolver() {
            if (destroyed || settings == null || registrationContext == null) {
                throw new IllegalStateException("La captura de registro no está disponible");
            }
            return settings.metadataResolver();
        }

        @Override public synchronized void destroy() {
            destroyed = true;
            var owned = registrationContext;
            registrationContext = null;
            settings = null;
            if (owned != null) owned.close();
        }
    }
}
