package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceDatabaseConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalAcceptanceService;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPrivateRequirementsDatabaseConfiguration;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.ArrayList;
import java.util.List;

/** Owns the acceptance context without exporting JDBC, credentials or transactions to the web factory. */
@Configuration(proxyBeanMethods = false)
@Conditional(LegalAcceptanceHttpConfiguration.Enabled.class)
public class LegalAcceptanceHttpConfiguration implements DisposableBean {
    public static final String ENABLED_PROPERTY = LegalAcceptanceDatabaseConfiguration.ENABLED_PROPERTY;
    private static final String PROPERTY_SOURCE = "legal-account-acceptance-isolated";
    private LegalAcceptanceHttpSettings settings;
    private AnnotationConfigApplicationContext acceptanceContext;
    private boolean destroyed;

    @Bean(destroyMethod = "")
    public synchronized LegalAcceptanceService legalAcceptanceService(Environment environment) {
        requireOpen();
        if (acceptanceContext != null) return acceptanceContext.getBean(LegalAcceptanceService.class);
        // Snapshot and validate secrets/proxy policy before allocating an isolated context or pool.
        var selected = settings(environment).isolatedProperties();
        AnnotationConfigApplicationContext isolated = new AnnotationConfigApplicationContext();
        try {
            var sources = isolated.getEnvironment().getPropertySources();
            List<String> defaults = new ArrayList<>();
            sources.forEach(source -> defaults.add(source.getName()));
            defaults.forEach(sources::remove);
            isolated.getEnvironment().setActiveProfiles();
            isolated.getEnvironment().setDefaultProfiles();
            sources.addFirst(new MapPropertySource(PROPERTY_SOURCE, selected));
            isolated.register(LegalAcceptanceDatabaseConfiguration.class);
            isolated.refresh();
            LegalAcceptanceService facade = isolated.getBean(LegalAcceptanceService.class);
            acceptanceContext = isolated;
            return facade;
        } catch (RuntimeException | Error failure) {
            try {
                isolated.close();
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    @Bean
    public synchronized LegalRequestMetadataResolver legalAcceptanceRequestMetadataResolver(Environment environment) {
        return settings(environment).metadataResolver();
    }

    private LegalAcceptanceHttpSettings settings(Environment environment) {
        requireOpen();
        if (settings == null) settings = LegalAcceptanceHttpSettings.from(environment);
        return settings;
    }

    private void requireOpen() {
        if (destroyed) throw new IllegalStateException("El contexto HTTP de aceptación ya está cerrado");
    }

    @Override
    public synchronized void destroy() {
        destroyed = true;
        AnnotationConfigApplicationContext owned = acceptanceContext;
        acceptanceContext = null;
        settings = null;
        if (owned != null) owned.close();
    }

    /** No resources or HTTP consumers exist when disabled; invalid flags never silently disable them. */
    public static final class Enabled implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String value = context.getEnvironment().getProperty(ENABLED_PROPERTY);
            if (value == null || "false".equals(value)) return false;
            if (!"true".equals(value)) {
                throw new IllegalArgumentException("El flag de aceptación legal requiere true o false exactos");
            }
            if (!"true".equals(context.getEnvironment().getProperty(
                    LegalPrivateRequirementsDatabaseConfiguration.ENABLED_PROPERTY))) {
                throw new IllegalArgumentException("La aceptación legal requiere account-read=true");
            }
            return true;
        }
    }
}
