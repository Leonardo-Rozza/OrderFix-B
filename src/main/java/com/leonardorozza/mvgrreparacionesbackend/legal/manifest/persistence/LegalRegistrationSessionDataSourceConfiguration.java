package com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.Ordered;

import javax.sql.DataSource;

/** Explicitly importable composition; deliberately not a scanned configuration or auto-configuration. */
public class LegalRegistrationSessionDataSourceConfiguration {
    @Bean(destroyMethod = "close")
    static LegalRegistrationSessionResources legalRegistrationSessionResources() {
        return new LegalRegistrationSessionResources();
    }

    @Bean
    static RoutingPostProcessor legalRegistrationSessionRoutingPostProcessor(
            ObjectProvider<LegalRegistrationSessionResources> resources) {
        return new RoutingPostProcessor(resources);
    }

    @Bean
    @Lazy(false)
    @DependsOn("dataSource")
    RegistrationSessionWiringReady legalRegistrationSessionWiringReady(
            LegalRegistrationSessionResources resources, @Qualifier("dataSource") DataSource dataSource) {
        // The parameter and DependsOn force full creation of the final DataSource, including its
        // disposable registration. A later observer may legitimately wrap our router.
        resources.markReady();
        return new RegistrationSessionWiringReady();
    }

    static final class RoutingPostProcessor implements BeanPostProcessor, Ordered {
        private final ObjectProvider<LegalRegistrationSessionResources> resources;

        private RoutingPostProcessor(ObjectProvider<LegalRegistrationSessionResources> resources) {
            this.resources = resources;
        }

        @Override public int getOrder() { return 0; }

        @Override public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (!"dataSource".equals(beanName)) return bean;
            // Resolve the managed owner before creating any private pool or protection.
            LegalRegistrationSessionResources owner = resources.getObject();
            try {
                if (!(bean instanceof HikariDataSource historical)) {
                    throw new IllegalArgumentException("La sesión de registro requiere el DataSource Hikari de la aplicación");
                }
                return owner.install(historical);
            } catch (RuntimeException failure) {
                owner.recordFailure(failure);
                // Spring registers destruction of this original only after all post-processors
                // return. The mandatory non-lazy gate rejects this context immediately afterwards;
                // a failed/pending owner cannot run work, and no operational fallback is enabled.
                return bean;
            }
        }
    }

    static final class RegistrationSessionWiringReady { }
}
