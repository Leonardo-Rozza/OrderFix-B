package com.leonardorozza.mvgrreparacionesbackend.service.impl;

import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationSessionDataSourceConfiguration;
import com.leonardorozza.mvgrreparacionesbackend.legal.manifest.persistence.LegalRegistrationSessionResources;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/** Explicit composition for the legal caller; not discovered by application component scanning. */
@Import(LegalRegistrationSessionDataSourceConfiguration.class)
public class LegalRegistrationSessionIssuerConfiguration {
    @Bean
    @Lazy(false)
    @DependsOn("legalRegistrationSessionWiringReady")
    LegalRegistrationSessionIssuer legalRegistrationSessionIssuer(AccountSessionPolicy policy,
            LegalRegistrationSessionResources resources,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            @Qualifier("dataSource") DataSource dataSource) {
        // Boot declares this bean as PlatformTransactionManager before constructing its JPA instance.
        if (!(manager instanceof JpaTransactionManager jpa)) {
            throw new IllegalArgumentException("La sesión de registro requiere un gestor JPA");
        }
        return new LegalRegistrationSessionIssuer(policy, resources, jpa, dataSource);
    }
}
