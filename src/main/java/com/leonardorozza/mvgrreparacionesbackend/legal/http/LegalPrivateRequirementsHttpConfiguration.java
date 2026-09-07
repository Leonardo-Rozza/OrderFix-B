package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPrivateRequirementsDatabaseConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPrivateRequirementsReadService;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Exposes only the authenticated requirements facade, keeping its mutable PostgreSQL graph outside the web factory.
 * The independent context receives three dedicated credentials and the shared strict account-read flag.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(LegalPrivateRequirementsHttpConfiguration.Enabled.class)
public class LegalPrivateRequirementsHttpConfiguration implements DisposableBean {

    public static final String ENABLED_PROPERTY = LegalPrivateRequirementsDatabaseConfiguration.ENABLED_PROPERTY;
    private static final String PROPERTY_SOURCE = "legal-private-requirements-isolated";

    private AnnotationConfigApplicationContext requirementsContext;
    private boolean destroyed;

    @Bean(destroyMethod = "")
    public synchronized LegalPrivateRequirementsReadService legalPrivateRequirementsReadService(Environment environment) {
        if (destroyed) {
            throw new IllegalStateException("El contexto HTTP de requisitos ya está cerrado");
        }
        if (requirementsContext != null) {
            return requirementsContext.getBean(LegalPrivateRequirementsReadService.class);
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
            isolated.register(LegalPrivateRequirementsDatabaseConfiguration.class);
            isolated.refresh();
            LegalPrivateRequirementsReadService facade = isolated.getBean(LegalPrivateRequirementsReadService.class);
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
            String key = LegalPrivateRequirementsDatabaseConfiguration.PROPERTY_PREFIX + suffix;
            String value = environment.getProperty(key);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("Los requisitos privados requieren " + key);
            }
            selected.put(key, value);
        }
        selected.put(LegalPrivateRequirementsDatabaseConfiguration.ENABLED_PROPERTY, "true");
        return Map.copyOf(selected);
    }

    /** Exact shared account-read flag; an invalid value must not silently disable the HTTP contract. */
    public static final class Enabled implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String value = context.getEnvironment().getProperty(ENABLED_PROPERTY);
            if (value == null || "false".equals(value)) {
                return false;
            }
            if (!"true".equals(value)) {
                throw new IllegalArgumentException("El flag de lectura legal requiere true o false exactos");
            }
            return true;
        }
    }
}
