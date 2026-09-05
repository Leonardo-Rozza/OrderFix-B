package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.leonardorozza.mvgrreparacionesbackend.config.security.LegalPublicRequirementsRequestMatcher;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicRequirementsDatabaseConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicRequirementsReadService;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Exposes only the registration facade, keeping its mutable PostgreSQL graph outside the web factory.
 * The independent context receives three dedicated credentials and its internal enable flag.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = LegalPublicRequirementsHttpConfiguration.ENABLED_PROPERTY, havingValue = "true")
public class LegalPublicRequirementsHttpConfiguration implements DisposableBean {

    public static final String ENABLED_PROPERTY = LegalPublicRequirementsRequestMatcher.ENABLED_PROPERTY;
    private static final String PROPERTY_SOURCE = "legal-public-requirements-isolated";

    private AnnotationConfigApplicationContext requirementsContext;
    private boolean destroyed;

    @Bean(destroyMethod = "")
    public synchronized LegalPublicRequirementsReadService legalPublicRequirementsReadService(Environment environment) {
        if (destroyed) {
            throw new IllegalStateException("El contexto HTTP de requisitos ya está cerrado");
        }
        if (requirementsContext != null) {
            return requirementsContext.getBean(LegalPublicRequirementsReadService.class);
        }
        Map<String, Object> selected = requirementsProperties(environment);
        AnnotationConfigApplicationContext isolated = new AnnotationConfigApplicationContext();
        try {
            // No parent, ambient profiles, system properties or web sources cross this boundary.
            MutablePropertySources sources = isolated.getEnvironment().getPropertySources();
            List<String> defaults = new ArrayList<>();
            sources.forEach(source -> defaults.add(source.getName()));
            defaults.forEach(sources::remove);
            isolated.getEnvironment().setActiveProfiles();
            isolated.getEnvironment().setDefaultProfiles();
            sources.addFirst(new MapPropertySource(PROPERTY_SOURCE, selected));
            isolated.register(LegalPublicRequirementsDatabaseConfiguration.class);
            isolated.refresh();
            LegalPublicRequirementsReadService facade = isolated.getBean(LegalPublicRequirementsReadService.class);
            requirementsContext = isolated;
            return facade;
        } catch (RuntimeException | Error failure) {
            try {
                isolated.close();
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != failure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    @Override
    public synchronized void destroy() {
        destroyed = true;
        AnnotationConfigApplicationContext owned = requirementsContext;
        requirementsContext = null;
        if (owned != null) {
            owned.close();
        }
    }

    private static Map<String, Object> requirementsProperties(Environment environment) {
        Objects.requireNonNull(environment, "environment");
        Map<String, Object> selected = new LinkedHashMap<>();
        for (String suffix : List.of("jdbc-url", "username", "password")) {
            String key = LegalPublicRequirementsDatabaseConfiguration.PROPERTY_PREFIX + suffix;
            String value = environment.getProperty(key);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("Los requisitos públicos requieren " + key);
            }
            selected.put(key, value);
        }
        selected.put(LegalPublicRequirementsDatabaseConfiguration.ENABLED_PROPERTY, "true");
        return Map.copyOf(selected);
    }
}
