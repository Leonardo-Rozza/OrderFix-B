package com.leonardorozza.mvgrreparacionesbackend.legal.http;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentReadDatabaseConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalPublicDocumentReadService;
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
 * Exposes the document facade while keeping its PostgreSQL graph outside the web bean factory.
 * The independent context receives only explicit reader credentials and its internal enable flag.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = LegalPublicDocumentHttpConfiguration.ENABLED_PROPERTY, havingValue = "true")
public class LegalPublicDocumentHttpConfiguration implements DisposableBean {

    public static final String ENABLED_PROPERTY = "ordenfix.legal.public-documents.enabled";
    private static final String PROPERTY_SOURCE = "legal-public-document-read-isolated";

    private AnnotationConfigApplicationContext readerContext;
    private boolean destroyed;

    @Bean(destroyMethod = "")
    public synchronized LegalPublicDocumentReadService legalPublicDocumentReadService(Environment environment) {
        if (destroyed) {
            throw new IllegalStateException("El contexto documental HTTP ya está cerrado");
        }
        if (readerContext != null) {
            return readerContext.getBean(LegalPublicDocumentReadService.class);
        }
        Map<String, Object> selected = readerProperties(environment);
        AnnotationConfigApplicationContext isolated = new AnnotationConfigApplicationContext();
        try {
            // Do not inherit a parent, profiles, system properties or the web property sources.
            MutablePropertySources sources = isolated.getEnvironment().getPropertySources();
            List<String> defaults = new ArrayList<>();
            sources.forEach(source -> defaults.add(source.getName()));
            defaults.forEach(sources::remove);
            sources.addFirst(new MapPropertySource(PROPERTY_SOURCE, selected));
            isolated.register(LegalPublicDocumentReadDatabaseConfiguration.class);
            isolated.refresh();
            LegalPublicDocumentReadService facade = isolated.getBean(LegalPublicDocumentReadService.class);
            readerContext = isolated;
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
        AnnotationConfigApplicationContext owned = readerContext;
        readerContext = null;
        if (owned != null) {
            owned.close();
        }
    }

    private static Map<String, Object> readerProperties(Environment environment) {
        Objects.requireNonNull(environment, "environment");
        Map<String, Object> selected = new LinkedHashMap<>();
        for (String suffix : List.of("jdbc-url", "username", "password")) {
            String key = LegalPublicDocumentReadDatabaseConfiguration.PROPERTY_PREFIX + suffix;
            String value = environment.getProperty(key);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("La lectura documental pública requiere " + key);
            }
            selected.put(key, value);
        }
        selected.put(LegalPublicDocumentReadDatabaseConfiguration.ENABLED_PROPERTY, "true");
        return Map.copyOf(selected);
    }
}
